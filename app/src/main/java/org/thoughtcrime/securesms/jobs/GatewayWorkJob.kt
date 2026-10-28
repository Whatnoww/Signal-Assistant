/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.gateway.GatewayRuntime
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Identifiers only; durable workflow data lives in Gateway's own encrypted database. */
class GatewayWorkJob private constructor(
  parameters: Parameters,
  private val requestId: Long,
  private val groupId: String,
  private val removed: List<String>,
  private val messageId: Long,
  private val timer: Boolean = false,
  private val room: String = "",
  private val intake: Boolean = false
) : BaseJob(parameters) {
  constructor(requestId: Long, delay: Long = 0, room: String = "") : this(parameters(if (delay > 0) "timer:$requestId:${System.currentTimeMillis() + delay}" else if (room.isNotBlank()) "room:$room" else "request:$requestId", delay), requestId, "", emptyList(), 0, delay > 0, room)
  constructor(groupId: String, removed: List<String>) : this(parameters("room:$groupId"), 0, groupId, removed, 0)
  constructor(messageId: Long, incoming: Boolean, room: String = "") : this(parameters(if (room.isNotBlank()) "room:$room" else "incoming:$messageId"), 0, "", emptyList(), messageId, false, room)
  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("request", requestId).putString("group", groupId)
    .putStringArray("removed", removed.toTypedArray()).putLong("message", messageId).putBoolean("timer", timer).putString("room", room).putBoolean("intake", intake).serialize()
  override fun onRun() {
    // A delayed job must never occupy the immediate decision queue for days.
    if (timer) {
      val queue = when { intake -> "intake:$groupId"; groupId.isNotBlank() -> "room:$groupId"; room.isNotBlank() -> "room:$room"; messageId != 0L -> "incoming:$messageId"; else -> "request:$requestId" }
      AppDependencies.jobManager.add(GatewayWorkJob(parameters(queue), requestId, groupId, removed, messageId, false, room, intake))
    } else {
      if (!GatewayRuntime.acquireWork()) { wakeAfter(1_000); return }
      try {
        GatewayRuntime.work(context, requestId, groupId, removed, messageId, intake)
      } catch (e: Exception) {
        if ((e is IOException || e is org.signal.core.util.groups.GroupChangeBusyException) && e !is org.signal.core.util.groups.GroupNotAMemberException && e !is org.signal.core.util.groups.GroupInsufficientRightsException) {
          // Release the room queue; removals and other requests must remain runnable.
          wakeAfter(if (e is org.signal.core.util.groups.GroupChangeBusyException) 1_000 else 30_000)
        } else if (e !is org.thoughtcrime.securesms.gateway.GatewayPausedException) throw e
      } finally { GatewayRuntime.releaseWork() }
    }
  }
  private fun wakeAfter(delay: Long) {
    AppDependencies.jobManager.add(GatewayWorkJob(parameters("timer:retry:${java.util.UUID.randomUUID()}", delay), requestId, groupId, removed, messageId, true, room, intake))
  }
  override fun onShouldRetry(e: Exception): Boolean = false
  override fun onFailure() { Log.w(Log.tag(GatewayWorkJob::class), "Gateway work deferred or could not be completed.") }
  class Factory : Job.Factory<GatewayWorkJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GatewayWorkJob {
      val data = JsonJobData.deserialize(serializedData)
      return GatewayWorkJob(parameters, data.getLong("request"), data.getString("group"), data.getStringArray("removed").toList(), data.getLong("message"), data.getBoolean("timer"), data.getString("room"), data.getBoolean("intake"))
    }
  }
  companion object {
    const val KEY = "GatewayWorkJob"
    private fun parameters(queue: String, delay: Long = 0): Parameters = Parameters.Builder().setQueue("$KEY:$queue")
      .addConstraint(NetworkConstraint.KEY).setInitialDelay(delay).setLifespan(TimeUnit.DAYS.toMillis(3651)).setMaxAttempts(Parameters.UNLIMITED)
      .apply {
        if (queue.startsWith("timer:")) setMaxInstancesForQueue(1)
        if (queue.startsWith("intake:")) setMaxInstancesForQueue(2) // One running plus one fresh snapshot after a burst.
      }.build()
    internal fun at(requestId: Long, room: String, at: Long): GatewayWorkJob {
      val delay = maxOf(0, at - System.currentTimeMillis())
      return if (delay == 0L) GatewayWorkJob(requestId, 0, room) else GatewayWorkJob(parameters("timer:$requestId:$at", delay), requestId, "", emptyList(), 0, true, room)
    }
    internal fun intake(sourceId: String) = GatewayWorkJob(parameters("intake:$sourceId"), 0, sourceId, emptyList(), 0, false, "", true)
    @JvmStatic fun recover() { AppDependencies.jobManager.add(GatewayWorkJob(0)) }
  }
}
