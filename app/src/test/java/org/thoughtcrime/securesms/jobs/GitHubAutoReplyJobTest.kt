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
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.RecipientTable.RegisteredState
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.github.CommandDocument
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.CommandStyle
import org.thoughtcrime.securesms.github.CommandStyleRange
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.dependencies.AppDependencies
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
    every { GitHubAutoReply.isCommandCandidate(any()) } answers { CommandDocument.isCommandCandidate(firstArg()) }
    every { GitHubAutoReply.getCachedReplyFor(any(), any()) } returns null
    every { GitHubAutoReply.needsRefresh(any()) } returns true
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns reply
    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } returns 1L
    every { RemoteConfig.regularDeleteThreshold } returns 86_400L
    every { RemoteConfig.adminDeleteThreshold } returns 86_400L
  }

  @After
  fun tearDown() {
    unmockkObject(GitHubAutoReply)
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

  private fun refreshJob(requestId: Long, replyId: Long) = GitHubAutoReplyRefreshJob(requestId, replyId, reply.contentHash()).apply {
    setContext(RuntimeEnvironment.getApplication())
  }

  private fun insertRequest(sender: RecipientId, groupId: GroupId? = null): Long {
    return SignalDatabase.messages.insertMessageInbox(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = sender,
        sentTimeMillis = 100L,
        serverTimeMillis = 100L,
        receivedTimeMillis = 200L,
        groupId = groupId,
        body = "!groups",
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
