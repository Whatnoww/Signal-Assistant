/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.addStyle
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.github.CommandStyle
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.MessageUtil
import java.util.concurrent.TimeUnit
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
    if (!canReply(recipient)) return

    val reply = GitHubAutoReply.getReplyFor(context, request.body) ?: return
    if (reply.text.isBlank()) return
    if (reply.text.toByteArray(Charsets.UTF_8).size > MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES) {
      Log.w(TAG, "Command reply exceeds Signal's maximum message size.")
      return
    }
    // Recheck after the network work, in case the request was removed or the chat was blocked/left.
    val latestRequest = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (latestRequest.isRemoteDelete || latestRequest.body != request.body || Recipient.resolved(request.fromRecipient.id).isBlocked) return
    val latestRecipient = Recipient.resolved(recipient.id)
    if (!canReply(latestRecipient)) return

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
    MessageSender.send(
      context,
      OutgoingMessage(
        threadRecipient = latestRecipient,
        sentTimeMillis = System.currentTimeMillis(),
        body = split.body,
        attachments = split.textSlide.map { listOf(it.asAttachment()) }.orElse(emptyList()),
        bodyRanges = ranges.build(),
        expiresIn = latestRecipient.expiresInSeconds.seconds.inWholeMilliseconds,
        isSecure = true
      ),
      request.threadId,
      MessageSender.SendType.SIGNAL,
      null,
      null
    )
  }

  private fun canReply(recipient: Recipient): Boolean {
    if (recipient.isBlocked || recipient.isSelf) return false
    if (!recipient.isGroup) return recipient.isRegistered
    if (!recipient.isPushGroup || !recipient.isActiveGroup) return false
    val group = SignalDatabase.groups.getGroup(recipient.id).orElse(null) ?: return false
    return !group.isAnnouncementGroup || group.isAdmin(Recipient.self())
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
  }
}
