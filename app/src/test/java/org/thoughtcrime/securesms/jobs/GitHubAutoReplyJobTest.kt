/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import android.net.Uri
import io.mockk.every
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
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.testutil.RecipientTestRule

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
    every { GitHubAutoReply.getReplyFor(any(), any()) } returns reply
    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } returns 1L
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
    assertTrue(GitHubAutoReplyJob(requestId).canReply(contact, threadId))
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
