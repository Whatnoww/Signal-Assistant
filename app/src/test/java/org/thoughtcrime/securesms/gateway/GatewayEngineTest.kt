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
class GatewayEngineTest {
  private lateinit var db: JdbcSqliteDatabase
  private lateinit var store: GatewayStore
  private lateinit var platform: FakePlatform
  private lateinit var engine: GatewayEngine
  private val source = GatewayGroup("source", "Source", true, true, "room", true, mentionAdmins = true)
  @Before fun setup() {
    db = JdbcSqliteDatabase.createInMemory(); GatewayStore.createSchema(db); store = GatewayStore(db)
    platform = FakePlatform(source); engine = GatewayEngine(store, platform)
  }
  @After fun cleanup() = db.close()

  private fun start(sourceId: String = "source"): Long {
    engine.intake(sourceId)
    return store.requests().last { it.source == sourceId }.id
  }
  private fun prompted(id: Long) {
    repeat(3) { engine.step(id) }
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(id)!!.phase)
  }
  private fun answer(id: Long) {
    val request = store.request(id)!!
    engine.answer(request.room, request.aci, 77, platform.clock + 1)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
  }

  @Test fun `normal admission prompts once and one response reaches review`() {
    val id = start(); prompted(id)
    assertEquals(listOf(GatewayMutation.ADD), platform.mutations.map { it.second })
    assertEquals(1, platform.sends.count { it.second == "spam" })
    answer(id); engine.step(id)
    assertEquals(1, platform.notifications.size)
    assertTrue(platform.groups.getValue("source").requests.containsKey("applicant"))
  }

  @Test fun `one reply satisfies the current shared-room batch and a later request needs another reply`() {
    val second = source.copy(id = "second", name = "Second")
    platform.addSource(second)
    val firstId = start(); prompted(firstId)
    val secondId = start("second"); prompted(secondId)
    answer(firstId)
    assertEquals(setOf(firstId, secondId), platform.notifications.single().toSet())
    val third = source.copy(id = "third", name = "Third")
    platform.addSource(third)
    val thirdId = start("third"); prompted(thirdId)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(thirdId)!!.phase)
    platform.clock += 10
    engine.answer("room", "applicant", 78, platform.clock)
    assertEquals(listOf(thirdId), platform.notifications.last())
    assertEquals(1, platform.mutations.count { it.second == GatewayMutation.ADD })
  }

  @Test fun `custom question belongs to its source and default question is used otherwise`() {
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(spamText = "A custom question")))
    val id = start(); prompted(id)
    assertEquals("A custom question", platform.fields.last { it.first == "spam" }.second["spamtext"])
  }

  @Test fun `intake blocklist rejects without admission`() {
    platform.blocked = setOf("applicant")
    val id = start(); engine.step(id)
    assertFalse(store.request(id)!!.active)
    assertEquals(listOf(GatewayMutation.DENY), platform.mutations.map { it.second })
    assertFalse("applicant" in platform.groups.getValue("source").members)
  }

  @Test fun `blocklisted admin-disabled source stays pending and gets a manual-action result`() {
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(admin = false)))
    platform.blocked = setOf("applicant")
    val id = start(); engine.step(id)
    assertTrue(platform.mutations.isEmpty())
    assertTrue(store.request(id)!!.data.getString("reason").contains("manual action required"))
    assertTrue("applicant" in platform.groups.getValue("source").requests)
  }

  @Test fun `missing blocklist cache pauses intake without admission`() {
    platform.blocked = null
    val id = start(); engine.step(id)
    assertEquals(GatewayPhase.INTAKE, store.request(id)!!.phase)
    assertTrue(platform.mutations.isEmpty())
  }

  @Test fun `new blocklist match before approval denies directly and never approves`() {
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.blocked = setOf("applicant")
    engine.step(id)
    assertEquals(GatewayMutation.DENY, platform.mutations.last().second)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.APPROVE })
    assertEquals("ACI-Blocklist", store.request(id)!!.data.getString("reason"))
  }

  @Test fun `caller must administer the selected source for either decision`() {
    val id = start(); prompted(id)
    assertThrows(IllegalArgumentException::class.java) { engine.decide("room", "applicant", store.request(id)!!.number, "outsider", false) }
    assertThrows(IllegalArgumentException::class.java) { engine.decide("room", "applicant", store.request(id)!!.number, "outsider", true) }
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(id)!!.phase)
  }

  @Test fun `displayed number resolves its source independently of the database id`() {
    store.intake(source, "someone-else", 1, GatewayDefaults(), platform.clock)
    platform.addSource(source.copy(id = "second"))
    val first = start()
    val second = start("second")
    platform.groups["second"] = platform.groups.getValue("second").copy(admins = setOf("other-admin", "bot"))
    assertTrue(first > 1)
    assertThrows(IllegalArgumentException::class.java) { engine.decide("room", "applicant", 2, "admin", true) }
    engine.decide("room", "applicant", 1, "admin", true)
    engine.step(first)
    assertEquals("approved", store.request(first)!!.data.getString("reason"))
    assertEquals(GatewayPhase.INTAKE, store.request(second)!!.phase)
    assertTrue("applicant" in platform.groups.getValue("second").requests)
  }

  @Test fun `commands received before a new session cannot approve or deny its reused number`() {
    val old = start()
    val receivedAt = platform.clock + 1
    store.finish(store.request(old)!!, "manual")
    platform.clock += 1000
    platform.groups["source"] = platform.groups.getValue("source").copy(requests = mapOf("applicant" to 2L))
    val fresh = start()
    assertEquals(1L, store.request(fresh)!!.number)
    listOf(true, false).forEach { approve ->
      listOf(null, 1L).forEach { number ->
        assertThrows(IllegalArgumentException::class.java) { engine.decide("room", "applicant", number, "admin", approve, receivedAt) }
      }
    }
    assertEquals(GatewayPhase.INTAKE, store.request(fresh)!!.phase)
    assertTrue(platform.mutations.isEmpty())
    engine.decide("room", "applicant", 1, "admin", true)
    engine.step(fresh)
    assertEquals("approved", store.request(fresh)!!.data.getString("reason"))
  }

  @Test fun `source admins can approve before admission a prompt or an applicant response`() {
    listOf(GatewayPhase.INTAKE, GatewayPhase.WAITING_FOR_MEMBERSHIP, GatewayPhase.PROMPTING, GatewayPhase.WAITING_FOR_RESPONSE).forEachIndexed { steps, phase ->
      val room = "room-$steps"
      val policy = source.copy(id = "source-$steps", destination = room)
      platform.groups[room] = platform.groups.getValue("room").copy(id = room)
      platform.addSource(policy)
      val id = start(policy.id)
      repeat(steps) { engine.step(id) }
      assertEquals(phase, store.request(id)!!.phase)

      engine.decide(room, "applicant", store.request(id)!!.number, "admin", true)
      engine.step(id)

      assertEquals("approved", store.request(id)!!.data.getString("reason"))
      assertTrue("applicant" in platform.groups.getValue(policy.id).members)
      platform.clock += policy.removalMillis(GatewayDefaults()) + 1
      engine.step(id) // Already scheduled screening timers cannot deny the approved member.
      assertTrue("applicant" in platform.groups.getValue(policy.id).members)
    }
    assertTrue(platform.notifications.isEmpty())
    assertFalse(platform.sends.any { it.second == "spam_reminder" })
  }

  @Test fun `early approval still denies a newly blocklisted applicant without admission`() {
    val id = start()
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.blocked = setOf("applicant")
    engine.step(id)
    assertEquals("ACI-Blocklist", store.request(id)!!.data.getString("reason"))
    assertEquals(listOf(GatewayMutation.DENY), platform.mutations.map { it.second })
    assertFalse("applicant" in platform.groups.getValue("source").members)
  }

  @Test fun `early approval cannot bypass disabled source administrative actions`() {
    val id = start()
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(admin = false)))
    assertThrows(IllegalArgumentException::class.java) { engine.decide("room", "applicant", store.request(id)!!.number, "admin", true) }
    assertEquals(GatewayPhase.INTAKE, store.request(id)!!.phase)
    assertTrue(platform.mutations.isEmpty())
  }

  @Test fun `early approval losing human permission resumes screening without inventing a reply`() {
    val id = start(); engine.step(id); engine.step(id)
    assertEquals(GatewayPhase.PROMPTING, store.request(id)!!.phase)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    val group = platform.groups.getValue("source")
    platform.groups["source"] = group.copy(admins = setOf("bot"))
    engine.step(id)
    assertEquals(GatewayPhase.PROMPTING, store.request(id)!!.phase)
    assertFalse(store.request(id)!!.data.has("replyId"))
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(id)!!.phase)
    assertTrue(platform.notifications.isEmpty())
    platform.groups["source"] = group
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
  }

  @Test fun `early approval waits for current bot permission and can resume without a reply`() {
    val id = start()
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    val group = platform.groups.getValue("source")
    platform.groups["source"] = group.copy(botAdmin = false)
    engine.step(id)
    assertEquals(GatewayPhase.APPROVING, store.request(id)!!.phase)
    assertTrue(platform.mutations.isEmpty())
    platform.groups["source"] = group
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
  }

  @Test fun `early approval only resolves its selected shared-room request`() {
    platform.addSource(source.copy(id = "second"))
    val first = start(); prompted(first)
    val second = start("second"); prompted(second)
    engine.decide("room", "applicant", store.request(first)!!.number, "admin", true)
    engine.step(first); engine.step(first)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(second)!!.phase)
    assertTrue("applicant" in platform.groups.getValue("room").members)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.CLEANUP })
    answer(second)
    assertEquals(listOf(second), platform.notifications.single())
  }

  @Test fun `same-group approval makes membership permanent before any screening response`() {
    repeat(4) { steps ->
      val policy = source.copy(id = "same-$steps", destination = "same-$steps")
      platform.addSource(policy)
      val id = start(policy.id)
      repeat(steps) { engine.step(id) }
      engine.decide(policy.id, "applicant", store.request(id)!!.number, "admin", true)
      engine.step(id)
      assertEquals("approved", store.request(id)!!.data.getString("reason"))
      assertFalse(store.session(policy.id, "applicant").optBoolean("owned"))
      platform.clock += policy.removalMillis(GatewayDefaults()) + 1
      engine.step(id)
      assertTrue("applicant" in platform.groups.getValue(policy.id).members)
    }
    assertFalse(platform.mutations.any { it.second in setOf(GatewayMutation.REMOVE, GatewayMutation.DENY, GatewayMutation.CLEANUP) })
  }

  @Test fun `an approval claimed during admission cannot be overwritten by admission completion`() {
    val id = start()
    platform.onMutation = { _, operation ->
      if (operation == GatewayMutation.ADD) engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    }
    engine.step(id)
    assertEquals(GatewayPhase.APPROVING, store.request(id)!!.phase)
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
    assertFalse(platform.sends.any { it.second == "spam" })
  }

  @Test fun `same-group native approval stays confirmed if the actor loses their role after the operation`() {
    platform.config = platform.config.copy(groups = mapOf("source" to source.copy(destination = "source")))
    val id = start()
    engine.decide("source", "applicant", store.request(id)!!.number, "admin", true)
    platform.onGroup = { group ->
      val state = platform.groups.getValue(group)
      if (group == "source" && "applicant" in state.members) platform.groups[group] = state.copy(admins = setOf("bot"))
    }
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
    assertTrue("applicant" in platform.groups.getValue("source").members)
    assertFalse(store.session("source", "applicant").optBoolean("owned"))
  }

  @Test fun `an early approval winning timeout validation prevents timeout denial`() {
    val id = start(); prompted(id)
    platform.clock += source.removalMillis(GatewayDefaults()) + 1
    var reads = 0
    platform.onGroup = { group ->
      if (group == "source" && ++reads == 2) engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    }
    engine.step(id)
    assertEquals(GatewayPhase.APPROVING, store.request(id)!!.phase)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.DENY })
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
  }

  @Test fun `human role lost after claiming approval prevents the mutation`() {
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.groups["source"] = platform.groups.getValue("source").copy(admins = emptySet())
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.APPROVE })
  }

  @Test fun `lost bot administrative permission leaves approval in progress without mutation`() {
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.groups["source"] = platform.groups.getValue("source").copy(botAdmin = false)
    engine.step(id)
    assertEquals(GatewayPhase.APPROVING, store.request(id)!!.phase)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.APPROVE })
  }

  @Test fun `throttle and emergency pause freeze work without disabling other sources`() {
    val id = start(); prompted(id)
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(throttle = true)))
    platform.clock += 10; engine.step(id)
    assertTrue(store.request(id)!!.data.has("pausedAt"))
    val sends = platform.sends.size
    platform.clock += 1000000000; engine.step(id)
    assertEquals(sends, platform.sends.size)
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source))
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(id)!!.phase)
    platform.config = platform.config.copy(paused = true)
    engine.step(id)
    assertTrue(store.request(id)!!.data.has("pausedAt"))
  }

  @Test fun `invitation fallback waits and uses a separate admission timeout`() {
    platform.invitationOnly = true
    val id = start(); engine.step(id); engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_MEMBERSHIP, store.request(id)!!.phase)
    assertTrue(platform.sends.isEmpty())
    platform.clock += source.admissionMillis(GatewayDefaults()) + 1
    engine.step(id)
    assertEquals(GatewayMutation.DENY, platform.mutations.last().second)
  }

  @Test fun `interrupted admission never blindly re-adds a removed applicant`() {
    val id = start()
    val request = store.request(id)!!
    store.save(request, request.transition(GatewayPhase.ADMITTING) { it.put("admissionAt", platform.clock) })
    engine.step(id)
    assertTrue(platform.mutations.isEmpty())
    assertEquals(GatewayPhase.FINISHED, store.request(id)!!.phase)
    assertTrue(store.request(id)!!.data.getString("reason").contains("source request unchanged"))
  }

  @Test fun `manual departure finishes every related source and does not touch native requests`() {
    platform.addSource(source.copy(id = "second"))
    val first = start(); prompted(first)
    val second = start("second"); prompted(second)
    platform.groups["room"] = platform.groups.getValue("room").copy(members = setOf("bot"))
    engine.step(first)
    assertFalse(store.request(first)!!.active); assertFalse(store.request(second)!!.active)
    engine.intake("source")
    assertEquals(2, store.requests().size)
    assertEquals(1, platform.mutations.size)
    assertTrue("applicant" in platform.groups.getValue("source").requests)
  }

  @Test fun `external approval affects one request and preserves shared Gateway membership`() {
    platform.addSource(source.copy(id = "second"))
    val first = start(); prompted(first)
    val second = start("second"); prompted(second)
    platform.groups["source"] = platform.groups.getValue("source").copy(requests = emptyMap(), members = setOf("bot", "applicant"))
    engine.step(first); engine.step(first)
    assertTrue(store.request(second)!!.active)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.CLEANUP })
    assertTrue("applicant" in platform.groups.getValue("room").members)
  }

  @Test fun `pre-existing Gateway members are never owned or cleaned up`() {
    platform.groups["room"] = platform.groups.getValue("room").copy(members = setOf("bot", "applicant"))
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", false)
    engine.step(id); engine.step(id)
    assertFalse(platform.mutations.any { it.second in setOf(GatewayMutation.ADD, GatewayMutation.CLEANUP) })
    assertTrue("applicant" in platform.groups.getValue("room").members)
  }

  @Test fun `same-group approval is local confirmation and denial removes admitted member`() {
    platform.config = platform.config.copy(groups = mapOf("source" to source.copy(destination = "source")))
    val id = start(); prompted(id); answer(id)
    engine.decide("source", "applicant", store.request(id)!!.number, "admin", true)
    engine.step(id)
    assertEquals(1, platform.mutations.count { it.second == GatewayMutation.APPROVE })
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
    assertTrue("applicant" in platform.groups.getValue("source").members)
  }

  @Test fun `reminder is sent once and answered requests do not time out`() {
    val id = start(); prompted(id)
    platform.clock += source.reminderMillis(GatewayDefaults())
    engine.step(id); engine.step(id)
    assertEquals(1, platform.sends.count { it.second == "spam_reminder" })
    answer(id)
    platform.clock += source.removalMillis(GatewayDefaults())
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
  }

  @Test fun `same-group approval preserves permanent room membership after another source finishes`() {
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(destination = "source")))
    platform.addSource(source.copy(id = "second", destination = "source"))
    val first = start(); prompted(first)
    val second = start("second"); prompted(second)
    engine.answer("source", "applicant", 77, platform.clock + 1)
    engine.decide("source", "applicant", store.request(first)!!.number, "admin", true); engine.step(first)
    engine.decide("source", "applicant", store.request(second)!!.number, "admin", false); engine.step(second)
    engine.step(second)
    assertTrue("applicant" in platform.groups.getValue("source").members)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.CLEANUP })
  }

  @Test fun `same-group denial removes the provisional member and correlates the departure`() {
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(destination = "source")))
    val id = start(); prompted(id)
    engine.decide("source", "applicant", store.request(id)!!.number, "admin", false)
    engine.step(id)
    store.finishDeparture("source", "applicant")
    assertEquals("denied", store.request(id)!!.data.getString("reason"))
    assertFalse("applicant" in platform.groups.getValue("source").members)
  }

  @Test fun `same-group local confirmation rechecks human authorization after the blocklist check`() {
    platform.config = platform.config.copy(groups = platform.config.groups + ("source" to source.copy(destination = "source")))
    val id = start(); prompted(id); answer(id)
    engine.decide("source", "applicant", store.request(id)!!.number, "admin", true)
    platform.onBlocklist = { platform.groups["source"] = platform.groups.getValue("source").copy(admins = setOf("bot")) }
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
    assertEquals(1, platform.mutations.count { it.second == GatewayMutation.APPROVE })
  }

  @Test fun `a qualifying response winning during timeout validation prevents timeout denial`() {
    val id = start(); prompted(id)
    platform.clock += source.removalMillis(GatewayDefaults()) + 1
    var reads = 0
    platform.onGroup = { group ->
      if (group == "source" && ++reads == 2) store.satisfy("room", "applicant", 88, platform.clock)
    }
    engine.step(id)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
    assertFalse(platform.mutations.any { it.second == GatewayMutation.DENY })
  }

  @Test fun `manual finish winning during approval check cannot be overwritten`() {
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.onBlocklist = { store.finishDeparture("room", "applicant") }
    engine.step(id)
    assertEquals("unplanned_gateway_departure", store.request(id)!!.data.getString("reason"))
    assertFalse(platform.mutations.any { it.second == GatewayMutation.APPROVE })
  }

  @Test fun `a fast response survives a pending native receipt and restart`() {
    val id = start()
    engine.step(id); engine.step(id)
    platform.pendingReceipt = true
    engine.answer("room", "applicant", 78, platform.clock + 1)
    assertEquals(78L, store.request(id)!!.data.getLong("deferredReplyId"))
    platform.pendingReceipt = false
    val recovered = GatewayEngine(store, platform)
    recovered.step(id)
    assertEquals(listOf(78L), platform.replayed)
    recovered.answer("room", "applicant", 78, platform.clock + 1)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
    assertEquals(1, platform.notifications.size)
  }

  @Test fun `a reply before a queued prompt actually sends cannot satisfy that future prompt`() {
    val id = start()
    engine.step(id); engine.step(id)
    platform.pendingReceipt = true
    val earlierReply = platform.clock + 1
    engine.answer("room", "applicant", 78, earlierReply)
    platform.clock += 100
    platform.pendingReceipt = false
    engine.step(id)
    engine.answer("room", "applicant", 78, earlierReply)
    assertEquals(GatewayPhase.WAITING_FOR_RESPONSE, store.request(id)!!.phase)
    assertTrue(platform.notifications.isEmpty())
    engine.answer("room", "applicant", 79, platform.clock + 1)
    assertEquals(GatewayPhase.WAITING_FOR_DECISION, store.request(id)!!.phase)
  }

  @Test fun `a new native request generation can proceed after a manual finish`() {
    val old = start(); prompted(old)
    store.finishDeparture("room", "applicant")
    platform.groups["source"] = platform.groups.getValue("source").copy(requests = mapOf("applicant" to 2))
    val fresh = start()
    assertTrue(fresh > old)
    assertEquals(2L, store.request(fresh)!!.generation)
  }

  @Test fun `cleanup in progress defers intake until the room has been reconciled`() {
    store.saveSession("room", "applicant", org.json.JSONObject().put("cleaning", true))
    engine.intake("source")
    assertTrue(store.requests().isEmpty())
  }

  @Test fun `bot permission recovery can retry the existing approval claim`() {
    val id = start(); prompted(id); answer(id)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    platform.groups["source"] = platform.groups.getValue("source").copy(botAdmin = false)
    engine.step(id)
    platform.groups["source"] = platform.groups.getValue("source").copy(botAdmin = true)
    engine.decide("room", "applicant", store.request(id)!!.number, "admin", true)
    engine.step(id)
    assertEquals("approved", store.request(id)!!.data.getString("reason"))
  }

  private class FakePlatform(source: GatewayGroup) : GatewayPlatform {
    var clock = 100000L
    var config = GatewayConfiguration(true, false, GatewayDefaults(), mapOf(source.id to source, "room" to GatewayGroup("room", "Room", false)), emptyMap(), emptyMap())
    val groups = mutableMapOf<String, GatewayGroupState>()
    var blocked: Set<String>? = emptySet()
    var invitationOnly = false
    var pendingReceipt = false
    var onGroup: (String) -> Unit = {}
    var onBlocklist: () -> Unit = {}
    var onMutation: (GatewayRequest, GatewayMutation) -> Unit = { _, _ -> }
    val replayed = mutableListOf<Long>()
    val mutations = mutableListOf<Pair<Long, GatewayMutation>>()
    val sends = mutableListOf<Pair<Long, String>>()
    val fields = mutableListOf<Pair<String, Map<String, String>>>()
    val notifications = mutableListOf<List<Long>>()
    val outgoing = mutableMapOf<String, Long>()
    init { addSource(source); groups["room"] = state("room", emptyMap()) }
    fun addSource(source: GatewayGroup) { config = config.copy(groups = config.groups + (source.id to source)); groups[source.id] = state(source.id, mapOf("applicant" to 1L)) }
    private fun state(id: String, requests: Map<String, Long>) = GatewayGroupState(id, id, setOf("bot"), setOf("admin", "bot"), requests, emptySet(), emptySet(), true, true, true, true)
    override fun now() = clock
    override fun configuration(online: Boolean) = config
    override fun group(id: String, fresh: Boolean): GatewayGroupState { onGroup(id); return groups.getValue(id) }
    override fun blocklist(): Set<String>? { onBlocklist(); return blocked }
    override fun schedule(id: Long, at: Long) = Unit
    override fun sendState(messageId: Long) = if (pendingReceipt) GatewaySendState() else GatewaySendState(clock)
    override fun promptTimestamp(request: GatewayRequest): Long? = if (outgoing.containsKey("${request.id}:prompt")) clock else null
    override fun replayAnswer(messageId: Long) { replayed += messageId }
    override fun send(request: GatewayRequest, key: String, template: String, fields: Map<String, String>, quoteId: Long?): Long {
      val event = "${request.id}:$key"
      return outgoing.getOrPut(event) { sends += request.id to template; this.fields += template to fields; sends.size.toLong() }
    }
    override fun notify(requests: List<GatewayRequest>, messageId: Long): Long { notifications += requests.map { it.id }; return 999L }
    override fun mutate(request: GatewayRequest, operation: GatewayMutation, actor: String?): GatewayMutationResult {
      onMutation(request, operation)
      mutations += request.id to operation
      val id = if (operation in setOf(GatewayMutation.ADD, GatewayMutation.CLEANUP)) request.room else request.source
      val group = groups.getValue(id)
      groups[id] = when (operation) {
        GatewayMutation.ADD -> if (invitationOnly) group.copy(invites = group.invites + request.aci) else group.copy(members = group.members + request.aci)
        GatewayMutation.APPROVE -> group.copy(members = group.members + request.aci, requests = group.requests - request.aci)
        GatewayMutation.DENY -> group.copy(requests = group.requests - request.aci)
        GatewayMutation.REMOVE, GatewayMutation.CLEANUP -> group.copy(members = group.members - request.aci, invites = group.invites - request.aci)
      }
      return GatewayMutationResult(operation in setOf(GatewayMutation.ADD, GatewayMutation.APPROVE), operation == GatewayMutation.APPROVE || operation == GatewayMutation.ADD && !invitationOnly)
    }
  }
}
