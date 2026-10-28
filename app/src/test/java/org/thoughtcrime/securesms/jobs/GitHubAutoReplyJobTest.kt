/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import android.net.Uri
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.storageservice.storage.protos.groups.Member
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.RecipientTable.RegisteredState
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.AdminCommands
import org.thoughtcrime.securesms.github.CommandDocument
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.CommandStyle
import org.thoughtcrime.securesms.github.CommandStyleRange
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.GroupScanCommand
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.RemoteConfig

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GitHubAutoReplyJobTest {
  @get:Rule
  val recipients = RecipientTestRule()

  private val sentMessages = mutableListOf<OutgoingMessage>()
  private val reply = CommandReply("Group Chats", emptyList(), emptyList())

  @Before
  fun setUp() {
    mockkObject(GitHubAutoReply)
    every { GitHubAutoReply.isCommandCandidate(any()) } answers { AdminCommands.isCandidate(firstArg()) || CommandDocument.isCommandCandidate(firstArg()) }
    every { GitHubAutoReply.getCachedReplyFor(any(), any()) } returns null
    every { GitHubAutoReply.needsRefresh(any()) } returns true
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns reply
    mockkObject(GroupTable.Companion)
    every { GroupTable.getCachedBlocklist() } returns emptySet()
    every { GroupTable.blocklistNeedsRefresh() } returns false
    every { GroupTable.getBlocklist() } returns emptySet()
    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } returns 1L
    every { RemoteConfig.regularDeleteThreshold } returns 86_400L
    every { RemoteConfig.adminDeleteThreshold } returns 86_400L
  }

  @After
  fun tearDown() {
    unmockkObject(GitHubAutoReply)
    unmockkObject(GroupTable.Companion)
    unmockkStatic(MessageSender::class)
  }

  @Test
  fun `accepted Signal-only contact receives reply in the same chat`() {
    val contact = recipients.createRecipient("Accepted Contact")
    runJob(insertRequest(contact))
    assertEquals("Group Chats", sentMessages.single().body)
    assertEquals(contact, sentMessages.single().threadRecipient.id)
  }

  @Test
  fun `phone address book contact is eligible without Signal profile sharing`() {
    val id = recipients.createRecipient("Phone Contact", profileSharing = false)
    val requestId = insertRequest(id)
    val contact = Recipient(id = id, isResolving = false, registeredValue = RegisteredState.REGISTERED, contactUri = Uri.parse("content://contacts/1"))
    val threadId = SignalDatabase.messages.getMessageRecord(requestId).threadId
    assertTrue(GitHubAutoReplyJob.canReply(contact, threadId))
  }

  @Test
  fun `pending message request does not fetch commands or send a reply`() {
    val stranger = recipients.createRecipient("Unknown Sender", profileSharing = false)
    val requestId = insertRequest(stranger)
    val threadId = SignalDatabase.messages.getMessageRecord(requestId).threadId
    assertFalse(RecipientUtil.isMessageRequestAccepted(threadId, Recipient.resolved(stranger)))
    runJob(requestId)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `previous outgoing message without contact status does not qualify`() {
    val stranger = recipients.createRecipient("Previous Conversation", profileSharing = false)
    SignalDatabase.messages.markAsSent(recipients.insertOutgoingMessage(stranger))
    val requestId = insertRequest(stranger)
    val threadId = SignalDatabase.messages.getMessageRecord(requestId).threadId
    assertTrue(RecipientUtil.isMessageRequestAccepted(threadId, Recipient.resolved(stranger)))
    runJob(requestId)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `sharing a group does not qualify a stranger for direct replies`() {
    val stranger = recipients.createRecipient("Group Member", profileSharing = false)
    recipients.createGroup(stranger)
    runJob(insertRequest(stranger))
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `hidden contact does not fetch commands or send a reply`() {
    val contact = recipients.createRecipient("Hidden Contact")
    val requestId = insertRequest(contact)
    SignalDatabase.recipients.markHidden(contact)
    Recipient.live(contact).refresh()
    runJob(requestId)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `contact removed while fetching commands does not receive a reply`() {
    val contact = recipients.createRecipient("Removed Contact")
    val requestId = insertRequest(contact)
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers {
      SignalDatabase.recipients.setProfileSharing(contact, false)
      Recipient.live(contact).refresh()
      reply
    }
    runJob(requestId)
    verify(exactly = 1) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `contact blocked while fetching commands does not receive a reply`() {
    val contact = recipients.createRecipient("Blocked Contact")
    val requestId = insertRequest(contact)
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers {
      SignalDatabase.recipients.setBlocked(contact, true, System.currentTimeMillis())
      Recipient.live(contact).refresh()
      reply
    }
    runJob(requestId)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `accepted group may reply to a member who is not a direct contact`() {
    val member = recipients.createRecipient("Group Member", profileSharing = false)
    val group = recipients.createGroup(member)
    runJob(insertRequest(member, group.groupId))
    assertEquals(group.recipientId, sentMessages.single().threadRecipient.id)
  }

  @Test
  fun `admin command uses stored sender ACI and current group without cached replies or refresh`() {
    val member = recipients.createRecipient("Admin", profileSharing = false)
    val group = recipients.createGroup(member)
    val senderAci = Recipient.resolved(member).requireAci().toString()
    val input = "!admin group enable autoupdate " + "Long group description ".repeat(4).trim()
    every { GitHubAutoReply.getAdminReply(any(), input, senderAci, group.groupId.toString()) } returns reply.copy(text = "Enabled autoupdate")
    runJob(insertRequest(member, group.groupId, input))
    assertEquals("Enabled autoupdate", sentMessages.single().body)
    assertEquals(group.recipientId, sentMessages.single().threadRecipient.id)
    verify(exactly = 1) { GitHubAutoReply.getAdminReply(any(), input, senderAci, group.groupId.toString()) }
    verify(exactly = 0) { GitHubAutoReply.getCachedReplyFor(any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.needsRefresh(any()) }
  }

  @Test
  fun `admin authorization errors return in the requesting group`() {
    val member = recipients.createRecipient("Other Sender", profileSharing = false)
    val group = recipients.createGroup(member)
    val senderAci = Recipient.resolved(member).requireAci().toString()
    val input = "!admin disable all"
    every { GitHubAutoReply.getAdminReply(any(), input, senderAci, group.groupId.toString()) } returns reply.copy(text = "Error: only the configured admin ACI can use !admin commands.")
    runJob(insertRequest(member, group.groupId, input))
    assertTrue(sentMessages.single().body.startsWith("Error:"))
    assertEquals(group.recipientId, sentMessages.single().threadRecipient.id)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
  }

  @Test
  fun `blocked group cannot execute admin commands`() {
    val member = recipients.createRecipient("Admin", profileSharing = false)
    val group = recipients.createGroup(member)
    SignalDatabase.recipients.setBlocked(group.recipientId, true, System.currentTimeMillis())
    Recipient.live(group.recipientId).refresh()
    runJob(insertRequest(member, group.groupId, "!admin disable all"))
    verify(exactly = 0) { GitHubAutoReply.getAdminReply(any(), any(), any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `unaccepted group does not fetch commands or send a reply`() {
    val member = recipients.createRecipient("Group Member", profileSharing = false)
    val group = recipients.createGroup(member)
    SignalDatabase.recipients.setProfileSharing(group.recipientId, false)
    Recipient.live(group.recipientId).refresh()
    val requestId = insertRequest(member, group.groupId)
    val threadId = SignalDatabase.messages.getMessageRecord(requestId).threadId
    assertFalse(RecipientUtil.isMessageRequestAccepted(threadId, Recipient.resolved(group.recipientId)))
    runJob(requestId)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `group left while fetching commands does not receive a reply`() {
    val member = recipients.createRecipient("Group Member")
    val group = recipients.createGroup(member)
    val requestId = insertRequest(member, group.groupId)
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers {
      SignalDatabase.groups.setMember(group.groupId, false)
      Recipient.live(group.recipientId).refresh()
      reply
    }
    runJob(requestId)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `cached response sends before any refresh and schedules follow-up`() {
    val contact = recipients.createRecipient("Cached Contact")
    val jobs = mutableListOf<Job>()
    every { GitHubAutoReply.getCachedReplyFor(any(), any()) } returns reply
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers { error("Network refresh must not delay cached send") }
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } answers {
      SignalDatabase.messages.insertMessageOutbox(secondArg(), thirdArg())
      thirdArg<Long>()
    }
    justRun { AppDependencies.jobManager.add(capture(jobs)) }
    runJob(insertRequest(contact))
    assertEquals("Group Chats", sentMessages.single().body)
    assertEquals(1, jobs.filterIsInstance<GitHubAutoReplyRefreshJob>().size)
  }

  @Test
  fun `fresh cached response sends without fetching or scheduling a refresh`() {
    val contact = recipients.createRecipient("Cached Contact")
    every { GitHubAutoReply.getCachedReplyFor(any(), any()) } returns reply
    every { GitHubAutoReply.needsRefresh(any()) } returns false
    runJob(insertRequest(contact))
    assertEquals(1, sentMessages.size)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
  }

  @Test
  fun `background refresh edits original reply when text changes`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns reply.copy(text = "New group links")
    val result = refreshJob(requestId, replyId).run()
    assertTrue(result.isSuccess)
    assertEquals("New group links", sentMessages.single().body)
    assertEquals(replyId, sentMessages.single().messageToEdit)
  }

  @Test
  fun `background refresh edits formatting changes too`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns reply.copy(styles = listOf(CommandStyleRange(CommandStyle.BOLD, 0, 5)))
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    assertEquals(replyId, sentMessages.single().messageToEdit)
    assertEquals(1, sentMessages.single().bodyRanges!!.ranges.size)
  }

  @Test
  fun `unchanged refresh sends no edit or duplicate message`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `refresh waits for initial delivery before fetching or editing`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    assertTrue(refreshJob(requestId, replyId).run().isRetry)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `contact blocked during background refresh receives no edit`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers {
      SignalDatabase.recipients.setBlocked(contact, true, System.currentTimeMillis())
      Recipient.live(contact).refresh()
      reply.copy(text = "Changed")
    }
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `deleted reply cannot be revived by a background refresh`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    SignalDatabase.messages.markAsRemoteDelete(SignalDatabase.messages.getMessageRecord(replyId), recipients.self)
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `manual edit prevents the background job from overwriting the reply`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    val threadId = SignalDatabase.messages.getMessageRecord(requestId).threadId
    SignalDatabase.messages.insertMessageOutbox(
      OutgoingMessage(
        threadRecipient = Recipient.resolved(contact),
        sentTimeMillis = System.currentTimeMillis() + 1,
        body = "My manual edit",
        isSecure = true,
        messageToEdit = replyId
      ),
      threadId
    )
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `command disabled during refresh does not produce another reply or edit`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns null
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `another completed refresh supplies updated content without a second fetch`() {
    val contact = recipients.createRecipient("Contact")
    val requestId = insertRequest(contact)
    val replyId = recipients.insertOutgoingMessage(contact, reply.text)
    SignalDatabase.messages.markAsSent(replyId)
    every { GitHubAutoReply.needsRefresh(any()) } returns false
    every { GitHubAutoReply.getCachedReplyFor(any(), any()) } returns reply.copy(text = "Already refreshed")
    assertTrue(refreshJob(requestId, replyId).run().isSuccess)
    assertEquals("Already refreshed", sentMessages.single().body)
    assertEquals(replyId, sentMessages.single().messageToEdit)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
  }

  @Test
  fun `scan in direct chat is silently ignored without reading either cache`() {
    val contact = recipients.createRecipient("Contact")
    runJob(insertRequest(contact, body = "!scan"))
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 0) { GitHubAutoReply.getCachedReplyFor(any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    verify(exactly = 0) { GroupTable.getCachedBlocklist() }
  }

  @Test
  fun `scan from ordinary member or outsider is silently ignored`() {
    val member = recipients.createRecipient("Member")
    val outsider = recipients.createRecipient("Outsider")
    val group = recipients.createGroup(member)
    demote(group, member)
    runJob(insertRequest(member, group.groupId, "!scan"))
    runJob(insertRequest(outsider, group.groupId, "!scan"))
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 0) { GitHubAutoReply.getCachedReplyFor(any(), any()) }
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    verify(exactly = 0) { GroupTable.getCachedBlocklist() }
  }

  @Test
  fun `admin scan uses cached configuration and blocklist before scheduling background refresh`() {
    val admin = recipients.createRecipient("Admin")
    val blocked = recipients.createRecipient("Blocked Member")
    val group = recipients.createGroup(admin, blocked)
    val aci = Recipient.resolved(blocked).requireAci().toString()
    val jobs = mutableListOf<Job>()
    every { GitHubAutoReply.getCachedReplyFor(any(), "!scan") } returns reply
    every { GitHubAutoReply.getReplyFor(any(), any()) } answers { error("Must send the cached scan first") }
    every { GroupTable.getCachedBlocklist() } returns setOf(aci)
    every { GroupTable.getBlocklist() } answers { error("Initial scan must never download the blocklist") }
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } answers {
      SignalDatabase.messages.insertMessageOutbox(secondArg(), thirdArg())
      thirdArg<Long>()
    }
    justRun { AppDependencies.jobManager.add(capture(jobs)) }

    runJob(insertRequest(admin, group.groupId, "!scan"))

    val sent = sentMessages.single()
    assertEquals("Group Chats\n$aci \uFFFC", sent.body)
    assertEquals(blocked, sent.mentions.single().recipientId)
    assertEquals('\uFFFC', sent.body[sent.mentions.single().start])
    val refresh = jobs.filterIsInstance<GitHubAutoReplyRefreshJob>().single()
    val serialized = org.thoughtcrime.securesms.jobmanager.JsonJobData.deserialize(refresh.serialize())
    assertEquals(CommandReply(sent.body, emptyList(), emptyList(), sent.mentions).contentHash(), serialized.getString("previous_hash"))
    verify(exactly = 0) { GroupTable.getBlocklist() }
  }

  @Test
  fun `stale blocklist schedules refresh even with fresh command configuration`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    val jobs = mutableListOf<Job>()
    every { GitHubAutoReply.getCachedReplyFor(any(), "!scan") } returns reply
    every { GitHubAutoReply.needsRefresh(any()) } returns false
    every { GroupTable.blocklistNeedsRefresh() } returns true
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } answers {
      SignalDatabase.messages.insertMessageOutbox(secondArg(), thirdArg())
      thirdArg<Long>()
    }
    justRun { AppDependencies.jobManager.add(capture(jobs)) }
    runJob(insertRequest(admin, group.groupId, "!scan"))
    assertEquals(1, sentMessages.size)
    assertEquals(1, jobs.filterIsInstance<GitHubAutoReplyRefreshJob>().size)
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
    verify(exactly = 0) { GroupTable.getBlocklist() }
  }

  @Test
  fun `disabled scan sends nothing and never reads blocklist`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    every { GitHubAutoReply.getReplyFor(any(), "!scan") } returns null
    runJob(insertRequest(admin, group.groupId, "!scan"))
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 0) { GroupTable.getCachedBlocklist() }
    verify(exactly = 0) { GroupTable.getBlocklist() }
  }

  @Test
  fun `sender demoted during configuration fetch receives no scan`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    every { GitHubAutoReply.getReplyFor(any(), "!scan") } answers {
      demote(group, admin)
      reply
    }
    runJob(insertRequest(admin, group.groupId, "!scan"))
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 0) { GroupTable.getCachedBlocklist() }
  }

  @Test
  fun `scan refresh edits original message with new blocklist matches and native mentions`() {
    val admin = recipients.createRecipient("Admin")
    val blocked = recipients.createRecipient("Blocked")
    val group = recipients.createGroup(admin, blocked)
    val recipient = Recipient.resolved(group.recipientId)
    val initial = GroupScanCommand.reply(reply, GroupScanCommand.authorizedGroup(recipient, Recipient.resolved(admin))!!)
    val requestId = insertRequest(admin, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId, initial.text)
    SignalDatabase.messages.markAsSent(replyId)
    val aci = Recipient.resolved(blocked).requireAci().toString()
    every { GroupTable.getBlocklist() } answers {
      every { GroupTable.getCachedBlocklist() } returns setOf(aci)
      setOf(aci)
    }
    val result = scanRefreshJob(requestId, replyId, initial).run()
    assertTrue(result.isSuccess)
    val edited = sentMessages.single()
    assertEquals(replyId, edited.messageToEdit)
    assertEquals("Group Chats\n$aci \uFFFC", edited.body)
    assertEquals(blocked, edited.mentions.single().recipientId)
  }

  @Test
  fun `unchanged scan refresh produces no edit`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    val initial = GroupScanCommand.reply(reply, SignalDatabase.groups.getGroup(group.groupId).get())
    val requestId = insertRequest(admin, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId, initial.text)
    SignalDatabase.messages.markAsSent(replyId)
    assertTrue(scanRefreshJob(requestId, replyId, initial).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 1) { GroupTable.getBlocklist() }
  }

  @Test
  fun `sender demoted during online blocklist check receives no scan edit`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    val requestId = insertRequest(admin, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId)
    SignalDatabase.messages.markAsSent(replyId)
    every { GroupTable.getBlocklist() } answers {
      demote(group, admin)
      emptySet()
    }
    assertTrue(scanRefreshJob(requestId, replyId, reply).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
  }

  @Test
  fun `scan refresh from unauthorized sender never checks online`() {
    val member = recipients.createRecipient("Member")
    val group = recipients.createGroup(member)
    demote(group, member)
    val requestId = insertRequest(member, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId)
    SignalDatabase.messages.markAsSent(replyId)
    assertTrue(scanRefreshJob(requestId, replyId, reply).run().isSuccess)
    assertTrue(sentMessages.isEmpty())
    verify(exactly = 0) { GroupTable.getBlocklist() }
    verify(exactly = 0) { GitHubAutoReply.getReplyFor(any(), any()) }
  }

  @Test
  fun `scan refresh adds continuation when online results exceed the inline mention limit`() {
    val admin = recipients.createRecipient("Admin")
    val blocked = (1..60).map { recipients.createRecipient("Member $it") }
    val group = recipients.createGroup(admin, *blocked.toTypedArray())
    val initial = GroupScanCommand.reply(reply, SignalDatabase.groups.getGroup(group.groupId).get())
    val requestId = insertRequest(admin, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId, initial.text)
    SignalDatabase.messages.markAsSent(replyId)
    val blocklist = blocked.map { Recipient.resolved(it).requireAci().toString() }.toSet()
    every { GroupTable.getBlocklist() } answers {
      every { GroupTable.getCachedBlocklist() } returns blocklist
      blocklist
    }
    assertTrue(scanRefreshJob(requestId, replyId, initial).run().isSuccess)
    assertEquals(2, sentMessages.size)
    assertEquals(replyId, sentMessages.first().messageToEdit)
    assertEquals(0L, sentMessages.last().messageToEdit)
    assertEquals(blocked.toSet(), sentMessages.flatMap { it.mentions }.map { it.recipientId }.toSet())
    sentMessages.forEach { assertTrue(it.body.toByteArray(Charsets.UTF_8).size <= 2048) }
  }

  @Test
  fun `scan refresh clears obsolete continuation and round trips its message IDs`() {
    val admin = recipients.createRecipient("Admin")
    val group = recipients.createGroup(admin)
    val requestId = insertRequest(admin, group.groupId, "!scan")
    val replyId = recipients.insertOutgoingMessage(group.recipientId, "Old first page", System.currentTimeMillis())
    val continuationId = recipients.insertOutgoingMessage(group.recipientId, "Old continuation", System.currentTimeMillis() + 1)
    SignalDatabase.messages.markAsSent(replyId)
    SignalDatabase.messages.markAsSent(continuationId)
    val originalJob = GitHubAutoReplyRefreshJob(requestId, replyId, "old-first-hash", listOf(continuationId), listOf("old-continuation-hash"))
    val restored = GitHubAutoReplyRefreshJob.Factory().create(originalJob.parameters, originalJob.serialize()).apply {
      setContext(RuntimeEnvironment.getApplication())
    }
    assertTrue(restored.run().isSuccess)
    assertEquals(listOf(replyId, continuationId), sentMessages.map { it.messageToEdit })
    assertTrue(sentMessages.last().body.startsWith("No additional group members"))
    assertTrue(sentMessages.all { it.mentions.isEmpty() })
  }

  private fun demote(group: RecipientTestRule.TestGroupInfo, member: RecipientId) {
    val current = SignalDatabase.groups.getGroup(group.groupId).get().requireV2GroupProperties().decryptedGroup
    val aci = Recipient.resolved(member).requireAci().toByteString()
    SignalDatabase.groups.update(
      group.groupId,
      current.copy(members = current.members.map { if (it.aciBytes == aci) it.copy(role = Member.Role.DEFAULT) else it }),
      null
    )
  }

  private fun scanRefreshJob(requestId: Long, replyId: Long, initial: CommandReply) = GitHubAutoReplyRefreshJob(requestId, replyId, initial.contentHash()).apply {
    setContext(RuntimeEnvironment.getApplication())
  }

  private fun refreshJob(requestId: Long, replyId: Long) = GitHubAutoReplyRefreshJob(requestId, replyId, reply.contentHash()).apply {
    setContext(RuntimeEnvironment.getApplication())
  }

  private fun insertRequest(sender: RecipientId, groupId: GroupId? = null, body: String = "!groups"): Long {
    return SignalDatabase.messages.insertMessageInbox(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = sender,
        sentTimeMillis = 100L,
        serverTimeMillis = 100L,
        receivedTimeMillis = 200L,
        groupId = groupId,
        body = body,
        isUnidentified = true
      )
    ).get().messageId
  }

  private fun runJob(requestId: Long) {
    val result = GitHubAutoReplyJob(requestId).apply {
      setContext(RuntimeEnvironment.getApplication())
    }.run()
    assertTrue("Reply job should complete successfully: $result", result.isSuccess)
  }
}
