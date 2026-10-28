/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.gateway.GatewayController
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import java.util.concurrent.TimeUnit

/** Isolates slow configuration/DM work from the ordinary cache-first command queue. */
class GatewayCommandJob private constructor(parameters: Parameters, private val messageId: Long) : BaseJob(parameters) {
  constructor(messageId: Long) : this(Parameters.Builder().setQueue("$KEY:$messageId").addConstraint(NetworkConstraint.KEY)
    .setMaxAttempts(Parameters.UNLIMITED).setLifespan(TimeUnit.HOURS.toMillis(1)).build(), messageId)
  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("message", messageId).serialize()
  override fun onRun() {
    val body = org.thoughtcrime.securesms.database.SignalDatabase.messages.getMessageRecordOrNull(messageId)?.body.orEmpty()
    val fast = body == "!scan" || body in GatewayController.helpAliases
    if (!fast && !org.thoughtcrime.securesms.gateway.GatewayRuntime.acquireWork()) throw org.thoughtcrime.securesms.transport.RetryLaterException()
    try {
      if (!GatewayController.handle(context, messageId)) AppDependencies.jobManager.add(GitHubAutoReplyJob(messageId))
    } finally {
      if (!fast) org.thoughtcrime.securesms.gateway.GatewayRuntime.releaseWork()
      org.thoughtcrime.securesms.gateway.GatewayRuntime.releaseUnsentInvocation(context, messageId)
    }
  }
  override fun onShouldRetry(e: Exception) = e is org.thoughtcrime.securesms.transport.RetryLaterException
  override fun onFailure() { Log.w(Log.tag(GatewayCommandJob::class), "Gateway command could not be completed.") }
  class Factory : Job.Factory<GatewayCommandJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) = GatewayCommandJob(parameters, JsonJobData.deserialize(serializedData).getLong("message"))
  }
  companion object { const val KEY = "GatewayCommandJob" }
}
