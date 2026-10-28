/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.storageservice.storage.protos.groups.Member
import org.signal.storageservice.storage.protos.groups.local.DecryptedRequestingMember
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.withAttachments
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.CommandDocument
import org.thoughtcrime.securesms.github.CommandsSnapshot
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.groups.GatewayGroupActions
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.AdminDeleteSendJob
import org.thoughtcrime.securesms.jobs.GatewayDmScanRefreshJob
import org.thoughtcrime.securesms.jobs.GatewayWorkJob
import org.thoughtcrime.securesms.jobs.GatewayRequestPollJob
import org.thoughtcrime.securesms.jobs.GatewayCommandJob
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.testing.JdbcSqliteDatabase
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.RemoteConfig
import java.io.File
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GatewayIntegrationTest {
  @get:Rule val recipients = RecipientTestRule()
  private val context get() = RuntimeEnvironment.getApplication()
  private lateinit var db: JdbcSqliteDatabase
  private lateinit var store: GatewayStore
  private lateinit var xml: String
  private val sent = mutableListOf<OutgoingMessage>()
  private val jobs = mutableListOf<Job>()
  private var cachedBlocklist: Set<String>? = emptySet()
  private var onlineBlocklist: Set<String> = emptySet()

  @Before fun setup() {
    db = JdbcSqliteDatabase.createInMemory()
    GatewayStore.createSchema(db)
    store = GatewayStore(db)
    xml = templateFile().readText()
    context.getDatabasePath(GatewayDatabase.NAME).apply { parentFile!!.mkdirs(); createNewFile() }
    mockkObject(GatewayDatabase.Companion)
    every { GatewayDatabase.get(any()) } returns store
    mockkObject(GitHubAutoReply)
    every { GitHubAutoReply.configurationDocument(any(), any()) } answers { CommandDocument.parse(xml) }
    every { GitHubAutoReply.configurationSnapshot(any(), any()) } answers { CommandsSnapshot(xml, xml, null) }
    mockkObject(GroupTable.Companion)
    every { GroupTable.getCachedBlocklist() } answers { cachedBlocklist }
    every { GroupTable.getBlocklist(any()) } answers { cachedBlocklist = onlineBlocklist; onlineBlocklist }
    mockkStatic(GroupManager::class)
    justRun { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), any(), any(), any(), any(), any()) } answers {
      val message = secondArg<OutgoingMessage>()
      sent += message
      recipients.insertOutgoingMessage(message, thirdArg())
    }
    every { AppDependencies.jobManager.add(any<Job>()) } answers { jobs += firstArg<Job>() }
    every { RemoteConfig.adminDeleteThreshold } returns 86400L
    every { RemoteConfig.regularDeleteThreshold } returns 86400L
  }

  @After fun cleanup() {
    unmockkStatic(MessageSender::class)
    unmockkStatic(GroupManager::class)
    unmockkStatic(GatewayGroupActions::class)
    unmockkObject(GroupTable.Companion)
    unmockkObject(GitHubAutoReply)
    unmockkObject(GatewayDatabase.Companion)
    context.getDatabasePath(GatewayDatabase.NAME).delete()
    db.close()
  }

  @Test fun `noncontact DM scan is cached first then refreshes all groups exactly once`() {
    val admin = recipients.createRecipient("Admin", profileSharing = false)
    val target = recipients.createRecipient("Applicant")
    val first = recipients.createGroup(admin, target)
    val second = recipients.createGroup(admin, target)
    rename(first, "Alpha"); rename(second, "Beta")
    val input = incoming(admin, body = "!scan")
    assertTrue(GatewayController.handle(context, input))
    assertTrue(sent.single().body.contains("Alpha"))
    assertTrue(sent.single().body.contains("Beta"))
    assertTrue(sent.single().body.contains("15 minutes"))
    verify(exactly = 0) { GroupTable.getBlocklist(any()) }
    assertFalse(Recipient.resolved(admin).isProfileSharing)
    val initial = sent.single()
    assertTrue(GatewayRuntime.preservesContact(context, admin.toLong(), initial.sentTimeMillis))
    val initialId = SignalDatabase.messages.getMessageFor(initial.sentTimeMillis, Recipient.self().id)!!.id
    SignalDatabase.messages.markAsSent(initialId)
    GatewayRuntime.sent(context, admin.toLong(), initial.sentTimeMillis)
    assertFalse(GatewayRuntime.preservesContact(context, admin.toLong(), initial.sentTimeMillis))
    onlineBlocklist = setOf(Recipient.resolved(target).requireAci().toString())
    val refresh = jobs.filterIsInstance<GatewayDmScanRefreshJob>().single()
    refresh.setContext(context)
    assertTrue(refresh.run().isSuccess)
    verify(exactly = 1) { GroupTable.getBlocklist(forceRefresh = true) }
    assertEquals(initialId, sent.last().messageToEdit)
    assertEquals(2, sent.last().mentions.count { it.recipientId == target })
    assertFalse(Recipient.resolved(admin).fresh().isProfileSharing)
    GatewayController.handle(context, incoming(admin, body = "!scan", timestamp = 101))
    assertTrue(sent.last().body.contains("scan again in"))
    assertEquals(1, jobs.filterIsInstance<GatewayDmScanRefreshJob>().size)
  }

  @Test fun `groupid DM preserves existing and noncontact states without group acknowledgement`() {
    listOf(false, true).forEach { contact ->
      val admin = recipients.createRecipient("Admin", profileSharing = contact)
      val group = recipients.createGroup(admin)
      val id = incoming(admin, group.groupId, "!groupid")
      val before = sent.size
      assertTrue(GatewayController.handle(context, id))
      assertEquals(before + 1, sent.size)
      assertEquals(admin, sent.last().threadRecipient.id)
      assertTrue(sent.last().body.contains(group.groupId.toString()))
      assertEquals(contact, Recipient.resolved(admin).fresh().isProfileSharing)
    }
  }

  @Test fun `groupid from ordinary member is silent`() {
    val member = recipients.createRecipient("Member")
    val group = recipients.createGroup(member)
    demote(group, member)
    GatewayController.handle(context, incoming(member, group.groupId, "!groupid"))
    assertTrue(sent.isEmpty())
  }

  @Test fun `gateway in an unconfigured group returns a private setup response`() {
    val admin = recipients.createRecipient("Admin", profileSharing = false)
    val group = recipients.createGroup(admin)
    assertTrue(GatewayController.handle(context, incoming(admin, group.groupId, "!gateway")))
    assertEquals(admin, sent.single().threadRecipient.id)
    assertTrue(sent.single().body.contains("not configured"))
    assertTrue(sent.single().body.contains("<silent>1</silent>"))
    assertFalse(Recipient.resolved(admin).fresh().isProfileSharing)
  }

  @Test fun `silent source administrators can read status and change ordinary Gateway settings`() {
    val admin = recipients.createRecipient("Admin", profileSharing = false)
    val group = recipients.createGroup(admin)
    configureSource(group)
    val document = CommandDocument.parse(xml)
    val source = document.document.getElementsByTagName("group").let { nodes ->
      (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.single { it.direct("groupid")?.textContent == group.groupId.toString() }
    }
    (source.direct("silent") ?: document.document.createElement("silent").also(source::appendChild)).textContent = "1"
    xml = document.toXml()
    every { GitHubAutoReply.updateConfiguration(any(), any()) } answers {
      val latest = CommandDocument.parse(xml)
      secondArg<(CommandDocument) -> Boolean>().invoke(latest)
      xml = latest.toXml()
    }
    assertTrue(GatewayController.handle(context, incoming(admin, group.groupId, "!gateway")))
    assertTrue(sent.single().body.contains("[silent]"))
    assertEquals(admin, sent.single().threadRecipient.id)
    assertTrue(GatewayController.handle(context, incoming(admin, group.groupId, "!gateway mentionadmins disable", 101)))
    val configuration = GatewayConfiguration.parse(CommandDocument.parse(xml))
    assertFalse(configuration.groups.getValue(group.groupId.toString()).publiclyListed)
    assertFalse(configuration.groups.getValue(group.groupId.toString()).mentionAdmins)
    assertEquals(admin, sent.last().threadRecipient.id)
    assertFalse(Recipient.resolved(admin).fresh().isProfileSharing)
  }

  @Test fun `help uses current group scope and hides throttled Gateway controls`() {
    val admin = recipients.createRecipient("Admin")
    val member = recipients.createRecipient("Member")
    val group = recipients.createGroup(admin, member)
    configureSource(group)
    demote(group, member)
    GatewayController.handle(context, incoming(member, group.groupId, "!help"))
    assertFalse(sent.last().body.contains("Gateway source controls"))
    assertFalse(sent.last().body.contains("!scan"))
    GatewayController.handle(context, incoming(admin, group.groupId, "!help", 101))
    assertTrue(sent.last().body.contains("Gateway source controls"))
    assertTrue(sent.last().body.contains("!scan"))
    update(group.groupId.toString(), "throttle", "1")
    GatewayController.handle(context, incoming(admin, group.groupId, "!help", 102))
    assertFalse(sent.last().body.contains("Gateway source controls"))
    assertTrue(sent.last().body.contains("!scan"))
  }

  @Test fun `malformed throttled Gateway configuration command stays silent`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    configureSource(group)
    update(group.groupId.toString(), "throttle", "1")
    GatewayController.handle(context, incoming(admin, group.groupId, "!gateway unknown"))
    assertTrue(sent.isEmpty())
  }

  @Test fun `queued prompt is cancelled on throttle and can be issued again on resume`() {
    val group = recipients.createGroup()
    configureSource(group)
    val sourceId = group.groupId.toString()
    update(sourceId, "admin", "1"); update(sourceId, "groupid", sourceId); update(sourceId, "enabled", "1")
    val source = GatewayConfiguration.parse(CommandDocument.parse(xml)).groups.getValue(sourceId)
    val request = store.intake(source, recipients.selfAci.toString(), 1, GatewayDefaults(), 100)
    store.save(request, request.transition(GatewayPhase.PROMPTING) { it.put("promptId", 99) })
    store.reserveOutgoing("review:${request.id}:prompt:0", request.id, "spam", 1000)
    update(sourceId, "throttle", "1")
    assertFalse(GatewayRuntime.maySend(context, 1000))
    assertEquals(GatewayPhase.WAITING_FOR_MEMBERSHIP, store.request(request.id)!!.phase)
    assertNull(store.outgoing("review:${request.id}:prompt:0"))
  }

  @Test fun `emergency pause suppresses tracked automation but preserves reporting transport and recovery`() {
    xml = xml.replace("<paused>0</paused>", "<paused>1</paused>")
    store.reserveOutgoing("ordinary:0", 0, "command", 1000)
    store.reserveOutgoing("recovery:0", 0, "owner-recovery", 2000)
    assertFalse(GatewayRuntime.maySend(context, 1000))
    assertTrue(GatewayRuntime.maySend(context, 2000))
    // Existing reporting announcements use their own transport and have no Assistant outbox marker.
    assertTrue(GatewayRuntime.maySend(context, 3000))
  }

  @Test fun `delayed reminders cannot hold the immediate request queue`() {
    val timer = GatewayWorkJob(17, 86400000)
    val immediate = GatewayWorkJob(17)
    assertNotEquals(timer.parameters.queue, immediate.parameters.queue)
    val restored = GatewayWorkJob.Factory().create(timer.parameters, timer.serialize())
    restored.setContext(context)
    assertTrue(restored.run().isSuccess)
    assertEquals(immediate.parameters.queue, jobs.filterIsInstance<GatewayWorkJob>().single().parameters.queue)
  }

  @Test fun `Gateway media deletion rechecks room policy and emergency pause without changing normal deletes`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    configureSource(room)
    val id = room.groupId.toString()
    update(id, "admin", "1"); update(id, "groupid", id); update(id, "enabled", "1"); update(id, "deletemedia", "1")
    val source = GatewayConfiguration.parse(CommandDocument.parse(xml)).groups.getValue(id)
    store.intake(source, Recipient.resolved(applicant).requireAci().toString(), 1, GatewayDefaults(), System.currentTimeMillis())
    val message = incoming(applicant, room.groupId, "media caption", System.currentTimeMillis())
    store.trackMediaDelete(message)
    assertTrue(GatewayRuntime.mayDelete(context, message))
    update(id, "deletemedia", "0")
    assertFalse(GatewayRuntime.mayDelete(context, message))
    store.forgetMediaDelete(message)
    xml = xml.replace("<paused>0</paused>", "<paused>1</paused>")
    assertTrue(GatewayRuntime.mayDelete(context, message))
  }

  @Test fun `notification mentions only enabled source admins and uses native applicants in decision commands`() {
    val firstAdmin = recipients.createRecipient("First admin")
    val secondAdmin = recipients.createRecipient("Second admin")
    val applicant = recipients.createRecipient("Applicant")
    val first = recipients.createGroup(firstAdmin)
    val second = recipients.createGroup(secondAdmin)
    val room = recipients.createGroup(firstAdmin, secondAdmin, applicant)
    configureSource(first)
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_GATEWAY_ID_HEX", room.groupId.toString())
    update(first.groupId.toString(), "admin", "1"); update(first.groupId.toString(), "enabled", "1")
    val document = CommandDocument.parse(xml)
    GatewayConfiguration.ensureRoom(document, second.groupId.toString(), "Second source")
    GatewayConfiguration.set(document, second.groupId.toString(), "groupid", room.groupId.toString())
    GatewayConfiguration.set(document, second.groupId.toString(), "admin", "1")
    GatewayConfiguration.set(document, second.groupId.toString(), "enabled", "1")
    xml = document.toXml()
    val config = GatewayConfiguration.parse(document)
    val aci = Recipient.resolved(applicant).requireAci().toString()
    repeat(3) { index ->
      val unrelated = recipients.createRecipient("Unrelated $index")
      store.intake(config.groups.getValue(first.groupId.toString()), Recipient.resolved(unrelated).requireAci().toString(), 1, config.defaults, 100)
    }
    val rows = listOf(first, second).map {
      val row = store.intake(config.groups.getValue(it.groupId.toString()), aci, 1, config.defaults, 100)
      store.save(row, row.transition(GatewayPhase.WAITING_FOR_DECISION))
      store.request(row.id)!!
    }
    val replyId = incoming(applicant, room.groupId, "I want to join", 150)
    SignalGatewayPlatform(context, store).notify(rows, replyId)
    val notification = sent.single()
    assertTrue(notification.mentions.any { it.recipientId == firstAdmin })
    assertFalse(notification.mentions.any { it.recipientId == secondAdmin || it.recipientId == recipients.self })
    assertTrue(notification.mentions.count { it.recipientId == applicant } >= 5)
    assertFalse(notification.body.contains("@applicant"))
    assertTrue(notification.body.contains("1 → Signal Community"))
    assertTrue(notification.body.contains("2 → Second source"))
    assertTrue(notification.body.contains("!approve \uFFFC 1"))
    assertTrue(notification.body.contains("!deny \uFFFC 2"))
    assertFalse(notification.body.contains("!approve \uFFFC ${rows.first().id}"))
    assertEquals(150L, notification.outgoingQuote!!.id)
  }

  @Test fun `human admin loss during a native conflict retry prevents approval`() {
    val admin = recipients.createRecipient("Admin")
    val applicant = recipients.createRecipient("Applicant")
    val group = recipients.createGroup(admin)
    configureSource(group)
    val id = group.groupId.toString()
    update(id, "admin", "1"); update(id, "groupid", id); update(id, "enabled", "1")
    val original = SignalDatabase.groups.requireGroup(group.groupId).requireV2GroupProperties().decryptedGroup
    SignalDatabase.groups.update(group.groupId, original.copy(requestingMembers = listOf(DecryptedRequestingMember(aciBytes = Recipient.resolved(applicant).requireAci().toByteString(), timestamp = 1))), null)
    val config = GatewayConfiguration.parse(CommandDocument.parse(xml))
    val request = store.intake(config.groups.getValue(id), Recipient.resolved(applicant).requireAci().toString(), 1, config.defaults, 100)
    store.save(request, request.transition(GatewayPhase.APPROVING))
    mockkStatic(GatewayGroupActions::class)
    var checks = 0
    every { GatewayGroupActions.apply(any(), any(), any(), any(), any()) } answers {
      val guard = arg<Runnable>(4)
      guard.run(); checks++
      demote(group, admin)
      guard.run(); checks++
      null
    }
    assertThrows(IllegalArgumentException::class.java) {
      SignalGatewayPlatform(context, store).mutate(store.request(request.id)!!, GatewayMutation.APPROVE, Recipient.resolved(admin).requireAci().toString())
    }
    assertEquals(1, checks)
    assertTrue(SignalDatabase.groups.requireGroup(group.groupId).requireV2GroupProperties().decryptedGroup.requestingMembers.isNotEmpty())
  }

  @Test fun `default group scan cooldown stays zero and Gateway throttle does not disable scans`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    configureSource(group)
    update(group.groupId.toString(), "throttle", "1")
    repeat(2) {
      // False hands the command to the unchanged ordinary group scan path.
      assertFalse(GatewayController.handle(context, incoming(admin, group.groupId, "!scan", 100L + it)))
    }
    assertTrue(sent.isEmpty())
  }

  @Test fun `a membership refresh retry does not download the DM blocklist a second time`() {
    val admin = recipients.createRecipient("Admin", profileSharing = false)
    recipients.createGroup(admin)
    GatewayController.handle(context, incoming(admin, body = "!scan"))
    sent.forEach {
      SignalDatabase.messages.markAsSent(SignalDatabase.messages.getMessageFor(it.sentTimeMillis, recipients.self)!!.id)
      GatewayRuntime.sent(context, admin.toLong(), it.sentTimeMillis)
    }
    var failOnce = true
    every { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) } answers {
      if (failOnce) { failOnce = false; throw java.io.IOException("Temporary native group read failure") }
    }
    val refresh = jobs.filterIsInstance<GatewayDmScanRefreshJob>().single()
    refresh.setContext(context)
    assertFalse(refresh.run().isSuccess)
    assertTrue(refresh.run().isSuccess)
    verify(exactly = 1) { GroupTable.getBlocklist(forceRefresh = true) }
  }

  @Test fun `a colliding timestamp cannot classify another Signal message as Assistant automation`() {
    xml = xml.replace("<paused>0</paused>", "<paused>1</paused>")
    store.reserveOutgoing("command:0", 0, "command", 1000)
    store.bindRecipient(1000, 7)
    store.outgoingInserted("command:0", 99)
    assertTrue(GatewayRuntime.maySend(context, 1000, 8, 100))
    assertTrue(GatewayRuntime.maySend(context, 1000, 7, 100))
    assertFalse(GatewayRuntime.maySend(context, 1000, 7, 99))
  }

  @Test fun `tracking failure after successful transport cannot fail the native Signal send`() {
    every { GatewayDatabase.get(any()) } throws IllegalStateException("Simulated unavailable tracking store")
    GatewayRuntime.sent(context, 7, 1000)
    // Profile sharing fails closed while ordinary untracked Signal transport can continue.
    assertTrue(GatewayRuntime.preservesContact(context, 7, 1000))
    assertTrue(GatewayRuntime.maySend(context, 1000, 7, 99))
  }

  @Test fun `an early native acknowledgement retains contact protection until the Signal send commit`() {
    val applicant = recipients.createRecipient("Applicant", profileSharing = false)
    val target = Recipient.resolved(applicant)
    val platform = SignalGatewayPlatform(context, store)
    val reply = platform.template("dm_status", mapOf("operation_status" to GatewayText("Status")))
    val id = platform.sendOnce("private-status:17", 0, "dm_status", target, reply).single()
    val timestamp = sent.single().sentTimeMillis
    GatewayRuntime.acknowledged(context, applicant.toLong(), timestamp, id)
    assertTrue(GatewayRuntime.preservesContact(context, applicant.toLong(), timestamp))
    assertNull(platform.sendState(id).sentAt)
    SignalDatabase.messages.markAsSent(id)
    GatewayRuntime.sent(context, applicant.toLong(), timestamp, id)
    assertFalse(GatewayRuntime.preservesContact(context, applicant.toLong(), timestamp))
    assertFalse(Recipient.resolved(applicant).fresh().isProfileSharing)
  }

  @Test fun `source administrator can approve an unanswered request using a native mention`() {
    val admin = recipients.createRecipient("Source admin")
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup(admin)
    val room = recipients.createGroup(admin, applicant)
    demote(room, admin) // Room administration is not the source authorization.
    val unrelated = recipients.createRecipient("Unrelated")
    val otherRequest = waitingForResponse(source, room, unrelated, System.currentTimeMillis() - 20000)
    val request = waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    val input = incoming(admin, room.groupId, "!approve \uFFFC 1", mentions = listOf(Mention(applicant, 9, 1)))

    assertTrue(GatewayController.handle(context, input))

    val claimed = store.request(request.id)!!
    assertEquals(GatewayPhase.APPROVING, claimed.phase)
    assertTrue(request.id > request.number)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(otherRequest.id)!!.phase)
    assertEquals(Recipient.resolved(admin).requireAci().toString(), claimed.data.getString("actor"))
    assertFalse(claimed.data.has("replyId"))
    assertTrue(jobs.any { it is GatewayWorkJob })
    assertTrue(sent.isEmpty())
  }

  @Test fun `queued numbered command cannot target a later applicant session with the same number`() {
    val admin = recipients.createRecipient("Source admin")
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup(admin)
    val room = recipients.createGroup(admin, applicant)
    val started = System.currentTimeMillis() - 20000
    val old = waitingForResponse(source, room, applicant, started)
    val input = incoming(admin, room.groupId, "!approve \uFFFC 1", started + 1000, receivedTime = started + 1000, mentions = listOf(Mention(applicant, 9, 1)))
    store.finish(store.request(old.id)!!, "manual")
    val config = GatewayConfiguration.parse(CommandDocument.parse(xml))
    val fresh = store.intake(config.groups.getValue(source.groupId.toString()), Recipient.resolved(applicant).requireAci().toString(), 2, config.defaults, started + 2000)
    assertEquals(1L, fresh.number)

    GatewayController.handle(context, input)

    assertEquals(GatewayPhase.INTAKE, store.request(fresh.id)!!.phase)
    assertTrue(sent.single().body.contains("predates the current review"))
    val current = incoming(admin, room.groupId, "!approve \uFFFC 1", started + 3000, receivedTime = started + 3000, mentions = listOf(Mention(applicant, 9, 1)))
    GatewayController.handle(context, current)
    assertEquals(GatewayPhase.APPROVING, store.request(fresh.id)!!.phase)
  }

  @Test fun `room admins see media controls before activation and can enable and disable them privately`() {
    val admin = recipients.createRecipient("Room admin", profileSharing = false)
    val source = recipients.createGroup()
    val room = recipients.createGroup(admin)
    configureSource(source)
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_GATEWAY_ID_HEX", room.groupId.toString())
    acceptConfigurationWrites()
    GatewayController.handle(context, incoming(admin, room.groupId, "!help"))
    assertTrue(sent.single().body.contains("!gateway deletemedia"))
    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway", 101))
    assertEquals(admin, sent.last().threadRecipient.id)
    assertTrue(sent.last().body.contains("Media deletion in Gateway review room: disabled"))
    assertTrue(sent.last().body.contains("!gateway deletemedia"))

    listOf("enable", "disable").forEachIndexed { index, toggle ->
      GatewayController.handle(context, incoming(admin, room.groupId, "!gateway deletemedia $toggle", 102L + index * 2))
      val updated = GatewayConfiguration.parse(CommandDocument.parse(xml))
      assertEquals(toggle == "enable", updated.groups.getValue(room.groupId.toString()).deleteMedia)
      assertFalse(updated.groups.getValue(source.groupId.toString()).deleteMedia)
      assertEquals(admin, sent.last().threadRecipient.id)
      assertFalse(Recipient.resolved(admin).fresh().isProfileSharing)
      GatewayController.handle(context, incoming(admin, room.groupId, "!gateway", 103L + index * 2))
      assertTrue(sent.last().body.contains("Media deletion in Gateway review room: ${if (toggle == "enable") "enabled" else "disabled"}"))
    }
    update(room.groupId.toString(), "throttle", "1")
    GatewayController.handle(context, incoming(admin, room.groupId, "!help", 106))
    assertFalse(sent.last().body.contains("!gateway deletemedia"))
  }

  @Test fun `media settings require current room administration and the Gateway room context`() {
    val sourceAdmin = recipients.createRecipient("Source admin")
    val source = recipients.createGroup(sourceAdmin)
    val room = recipients.createGroup(sourceAdmin)
    demote(room, sourceAdmin)
    configureSource(source)
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_GATEWAY_ID_HEX", room.groupId.toString())
    acceptConfigurationWrites()
    GatewayController.handle(context, incoming(sourceAdmin, room.groupId, "!gateway deletemedia enable"))
    verify(exactly = 0) { GitHubAutoReply.updateConfiguration(any(), any()) }
    assertTrue(sent.isEmpty())
    GatewayController.handle(context, incoming(sourceAdmin, source.groupId, "!gateway deletemedia enable", 101))
    assertTrue(sent.single().body.contains("in the configured Gateway group"))
    assertFalse(GatewayConfiguration.parse(CommandDocument.parse(xml)).groups.getValue(source.groupId.toString()).deleteMedia)
  }

  @Test fun `referenced lobby without any configuration entry can create its private media policy`() {
    val admin = recipients.createRecipient("Lobby admin", profileSharing = false)
    val source = recipients.createGroup()
    val room = recipients.createGroup(admin)
    rename(room, "Unlisted lobby")
    configureSource(source)
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_GATEWAY_ID_HEX", room.groupId.toString())
    val initial = CommandDocument.parse(xml)
    val universal = initial.document.documentElement.direct("gateway")!!
    universal.removeChild(universal.direct("privategroups")!!)
    xml = initial.toXml()
    assertFalse(GatewayConfiguration.parse(initial).groups.containsKey(room.groupId.toString()))
    acceptConfigurationWrites()

    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway deletemedia enable"))

    val updated = CommandDocument.parse(xml)
    val policy = GatewayConfiguration.parse(updated).groups.getValue(room.groupId.toString())
    assertTrue(policy.deleteMedia)
    assertFalse(policy.publiclyListed)
    val privateGroups = updated.document.documentElement.direct("gateway")!!.direct("privategroups")!!
    assertEquals(room.groupId.toString(), privateGroups.direct("group")!!.direct("groupid")!!.textContent)
    assertEquals("1", privateGroups.direct("group")!!.direct("silent")!!.textContent)
    assertFalse(updated.render("!groups") { org.thoughtcrime.securesms.github.CommandLinkState() }!!.text.contains("Unlisted lobby"))
    assertFalse(Recipient.resolved(admin).fresh().isProfileSharing)
    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway", 101))
    assertTrue(sent.last().body.contains("Media deletion in Unlisted lobby: enabled"))
  }

  @Test fun `losing the room administrator role during an XML write cannot change media settings`() {
    val admin = recipients.createRecipient("Room admin")
    val source = recipients.createGroup()
    val room = recipients.createGroup(admin)
    configureSource(source)
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_GATEWAY_ID_HEX", room.groupId.toString())
    every { GitHubAutoReply.updateConfiguration(any(), any()) } answers {
      demote(room, admin)
      secondArg<(CommandDocument) -> Boolean>().invoke(CommandDocument.parse(xml))
    }
    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway deletemedia enable"))
    assertFalse(GatewayConfiguration.parse(CommandDocument.parse(xml)).groups.getValue(room.groupId.toString()).deleteMedia)
    assertFalse(sent.last().body.contains("configuration updated"))
  }

  @Test fun `media control enabled by command queues deletion only for active applicant attachments`() {
    val admin = recipients.createRecipient("Room admin")
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(admin, applicant)
    val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
    acceptConfigurationWrites()
    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway deletemedia enable"))
    val media = incoming(applicant, room.groupId, "caption", System.currentTimeMillis(), attachments = listOf(image()))
    observe(media)
    assertEquals(1, jobs.filterIsInstance<AdminDeleteSendJob>().size)
    assertTrue(store.mediaDeleteTracked(media))
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    GatewayController.handle(context, incoming(admin, room.groupId, "!gateway deletemedia disable", 101))
    val other = incoming(applicant, room.groupId, "another caption", System.currentTimeMillis() + 1, attachments = listOf(image()))
    observe(other)
    assertEquals(1, jobs.filterIsInstance<AdminDeleteSendJob>().size)
    assertFalse(GatewayRuntime.mayDelete(context, media))
  }

  @Test fun `queued prompts remain cancelled after early approval tracking is pruned`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
    val prompt = recipients.insertOutgoingMessage(room.recipientId, "Pending question")
    val timestamp = SignalDatabase.messages.getMessageRecord(prompt).dateSent
    val key = "review:${request.id}:prompt:0"
    store.reserveOutgoing(key, request.id, "spam", timestamp)
    store.outgoingInserted(key, prompt)
    store.finish(store.request(request.id)!!, "approved")
    val finished = store.request(request.id)!!
    store.compactFinished(finished)
    store.prune(finished, true, true, true)
    assertNull(store.request(request.id))
    assertFalse(GatewayRuntime.maySend(context, timestamp, room.recipientId.toLong(), prompt))
    assertNull(store.outgoing(key))
  }

  @Test fun `applicant link previews with and without thumbnails are deleted and do not answer screening`() {
    listOf(false, true).forEachIndexed { index, thumbnail ->
      val applicant = recipients.createRecipient("Applicant $index")
      val room = recipients.createGroup(applicant)
      val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
      update(room.groupId.toString(), "deletemedia", "1")
      val preview = LinkPreview("https://example.org/$index", "Example", "Description", 0L, if (thumbnail) Optional.of(image()) else Optional.empty<Attachment>())
      val id = incoming(applicant, room.groupId, "See ${preview.url}", System.currentTimeMillis(), previews = listOf(preview))
      val message = SignalDatabase.messages.getMessageRecord(id).withAttachments() as MmsMessageRecord
      assertTrue(message.slideDeck.slides.isEmpty())
      assertEquals(1, message.linkPreviews.size)
      assertEquals(thumbnail, message.linkPreviews.single().thumbnail.isPresent)

      observe(id)

      assertEquals(index + 1, jobs.filterIsInstance<AdminDeleteSendJob>().size)
      assertTrue(store.mediaDeleteTracked(id))
      assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    }
    assertTrue(sent.isEmpty())
  }

  @Test fun `link-preview deletion respects disabled policy applicant scope and current bot permission`() {
    val applicant = recipients.createRecipient("Applicant")
    val other = recipients.createRecipient("Other member")
    val room = recipients.createGroup(applicant, other)
    val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
    val preview = LinkPreview("https://example.org/", "Example", "Description", 0L, Optional.empty<Attachment>())
    observe(incoming(applicant, room.groupId, preview.url, System.currentTimeMillis(), previews = listOf(preview)))
    assertTrue(jobs.filterIsInstance<AdminDeleteSendJob>().isEmpty())
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    update(room.groupId.toString(), "deletemedia", "1")
    observe(incoming(other, room.groupId, preview.url, System.currentTimeMillis() + 1, previews = listOf(preview)))
    demote(room, recipients.self)
    observe(incoming(applicant, room.groupId, preview.url, System.currentTimeMillis() + 2, previews = listOf(preview)))
    assertTrue(jobs.filterIsInstance<AdminDeleteSendJob>().isEmpty())
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
  }

  @Test fun `a plain text URL without a native preview remains a qualifying text response`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
    update(room.groupId.toString(), "deletemedia", "1")
    observe(incoming(applicant, room.groupId, "I found the group on https://example.org/", System.currentTimeMillis()))
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(request.id)!!.phase)
    assertTrue(jobs.filterIsInstance<AdminDeleteSendJob>().isEmpty())
    assertEquals(1, sent.size)
  }

  @Test fun `request polling refreshes only enabled sources without online XML or blocklist checks`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    jobs.clear()
    val job = GatewayRequestPollJob().also { it.setContext(context) }
    val result = job.run()
    assertTrue(result.isRetry)
    assertEquals(30000L, pollBackoff(result))
    verify(exactly = 1) { GroupManager.forceSanityUpdateFromServer(any(), source.masterKey, any()) }
    verify(exactly = 0) { GroupManager.forceSanityUpdateFromServer(any(), room.masterKey, any()) }
    verify(exactly = 0) { GitHubAutoReply.configurationSnapshot(any(), true) }
    verify(exactly = 0) { GroupTable.getBlocklist(any()) }
    assertTrue(jobs.isEmpty())
    assertNotEquals(GatewayWorkJob(1).parameters.queue, job.parameters.queue)
  }

  @Test fun `request polling discovers a server join without a received group event`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    configureSource(source)
    update(source.groupId.toString(), "groupid", room.groupId.toString())
    update(source.groupId.toString(), "enabled", "1")
    val current = SignalDatabase.groups.requireGroup(source.groupId).requireV2GroupProperties().decryptedGroup
    every { GroupManager.forceSanityUpdateFromServer(any(), source.masterKey, any()) } answers {
      SignalDatabase.groups.update(source.groupId, current.copy(requestingMembers = listOf(
        DecryptedRequestingMember(aciBytes = Recipient.resolved(applicant).requireAci().toByteString(), timestamp = 1)
      )), null)
    }
    assertTrue(GatewayRequestPollJob().also { it.setContext(context) }.run().isRetry)
    val intake = jobs.filterIsInstance<GatewayWorkJob>().first { JsonJobData.deserialize(it.serialize()).getBoolean("intake") }
    justRun { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
    intake.setContext(context)
    assertTrue(intake.run().isSuccess)
    assertEquals(1L, store.requests().single().number)
    assertEquals(Recipient.resolved(applicant).requireAci().toString(), store.requests().single().aci)
  }

  @Test fun `polling stops for emergency pause source throttle and explicit polling disable`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    val job = GatewayRequestPollJob().also { it.setContext(context) }
    xml = xml.replace("<paused>0</paused>", "<paused>1</paused>")
    assertTrue(job.run().isSuccess)
    xml = xml.replace("<paused>1</paused>", "<paused>0</paused>")
    update(source.groupId.toString(), "throttle", "1")
    assertTrue(job.run().isSuccess)
    update(source.groupId.toString(), "throttle", "0")
    val document = CommandDocument.parse(xml)
    val defaults = document.document.documentElement.direct("gateway")!!.direct("defaults")!!
    (defaults.direct("joinpollseconds") ?: document.document.createElement("joinpollseconds").also(defaults::appendChild)).textContent = "0"
    xml = document.toXml()
    assertTrue(job.run().isSuccess)
    verify(exactly = 0) { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
  }

  @Test fun `one unavailable source does not block other request refreshes and restored jobs use current timing`() {
    val applicant = recipients.createRecipient("Applicant")
    val first = recipients.createGroup()
    val second = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    waitingForResponse(first, room, applicant, System.currentTimeMillis() - 20000)
    waitingForResponse(second, room, applicant, System.currentTimeMillis() - 20000)
    every { GroupManager.forceSanityUpdateFromServer(any(), first.masterKey, any()) } throws java.io.IOException("Unavailable")
    val job = GatewayRequestPollJob()
    val restored = GatewayRequestPollJob.Factory().create(job.parameters, job.serialize()).also { it.setContext(context) }
    val result = restored.run()
    assertTrue(result.isRetry)
    assertEquals(30000L, pollBackoff(result))
    verify(exactly = 1) { GroupManager.forceSanityUpdateFromServer(any(), second.masterKey, any()) }
  }

  @Test fun `polling honours a current interval change and server retry after`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    val document = CommandDocument.parse(xml)
    val defaults = document.document.documentElement.direct("gateway")!!.direct("defaults")!!
    (defaults.direct("joinpollseconds") ?: document.document.createElement("joinpollseconds").also(defaults::appendChild)).textContent = "60"
    xml = document.toXml()
    val job = GatewayRequestPollJob().also { it.setContext(context) }
    assertEquals(60000L, pollBackoff(job.run()))
    every { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) } throws NonSuccessfulResponseCodeException(429, "Rate limited", "", mapOf("Retry-After" to "120"))
    assertEquals(120000L, pollBackoff(job.run()))
  }

  @Test fun `approval command retries a busy native group promptly without losing the decision`() {
    val admin = recipients.createRecipient("Source admin")
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup(admin)
    val room = recipients.createGroup(admin, applicant)
    val request = waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    val id = incoming(admin, room.groupId, "!approve \uFFFC 1", mentions = listOf(Mention(applicant, 9, 1)))
    every { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) } throws org.signal.core.util.groups.GroupChangeBusyException("Busy")
    val job = GatewayCommandJob(id).also { it.setContext(context) }
    val result = job.run()
    assertTrue(result.isRetry)
    assertEquals(1000L, pollBackoff(result))
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    justRun { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
    assertTrue(job.run().isSuccess)
    assertEquals(GatewayPhase.APPROVING, store.request(request.id)!!.phase)
  }

  @Test fun `unchanged room updates neither rediscover all sources nor wake waiting applicants`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    jobs.clear()
    GatewayRuntime.work(context, 0, room.groupId.toString(), emptyList(), 0)
    assertTrue(jobs.isEmpty())
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    verify(exactly = 0) { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.configurationSnapshot(any(), true) }
  }

  @Test fun `waiting work uses committed pending state without forcing network refreshes`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup()
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    val state = SignalDatabase.groups.requireGroup(source.groupId).requireV2GroupProperties().decryptedGroup
    SignalDatabase.groups.update(source.groupId, state.copy(requestingMembers = listOf(
      DecryptedRequestingMember(aciBytes = Recipient.resolved(applicant).requireAci().toByteString(), timestamp = 1)
    )), null)
    GatewayRuntime.work(context, request.id, "", emptyList(), 0)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    verify(exactly = 0) { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.configurationSnapshot(any(), true) }
  }

  @Test fun `external source approval still wakes and resolves the affected review`() {
    val applicant = recipients.createRecipient("Applicant")
    val source = recipients.createGroup(applicant)
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(source, room, applicant, System.currentTimeMillis() - 20000)
    jobs.clear()
    GatewayRuntime.work(context, 0, source.groupId.toString(), emptyList(), 0)
    val work = jobs.filterIsInstance<GatewayWorkJob>().first { JsonJobData.deserialize(it.serialize()).getLong("request") == request.id }
    work.setContext(context)
    assertTrue(work.run().isSuccess)
    assertEquals("externally_approved", store.request(request.id)!!.data.getString("reason"))
  }

  @Test fun `a queued reply cannot notify administrators after a committed room departure`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val request = waitingForResponse(room, room, applicant, System.currentTimeMillis() - 20000)
    val aci = Recipient.resolved(applicant).requireAci()
    store.saveSession(room.groupId.toString(), aci.toString(), store.session(room.groupId.toString(), aci.toString()).put("present", true))
    val reply = incoming(applicant, room.groupId, "My answer", System.currentTimeMillis())
    val state = SignalDatabase.groups.requireGroup(room.groupId).requireV2GroupProperties().decryptedGroup
    SignalDatabase.groups.update(room.groupId, state.copy(members = state.members.filterNot { it.aciBytes == aci.toByteString() }), null)
    observe(reply)
    assertEquals("unplanned_gateway_departure", store.request(request.id)!!.data.getString("reason"))
    assertTrue(sent.isEmpty())
  }

  @Test fun `published configuration parsing is reused only for the same accepted revision`() {
    val first = CommandsSnapshot(xml, "performance-test-first", null)
    every { GitHubAutoReply.configurationSnapshot(any(), any()) } returns first
    val cached = GatewayRuntime.configuration(context)
    assertSame(cached, GatewayRuntime.configuration(context))
    val next = CommandsSnapshot(xml.replace("<paused>0</paused>", "<paused>1</paused>"), "performance-test-next", null)
    every { GitHubAutoReply.configurationSnapshot(any(), any()) } returns next
    assertTrue(GatewayRuntime.configuration(context)!!.paused)
    assertNotSame(cached, GatewayRuntime.configuration(context))
  }

  @Test fun `group conversion caching follows native content and never bypasses a requested refresh`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    val platform = SignalGatewayPlatform(context, store)
    val id = group.groupId.toString()
    val initial = platform.group(id, false)
    assertSame(initial, platform.group(id, false))
    demote(group, admin)
    val changed = platform.group(id, false)
    assertFalse(Recipient.resolved(admin).requireAci().toString() in changed.admins)
    assertNotSame(initial, changed)
    every { GroupManager.forceSanityUpdateFromServer(any(), any(), any()) } answers { demote(group, recipients.self) }
    assertFalse(platform.group(id, true).botAdmin)
    verify(exactly = 1) { GroupManager.forceSanityUpdateFromServer(any(), group.masterKey, any()) }
  }

  @Test fun `source mutations refresh the target once when it is the source itself`() {
    val admin = recipients.createRecipient("Admin")
    val applicant = recipients.createRecipient("Applicant")
    val group = recipients.createGroup(admin)
    configureSource(group)
    val id = group.groupId.toString()
    update(id, "groupid", id); update(id, "enabled", "1"); update(id, "admin", "1")
    val state = SignalDatabase.groups.requireGroup(group.groupId).requireV2GroupProperties().decryptedGroup
    SignalDatabase.groups.update(group.groupId, state.copy(requestingMembers = listOf(
      DecryptedRequestingMember(aciBytes = Recipient.resolved(applicant).requireAci().toByteString(), timestamp = 1)
    )), null)
    val config = GatewayConfiguration.parse(CommandDocument.parse(xml))
    val request = store.intake(config.groups.getValue(id), Recipient.resolved(applicant).requireAci().toString(), 1, config.defaults, 100)
    store.save(request, request.transition(GatewayPhase.APPROVING))
    mockkStatic(GatewayGroupActions::class)
    every { GatewayGroupActions.apply(any(), any(), any(), any(), any()) } answers { arg<Runnable>(4).run(); null }

    SignalGatewayPlatform(context, store).mutate(store.request(request.id)!!, GatewayMutation.APPROVE, Recipient.resolved(admin).requireAci().toString())

    verify(exactly = 1) { GroupManager.forceSanityUpdateFromServer(any(), group.masterKey, any()) }
    verify(exactly = 1) { GitHubAutoReply.configurationSnapshot(any(), true) }
  }

  @Test fun `urgent work keeps room serialization and its priority across delayed retries`() {
    jobs.clear()
    val admission = GatewayWorkJob(1, 0, "room")
    val reply = GatewayWorkJob(2, true, "room")
    val departure = GatewayWorkJob("room", listOf("applicant"))
    assertEquals(admission.parameters.queue, reply.parameters.queue)
    assertEquals(reply.parameters.queue, departure.parameters.queue)
    assertTrue(queuePriority(reply) > queuePriority(admission))
    assertEquals(queuePriority(reply), queuePriority(departure))
    val delayed = GatewayWorkJob(1, 1000, "room", urgent = true)
    val restored = GatewayWorkJob.Factory().create(delayed.parameters, delayed.serialize()).also { it.setContext(context) }
    assertTrue(restored.run().isSuccess)
    assertEquals(queuePriority(reply), queuePriority(jobs.filterIsInstance<GatewayWorkJob>().single()))
  }

  @Test fun `persisted work without a priority flag remains readable after upgrade`() {
    val job = GatewayWorkJob(1)
    val legacy = JsonJobData.Builder().putLong("request", 1).putString("group", "").putStringArray("removed", emptyArray())
      .putLong("message", 0).putBoolean("timer", false).putString("room", "").putBoolean("intake", false).serialize()
    val restored = GatewayWorkJob.Factory().create(job.parameters, legacy)
    assertFalse(JsonJobData.deserialize(restored.serialize()).getBoolean("urgent"))
  }

  @Test fun `text replies quoting the prompt or another message satisfy screening and notify against the answer`() {
    listOf(true, false).forEachIndexed { index, quotingPrompt ->
      val applicant = recipients.createRecipient("Applicant $index")
      val other = recipients.createRecipient("Other $index")
      val room = recipients.createGroup(applicant, other)
      val promptAt = System.currentTimeMillis() - 20000
      val request = waitingForResponse(room, room, applicant, promptAt)
      val targetId = if (quotingPrompt) {
        recipients.insertOutgoingMessage(room.recipientId, "Why would you like to join?", promptAt)
      } else {
        incoming(other, room.groupId, "Another conversation", promptAt - 1000)
      }
      val target = SignalDatabase.messages.getMessageRecord(targetId)
      val quote = QuoteModel(target.dateSent, target.fromRecipient.id, target.body, false, null, null, QuoteModel.Type.NORMAL, null)
      val answerId = incoming(applicant, room.groupId, "I'd like to participate", promptAt + 1000, quote = quote)
      assertNotNull((SignalDatabase.messages.getMessageRecord(answerId) as MmsMessageRecord).quote)
      val before = sent.size

      observe(answerId)

      assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(request.id)!!.phase)
      assertEquals(answerId, store.request(request.id)!!.data.getLong("replyId"))
      assertEquals(before + 1, sent.size)
      assertEquals(promptAt + 1000, sent.last().outgoingQuote!!.id)
      assertEquals(applicant, sent.last().outgoingQuote!!.author)
    }
  }

  @Test fun `text quoting a media message counts without deleting the applicant reply`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val promptAt = System.currentTimeMillis() - 20000
    val request = waitingForResponse(room, room, applicant, promptAt)
    update(room.groupId.toString(), "deletemedia", "1")
    val quote = QuoteModel(promptAt - 1000, recipients.self, "A photo", false, image(quoted = true), null, QuoteModel.Type.NORMAL, null)
    val answerId = incoming(applicant, room.groupId, "I enjoy this topic", promptAt + 1000, quote = quote)
    val message = SignalDatabase.messages.getMessageRecord(answerId).withAttachments() as MmsMessageRecord
    assertTrue(message.quote!!.attachment.slides.isNotEmpty())
    assertTrue(message.slideDeck.slides.isEmpty())

    observe(answerId)

    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(request.id)!!.phase)
    assertTrue(jobs.filterIsInstance<AdminDeleteSendJob>().isEmpty())
    assertEquals(promptAt + 1000, sent.single().outgoingQuote!!.id)
  }

  @Test fun `quoted content alone and quoted commands do not satisfy screening`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val promptAt = System.currentTimeMillis() - 20000
    val request = waitingForResponse(room, room, applicant, promptAt)
    val quote = QuoteModel(promptAt, recipients.self, "Nonempty quoted question", false, null, null, QuoteModel.Type.NORMAL, null)
    listOf("", "   ", "!help", "#command").forEachIndexed { index, body ->
      observe(incoming(applicant, room.groupId, body, promptAt + 1000 + index, quote = quote))
      assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    }
    assertTrue(sent.isEmpty())
  }

  @Test fun `media attached to a quoted reply does not satisfy screening even with a caption`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val promptAt = System.currentTimeMillis() - 20000
    val request = waitingForResponse(room, room, applicant, promptAt)
    val quote = QuoteModel(promptAt, recipients.self, "The question", false, null, null, QuoteModel.Type.NORMAL, null)
    val answerId = incoming(applicant, room.groupId, "A caption", promptAt + 1000, quote = quote, attachments = listOf(image()))
    assertTrue((SignalDatabase.messages.getMessageRecord(answerId).withAttachments() as MmsMessageRecord).slideDeck.slides.isNotEmpty())

    observe(answerId)

    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    assertTrue(sent.isEmpty())
  }

  @Test fun `quoted replies still require the applicant identity and must follow the prompt`() {
    val applicant = recipients.createRecipient("Applicant")
    val other = recipients.createRecipient("Other")
    val room = recipients.createGroup(applicant, other)
    val promptAt = System.currentTimeMillis() - 20000
    val request = waitingForResponse(room, room, applicant, promptAt)
    val quote = QuoteModel(promptAt - 2000, other, "Older message", false, null, null, QuoteModel.Type.NORMAL, null)
    observe(incoming(other, room.groupId, "I'll answer for them", promptAt + 1000, quote = quote))
    observe(incoming(applicant, room.groupId, "An earlier reply", promptAt - 1000, quote = quote, receivedTime = promptAt - 1000))
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(request.id)!!.phase)
    assertTrue(sent.isEmpty())
  }

  @Test fun `one quoted answer satisfies the shared current batch while later prompts need another reply`() {
    val applicant = recipients.createRecipient("Applicant")
    val room = recipients.createGroup(applicant)
    val promptAt = System.currentTimeMillis() - 20000
    val first = waitingForResponse(recipients.createGroup(), room, applicant, promptAt)
    val second = waitingForResponse(recipients.createGroup(), room, applicant, promptAt)
    val quote = QuoteModel(promptAt, recipients.self, "First question", false, null, null, QuoteModel.Type.NORMAL, null)
    val answerId = incoming(applicant, room.groupId, "My answer", promptAt + 1000, quote = quote, receivedTime = promptAt + 1000)
    observe(answerId)
    listOf(first, second).forEach {
      assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(it.id)!!.phase)
      assertEquals(answerId, store.request(it.id)!!.data.getLong("replyId"))
    }
    assertEquals(1, sent.size)
    val third = waitingForResponse(recipients.createGroup(), room, applicant, promptAt + 2000)
    observe(answerId) // Replayed older work cannot satisfy the new prompt.
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(third.id)!!.phase)
    assertEquals(1, sent.size)

    val laterAnswer = incoming(applicant, room.groupId, "Another answer", promptAt + 3000, quote = quote, receivedTime = promptAt + 3000)
    observe(laterAnswer)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(third.id)!!.phase)
    assertEquals(laterAnswer, store.request(third.id)!!.data.getLong("replyId"))
    assertEquals(2, sent.size)
    assertEquals(promptAt + 3000, sent.last().outgoingQuote!!.id)
    listOf(first, second).forEach { assertEquals(answerId, store.request(it.id)!!.data.getLong("replyId")) }
  }

  private fun waitingForResponse(source: RecipientTestRule.TestGroupInfo, room: RecipientTestRule.TestGroupInfo, applicant: RecipientId, promptAt: Long): GatewayRequest {
    configureSource(source)
    val document = CommandDocument.parse(xml)
    val id = source.groupId.toString()
    GatewayConfiguration.ensureRoom(document, id, "Source")
    GatewayConfiguration.set(document, id, "groupid", room.groupId.toString())
    GatewayConfiguration.set(document, id, "enabled", "1")
    GatewayConfiguration.set(document, id, "admin", "1")
    xml = document.toXml()
    val config = GatewayConfiguration.parse(document)
    val request = store.intake(config.groups.getValue(id), Recipient.resolved(applicant).requireAci().toString(), 1, config.defaults, promptAt)
    assertTrue(store.save(request, request.transition(GatewayPhase.WAITING_FOR_RESPONSE) { it.put("promptAt", promptAt) }))
    return store.request(request.id)!!
  }

  private fun observe(messageId: Long) = GatewayRuntime.work(context, 0, "", emptyList(), messageId)

  private fun pollBackoff(result: Job.Result): Long = Job.Result::class.java.getDeclaredMethod("getBackoffInterval").apply { isAccessible = true }.invoke(result) as Long
  private fun queuePriority(job: Job): Int = Job.Parameters::class.java.getDeclaredMethod("getQueuePriority").apply { isAccessible = true }.invoke(job.parameters) as Int

  private fun image(quoted: Boolean = false): Attachment = PointerAttachment(
    contentType = "image/jpeg", transferState = AttachmentTable.TRANSFER_PROGRESS_PENDING, size = 0, fileName = "photo.jpg",
    cdn = Cdn.CDN_0, location = "123", key = null, iv = null, digest = null, incrementalDigest = null,
    incrementalMacChunkSize = 0, fastPreflightId = null, voiceNote = false, borderless = false, videoGif = false,
    width = 100, height = 100, uploadTimestamp = 0, caption = null, stickerLocator = null, blurHash = null, uuid = null, quote = quoted
  )

  private fun incoming(sender: RecipientId, group: GroupId? = null, body: String, timestamp: Long = 100,
                       quote: QuoteModel? = null, attachments: List<Attachment> = emptyList(), receivedTime: Long = System.currentTimeMillis(), mentions: List<Mention> = emptyList(), previews: List<LinkPreview> = emptyList()): Long = SignalDatabase.messages.insertMessageInbox(
    IncomingMessage(type = MessageType.NORMAL, from = sender, sentTimeMillis = timestamp, serverTimeMillis = timestamp,
      receivedTimeMillis = receivedTime, groupId = group, body = body, isUnidentified = true, quote = quote, attachments = attachments, mentions = mentions, linkPreviews = previews)
  ).get().messageId

  private fun acceptConfigurationWrites() {
    every { GitHubAutoReply.updateConfiguration(any(), any()) } answers {
      val latest = CommandDocument.parse(xml)
      secondArg<(CommandDocument) -> Boolean>().invoke(latest)
      xml = latest.toXml()
    }
  }

  private fun configureSource(group: RecipientTestRule.TestGroupInfo) {
    xml = xml.replace("__signal_group__v2__!REPLACE_WITH_SOURCE_ID_HEX", group.groupId.toString())
  }
  private fun update(id: String, field: String, value: String) {
    val document = CommandDocument.parse(xml)
    GatewayConfiguration.set(document, id, field, value)
    xml = document.toXml()
  }
  private fun rename(group: RecipientTestRule.TestGroupInfo, name: String) {
    val current = SignalDatabase.groups.requireGroup(group.groupId).requireV2GroupProperties().decryptedGroup
    SignalDatabase.groups.update(group.groupId, current.copy(title = name), null)
  }
  private fun demote(group: RecipientTestRule.TestGroupInfo, member: RecipientId) {
    val current = SignalDatabase.groups.requireGroup(group.groupId).requireV2GroupProperties().decryptedGroup
    val aci = Recipient.resolved(member).requireAci().toByteString()
    SignalDatabase.groups.update(group.groupId, current.copy(members = current.members.map { if (it.aciBytes == aci) it.copy(role = Member.Role.DEFAULT) else it }), null)
  }
  private fun templateFile(): File = listOf(File("docs/gateway/gateway.template.xml"), File("../docs/gateway/gateway.template.xml")).first { it.exists() }
}
