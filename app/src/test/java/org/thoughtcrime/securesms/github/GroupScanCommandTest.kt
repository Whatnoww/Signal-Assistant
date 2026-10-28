/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedRequestingMember
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.MentionUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupScanCommandTest {
  @get:Rule
  val recipients = RecipientTestRule()

  private val heading = CommandReply("Scan 🔎", listOf(CommandStyleRange(CommandStyle.BOLD, 0, 7)), emptyList())

  @Before
  fun setUp() {
    mockkObject(GroupTable.Companion)
    every { GroupTable.getCachedBlocklist() } returns emptySet()
    every { GroupTable.getBlocklist(any()) } answers { error("Rendering a scan must not download the blocklist") }
  }

  @After
  fun tearDown() {
    unmockkObject(GroupTable.Companion)
  }

  @Test
  fun `one line per matching full member includes self and excludes outsiders and pending members`() {
    val blocked = recipients.createRecipient("Blocked Member")
    val clean = recipients.createRecipient("Clean Member")
    val pending = recipients.createRecipient("Pending Member")
    val requesting = recipients.createRecipient("Requesting Member")
    val group = recipients.createGroup(blocked, clean)
    val current = SignalDatabase.groups.getGroup(group.groupId).get().requireV2GroupProperties().decryptedGroup
    val state = current.copy(
      pendingMembers = listOf(DecryptedPendingMember(serviceIdBytes = Recipient.resolved(pending).requireAci().toByteString())),
      requestingMembers = listOf(DecryptedRequestingMember(aciBytes = Recipient.resolved(requesting).requireAci().toByteString()))
    )
    val record = mockk<GroupRecord>()
    every { record.requireV2GroupProperties() } returns GroupTable.V2GroupProperties(group.masterKey, 0, state.encode())
    val blockedAci = Recipient.resolved(blocked).requireAci().toString()
    every { GroupTable.getCachedBlocklist() } returns setOf(
      blockedAci,
      recipients.selfAci.toString(),
      Recipient.resolved(pending).requireAci().toString(),
      Recipient.resolved(requesting).requireAci().toString(),
      ACI.from(UUID.randomUUID()).toString()
    )

    val result = GroupScanCommand.reply(heading, record)

    assertEquals(3, result.text.lines().size)
    assertTrue(result.text.contains("$blockedAci \uFFFC"))
    assertTrue(result.text.contains("${recipients.selfAci} \uFFFC"))
    assertEquals(setOf(blocked, recipients.self), result.mentions.map { it.recipientId }.toSet())
    assertEquals(heading.styles, result.styles)
    result.mentions.forEach { assertEquals("\uFFFC", result.text.substring(it.start, it.start + it.length)) }
    val displayed = MentionUtil.updateBodyAndMentionsWithDisplayNames(RuntimeEnvironment.getApplication(), result.text, result.mentions)
    assertTrue(displayed.body.toString().contains("@Blocked Member"))
    verify(exactly = 0) { GroupTable.getBlocklist(any()) }
  }

  @Test
  fun `unavailable cache is distinct from a downloaded empty blocklist`() {
    val group = recipients.createGroup()
    val record = SignalDatabase.groups.getGroup(group.groupId).get()
    every { GroupTable.getCachedBlocklist() } returns null
    val unavailable = GroupScanCommand.reply(heading, record)
    assertEquals("Scan 🔎\nThe ACI-Blocklist cache is not available yet.", unavailable.text)
    every { GroupTable.getCachedBlocklist() } returns emptySet()
    val empty = GroupScanCommand.reply(heading, record)
    assertEquals("Scan 🔎\nNo group members were found on the cached ACI-Blocklist.", empty.text)
    assertTrue(empty.mentions.isEmpty())
  }

  @Test
  fun `empty heading still sends generated results with correct mention offset`() {
    val group = recipients.createGroup()
    every { GroupTable.getCachedBlocklist() } returns setOf(recipients.selfAci.toString())
    val result = GroupScanCommand.reply(CommandReply("", emptyList(), emptyList()), SignalDatabase.groups.getGroup(group.groupId).get())
    assertEquals("${recipients.selfAci} \uFFFC", result.text)
    assertEquals(37, result.mentions.single().start)
  }

  @Test
  fun `scan XML uses existing activation controls and preserves native heading formatting`() {
    val document = CommandDocument.parse("<commands><command><active>1</active><triggers><trigger>!scan</trigger></triggers><response><text><b>Scan 🔎</b></text></response></command></commands>")
    val configured = document.render("!scan") { error("No group-link checks") }!!
    assertEquals(heading.text, configured.text)
    assertEquals(heading.styles, configured.styles)
    listOf("!SCAN", "!scan extra", " !scan", "!scan ").forEach {
      assertEquals(null, document.render(it) { error("No group-link checks") })
    }
    document.setCommandsActive("!scan", false)
    assertEquals(null, document.render("!scan") { error("Disabled") })
    document.setCommandsActive("all", true)
    assertEquals(configured, document.render("!scan") { error("No group-link checks") })
  }

  @Test
  fun `mention identity changes hash even when placeholder text remains identical`() {
    val first = recipients.createRecipient("First")
    val second = recipients.createRecipient("Second")
    val base = CommandReply("\uFFFC", emptyList(), emptyList(), listOf(Mention(first, 0, 1)))
    assertFalse(base.contentHash() == base.copy(mentions = listOf(Mention(second, 0, 1))).contentHash())
  }

  @Test
  fun `large scan pages preserve every member line and native mention within the inline byte limit`() {
    val text = StringBuilder(heading.text)
    val mentions = mutableListOf<Mention>()
    repeat(120) { index ->
      text.append('\n').append(UUID.randomUUID()).append(' ')
      mentions += Mention(RecipientId.from(index.toLong() + 1), text.length, 1)
      text.append('\uFFFC')
    }
    val full = heading.copy(text = text.toString(), mentions = mentions)
    val pages = GroupScanCommand.pages(full)
    assertTrue(pages.size > 1)
    assertEquals(full.text, pages.joinToString("\n") { it.text })
    assertEquals(mentions.map { it.recipientId }, pages.flatMap { it.mentions }.map { it.recipientId })
    pages.forEach { page ->
      assertTrue(page.text.toByteArray(Charsets.UTF_8).size <= 2048)
      page.mentions.forEach { assertEquals("\uFFFC", page.text.substring(it.start, it.start + it.length)) }
    }
    assertEquals(heading.styles, pages.first().styles)
  }

  @Test
  fun `oversized Unicode heading can be split without losing formatting offsets or code points`() {
    val text = "🔎".repeat(700)
    val full = CommandReply(text, listOf(CommandStyleRange(CommandStyle.BOLD, 0, text.length)), emptyList())
    val pages = GroupScanCommand.pages(full)
    assertEquals(text, pages.joinToString("") { it.text })
    pages.forEach { page ->
      assertTrue(page.text.toByteArray(Charsets.UTF_8).size <= 2048)
      assertEquals(listOf(CommandStyleRange(CommandStyle.BOLD, 0, page.text.length)), page.styles)
    }
  }
}
