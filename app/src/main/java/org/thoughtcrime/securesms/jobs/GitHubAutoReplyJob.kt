/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.addStyle
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.CommandStyle
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.GroupScanCommand
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.MessageUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Prepares a reply; the usual Signal send jobs handle delivery, retrying and linked-device sync. */
class GitHubAutoReplyJob private constructor(parameters: Parameters, private val requestId: Long) : BaseJob(parameters) {
  constructor(requestId: Long) : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxAttempts(1)
      .setLifespan(TimeUnit.HOURS.toMillis(1))
      .addConstraint(NetworkConstraint.KEY)
      .build(),
    requestId
  )

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("request_id", requestId).serialize()

  override fun onRun() {
    val request = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (request.isOutgoing || request.isRemoteDelete || request.fromRecipient.isSelf || request.fromRecipient.isBlocked) return
    if (!GitHubAutoReply.isCommandCandidate(request.body)) return
    val recipient = SignalDatabase.threads.getRecipientForThreadId(request.threadId) ?: return
    if (!canReply(recipient, request.threadId)) return

    val isScan = request.body == GroupScanCommand.TRIGGER
    if (isScan && GroupScanCommand.authorizedGroup(recipient, request.fromRecipient) == null) return
    val isAdmin = GitHubAutoReply.isAdminCommand(request.body)
    val cached = if (isAdmin) null else GitHubAutoReply.getCachedReplyFor(context, request.body)
    val reply = if (isAdmin) {
      GitHubAutoReply.getAdminReply(
        context,
        request.body,
        request.fromRecipient.aci.orElse(null)?.toString(),
        recipient.groupId.orElse(null)?.takeIf { it.isV2 }?.toString()
      )
    } else {
      cached ?: GitHubAutoReply.getReplyFor(context, request.body) ?: return
    }
    // Recheck after the network work, in case the request was removed or the chat was blocked/left.
    val latestRequest = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (latestRequest.isRemoteDelete || latestRequest.body != request.body || Recipient.resolved(request.fromRecipient.id).isBlocked) return
    val latestRecipient = Recipient.resolved(recipient.id)
    if (!canReply(latestRecipient, request.threadId)) return

    val shouldRefresh = (cached != null && GitHubAutoReply.needsRefresh(request.body)) || (isScan && GroupTable.blocklistNeedsRefresh())
    val finalReply = if (isScan) {
      val group = GroupScanCommand.authorizedGroup(latestRecipient, Recipient.resolved(request.fromRecipient.id)) ?: return
      GroupScanCommand.reply(reply, group)
    } else {
      reply
    }
    val replies = if (isScan) GroupScanCommand.pages(finalReply) else listOf(finalReply)
    val replyIds = mutableListOf<Long>()
    for (page in replies) {
      val outgoing = sendReply(context, page, latestRecipient, request.threadId) ?: return
      if (!shouldRefresh) continue
      val inserted = SignalDatabase.messages.getMessageFor(outgoing.sentTimeMillis, Recipient.self().id)
      if (inserted == null || inserted.threadId != request.threadId || !inserted.isOutgoing) return
      replyIds += inserted.id
    }
    if (shouldRefresh && replyIds.isNotEmpty()) {
      AppDependencies.jobManager.add(
        GitHubAutoReplyRefreshJob(requestId, replyIds.first(), replies.first().contentHash(), replyIds.drop(1), replies.drop(1).map { it.contentHash() })
      )
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() {
    Log.w(TAG, "Unable to prepare GitHub command reply.")
  }

  class Factory : Job.Factory<GitHubAutoReplyJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GitHubAutoReplyJob {
      return GitHubAutoReplyJob(parameters, JsonJobData.deserialize(serializedData).getLong("request_id"))
    }
  }

  companion object {
    const val KEY = "GitHubAutoReplyJob"
    private val TAG = Log.tag(GitHubAutoReplyJob::class)
    private val lastSentTimeMillis = AtomicLong()

    internal fun canReply(recipient: Recipient, threadId: Long): Boolean {
      if (recipient.isBlocked || recipient.isSelf || recipient.isHidden) return false
      if (!recipient.isGroup) {
        // Accepted Signal-only contacts use profile sharing; mutual group membership alone is insufficient.
        return recipient.isIndividual && recipient.isRegistered &&
          (recipient.isSystemContact || recipient.isProfileSharing) &&
          RecipientUtil.isMessageRequestAccepted(threadId, recipient)
      }
      if (!recipient.isPushGroup || !recipient.isActiveGroup) return false
      if (!RecipientUtil.isMessageRequestAccepted(threadId, recipient)) return false
      val group = SignalDatabase.groups.getGroup(recipient.id).orElse(null) ?: return false
      return group.isActive && (!group.isAnnouncementGroup || group.isAdmin(Recipient.self()))
    }

    internal fun sendReply(context: Context, reply: CommandReply, recipient: Recipient, threadId: Long, messageToEdit: Long = 0): OutgoingMessage? {
      if (reply.text.isBlank()) return null
      if (reply.text.toByteArray(Charsets.UTF_8).size > MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES) {
        Log.w(TAG, "Command reply exceeds Signal's maximum message size.")
        return null
      }
      val ranges = BodyRangeList.Builder()
      reply.styles.forEach { range ->
        val style = when (range.style) {
          CommandStyle.BOLD -> BodyRangeList.BodyRange.Style.BOLD
          CommandStyle.ITALIC -> BodyRangeList.BodyRange.Style.ITALIC
          CommandStyle.STRIKETHROUGH -> BodyRangeList.BodyRange.Style.STRIKETHROUGH
          CommandStyle.MONOSPACE -> BodyRangeList.BodyRange.Style.MONOSPACE
          CommandStyle.SPOILER -> BodyRangeList.BodyRange.Style.SPOILER
        }
        ranges.addStyle(style, range.start, range.length)
      }
      val split = MessageUtil.getSplitMessage(context, reply.text)
      val outgoing = OutgoingMessage(
        threadRecipient = recipient,
        sentTimeMillis = lastSentTimeMillis.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) },
        body = split.body,
        attachments = split.textSlide.map { listOf(it.asAttachment()) }.orElse(emptyList()),
        bodyRanges = ranges.build(),
        mentions = reply.mentions,
        expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
        isSecure = true,
        messageToEdit = messageToEdit
      )
      MessageSender.send(context, outgoing, threadId, MessageSender.SendType.SIGNAL, null, null)
      return outgoing
    }
  }
}
