/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

object GitHubAutoReply {

  private val TAG = Log.tag(GitHubAutoReply::class.java)

  // ---- CONFIG ----
  // These should come from local.properties via build.gradle:
  //
  // local.properties:
  // GITHUB_TOKEN=ghp_xxx...
  // COMMANDS_XML_URL=https://raw.githubusercontent.com/YourUser/YourRepo/main/commands.xml
  //
  // app/build.gradle:
  // buildConfigField "String", "GITHUB_TOKEN", "\"${localProps['GITHUB_TOKEN'] ?: ""}\""
  // buildConfigField "String", "COMMANDS_XML_URL", "\"${localProps['COMMANDS_XML_URL'] ?: ""}\""
  private const val TOKEN: String = BuildConfig.GITHUB_TOKEN
  private const val COMMANDS_URL: String = BuildConfig.RESPONSE_XML
  // -----------------

  private const val PREFS_NAME = "signal_assistant_commands"
  private const val PREF_ETAG  = "etag"
  private const val CACHE_FILE_NAME = "commands.xml"

  private val client = OkHttpClient()

  // in-memory cache: trigger (normalized) -> response
  @Volatile
  private var commandMap: Map<String, String>? = null

  /**
   * Public API:
   *
   * 1) Call [refreshFromGithub] at app start or on some schedule (OFF main thread).
   * 2) Call [getResponseFor] any time to get a response for a trigger.
   */

  /**
   * Try to refresh the commands.xml from GitHub.
   *
   * Uses ETag-based conditional GET so it only downloads if changed.
   * Returns true if a new version was downloaded and parsed.
   */
  fun refreshFromGithub(context: Context): Boolean {
    if (COMMANDS_URL.isBlank()) {
      Log.w(TAG, "No COMMANDS_XML_URL configured; skipping refresh.")
      return false
    }

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val storedEtag = prefs.getString(PREF_ETAG, null)

    val reqBuilder = Request.Builder()
      .url(COMMANDS_URL)
      .header("Accept", "application/xml")

    if (!TOKEN.isBlank()) {
      reqBuilder.header("Authorization", "Bearer $TOKEN")
    }
    if (!storedEtag.isNullOrBlank()) {
      reqBuilder.header("If-None-Match", storedEtag)
    }

    val request = reqBuilder.build()
    val response = client.newCall(request).execute()

    response.use { res ->
      if (res.code == 304) {
        Log.d(TAG, "Commands XML not modified (304). Using cached version.")
        // keep existing file + map
        if (commandMap == null) {
          // lazy load from disk if we never parsed yet
          loadFromDisk(context)
        }
        return false
      }

      if (!res.isSuccessful) {
        Log.w(TAG, "Failed to fetch commands.xml: HTTP ${res.code} ${res.message}")
        // fall back to disk cache if present
        if (commandMap == null) {
          loadFromDisk(context)
        }
        return false
      }

      val bodyString = res.body?.string().orEmpty()
      if (bodyString.isBlank()) {
        Log.w(TAG, "commands.xml body is empty.")
        return false
      }

      // Write to disk
      val file = File(context.filesDir, CACHE_FILE_NAME)
      file.writeText(bodyString, Charsets.UTF_8)

      // Update ETag if available
      val newEtag = res.header("ETag")
      if (!newEtag.isNullOrBlank()) {
        prefs.edit().putString(PREF_ETAG, newEtag).apply()
      }

      // Parse and populate in-memory map
      commandMap = parseXml(bodyString)
      Log.d(TAG, "commands.xml updated and parsed successfully.")
      return true
    }
  }

  /**
   * Get the response for a given trigger string (e.g. "!help").
   * Returns null if there is no matching command or if the XML was never loaded.
   *
   * IMPORTANT: Make sure you've called [refreshFromGithub] (or have a cache on disk)
   * before relying on this. If the XML is only on disk, this will lazily load it.
   */
  fun getResponseFor(context: Context, input: String): String? {
    val normalized = normalizeTrigger(input)

    // lazy init from disk if needed
    if (commandMap == null) {
      loadFromDisk(context)
    }

    val map = commandMap ?: return null
    return map[normalized]
  }

  // ---- Internal helpers ----

  @Synchronized
  private fun loadFromDisk(context: Context) {
    if (commandMap != null) return // already loaded

    val file = File(context.filesDir, CACHE_FILE_NAME)
    if (!file.exists()) {
      Log.w(TAG, "No cached commands.xml found on disk.")
      return
    }

    val xml = file.readText(Charsets.UTF_8)
    if (xml.isBlank()) {
      Log.w(TAG, "Cached commands.xml is empty.")
      return
    }

    commandMap = parseXml(xml)
    Log.d(TAG, "commands.xml loaded from disk and parsed.")
  }

  /**
   * Parse the commands.xml into a map: trigger (normalized) -> response.
   *
   * Expected XML structure (example):
   *
   * <commands>
   *   <command>
   *     <triggers>
   *       <trigger>!help</trigger>
   *       <trigger>!list</trigger>
   *     </triggers>
   *     <response><![CDATA[...]]></response>
   *   </command>
   *   ...
   * </commands>
   */
  private fun parseXml(xml: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    try {
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      val inputStream = xml.byteInputStream(Charsets.UTF_8)
      val doc = builder.parse(inputStream)
      doc.documentElement.normalize()

      val commandNodes: NodeList = doc.getElementsByTagName("command")

      for (i in 0 until commandNodes.length) {
        val commandNode = commandNodes.item(i)
        if (commandNode !is Element) continue

        val triggersElement = commandNode.getElementsByTagName("triggers")
          .item(0) as? Element ?: continue

        val triggerNodes = triggersElement.getElementsByTagName("trigger")
        val responseNode = commandNode.getElementsByTagName("response")
          .item(0) as? Element ?: continue

        val responseText = responseNode.textContent ?: continue

        for (tIndex in 0 until triggerNodes.length) {
          val triggerEl = triggerNodes.item(tIndex) as? Element ?: continue
          val triggerRaw = triggerEl.textContent ?: continue
          val normalized = normalizeTrigger(triggerRaw)
          if (normalized.isNotEmpty()) {
            result[normalized] = responseText
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error parsing commands.xml: ${e.message}", e)
    }

    return result
  }

  private fun normalizeTrigger(raw: String): String {
    return raw.trim().lowercase()
  }
}
