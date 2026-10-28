/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.thoughtcrime.securesms.database.model.Mention

internal sealed interface GatewayCommand {
  data object Status : GatewayCommand
  data object GroupId : GatewayCommand
  data class Set(val field: String, val value: String?) : GatewayCommand
  data class Throttle(val group: String, val enabled: Boolean) : GatewayCommand
  data class Decide(val approve: Boolean, val target: Mention, val number: Long?) : GatewayCommand

  companion object {
    fun candidate(body: String): Boolean = listOf("!gateway", "!groupid", "!approve", "!deny").any { body == it || body.startsWith("$it ") } || body.startsWith("!admin gateway ")

    fun parse(body: String, mentions: List<Mention>): GatewayCommand {
      require(body.length <= 4096 && body == body.trim() && body.none { it.isISOControl() && it != '\n' }) { "Invalid Gateway command." }
      if (body == "!groupid") return GroupId
      if (body == "!gateway") return Status
      val owner = Regex("!admin gateway throttle (\\S+) (enable|disable)").matchEntire(body)
      if (owner != null) return Throttle(owner.groupValues[1], owner.groupValues[2] == "enable")
      if (body.startsWith("!approve ") || body.startsWith("!deny ")) {
        val target = requireNotNull(mentions.singleOrNull()) { "Use exactly one native Signal mention." }
        require(target.start >= 0 && target.length > 0 && target.start + target.length <= body.length) { "Invalid mention." }
        val normalized = body.substring(0, target.start) + "\uFFFC" + body.substring(target.start + target.length)
        val match = requireNotNull(Regex("!(approve|deny) \uFFFC(?: ([1-9][0-9]*))?").matchEntire(normalized)) { "Use !approve @member [number] or !deny @member [number]." }
        val number = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { requireNotNull(it.toLongOrNull()) { "Invalid request number." } }
        return Decide(match.groupValues[1] == "approve", target, number)
      }
      val toggle = Regex("!gateway (?:(admin|deletemedia|mentionadmins) )?(enable|disable)").matchEntire(body)
      if (toggle != null) return Set(toggle.groupValues[1].ifBlank { "enabled" }, if (toggle.groupValues[2] == "enable") "1" else "0")
      val argument = requireNotNull(Regex("!gateway (group|spamtext|reminder|removal) ([\\s\\S]+)").matchEntire(body)) { "Unknown Gateway command." }
      val field = when (argument.groupValues[1]) { "group" -> "groupid"; "reminder" -> "reminderhours"; "removal" -> "removalhours"; else -> "spamtext" }
      val value = argument.groupValues[2]
      if (field.endsWith("hours") && value != "default") require(value.toLongOrNull()?.let { it in 1L..87600L } == true) { "Specify positive hours or default." }
      return Set(field, if (field.endsWith("hours") && value == "default") null else value)
    }
  }
}
