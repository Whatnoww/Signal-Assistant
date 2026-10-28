/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.github.CommandReply

internal data class GatewayText(val text: String, val mentions: List<Mention> = emptyList())

// Android's ICU regex engine requires both literal braces to be escaped.
private val GATEWAY_PLACEHOLDER = Regex("""\{[a-z_]+\}""")

/** Substitutions are data, never reparsed XML. Offsets remain UTF-16 for native Signal ranges. */
internal fun gatewayReply(template: CommandReply, values: Map<String, GatewayText>): CommandReply {
  val text = StringBuilder()
  val mentions = mutableListOf<Mention>()
  val changes = mutableListOf<Triple<Int, Int, Int>>()
  var offset = 0
  GATEWAY_PLACEHOLDER.findAll(template.text).forEach { match ->
    val value = values[match.value.drop(1).dropLast(1)] ?: GatewayText("")
    text.append(template.text.substring(offset, match.range.first))
    val start = text.length
    text.append(value.text)
    value.mentions.forEach { mentions += Mention(it.recipientId, start + it.start, it.length) }
    changes += Triple(match.range.first, match.value.length, value.text.length)
    offset = match.range.last + 1
  }
  text.append(template.text.substring(offset))
  fun mapped(position: Int, end: Boolean): Int {
    var adjustment = 0
    changes.forEach { (start, oldLength, newLength) ->
      if (position >= start + oldLength) adjustment += newLength - oldLength
      else if (position > start) return start + adjustment + if (end) newLength else 0
    }
    return position + adjustment
  }
  val styles = template.styles.mapNotNull { range ->
    val start = mapped(range.start, false)
    val length = mapped(range.start + range.length, true) - start
    if (length > 0) range.copy(start = start, length = length) else null
  }
  return template.copy(text = text.toString(), styles = styles, mentions = mentions)
}
