/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.junit.Assert.*
import org.junit.Test
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.github.CommandDocument
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.CommandStyle
import org.thoughtcrime.securesms.github.CommandStyleRange

class GatewayConfigurationTest {
  private fun document(block: String = "", defaults: String = "") = CommandDocument.parse("""
    <commands><gateway><defaults>$defaults</defaults></gateway>
    <command><triggers><trigger>!groups</trigger></triggers><response><groups><category name="A">
    <group><groupid>source</groupid><description>Source</description><autoupdate>0</autoupdate><link>https://signal.group/#EXAMPLE</link><gateway>$block</gateway></group>
    </category></groups></response></command></commands>
  """.trimIndent())

  @Test fun `missing booleans are disabled and timing defaults are distinct`() {
    val config = GatewayConfiguration.parse(document())
    val group = config.groups.getValue("source")
    assertFalse(group.enabled); assertFalse(group.admin); assertFalse(group.deleteMedia)
    assertFalse(group.mentionAdmins); assertFalse(group.throttle)
    assertEquals(72L, config.defaults.reminderHours)
    assertEquals(96L, config.defaults.removalHours)
    assertEquals(96L, config.defaults.admissionHours)
    assertEquals(15L, config.defaults.dmScanMinutes)
    assertEquals(0L, config.defaults.groupScanMinutes)
  }

  @Test fun `same-group admin disabled suspends work without falling back to a cached admin-enabled policy`() {
    val suspended = GatewayConfiguration.parse(document("<enabled>1</enabled><groupid>source</groupid><admin>0</admin>"))
    assertTrue(suspended.isPaused(suspended.groups.getValue("source")))
    assertTrue(GatewayConfiguration.parse(document("<enabled>1</enabled><groupid>source</groupid><admin>1</admin>")).groups.getValue("source").enabled)
  }

  @Test fun `overrides can be reset without losing other XML or mixed text`() {
    val doc = document("<spamtext>Why?</spamtext><reminderhours>24</reminderhours><removalhours>120</removalhours>")
    GatewayConfiguration.set(doc, "source", "spamtext", "A & B < C 🔎")
    GatewayConfiguration.set(doc, "source", "reminderhours", null)
    val parsed = GatewayConfiguration.parse(doc).groups.getValue("source")
    assertNull(parsed.reminderHours)
    assertEquals(120L, parsed.removalHours)
    assertEquals("A & B < C 🔎", parsed.spamText)
    assertTrue(doc.toXml().contains("https://signal.group/#EXAMPLE"))
  }

  @Test fun `invalid booleans and nonordered grace periods fail validation`() {
    listOf("true", "", "2").forEach { bad -> assertThrows(IllegalArgumentException::class.java) { GatewayConfiguration.parse(document("<enabled>$bad</enabled>")) } }
    assertThrows(IllegalArgumentException::class.java) { GatewayConfiguration.parse(document("<reminderhours>96</reminderhours><removalhours>48</removalhours>")) }
    assertThrows(IllegalArgumentException::class.java) { GatewayConfiguration.parse(document("<admissionhours>0</admissionhours>")) }
  }

  @Test fun `Gateway updates indent source room and universal configuration with two spaces per level`() {
    val doc = document("<enabled>0</enabled><groupid>room</groupid>", "<spamtext>Default question</spamtext><scancooldowns><dmminutes>15</dmminutes><groupminutes>0</groupminutes></scancooldowns>")
    GatewayConfiguration.ensureRoom(doc, "room", "Review room")
    GatewayConfiguration.set(doc, "source", "spamtext", "A & B < C 🔎\nWhy would you join?")
    val before = GatewayConfiguration.parse(doc)
    val reply = doc.render("!groups") { org.thoughtcrime.securesms.github.CommandLinkState() }
    val saved = doc.toXml()
    assertTrue(saved.contains("\n  <gateway>\n    <defaults>\n      <spamtext>"))
    assertTrue(saved.contains("\n      <scancooldowns>\n        <dmminutes>15</dmminutes>\n"))
    assertTrue(saved.contains("\n    <privategroups>\n      <group>\n        <groupid>room</groupid>\n"))
    assertTrue(saved.contains("\n        <gateway>\n          <groupid>room</groupid>\n"))
    assertTrue(saved.contains("\n            <gateway>\n              <enabled>0</enabled>\n"))
    val restored = CommandDocument.parse(saved)
    assertEquals(before, GatewayConfiguration.parse(restored))
    assertEquals(reply, restored.render("!groups") { org.thoughtcrime.securesms.github.CommandLinkState() })
    assertEquals(saved, restored.toXml())
  }

  @Test fun `throttle pauses processing and does not need enabled destination policy`() {
    val doc = document("<enabled>1</enabled><groupid>room</groupid>")
    val root = doc.document.documentElement.direct("gateway")!!
    val privateGroups = doc.document.createElement("privategroups").also(root::appendChild)
    val room = doc.document.createElement("group").also(privateGroups::appendChild)
    val id = doc.document.createElement("groupid").also(room::appendChild); id.textContent = "room"
    val block = doc.document.createElement("gateway").also(room::appendChild)
    val throttle = doc.document.createElement("throttle").also(block::appendChild); throttle.textContent = "1"
    val config = GatewayConfiguration.parse(doc)
    assertTrue(config.isPaused(config.groups.getValue("source")))
    assertFalse(config.groups.getValue("room").publiclyListed)
  }

  @Test fun `legacy duplicate public entries do not gain Gateway behavior`() {
    val legacy = CommandDocument.parse("<commands><command><response><groups><group><groupid>x</groupid><description>A</description></group><group><groupid>x</groupid><description>B</description></group></groups></response></command></commands>")
    assertFalse(GatewayConfiguration.parse(legacy).configured)
    GatewayConfiguration.setPaused(legacy, true)
    assertTrue(GatewayConfiguration.parse(legacy).paused)
    GatewayConfiguration.setPaused(legacy, false)
    assertFalse(GatewayConfiguration.parse(legacy).paused)
  }

  @Test fun `silent groups remain ordinary enabled Gateway sources without public membership disclosure`() {
    val doc = document("<enabled>1</enabled><groupid>room</groupid><admin>1</admin>")
    val group = doc.document.getElementsByTagName("group").item(0) as org.w3c.dom.Element
    group.appendChild(doc.document.createElement("silent").also { it.textContent = "1" })
    val config = GatewayConfiguration.parse(doc)
    val source = config.groups.getValue("source")
    assertTrue(source.enabled)
    assertTrue(source.admin)
    assertEquals("room", source.destination)
    assertFalse(source.publiclyListed)
    assertFalse(config.isPaused(source))
    GatewayConfiguration.set(doc, "source", "mentionadmins", "1")
    assertTrue(GatewayConfiguration.parse(doc).groups.getValue("source").mentionAdmins)
    assertEquals("", doc.render("!groups") { error("Silent group must not publish a link") }!!.text)
  }

  @Test fun `native substitutions preserve UTF16 styles and escape no XML into templates`() {
    val base = CommandReply("🔎 {applicant}: {question}", listOf(CommandStyleRange(CommandStyle.BOLD, 3, 11)), emptyList())
    val target = RecipientId.from(4)
    val reply = gatewayReply(base, mapOf("applicant" to GatewayText("\uFFFC", listOf(Mention(target, 0, 1))), "question" to GatewayText("<b>A & B</b>")))
    assertEquals("🔎 \uFFFC: <b>A & B</b>", reply.text)
    assertEquals(Mention(target, 3, 1), reply.mentions.single())
    assertEquals(CommandStyleRange(CommandStyle.BOLD, 3, 1), reply.styles.single())
  }

  @Test fun `argument parser uses native mention metadata and bounded positive numbers`() {
    val mention = Mention(RecipientId.from(5), 9, 5)
    val parsed = GatewayCommand.parse("!approve Alice 17", listOf(mention)) as GatewayCommand.Decide
    assertTrue(parsed.approve); assertEquals(17L, parsed.number)
    assertThrows(IllegalArgumentException::class.java) { GatewayCommand.parse("!approve Alice 17", emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { GatewayCommand.parse("!approve Alice 0", listOf(mention)) }
    assertEquals(GatewayCommand.Set("reminderhours", null), GatewayCommand.parse("!gateway reminder default", emptyList()))
    assertEquals(GatewayCommand.Set("spamtext", "A & B\nwhy?"), GatewayCommand.parse("!gateway spamtext A & B\nwhy?", emptyList()))
  }
}
