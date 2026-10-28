/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AciBlocklistCacheTest {
  private val cacheField = GroupTable::class.java.getDeclaredField("cachedBlocklist").apply { isAccessible = true }
  private val timestampField = GroupTable::class.java.getDeclaredField("lastBlocklistFetch").apply { isAccessible = true }
  private val client = GroupTable::class.java.getDeclaredField("blocklistClient").apply { isAccessible = true }.get(null) as OkHttpClient
  private var originalCache: Any? = null
  private var originalTimestamp = 0L
  private var remoteBody = FIRST_ACI
  private var remoteCode = 200
  private var offline = false
  private val requests = mutableListOf<Request>()

  @Before
  fun setUp() {
    originalCache = cacheField.get(null)
    originalTimestamp = timestampField.getLong(null)
    cacheField.set(null, null)
    timestampField.setLong(null, 0L)
    mockkObject(client)
    every { client.newCall(any()) } answers {
      val request = firstArg<Request>()
      requests += request
      mockk<Call> {
        every { execute() } answers {
          if (offline) throw IOException("offline")
          Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(remoteCode).message("test")
            .body(remoteBody.toResponseBody()).build()
        }
      }
    }
  }

  @After
  fun tearDown() {
    unmockkObject(client)
    cacheField.set(null, originalCache)
    timestampField.setLong(null, originalTimestamp)
  }

  @Test
  fun `forced scans detect successive manual changes inside the normal cache interval`() {
    assertEquals(setOf(FIRST_ACI), GroupTable.getBlocklist(forceRefresh = true))
    remoteBody = SECOND_ACI
    assertEquals(setOf(FIRST_ACI), GroupTable.getBlocklist())
    assertEquals(1, requests.size)

    assertEquals(setOf(SECOND_ACI), GroupTable.getBlocklist(forceRefresh = true))
    remoteBody = "$FIRST_ACI,$SECOND_ACI"
    assertEquals(setOf(FIRST_ACI, SECOND_ACI), GroupTable.getBlocklist(forceRefresh = true))
    assertEquals(3, requests.size)
    requests.forEach { assertEquals("no-cache", it.header("Cache-Control")) }
    assertEquals(setOf(FIRST_ACI, SECOND_ACI), GroupTable.getCachedBlocklist())
  }

  @Test
  fun `successful empty download removes cached entries`() {
    GroupTable.getBlocklist(forceRefresh = true)
    remoteBody = ""
    assertEquals(emptySet<String>(), GroupTable.getBlocklist(forceRefresh = true))
    assertEquals(emptySet<String>(), GroupTable.getCachedBlocklist())
    assertEquals(2, requests.size)
  }

  @Test
  fun `failed forced checks retain last good snapshot and allow another immediate attempt`() {
    GroupTable.getBlocklist(forceRefresh = true)
    val successfulTimestamp = timestampField.getLong(null)
    offline = true
    assertEquals(setOf(FIRST_ACI), GroupTable.getBlocklist(forceRefresh = true))
    offline = false
    remoteCode = 503
    assertEquals(setOf(FIRST_ACI), GroupTable.getBlocklist(forceRefresh = true))
    assertEquals(successfulTimestamp, timestampField.getLong(null))
    remoteCode = 200
    remoteBody = SECOND_ACI
    assertEquals(setOf(SECOND_ACI), GroupTable.getBlocklist(forceRefresh = true))
    assertEquals(4, requests.size)
  }

  @Test
  fun `failed first check leaves cache unavailable`() {
    offline = true
    assertEquals(emptySet<String>(), GroupTable.getBlocklist(forceRefresh = true))
    assertNull(GroupTable.getCachedBlocklist())
  }

  companion object {
    private const val FIRST_ACI = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private const val SECOND_ACI = "11111111-2222-4333-8444-555555555555"
  }
}
