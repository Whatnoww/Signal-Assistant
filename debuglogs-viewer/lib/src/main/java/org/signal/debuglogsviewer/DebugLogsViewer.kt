/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.debuglogsviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Runnable
import org.json.JSONObject
import java.util.function.Consumer

var readOnly = true

object DebugLogsViewer {
  @JvmStatic
  fun initWebView(webview: WebView, context: Context, onFinished: Runnable) {
    webview.settings.apply {
      javaScriptEnabled = true
      builtInZoomControls = true
      displayZoomControls = false
    }
    webview.isVerticalScrollBarEnabled = false
    webview.isHorizontalScrollBarEnabled = false

    webview.loadUrl("file:///android_asset/debuglogs-viewer.html")

    webview.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        // Set dark mode colors if in dark mode
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isDarkMode) {
          webview.evaluateJavascript("document.body.classList.add('dark');", null)
        }
        onFinished.run()
      }
    }
  }

  @JvmStatic
  fun presentLines(webview: WebView, lines: String) {
    // Set the debug log lines
    val escaped = JSONObject.quote(lines)
    webview.evaluateJavascript("editor.insert($escaped); logLines=$escaped;", null)
  }

  @JvmStatic
  fun scrollToTop(webview: WebView) {
    webview.evaluateJavascript("editor.scrollToRow(0);", null)
  }

  @JvmStatic
  fun scrollToBottom(webview: WebView) {
    webview.evaluateJavascript("editor.scrollToRow(editor.session.getLength() - 1);", null)
  }

  @JvmStatic
  fun onSearchInput(webview: WebView, query: String) {
    webview.evaluateJavascript("onSearchInput('$query')", null)
  }

  @JvmStatic
  fun onSearch(webview: WebView) {
    webview.evaluateJavascript("onSearch()", null)
  }

  @JvmStatic
  fun onFilter(webview: WebView) {
    webview.evaluateJavascript("onFilter()", null)
  }

  @JvmStatic
  fun onFilterClose(webview: WebView) {
    webview.evaluateJavascript("onFilterClose()", null)
  }

  @JvmStatic
  fun onSearchUp(webview: WebView) {
    webview.evaluateJavascript("onSearchUp();", null)
  }

  @JvmStatic
  fun onSearchDown(webview: WebView) {
    webview.evaluateJavascript("onSearchDown();", null)
  }

  @JvmStatic
  fun getSearchPosition(webView: WebView, callback: Consumer<String?>) {
    webView.evaluateJavascript("getSearchPosition();", ValueCallback { value: String? -> callback.accept(value?.trim('"') ?: "") })
  }

  @JvmStatic
  fun onToggleCaseSensitive(webview: WebView) {
    webview.evaluateJavascript("onToggleCaseSensitive();", null)
  }

  @JvmStatic
  fun onSearchClose(webview: WebView) {
    webview.evaluateJavascript("onSearchClose();", null)
  }

  @JvmStatic
  fun onEdit(webview: WebView) {
    readOnly = !readOnly
    webview.evaluateJavascript("editor.setReadOnly($readOnly);", null)
  }

  @JvmStatic
  fun onCancelEdit(webview: WebView, lines: String) {
    readOnly = !readOnly
    webview.evaluateJavascript("editor.setReadOnly($readOnly);", null)
    webview.evaluateJavascript("editor.setValue($lines, -1);", null)
  }

  @JvmStatic
  fun onCopy(webview: WebView, context: Context, appName: String) {
    webview.evaluateJavascript("editor.getValue();") { value ->
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = ClipData.newPlainText(appName, value)
      clipboard.setPrimaryClip(clip)
    }
  }
}
