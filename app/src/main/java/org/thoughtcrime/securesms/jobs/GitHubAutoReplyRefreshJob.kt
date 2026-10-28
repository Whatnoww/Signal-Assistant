/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.GroupScanCommand
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
  private val previousHash: String,
  private val continuationIds: List<Long>,
  private val continuationHashes: List<String>
) : BaseJob(parameters) {
  constructor(requestId: Long, replyId: Long, previousHash: String, continuationIds: List<Long> = emptyList(), continuationHashes: List<String> = emptyList()) : this(
    Parameters.Builder().setQueue("$KEY:$replyId")
      .addConstraint(NetworkConstraint.KEY).setMaxAttempts(Parameters.UNLIMITED)
      .setLifespan(TimeUnit.HOURS.toMillis(1)).build(),
    requestId, replyId, previousHash, continuationIds, continuationHashes
  )

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray? = JsonJobData.Builder()
    .putLong("request_id", requestId).putLong("reply_id", replyId).putString("previous_hash", previousHash)
    .putLongArray("continuation_ids", continuationIds.toLongArray()).putStringArray("continuation_hashes", continuationHashes.toTypedArray()).serialize()

  override fun onRun() {
    if (org.thoughtcrime.securesms.gateway.GatewayRuntime.paused(context)) return
    val request = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (request.isRemoteDelete || request.isOutgoing || request.fromRecipient.isSelf || request.fromRecipient.isBlocked) return
    val recipient = SignalDatabase.threads.getRecipientForThreadId(request.threadId) ?: return
    if (!GitHubAutoReplyJob.canReply(recipient, request.threadId)) return
    val isScan = request.body == GroupScanCommand.TRIGGER
    if (isScan && GroupScanCommand.authorizedGroup(recipient, request.fromRecipient) == null) return
    val original = editableReply(request.threadId) ?: return
    if (original.isPending) throw RetryLaterException()

    val configured = if (GitHubAutoReply.needsRefresh(request.body)) {
      GitHubAutoReply.getReplyFor(context, request.body)
    } else {
      GitHubAutoReply.getCachedReplyFor(context, request.body)
    } ?: return
    // This job has its own queue, so online blocklist checks never delay new command replies.
    if (isScan) GroupTable.getBlocklist(forceRefresh = true)

    val latestRequest = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (org.thoughtcrime.securesms.gateway.GatewayRuntime.paused(context)) return
    if (latestRequest.isRemoteDelete || latestRequest.body != request.body || Recipient.resolved(request.fromRecipient.id).isBlocked) return
    val latestRecipient = Recipient.resolved(recipient.id)
    if (!GitHubAutoReplyJob.canReply(latestRecipient, request.threadId)) return
    val updated = if (isScan) {
      val group = GroupScanCommand.authorizedGroup(latestRecipient, Recipient.resolved(request.fromRecipient.id)) ?: return
      GroupScanCommand.reply(configured, group)
    } else {
      configured
    }
    if (isScan) {
      updateScanPages(updated, latestRecipient, request.threadId, request.fromRecipient)
      return
    }
    if (updated.contentHash() == previousHash) return
    val latestReply = editableReply(request.threadId) ?: return
    if (latestReply.isPending || !MessageConstraintsUtil.isValidEditMessageSend(latestReply, System.currentTimeMillis())) return
    GitHubAutoReplyJob.sendReply(context, updated, latestRecipient, request.threadId, messageToEdit = replyId)
  }

  private fun updateScanPages(updated: CommandReply, recipient: Recipient, threadId: Long, sender: Recipient) {
    val ids = listOf(replyId) + continuationIds
    val hashes = listOf(previousHash) + continuationHashes
    val originals = ids.map { editableReply(threadId, it) ?: return }
    if (originals.any { it.isPending }) throw RetryLaterException()
    if (originals.any { !MessageConstraintsUtil.isValidEditMessageSend(it, System.currentTimeMillis()) }) return
    val pages = GroupScanCommand.pages(updated)
    for (index in 0 until maxOf(pages.size, ids.size)) {
      val page = pages.getOrNull(index) ?: CommandReply("No additional group members were found on the cached ACI-Blocklist.", emptyList(), emptyList())
      if (page.contentHash() == hashes.getOrNull(index)) continue
      if (GroupScanCommand.authorizedGroup(Recipient.resolved(recipient.id), Recipient.resolved(sender.id)) == null) return
      GitHubAutoReplyJob.sendReply(context, page, recipient, threadId, messageToEdit = ids.getOrNull(index) ?: 0)
    }
  }

  private fun editableReply(threadId: Long, messageId: Long = replyId): MmsMessageRecord? {
    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId) as? MmsMessageRecord ?: return null
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
      return GitHubAutoReplyRefreshJob(
        parameters, data.getLong("request_id"), data.getLong("reply_id"), data.getString("previous_hash"),
        if (data.hasLongArray("continuation_ids")) data.getLongArray("continuation_ids").toList() else emptyList(),
        if (data.hasStringArray("continuation_hashes")) data.getStringArray("continuation_hashes").toList() else emptyList()
      )
    }
  }

  companion object {
    const val KEY = "GitHubAutoReplyRefreshJob"
    private val TAG = Log.tag(GitHubAutoReplyRefreshJob::class)
  }
}
