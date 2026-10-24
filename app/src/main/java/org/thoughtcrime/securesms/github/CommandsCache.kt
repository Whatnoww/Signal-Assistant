/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.os.SystemClock
import java.util.concurrent.TimeUnit

/** Snapshot reads stay nonblocking while a background refresh is in progress. */
internal class CommandsCache(private val now: () -> Long = SystemClock::elapsedRealtime) {
  @Volatile
  var snapshot: CommandsSnapshot? = null
    private set

  @Volatile
  private var lastCheckedAt: Long? = null

  @Synchronized
  fun restore(saved: CommandsSnapshot?) {
    if (snapshot == null) snapshot = saved
  }

  fun needsRefresh(): Boolean {
    val checkedAt = lastCheckedAt ?: return true
    val elapsed = now() - checkedAt
    return elapsed < 0 || elapsed >= FRESHNESS_MILLIS
  }

  @Synchronized
  fun refresh(force: Boolean = false, fetch: (CommandsSnapshot?) -> CommandsSnapshot): Boolean {
    if (!force && !needsRefresh()) return false
    val previous = snapshot
    try {
      snapshot = fetch(previous)
      return snapshot != previous
    } finally {
      // Throttle failed attempts too, keeping the last good snapshot available.
      lastCheckedAt = now()
    }
  }

  @Synchronized
  fun replace(updated: CommandsSnapshot) {
    snapshot = updated
    lastCheckedAt = now()
  }

  @Synchronized
  fun invalidate() {
    lastCheckedAt = null
  }

  companion object {
    val FRESHNESS_MILLIS: Long = TimeUnit.MINUTES.toMillis(1)
  }
}
