/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import java.util.concurrent.TimeUnit

/** Revalidates a cached reply and edits it only after its original send completes. */
class GitHubAutoReplyRefreshJob private constructor(
  parameters: Parameters,
  private val requestId: Long,
  private val replyId: Long,
  private val previousHash: String
) : BaseJob(parameters) {
  constructor(requestId: Long, replyId: Long, previousHash: String) : this(
    Parameters.Builder().setQueue("$KEY:$replyId")
      .addConstraint(NetworkConstraint.KEY).setMaxAttempts(Parameters.UNLIMITED)
      .setLifespan(TimeUnit.HOURS.toMillis(1)).build(),
    requestId, replyId, previousHash
  )

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray? = JsonJobData.Builder()
    .putLong("request_id", requestId).putLong("reply_id", replyId).putString("previous_hash", previousHash).serialize()

  override fun onRun() {
    val request = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (request.isRemoteDelete || request.isOutgoing || request.fromRecipient.isSelf || request.fromRecipient.isBlocked) return
    val recipient = SignalDatabase.threads.getRecipientForThreadId(request.threadId) ?: return
    if (!GitHubAutoReplyJob.canReply(recipient, request.threadId)) return
    val original = editableReply(request.threadId) ?: return
    if (original.isPending) throw RetryLaterException()

    val updated = if (GitHubAutoReply.needsRefresh(request.body)) {
      GitHubAutoReply.getReplyFor(context, request.body)
    } else {
      GitHubAutoReply.getCachedReplyFor(context, request.body)
    } ?: return
    if (updated.contentHash() == previousHash) return

    val latestRequest = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (latestRequest.isRemoteDelete || latestRequest.body != request.body || Recipient.resolved(request.fromRecipient.id).isBlocked) return
    val latestRecipient = Recipient.resolved(recipient.id)
    if (!GitHubAutoReplyJob.canReply(latestRecipient, request.threadId)) return
    val latestReply = editableReply(request.threadId) ?: return
    if (latestReply.isPending || !MessageConstraintsUtil.isValidEditMessageSend(latestReply, System.currentTimeMillis())) return
    GitHubAutoReplyJob.sendReply(context, updated, latestRecipient, request.threadId, messageToEdit = replyId)
  }

  private fun editableReply(threadId: Long): MmsMessageRecord? {
    val record = SignalDatabase.messages.getMessageRecordOrNull(replyId) as? MmsMessageRecord ?: return null
    return record.takeIf { it.threadId == threadId && it.isOutgoing && !it.isFailed && !it.isRemoteDelete && it.latestRevisionId == null }
  }

  override fun onShouldRetry(e: Exception): Boolean = e is RetryLaterException
  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long = TimeUnit.SECONDS.toMillis(5)
  override fun onFailure() {
    Log.w(TAG, "Unable to refresh cached command reply.")
  }

  class Factory : Job.Factory<GitHubAutoReplyRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GitHubAutoReplyRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      return GitHubAutoReplyRefreshJob(parameters, data.getLong("request_id"), data.getLong("reply_id"), data.getString("previous_hash"))
    }
  }

  companion object {
    const val KEY = "GitHubAutoReplyRefreshJob"
    private val TAG = Log.tag(GitHubAutoReplyRefreshJob::class)
  }
}
