/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.thoughtcrime.securesms.github.CommandDocument
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.isSilentGroup
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.concurrent.TimeUnit

internal data class GatewayDefaults(
  val spamText: String = "Please tell us briefly why you would like to join.",
  val reminderHours: Long = 72,
  val removalHours: Long = 96,
  val admissionHours: Long = 96,
  val dmScanMinutes: Long = 15,
  val groupScanMinutes: Long = 0,
  val joinPollSeconds: Long = 30
)

internal data class GatewayGroup(
  val id: String,
  val name: String,
  val publiclyListed: Boolean,
  val enabled: Boolean = false,
  val destination: String = "",
  val admin: Boolean = false,
  val deleteMedia: Boolean = false,
  val mentionAdmins: Boolean = false,
  val throttle: Boolean = false,
  val spamText: String? = null,
  val reminderHours: Long? = null,
  val removalHours: Long? = null,
  val admissionHours: Long? = null
) {
  fun reminderMillis(defaults: GatewayDefaults) = TimeUnit.HOURS.toMillis(reminderHours ?: defaults.reminderHours)
  fun removalMillis(defaults: GatewayDefaults) = TimeUnit.HOURS.toMillis(removalHours ?: defaults.removalHours)
  fun admissionMillis(defaults: GatewayDefaults) = TimeUnit.HOURS.toMillis(admissionHours ?: defaults.admissionHours)
}

/** Read-only configuration snapshot. No applicant state is published to GitHub. */
internal data class GatewayConfiguration(
  val configured: Boolean,
  val paused: Boolean,
  val defaults: GatewayDefaults,
  val groups: Map<String, GatewayGroup>,
  val messages: Map<String, CommandReply>,
  val help: Map<String, CommandReply>
) {
  fun isPaused(source: GatewayGroup): Boolean = paused || !source.enabled || source.throttle || groups[source.destination]?.throttle == true || source.destination == source.id && !source.admin
  fun requireWorkflowTemplates() {
    val missing = setOf("spam_reminder", "timeout", "blocklist_rejection", "approval", "denial", "manual_finish", "status", "permission_error", "administrator_notification").minus(messages.keys)
    require(missing.isEmpty()) { "Required Gateway responses are missing from the XML configuration." }
  }

  companion object {
    fun parse(commands: CommandDocument): GatewayConfiguration {
      val root = commands.document.documentElement
      val universal = root.direct("gateway")
      val defaults = universal?.direct("defaults")
      val fallback = GatewayDefaults()
      if (defaults == null) return GatewayConfiguration(false, universal.boolean("paused"), fallback, emptyMap(), emptyMap(), emptyMap())
      val times = GatewayDefaults(
        spamText = defaults?.value("spamtext")?.also { require(it.isNotBlank()) { "The default spam question must not be empty." } } ?: fallback.spamText,
        reminderHours = defaults.hours("reminderhours") ?: fallback.reminderHours,
        removalHours = defaults.hours("removalhours") ?: fallback.removalHours,
        admissionHours = defaults.hours("admissionhours") ?: fallback.admissionHours,
        dmScanMinutes = defaults?.direct("scancooldowns").minutes("dmminutes") ?: fallback.dmScanMinutes,
        groupScanMinutes = defaults?.direct("scancooldowns").minutes("groupminutes") ?: fallback.groupScanMinutes,
        joinPollSeconds = defaults?.value("joinpollseconds")?.let { value ->
          requireNotNull(value.toLongOrNull()?.takeIf { it == 0L || it in 15L..3600L }) { "joinpollseconds must be 0 or between 15 and 3600 seconds." }
        } ?: fallback.joinPollSeconds
      )
      require(times.reminderHours < times.removalHours) { "Reminder must precede removal." }
      val groups = linkedMapOf<String, GatewayGroup>()
      val defined = mutableSetOf<String>()
      fun add(element: Element, publiclyListed: Boolean) {
        val id = element.value("groupid") ?: return
        if (id == "0" || id.isBlank()) return
        val block = element.direct("gateway")
        val silent = element.isSilentGroup()
        if (block == null && id in groups) return
        val settings = GatewayGroup(
          id, element.value("description") ?: id, publiclyListed && !silent,
          block.boolean("enabled"), block?.value("groupid").orEmpty(), block.boolean("admin"),
          block.boolean("deletemedia"), block.boolean("mentionadmins"), block.boolean("throttle"),
          block?.value("spamtext")?.also { require(it.isNotBlank()) { "Spam question must not be empty." } },
          block.hours("reminderhours"), block.hours("removalhours"), block.hours("admissionhours")
        )
        require((settings.reminderHours ?: times.reminderHours) < (settings.removalHours ?: times.removalHours)) { "Reminder must precede removal for ${settings.name}." }
        require(!settings.enabled || settings.destination.isNotBlank()) { "An enabled Gateway needs a destination." }
        // An online manual admin=0 edit must suspend same-group work, not reject
        // the snapshot and accidentally keep using a cached admin=1 policy.
        val previous = groups[id]
        require(id !in defined || previous == settings) { "Conflicting Gateway definitions for ${settings.name}." }
        if (block != null) defined += id
        groups[id] = settings
      }
      root.directChildren("command").forEach { command ->
        val nodes = command.getElementsByTagName("group")
        for (i in 0 until nodes.length) add(nodes.item(i) as Element, true)
      }
      universal?.direct("privategroups")?.directChildren("group")?.forEach { add(it, false) }
      fun templates(parent: Element?, tag: String, key: String): Map<String, CommandReply> = parent?.directChildren(tag).orEmpty().associate { element ->
        val id = element.getAttribute(key)
        require(id.isNotBlank()) { "A Gateway template needs an identifier." }
        val doc = commands.document.implementation.createDocument(null, "commands", null)
        val command = doc.createElement("command").also { doc.documentElement.appendChild(it) }
        val triggers = doc.createElement("triggers").also { command.appendChild(it) }
        doc.createElement("trigger").also { it.textContent = "!template"; triggers.appendChild(it) }
        val response = doc.createElement("response").also { command.appendChild(it) }
        for (i in 0 until element.childNodes.length) response.appendChild(doc.importNode(element.childNodes.item(i), true))
        val writer = java.io.StringWriter()
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(writer))
        id to requireNotNull(CommandDocument.parse(writer.toString()).render("!template") { org.thoughtcrime.securesms.github.CommandLinkState() })
      }
      return GatewayConfiguration(defaults != null, universal.boolean("paused"), times, groups, templates(universal?.direct("responses"), "message", "id"), templates(universal?.direct("help"), "section", "scope"))
    }

    fun setPaused(commands: CommandDocument, paused: Boolean) {
      commands.document.documentElement.ensure("gateway").set("paused", if (paused) "1" else "0")
    }

    /** Room policy is private unless the group already has a public/source entry. */
    fun ensureRoom(commands: CommandDocument, id: String, name: String) {
      if (parse(commands).groups.containsKey(id)) return
      val parent = commands.document.documentElement.ensure("gateway").ensure("privategroups")
      val group = commands.document.createElement("group").also(parent::appendChild)
      group.set("groupid", id)
      group.set("description", name)
      group.set("silent", "1")
      val policy = group.ensure("gateway")
      policy.set("groupid", id)
      listOf("enabled", "admin", "deletemedia", "mentionadmins", "throttle").forEach { policy.set(it, "0") }
    }

    fun set(commands: CommandDocument, id: String, field: String, value: String?) {
      require(field in setOf("enabled", "groupid", "admin", "deletemedia", "mentionadmins", "throttle", "spamtext", "reminderhours", "removalhours", "admissionhours"))
      val nodes = commands.document.getElementsByTagName("group")
      val matches = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.value("groupid") == id }
      require(matches.size == 1) { "Group must have exactly one configuration entry." }
      val block = matches.single().ensure("gateway")
      if (value == null) block.direct(field)?.let { block.removeChild(it) } else block.set(field, value)
      parse(commands)
    }
  }
}

internal fun Element.directChildren(tag: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
internal fun Element.direct(tag: String): Element? = directChildren(tag).firstOrNull()
private fun Element.value(tag: String): String? = direct(tag)?.textContent?.trim()
private fun Element?.boolean(tag: String): Boolean {
  val value = this?.value(tag) ?: return false
  require(value == "0" || value == "1") { "$tag must be 0 or 1." }
  return value == "1"
}
private fun Element?.hours(tag: String): Long? = this?.value(tag)?.let { value -> value.toLongOrNull()?.also { require(it in 1..87600) { "$tag must be positive hours (at most ten years)." } } ?: error("$tag must be hours.") }
private fun Element?.minutes(tag: String): Long? = this?.value(tag)?.let { value -> value.toLongOrNull()?.also { require(it in 0..5256000) } ?: error("$tag must be minutes.") }
private fun Element.ensure(tag: String): Element = direct(tag) ?: ownerDocument.createElement(tag).also { appendChild(it) }
private fun Element.set(tag: String, value: String) { ensure(tag).textContent = value }
