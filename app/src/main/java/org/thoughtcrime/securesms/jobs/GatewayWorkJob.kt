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
  private val intake: Boolean = false,
  private val urgent: Boolean = false,
  private val manual: List<String> = emptyList(),
  private val eventAt: Long = 0
) : BaseJob(parameters) {
  constructor(requestId: Long, delay: Long = 0, room: String = "", urgent: Boolean = false) : this(parameters(if (delay > 0) "timer:$requestId:${System.currentTimeMillis() + delay}" else if (room.isNotBlank()) "room:$room" else "request:$requestId", delay, urgent), requestId, "", emptyList(), 0, delay > 0, room, urgent = urgent)
  constructor(groupId: String, removed: List<String>, manual: List<String> = emptyList(), eventAt: Long = System.currentTimeMillis()) : this(parameters("room:$groupId", urgent = true), 0, groupId, removed, 0, urgent = true, manual = manual, eventAt = eventAt)
  constructor(messageId: Long, incoming: Boolean, room: String = "") : this(parameters(if (room.isNotBlank()) "room:$room" else "incoming:$messageId", urgent = true), 0, "", emptyList(), messageId, false, room, urgent = true)
  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = JsonJobData.Builder().putLong("request", requestId).putString("group", groupId)
    .putStringArray("removed", removed.toTypedArray()).putStringArray("manual", manual.toTypedArray()).putLong("event_at", eventAt).putLong("message", messageId).putBoolean("timer", timer).putString("room", room).putBoolean("intake", intake).putBoolean("urgent", urgent).serialize()
  override fun onRun() {
    // A delayed job must never occupy the immediate decision queue for days.
    if (timer) {
      val queue = when { intake -> "intake:$groupId"; groupId.isNotBlank() -> "room:$groupId"; room.isNotBlank() -> "room:$room"; messageId != 0L -> "incoming:$messageId"; else -> "request:$requestId" }
      AppDependencies.jobManager.add(GatewayWorkJob(parameters(queue, urgent = urgent), requestId, groupId, removed, messageId, false, room, intake, urgent, manual, eventAt))
    } else {
      if (!GatewayRuntime.acquireWork()) { wakeAfter(1_000); return }
      try {
        GatewayRuntime.work(context, requestId, groupId, removed, messageId, intake, manual, eventAt)
      } catch (e: Exception) {
        if ((e is IOException || e is org.signal.core.util.groups.GroupChangeBusyException) && e !is org.signal.core.util.groups.GroupNotAMemberException && e !is org.signal.core.util.groups.GroupInsufficientRightsException) {
          // Release the room queue; removals and other requests must remain runnable.
          wakeAfter(if (e is org.signal.core.util.groups.GroupChangeBusyException) 1_000 else 30_000)
        } else if (e !is org.thoughtcrime.securesms.gateway.GatewayPausedException) throw e
      } finally { GatewayRuntime.releaseWork() }
    }
  }
  private fun wakeAfter(delay: Long) {
    AppDependencies.jobManager.add(GatewayWorkJob(parameters("timer:retry:${java.util.UUID.randomUUID()}", delay, urgent), requestId, groupId, removed, messageId, true, room, intake, urgent, manual, eventAt))
  }
  override fun onShouldRetry(e: Exception): Boolean = false
  override fun onFailure() { Log.w(Log.tag(GatewayWorkJob::class), "Gateway work deferred or could not be completed.") }
  class Factory : Job.Factory<GatewayWorkJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GatewayWorkJob {
      val data = JsonJobData.deserialize(serializedData)
      return GatewayWorkJob(parameters, data.getLong("request"), data.getString("group"), data.getStringArray("removed").toList(), data.getLong("message"), data.getBoolean("timer"), data.getString("room"), data.getBoolean("intake"), data.getBooleanOrDefault("urgent", false), if (data.hasStringArray("manual")) data.getStringArray("manual").toList() else emptyList(), data.getLongOrDefault("event_at", 0))
    }
  }
  companion object {
    const val KEY = "GatewayWorkJob"
    private fun parameters(queue: String, delay: Long = 0, urgent: Boolean = false): Parameters = Parameters.Builder().setQueue("$KEY:$queue")
      .setQueuePriority(if (urgent) Parameters.PRIORITY_HIGH else Parameters.PRIORITY_DEFAULT)
      .addConstraint(NetworkConstraint.KEY).setInitialDelay(delay).setLifespan(TimeUnit.DAYS.toMillis(3651)).setMaxAttempts(Parameters.UNLIMITED)
      .apply {
        if (queue.startsWith("timer:")) setMaxInstancesForQueue(1)
        if (queue.startsWith("intake:")) setMaxInstancesForQueue(2) // One running plus one fresh snapshot after a burst.
      }.build()
    internal fun at(requestId: Long, room: String, at: Long, urgent: Boolean = false): GatewayWorkJob {
      val delay = maxOf(0, at - System.currentTimeMillis())
      return if (delay == 0L) GatewayWorkJob(requestId, 0, room, urgent) else GatewayWorkJob(parameters("timer:$requestId:$at", delay, urgent), requestId, "", emptyList(), 0, true, room, urgent = urgent)
    }
    internal fun intake(sourceId: String) = GatewayWorkJob(parameters("intake:$sourceId"), 0, sourceId, emptyList(), 0, false, "", true)
    @JvmStatic fun recover() { AppDependencies.jobManager.add(GatewayWorkJob(0)) }
  }
}
