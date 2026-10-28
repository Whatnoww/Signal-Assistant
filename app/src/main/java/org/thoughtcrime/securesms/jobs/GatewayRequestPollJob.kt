/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.jobs

import android.app.Application
import org.signal.core.util.logging.Log
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.gateway.GatewayConfiguration
import org.thoughtcrime.securesms.gateway.GatewayDatabase
import org.thoughtcrime.securesms.gateway.GatewayPhase
import org.thoughtcrime.securesms.gateway.GatewayRuntime
import org.thoughtcrime.securesms.gateway.SignalGatewayPlatform
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.concurrent.TimeUnit

/** One durable, isolated worker discovers joins in quiet groups between native update events. */
class GatewayRequestPollJob private constructor(parameters: Parameters) : Job(parameters) {
  internal constructor() : this(Parameters.Builder().setQueue(KEY).setMaxInstancesForFactory(1)
    .setGlobalPriority(Parameters.PRIORITY_LOWER).addConstraint(NetworkConstraint.KEY)
    .setMaxAttempts(Parameters.UNLIMITED).setLifespan(TimeUnit.DAYS.toMillis(3651)).build())

  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = null
  override fun onFailure() { Log.w("Gateway", "Source request refresh stopped; startup or configuration recovery can restart it.") }

  override fun run(): Result {
    if (!SignalStore.account.isPrimaryDevice || !SignalStore.account.isRegistered) return Result.success()
    var interval = TimeUnit.SECONDS.toMillis(30)
    try {
      val config = GatewayRuntime.configuration(context) ?: return Result.success()
      if (!enabled(config)) return Result.success()
      interval = TimeUnit.SECONDS.toMillis(config.defaults.joinPollSeconds)
      val store = GatewayDatabase.get(context.applicationContext as Application)
      val platform = SignalGatewayPlatform(context, store)
      for (id in config.groups.values.filter { !config.isPaused(it) }.map { it.id }) {
        // Reload only the accepted local snapshot, including pause/throttle changes during a pass.
        val latest = GatewayRuntime.configuration(context) ?: return Result.success()
        if (!enabled(latest)) return Result.success()
        val source = latest.groups[id] ?: continue
        if (latest.isPaused(source)) continue
        try {
          if (!platform.group(id, false).active) continue
          val state = platform.group(id, true)
          if (!state.active) continue
          val known = store.requests().filter { it.source == id }
          val unseen = state.requests.count { (aci, generation) -> known.none {
            it.aci == aci && (it.generation == generation || !it.active && generation == 0L)
          } }
          val retryIntake = known.any { it.phase == GatewayPhase.INTAKE && state.requests[it.aci] == it.generation }
          if (unseen > 0 || retryIntake) {
            Log.i("Gateway", "Source …${id.takeLast(8)} refreshed: $unseen new pending review(s).")
            AppDependencies.jobManager.add(GatewayWorkJob.intake(id))
          }
        } catch (e: Exception) {
          Log.w("Gateway", "Source request refresh deferred (…${id.takeLast(8)}, ${e.javaClass.simpleName}).")
          if (e is NonSuccessfulResponseCodeException && e.code == 429) {
            val seconds = e.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value?.toLongOrNull()
              ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 } ?: 300L
            return Result.retry(maxOf(interval, TimeUnit.SECONDS.toMillis(seconds)))
          }
          // An unavailable source must not prevent checking unrelated groups.
        }
      }
      val latest = GatewayRuntime.configuration(context) ?: return Result.success()
      if (!enabled(latest)) return Result.success()
      interval = TimeUnit.SECONDS.toMillis(latest.defaults.joinPollSeconds)
    } catch (e: Exception) {
      Log.w("Gateway", "Source request refresh deferred (${e.javaClass.simpleName}).")
    }
    // JobManager persists the next run and releases this worker; there is no sleeping thread.
    return Result.retry(interval)
  }

  class Factory : Job.Factory<GatewayRequestPollJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) = GatewayRequestPollJob(parameters)
  }

  companion object {
    const val KEY = "GatewayRequestPollJob"
    private fun enabled(config: GatewayConfiguration) = config.configured && !config.paused && config.defaults.joinPollSeconds > 0 && config.groups.values.any { !config.isPaused(it) }
    internal fun schedule(config: GatewayConfiguration) {
      if (enabled(config)) AppDependencies.jobManager.add(GatewayRequestPollJob())
    }
  }
}
