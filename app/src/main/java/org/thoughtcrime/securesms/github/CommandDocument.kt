/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import okio.ByteString.Companion.encodeUtf8
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Text and styles are separate so future media attachments need not change command matching/rendering. */
internal data class CommandReply(val text: String, val styles: List<CommandStyleRange>, val linkUpdates: List<CommandLinkUpdate>) {
  fun contentHash(): String {
    val canonicalStyles = styles.sortedWith(compareBy({ it.start }, { it.length }, { it.style.name }))
      .joinToString(";") { "${it.style}:${it.start}:${it.length}" }
    return "$text\u0000$canonicalStyles".encodeUtf8().sha256().hex()
  }
}
internal data class CommandStyleRange(val style: CommandStyle, val start: Int, val length: Int)
internal enum class CommandStyle {
  BOLD,
  ITALIC,
  STRIKETHROUGH,
  MONOSPACE,
  SPOILER
}
internal data class CommandGroup(val groupId: String, val description: String, val autoUpdate: Boolean, val link: String, val outdated: Boolean)
internal data class CommandLinkState(val currentLink: String? = null, val outdated: Boolean? = null)
internal data class CommandLinkUpdate(val groupId: String, val previousLink: String, val link: String)

/** The XML is also retained as a DOM so publishing link changes preserves unrelated commands and tags. */
internal class CommandDocument private constructor(private val document: Document) {
  companion object {
    fun parse(xml: String, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): CommandDocument {
      // Android's DOM factory does not support the JAXP DTD-disabling feature flags.
      // Reject declarations before parsing, with a resolver as a second barrier to external reads.
      require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed in commands XML" }
      factory.isExpandEntityReferences = false
      val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
      }
      val document = xml.byteInputStream(Charsets.UTF_8).use { builder.parse(it) }
      require(document.documentElement.tagName == "commands") { "Expected <commands> root" }
      return CommandDocument(document)
    }

    fun isCommandCandidate(input: String?): Boolean = input != null && input.length in 2..64 &&
      (input.startsWith("!") || input.startsWith("#")) && input.last() != ' ' &&
      input.none { it.isISOControl() || (it.isWhitespace() && it != ' ') }
  }

  fun render(input: String, resolve: (CommandGroup) -> CommandLinkState): CommandReply? {
    val command = document.documentElement.children("command").firstOrNull { command ->
      command.isCommandActive() && command.child("triggers")?.children("trigger")?.any { it.textContent == input } == true
    } ?: return null
    val response = command.child("response") ?: return null
    val renderer = Renderer(resolve)
    renderer.renderChildren(response)
    val text = renderer.text.toString().trimEnd()
    val styles = renderer.styles.mapNotNull { range ->
      val length = minOf(range.length, text.length - range.start)
      if (length > 0) range.copy(length = length) else null
    }
    return CommandReply(text, styles, renderer.linkUpdates)
  }

  /** Apply only changes whose ID, old link and opt-in still match the latest GitHub file. */
  fun applyLinkUpdates(updates: List<CommandLinkUpdate>): Boolean {
    var changed = false
    for (command in document.documentElement.children("command").filter { it.isCommandActive() }) {
      val groups = command.getElementsByTagName("group")
      for (i in 0 until groups.length) {
        val element = groups.item(i) as Element
        val group = element.asGroup()
        val update = updates.firstOrNull { it.groupId == group.groupId && it.previousLink == group.link }
        if (update != null && group.groupId != "0" && group.autoUpdate) {
          element.child("link")!!.textContent = update.link
          element.child("outdated")?.textContent = "0"
          changed = true
        }
      }
    }
    return changed
  }

  fun toXml(): String {
    val writer = StringWriter()
    TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
  }

  /** Includes disabled commands and every alias, so administration can always re-enable them. */
  fun setCommandsActive(trigger: String, enabled: Boolean): Int {
    val commands = document.documentElement.children("command").filter { command ->
      trigger == "all" || command.child("triggers")?.children("trigger")?.any { it.textContent == trigger } == true
    }
    if (commands.isEmpty()) throw AdminCommandException("No configured command matches $trigger.")
    commands.forEach { it.setValue("active", if (enabled) "1" else "0") }
    return commands.size
  }

  fun listedGroups(groupId: String): List<CommandGroup> = groupElements().map { it.asGroup() }.filter { it.groupId == groupId }

  fun enableGroupAutoUpdate(description: String, groupId: String, state: CommandLinkState) {
    val matches = groupElements().filter { it.value("description") == description }
    if (matches.isEmpty()) throw AdminCommandException("No listed group has the description: $description.")
    if (matches.size != 1) throw AdminCommandException("More than one listed group has that description; use a unique description.")
    matches.single().apply {
      setValue("groupid", groupId)
      setValue("autoupdate", "1")
      if (state.currentLink != null) {
        setValue("link", state.currentLink)
        setValue("outdated", "0")
      } else if (state.outdated == true) {
        setValue("outdated", "1")
      }
    }
  }

  fun disableGroupAutoUpdate(groupId: String): Int {
    val matches = groupElements().filter { it.value("groupid") == groupId }
    if (matches.isEmpty()) throw AdminCommandException("This group is not on the list. Enable autoupdate with its listed description first.")
    // Retain both the ID and link so later requests can still detect an outdated link.
    matches.forEach { it.setValue("autoupdate", "0") }
    return matches.size
  }

  fun createCategory(name: String) {
    val response = groupListResponse()
    if (response.descendants("category").any { it.getAttribute("name").trim() == name }) {
      throw AdminCommandException("Category $name already exists.")
    }
    val lists = response.descendants("groups")
    if (lists.size > 1) throw AdminCommandException("The !groups response has multiple group lists; use a single list before creating categories.")
    val list = lists.singleOrNull() ?: document.createElement("groups").also { response.appendChild(it) }
    list.appendChild(document.createElement("category").apply { setAttribute("name", name) })
  }

  /** Validate against the latest XML before resolving the current invite link. */
  fun validateGroupAddition(category: String, description: String, groupId: String) {
    val response = groupListResponse()
    requireCategory(response, category)
    val groups = response.descendants("group")
    if (groups.any { it.value("groupid") == groupId }) {
      throw AdminCommandException("This group is already listed. Use !admin group move <category> to move it.")
    }
    if (groups.any { it.value("description") == description }) {
      throw AdminCommandException("A listed group already has the description: $description. Choose a unique name.")
    }
  }

  fun addGroup(category: String, description: String, groupId: String, link: String) {
    validateGroupAddition(category, description, groupId)
    require(link.startsWith("https://signal.group/#")) { "Expected a Signal invite link" }
    val target = requireCategory(groupListResponse(), category)
    target.appendChild(document.createElement("group").apply {
      setValue("groupid", groupId)
      setValue("description", description)
      setValue("autoupdate", "1")
      setValue("link", link)
      setValue("outdated", "0")
    })
  }

  /** Move the existing node, including any extra metadata, to the end of the target category. */
  fun moveGroup(category: String, groupId: String): Boolean {
    val response = groupListResponse()
    val target = requireCategory(response, category)
    val matches = response.descendants("group").filter { it.value("groupid") == groupId }
    if (matches.isEmpty()) throw AdminCommandException("This group is not on the !groups list. Use !admin group add <category> <name> first.")
    if (matches.size != 1) throw AdminCommandException("This group has multiple entries in !groups; keep a single entry before moving it.")
    val group = matches.single()
    if (group.parentNode == target) return false
    target.appendChild(group)
    return true
  }

  private fun groupListResponse(): Element {
    val commands = document.documentElement.children("command").filter { command ->
      command.child("triggers")?.children("trigger")?.any { it.textContent == "!groups" } == true
    }
    if (commands.size != 1) throw AdminCommandException("Configure exactly one command with the !groups trigger before managing categories.")
    return commands.single().child("response") ?: throw AdminCommandException("The !groups command has no response to hold categories.")
  }

  private fun requireCategory(response: Element, name: String): Element {
    val matches = response.descendants("category").filter { it.getAttribute("name").trim() == name }
    if (matches.isEmpty()) throw AdminCommandException("Category $name does not exist. Create it with !admin group category create $name.")
    if (matches.size != 1) throw AdminCommandException("More than one category is named $name; use unique category names.")
    return matches.single()
  }

  private fun groupElements(): List<Element> = document.documentElement.children("command").flatMap { command ->
    val groups = command.getElementsByTagName("group")
    (0 until groups.length).map { groups.item(it) as Element }
  }

  private class Renderer(val resolve: (CommandGroup) -> CommandLinkState) {
    val text = StringBuilder()
    val styles = mutableListOf<CommandStyleRange>()
    val linkUpdates = mutableListOf<CommandLinkUpdate>()

    fun renderChildren(parent: Node) {
      for (i in 0 until parent.childNodes.length) {
        val node = parent.childNodes.item(i)
        when (node.nodeType) {
          Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
            // Ignore XML indentation, while retaining spaces between inline tags and legacy CDATA.
            if (!node.nodeValue.isBlank() || !node.nodeValue.contains('\n')) text.append(node.nodeValue)
          }
          Node.ELEMENT_NODE -> renderElement(node as Element)
        }
      }
    }

    private fun renderElement(element: Element) {
      when (element.tagName) {
        "text" -> renderChildren(element)
        "b", "bold", "strong" -> styled(CommandStyle.BOLD) { renderChildren(element) }
        "i", "italic", "em" -> styled(CommandStyle.ITALIC) { renderChildren(element) }
        "s", "strikethrough" -> styled(CommandStyle.STRIKETHROUGH) { renderChildren(element) }
        "code", "mono" -> styled(CommandStyle.MONOSPACE) { renderChildren(element) }
        "spoiler" -> styled(CommandStyle.SPOILER) { renderChildren(element) }
        "br" -> text.append('\n')
        "p" -> {
          renderChildren(element)
          text.append("\n\n")
        }
        "groups" -> renderChildren(element)
        "category" -> {
          styled(CommandStyle.BOLD) { text.append(element.getAttribute("name").trim().removeSuffix(":")).append(':') }
          text.append("\n\n")
          element.children("group").forEach(::renderGroup)
        }
        // Reserve <image> for a future media sender instead of silently dropping attachments.
        else -> error("Unsupported response tag: ${element.tagName}")
      }
    }

    private fun renderGroup(element: Element) {
      val group = element.asGroup()
      val state = resolve(group)
      val canUpdate = group.autoUpdate && group.groupId != "0" && state.currentLink != null
      val link = if (canUpdate) state.currentLink!! else group.link
      val outdated = if (canUpdate) false else state.outdated ?: group.outdated
      text.append(group.description.trim().removeSuffix(":"))
      if (outdated) text.append(" (outdated link)")
      text.append(":\n")
      styled(CommandStyle.ITALIC) { text.append(link) }
      text.append("\n\n")
      if (canUpdate && link != group.link) linkUpdates += CommandLinkUpdate(group.groupId, group.link, link)
    }

    private fun styled(style: CommandStyle, append: () -> Unit) {
      val start = text.length
      append()
      if (text.length > start) styles += CommandStyleRange(style, start, text.length - start)
    }
  }
}

private fun Element.children(tag: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
private fun Element.descendants(tag: String): List<Element> {
  val elements = getElementsByTagName(tag)
  return (0 until elements.length).map { elements.item(it) as Element }
}
private fun Element.value(tag: String): String = child(tag)?.textContent?.trim().orEmpty()
private fun Element.setValue(tag: String, value: String) {
  val element = child(tag) ?: ownerDocument.createElement(tag).also { appendChild(it) }
  element.textContent = value
}
private fun Element.isCommandActive(): Boolean = (child("active")?.textContent?.trim() ?: "1") == "1"
private fun Element.asGroup(): CommandGroup {
  val groupId = value("groupid").ifEmpty { "0" }
  val autoUpdate = value("autoupdate")
  require(autoUpdate == "0" || autoUpdate == "1") { "autoupdate must be 0 or 1" }
  val description = value("description")
  val link = value("link")
  require(description.isNotBlank() && link.startsWith("https://signal.group/#")) { "Group requires a description and a Signal invite link" }
  return CommandGroup(groupId, description, autoUpdate == "1", link, value("outdated") == "1")
}
