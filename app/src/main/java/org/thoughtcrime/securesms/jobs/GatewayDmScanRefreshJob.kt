/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.jobs

import android.app.Application
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.gateway.GatewayController
import org.thoughtcrime.securesms.gateway.GatewayDatabase
import org.thoughtcrime.securesms.gateway.GatewayRuntime
import org.thoughtcrime.securesms.gateway.SignalGatewayPlatform
import org.thoughtcrime.securesms.github.GroupScanCommand
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import java.util.concurrent.TimeUnit

class GatewayDmScanRefreshJob private constructor(parameters: Parameters, private val requestId: Long, private val replies: List<Long>, private val hashes: List<String>) : BaseJob(parameters) {
  constructor(requestId: Long, replies: List<Long>, hashes: List<String>) : this(
    Parameters.Builder().setQueue("$KEY:$requestId").addConstraint(NetworkConstraint.KEY).setLifespan(TimeUnit.HOURS.toMillis(1)).setMaxAttempts(Parameters.UNLIMITED).build(), requestId, replies, hashes
  )
  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("request", requestId).putLongArray("replies", replies.toLongArray()).putStringArray("hashes", hashes.toTypedArray()).serialize()
  override fun onRun() {
    if (!GatewayRuntime.acquireWork()) throw RetryLaterException()
    try { refreshWithTracking() } finally { GatewayRuntime.releaseWork() }
  }
  private fun refreshWithTracking() {
    val store = GatewayDatabase.get(context.applicationContext as Application)
    var complete = false
    try {
      refreshScan(store)
      complete = true
    } finally {
      if (complete) { store.releaseCooldown(requestId); store.finishScanRefresh(requestId) }
    }
  }
  private fun refreshScan(store: org.thoughtcrime.securesms.gateway.GatewayStore) {
    val config = GatewayRuntime.configuration(context, true) ?: return
    if (config.paused) return
    val request = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (request.isRemoteDelete || request.body != "!scan" || request.isOutgoing) return
    val sender = Recipient.resolved(request.fromRecipient.id)
    if (sender.isBlocked || sender.isHidden || sender.isSelf || !sender.aci.isPresent) return
    val originals = replies.map { SignalDatabase.messages.getMessageRecordOrNull(it) as? MmsMessageRecord ?: return }
    if (originals.any { it.isFailed || it.isRemoteDelete || it.latestRevisionId != null || it.threadId != request.threadId }) return
    if (originals.any { it.isPending }) throw RetryLaterException()
    store.consumeCooldown(requestId, System.currentTimeMillis())
    val platform = SignalGatewayPlatform(context, store)
    // Retrying a later membership read must not repeat the identical online check.
    if (store.claimScanRefresh(requestId)) platform.blocklist()
    GatewayController.knownAdminGroups(sender).forEach { platform.group(it.id.toString(), true) }
    val latestRequest = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    if (latestRequest.isRemoteDelete || latestRequest.body != "!scan" || GatewayRuntime.paused(context)) return
    val latestSender = Recipient.resolved(sender.id)
    if (latestSender.isBlocked || latestSender.isHidden) return
    val updated = GatewayController.dmScanReply(context, latestSender, platform)
    val pages = GroupScanCommand.pages(updated)
    for (index in 0 until maxOf(pages.size, replies.size)) {
      val page = pages.getOrNull(index) ?: platform.template("dm_status", mapOf("operation_status" to org.thoughtcrime.securesms.gateway.GatewayText("No additional scan results.")))
      if (page.contentHash() == hashes.getOrNull(index)) continue
      val old = originals.getOrNull(index)
      if (old != null) {
        val latest = SignalDatabase.messages.getMessageRecordOrNull(old.id) as? MmsMessageRecord ?: return
        if (latest.isRemoteDelete || latest.latestRevisionId != null || !MessageConstraintsUtil.isValidEditMessageSend(latest, System.currentTimeMillis())) return
        val timestamp = GitHubAutoReplyJob.nextSentTimeMillis()
        store.preserveContact(sender.id.toLong(), timestamp)
        GitHubAutoReplyJob.sendReply(context, page, latestSender, request.threadId, messageToEdit = old.id, timestamp = timestamp)
      } else platform.sendOnce("dm-scan-refresh:$requestId:$index", 0, "scan", latestSender, page)
    }
  }
  override fun onShouldRetry(e: Exception): Boolean = e is RetryLaterException || e is java.io.IOException
  override fun onFailure() {
    val store = GatewayDatabase.get(context.applicationContext as Application)
    store.releaseCooldown(requestId)
    store.finishScanRefresh(requestId)
  }
  class Factory : Job.Factory<GatewayDmScanRefreshJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GatewayDmScanRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      return GatewayDmScanRefreshJob(parameters, data.getLong("request"), data.getLongArray("replies").toList(), data.getStringArray("hashes").toList())
    }
  }
  companion object { const val KEY = "GatewayDmScanRefreshJob" }
}
