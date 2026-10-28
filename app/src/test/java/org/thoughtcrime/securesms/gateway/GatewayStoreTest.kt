/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.testing.JdbcSqliteDatabase

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GatewayStoreTest {
  private lateinit var db: JdbcSqliteDatabase
  private lateinit var store: GatewayStore
  private val source = GatewayGroup("source", "Source", true, true, "room", true)
  @Before fun setup() { db = JdbcSqliteDatabase.createInMemory(); GatewayStore.createSchema(db); store = GatewayStore(db) }
  @After fun cleanup() = db.close()
  private fun intake(generation: Long = 1) = store.intake(source, "applicant", generation, GatewayDefaults(), 1000)

  @Test fun `approval and denial claims have only one winner`() {
    val request = intake()
    val competing = store.request(request.id)!!
    assertTrue(store.save(request, request.transition(GatewayPhase.APPROVING)))
    assertFalse(store.save(competing, competing.transition(GatewayPhase.DENYING)))
    assertEquals(GatewayPhase.APPROVING, store.request(request.id)!!.phase)
  }

  @Test fun `failed restricted sends retain their audience without stranding an unused cooldown`() {
    assertEquals(0L, store.reserveCooldown("scan", 10, 100, 1000))
    store.reserveOutgoing("first", 0, "audience:{}", 100, 10)
    store.reserveOutgoing("second", 0, "audience:{}", 101, 10)
    store.markAudienceFailed(100)
    store.releaseIfIdle(10)
    assertTrue(store.reserveCooldown("scan", 11, 200, 1000) > 0)
    store.markAudienceFailed(101)
    store.releaseIfIdle(10)
    assertEquals(0L, store.reserveCooldown("scan", 11, 200, 1000))
    assertEquals("audience:{}", store.outgoingRequest(100)!!.second)
    store.bindMessage(100, 50)
    store.recordAck(100, 300)
    assertEquals(300L, store.confirmedAt(50))
  }

  @Test fun `manual finish suppresses rediscovery until native request is gone`() {
    val request = intake()
    store.finishDeparture("room", "applicant")
    assertFalse(store.request(request.id)!!.active)
    assertEquals(request.id, intake().id)
    val finished = store.request(request.id)!!
    store.prune(finished, false, true, true)
    assertNotNull(store.request(request.id))
    store.prune(finished, true, true, true)
    assertNull(store.request(request.id))
    assertTrue(intake(2).id > request.id)
  }

  @Test fun `only an existing sent prompt batch is satisfied and disabled sources stay untouched`() {
    val first = intake()
    val second = store.intake(source.copy(id = "other"), "applicant", 2, GatewayDefaults(), 1000)
    listOf(first, second).forEach { store.save(it, it.transition(GatewayPhase.WAITING_FOR_RESPONSE) { data -> data.put("promptAt", 2000) }) }
    assertTrue(store.satisfy("room", "applicant", 17, 1999).isEmpty())
    assertEquals(listOf(first.id), store.satisfy("room", "applicant", 18, 2001, setOf(first.id)))
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(second.id)!!.phase)
    val later = intake(3)
    store.save(later, later.transition(GatewayPhase.WAITING_FOR_RESPONSE) { it.put("promptAt", 3000) })
    assertFalse(later.id in store.satisfy("room", "applicant", 19, 2500))
    assertEquals(18L, store.request(first.id)!!.data.getLong("replyId"))
  }

  @Test fun `pause freezes remaining time exactly once`() {
    val request = intake()
    store.save(request, request.transition(GatewayPhase.WAITING_FOR_RESPONSE) { it.put("promptAt", 1000).put("admissionAt", 500) })
    store.setPaused(store.request(request.id)!!, true, 2000)
    assertFalse(store.setPaused(store.request(request.id)!!, true, 4000))
    store.setPaused(store.request(request.id)!!, false, 6000)
    val resumed = store.request(request.id)!!
    assertEquals(5000L, resumed.data.getLong("promptAt"))
    assertEquals(4500L, resumed.data.getLong("admissionAt"))
    assertFalse(resumed.data.has("pausedAt"))
  }

  @Test fun `a prompt acknowledged during pause retains its full grace period on resume`() {
    val request = intake()
    store.save(request, request.transition(GatewayPhase.PROMPTING) { it.put("promptId", 7) })
    store.reserveOutgoing("review:${request.id}:prompt:0", request.id, "spam", 1500)
    store.outgoingInserted("review:${request.id}:prompt:0", 7)
    store.setPaused(store.request(request.id)!!, true, 2000)
    store.recordAck(1500, 2500)
    store.setPaused(store.request(request.id)!!, false, 6000)
    assertEquals(3500L, store.request(request.id)!!.data.getLong("promptPauseMillis"))
    assertEquals(6000L, store.confirmedAt(7)!! + store.request(request.id)!!.data.getLong("promptPauseMillis"))
  }

  @Test fun `cooldown reservations are atomic and release only unsent results`() {
    assertEquals(0L, store.reserveCooldown("dm:aci", 1, 1000, 900000))
    assertEquals(899999L, store.reserveCooldown("dm:aci", 2, 1001, 900000))
    store.releaseCooldown(1)
    assertEquals(0L, store.reserveCooldown("dm:aci", 2, 1002, 900000))
    store.consumeCooldown(2)
    store.releaseCooldown(2)
    assertTrue(store.reserveCooldown("dm:aci", 3, 1003, 900000) > 0)
    assertEquals(0L, store.reserveCooldown("dm:aci", 3, 1000000, 900000))
  }

  @Test fun `finished rows drop timers and replies while pending audit references survive`() {
    val request = intake()
    store.save(request, request.transition { it.put("promptAt", 1000).put("replyId", 7).put("auditId", 8) })
    store.finish(store.request(request.id)!!, "manual")
    val data = store.request(request.id)!!.data
    assertFalse(data.has("promptAt")); assertFalse(data.has("replyId"))
    assertEquals(8L, data.getLong("auditId"))
    assertEquals("manual", data.getString("reason"))
  }

  @Test fun `private-send markers never modify main recipient state and are removable`() {
    store.preserveContact(7, 1000)
    assertTrue(store.preservesContact(7, 1000))
    assertFalse(store.preservesContact(7, 1001))
    store.forgetPrivateSend(7, 1000)
    assertFalse(store.preservesContact(7, 1000))
    db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
      val tables = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
      assertFalse("recipient" in tables); assertFalse("message" in tables)
    }
    assertNotEquals("signal.db", GatewayDatabase.NAME)
  }

  @Test fun `cleanup and intake cannot both claim the same session`() {
    val row = intake()
    store.finish(row, "denied")
    val terminal = store.request(row.id)!!
    store.saveSession("room", "applicant", org.json.JSONObject().put("owned", true))
    assertTrue(store.claimCleanup(terminal))
    assertNull(store.intakeUnlessCleaning(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 2000))
    store.finishDeparture("room", "applicant")
    assertEquals("denied", store.request(row.id)!!.data.getString("reason"))
  }

  @Test fun `unplanned departure releases membership ownership even if a human readmits the applicant`() {
    intake()
    store.saveSession("room", "applicant", org.json.JSONObject().put("owned", true).put("present", true))
    store.finishDeparture("room", "applicant")
    assertFalse(store.session("room", "applicant").optBoolean("owned"))
    assertFalse(store.claimCleanup(store.requests().single()))
  }

  @Test fun `send completion starts cooldown and receipts survive store recreation`() {
    store.reserveCooldown("dm", 9, 1000, 900000)
    store.reserveOutgoing("scan:0", 0, "scan", 1100, 9)
    store.outgoingInserted("scan:0", 7)
    store.confirmOutgoing(1100, 5000)
    assertTrue(store.invocationSent(9))
    assertEquals(899999L, GatewayStore(db).reserveCooldown("dm", 10, 5001, 900000))
    assertTrue(store.pendingOutgoing().isEmpty())
  }

  @Test fun `completed audit tracking is compacted while a minimal suppression row remains`() {
    val request = intake()
    store.finish(request, "manual")
    store.reserveOutgoing("review:${request.id}:outcome:0", request.id, "manual_finish", 1100)
    store.outgoingInserted("review:${request.id}:outcome:0", 7)
    store.confirmOutgoing(1100, 1200)
    store.compactFinished(store.request(request.id)!!)
    assertTrue(store.pendingOutgoing().isEmpty())
    assertEquals("manual", store.request(request.id)!!.data.getString("reason"))
  }

  @Test fun `finishing one review preserves shared notifications while cancelling its own pending prompt`() {
    val first = intake()
    val second = store.intake(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 1000)
    store.reserveOutgoing("batch:0", first.id, "administrator_notification", 1100)
    store.reserveOutgoing("prompt:0", first.id, "spam", 1200)
    store.finish(first, "approved")
    val finished = store.request(first.id)!!
    store.compactFinished(finished)
    store.prune(finished, true, true, true)
    assertEquals(second.id to "administrator_notification", store.outgoingRequest(1100))
    assertEquals(0L to "cancelled-review", store.outgoingRequest(1200))
    assertNotNull(store.request(second.id))
  }

  @Test fun `display numbers are scoped to applicant and room while internal ids remain unique`() {
    repeat(5) { store.intake(source, "unrelated-$it", 1, GatewayDefaults(), 1000) }
    val first = intake()
    val second = store.intake(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 1000)
    assertTrue(first.id > first.number)
    assertEquals(1L, first.number)
    assertEquals(2L, second.number)
    assertEquals(first.id, intake().id)
    assertEquals(3L, store.intake(source.copy(id = "third"), "applicant", 1, GatewayDefaults(), 1000).number)
    assertEquals(1L, store.intake(source, "someone-else", 1, GatewayDefaults(), 1000).number)
    assertEquals(1L, store.intake(source.copy(id = "elsewhere", destination = "other-room"), "applicant", 1, GatewayDefaults(), 1000).number)
  }

  @Test fun `session numbers survive pruning membership snapshots and reopening without reusing active session numbers`() {
    val first = intake()
    val snapshot = store.session("room", "applicant")
    val second = store.intake(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 1000)
    store.finish(second, "denied")
    store.prune(store.request(second.id)!!, true, true, true)
    store.saveSession("room", "applicant", snapshot.put("present", true))
    store.removeSession("room", "applicant") // A stale cleanup must not discard a live session's counter.
    val reopened = GatewayStore(db)
    val third = reopened.intake(source.copy(id = "third"), "applicant", 1, GatewayDefaults(), 1000)
    assertEquals(1L, reopened.request(first.id)!!.number)
    assertEquals(3L, third.number)
  }

  @Test fun `all reviews finishing resets the next session even while old suppression rows remain`() {
    val first = intake()
    val second = store.intake(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 1000)
    store.finish(first, "manual")
    store.finish(second, "manual")
    val next = store.intake(source, "applicant", 2, GatewayDefaults(), 2000)
    assertTrue(next.id > second.id)
    assertEquals(1L, next.number)
    assertEquals(2L, next.generation)
    assertNotNull(store.request(first.id))
  }

  @Test fun `legacy active mappings remain valid until that applicants session finishes`() {
    repeat(5) { store.intake(source, "unrelated-$it", 1, GatewayDefaults(), 1000) }
    val original = intake()
    store.save(original, original.transition { it.remove("number") })
    val legacy = store.request(original.id)!!
    assertEquals(legacy.id, legacy.number)
    val later = store.intake(source.copy(id = "second"), "applicant", 1, GatewayDefaults(), 1000)
    assertEquals(legacy.number + 1, later.number)
    store.finish(legacy, "approved")
    store.finish(later, "denied")
    assertEquals(1L, store.intake(source, "applicant", 2, GatewayDefaults(), 2000).number)
  }
}
