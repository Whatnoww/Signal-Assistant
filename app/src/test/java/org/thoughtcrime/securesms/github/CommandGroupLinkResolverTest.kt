/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGroupLinkResolverTest {
  @Test
  fun `zero ID only checks the configured invite and never looks up or refreshes a group`() {
    val resolver = CommandGroupLinkResolver(object : CommandGroupLinkLookup {
      override fun currentGroupLink(encodedId: String): CommandLinkState = error("Manual ID must never be refreshed")
      override fun validateLink(link: String): CommandLinkState = CommandLinkState(outdated = true)
    })
    val result = resolver.resolve(group(id = "0"))
    assertNull(result.currentLink)
    assertEquals(true, result.outdated)
  }

  @Test
  fun `known changed link reports replacement and outdated configured link`() {
    val resolver = CommandGroupLinkResolver(object : CommandGroupLinkLookup {
      override fun currentGroupLink(encodedId: String): CommandLinkState = CommandLinkState(currentLink = "new-link")
      override fun validateLink(link: String): CommandLinkState = error("Current state is already known")
    })
    assertEquals(CommandLinkState("new-link", true), resolver.resolve(group()))
    assertEquals(CommandLinkState("new-link", false), resolver.resolve(group().copy(link = "new-link")))
  }

  @Test
  fun `disabled link is outdated without probing configured invite`() {
    val resolver = CommandGroupLinkResolver(object : CommandGroupLinkLookup {
      override fun currentGroupLink(encodedId: String): CommandLinkState = CommandLinkState(outdated = true)
      override fun validateLink(link: String): CommandLinkState = error("Disabled link is already known")
    })
    assertEquals(CommandLinkState(outdated = true), resolver.resolve(group()))
  }

  @Test
  fun `missing group access falls back to invite validation without guessing an update`() {
    val resolver = CommandGroupLinkResolver(object : CommandGroupLinkLookup {
      override fun currentGroupLink(encodedId: String): CommandLinkState = CommandLinkState()
      override fun validateLink(link: String): CommandLinkState = CommandLinkState(outdated = false)
    })
    assertEquals(CommandLinkState(outdated = false), resolver.resolve(group()))
  }

  @Test
  fun `duplicate groups and links are checked once per reply`() {
    var groups = 0
    var links = 0
    val resolver = CommandGroupLinkResolver(object : CommandGroupLinkLookup {
      override fun currentGroupLink(encodedId: String): CommandLinkState {
        groups++
        return CommandLinkState()
      }
      override fun validateLink(link: String): CommandLinkState {
        links++
        return CommandLinkState()
      }
    })
    repeat(3) { resolver.resolve(group()) }
    resolver.resolve(group(id = "0"))
    assertEquals(1, groups)
    assertEquals(1, links)
  }

  private fun group(id: String = "group-1") = CommandGroup(id, "Example", true, "old-link", false)
}
