/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CommandsCacheTest {
  private var time = 0L
  private val cache = CommandsCache { time }
  private val original = CommandsSnapshot("original", "sha-1", "etag-1")

  @Test
  fun `repeated requests inside freshness window make no network calls`() {
    var calls = 0
    repeat(10) {
      cache.refresh { calls++; original }
      time += 1000
    }
    assertEquals(1, calls)
    assertSame(original, cache.snapshot)
  }

  @Test
  fun `expiry revalidates using previous snapshot and ETag`() {
    cache.refresh { original }
    time = CommandsCache.FRESHNESS_MILLIS
    val updated = original.copy(xml = "changed", sha = "sha-2")
    assertTrue(cache.refresh { previous -> assertSame(original, previous); updated })
    assertSame(updated, cache.snapshot)
    assertFalse(cache.needsRefresh())
  }

  @Test
  fun `not modified response renews freshness without replacing content`() {
    cache.refresh { original }
    time = CommandsCache.FRESHNESS_MILLIS
    assertFalse(cache.refresh { it!! })
    assertFalse(cache.needsRefresh())
  }

  @Test
  fun `explicit invalidation bypasses freshness while preserving stale response`() {
    cache.refresh { original }
    cache.invalidate()
    assertSame(original, cache.snapshot)
    assertTrue(cache.needsRefresh())
    var fetched = false
    cache.refresh { fetched = true; it!! }
    assertTrue(fetched)
  }

  @Test
  fun `failed refresh keeps old data and throttles repeated attempts`() {
    cache.restore(original)
    assertThrows(IOException::class.java) { cache.refresh { throw IOException("offline") } }
    assertSame(original, cache.snapshot)
    assertFalse(cache.refresh { error("Must not retry until expiry") })
    time = CommandsCache.FRESHNESS_MILLIS
    assertTrue(cache.needsRefresh())
  }

  @Test
  fun `disk restore is available immediately but needs revalidation`() {
    cache.restore(original)
    assertSame(original, cache.snapshot)
    assertTrue(cache.needsRefresh())
  }

  @Test
  fun `successful publish immediately replaces cache`() {
    cache.refresh { original }
    val published = original.copy(xml = "published", sha = "new-sha")
    cache.replace(published)
    assertSame(published, cache.snapshot)
    assertFalse(cache.needsRefresh())
  }

  @Test
  fun `force refresh bypasses TTL`() {
    cache.refresh { original }
    var calls = 0
    cache.refresh(force = true) { calls++; original }
    assertEquals(1, calls)
  }

  @Test
  fun `content hash detects formatting changes but ignores update bookkeeping`() {
    val reply = CommandReply("hello", emptyList(), emptyList())
    assertEquals("hello\u0000".encodeUtf8().sha256().hex(), reply.contentHash())
    assertFalse(reply.contentHash() == reply.copy(styles = listOf(CommandStyleRange(CommandStyle.BOLD, 0, 5))).contentHash())
    assertEquals(reply.contentHash(), reply.copy(linkUpdates = listOf(CommandLinkUpdate("id", "old", "new"))).contentHash())
  }
}
