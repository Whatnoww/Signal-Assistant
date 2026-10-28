/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.jobs

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.gateway.GatewayAudience
import org.thoughtcrime.securesms.gateway.GatewayController
import org.thoughtcrime.securesms.gateway.GatewayDatabase
import org.thoughtcrime.securesms.gateway.GatewayRuntime
import org.thoughtcrime.securesms.gateway.SignalGatewayPlatform
import org.thoughtcrime.securesms.github.AdminCommands
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.CommandLinkState
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.transport.RetryLaterException
import java.util.concurrent.TimeUnit

/** Public base first, then per-role edits addressed only to their eligible viewers. */
class GatewayHelpJob private constructor(parameters: Parameters, private val requestId: Long, private val rootId: Long) : BaseJob(parameters) {
  constructor(requestId: Long, rootId: Long) : this(Parameters.Builder().setQueue("$KEY:$requestId")
    .addConstraint(NetworkConstraint.KEY).setMaxAttempts(Parameters.UNLIMITED).setLifespan(TimeUnit.HOURS.toMillis(1)).build(), requestId, rootId)
  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("request", requestId).putLong("root", rootId).serialize()
  override fun onRun() {
    val request = SignalDatabase.messages.getMessageRecordOrNull(requestId) ?: return
    val root = SignalDatabase.messages.getMessageRecordOrNull(rootId) ?: return
    if (request.isRemoteDelete || root.isFailed || root.isRemoteDelete) return
    if (root.isPending) throw RetryLaterException()
    val recipient = SignalDatabase.threads.getRecipientForThreadId(request.threadId) ?: return
    if (!recipient.isGroup || recipient.isBlocked || recipient.isHidden) return
    val config = GatewayRuntime.configuration(context) ?: return
    if (config.paused) return
    val document = GitHubAutoReply.configurationDocument(context) ?: return
    val ordinary = document.render(request.body) { CommandLinkState() } ?: return
    val store = GatewayDatabase.get(context.applicationContext as Application)
    val platform = SignalGatewayPlatform(context, store)
    val state = platform.group(recipient.requireGroupId().toString(), false)
    val self = org.thoughtcrime.securesms.recipients.Recipient.self().requireAci().toString()
    val reviewers = store.requests().filter { it.active && it.room == state.id && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }
      .map { it.source }.distinct().flatMap { platform.group(it, false).admins }.toSet()
    val viewers = ((state.admins + reviewers).intersect(state.members)).filter { it != self && !AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, it) }
      .groupBy { GatewayController.helpScopes(context, recipient, it, config, document, platform) - "public" }
    viewers.filterKeys { it.isNotEmpty() }.forEach { (scopes, acis) ->
      val reply = GatewayController.concatenate(listOf(ordinary) + (setOf("public") + scopes).mapNotNull(config.help::get))
      platform.sendOnce("help-admin:$requestId:${scopes.sorted().joinToString(",")}", 0, "help", recipient, reply,
        audience = GatewayAudience(acis.toSet(), scopes, root.dateSent), messageToEdit = rootId, paginate = false)
    }
    val owner = state.members.firstOrNull { AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, it) } ?: return
    val scopes = config.help.keys - setOf("dm-group-admin", "public")
    val reply = GatewayController.concatenate(listOf(ordinary) + (setOf("public") + scopes).mapNotNull(config.help::get))
    platform.sendOnce("help-owner:$requestId", 0, "help", recipient, reply,
      audience = GatewayAudience(setOf(owner), setOf("owner"), root.dateSent), messageToEdit = rootId, paginate = false)
  }
  override fun onShouldRetry(e: Exception) = e is RetryLaterException || e is java.io.IOException
  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception) = 5000L
  override fun onFailure() { Log.w("Gateway", "Scoped help could not be delivered.") }
  class Factory : Job.Factory<GatewayHelpJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GatewayHelpJob = JsonJobData.deserialize(serializedData).let { GatewayHelpJob(parameters, it.getLong("request"), it.getLong("root")) }
  }
  companion object { const val KEY = "GatewayHelpJob" }
}
