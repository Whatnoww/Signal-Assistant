/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

/** The sender ACI comes from the stored incoming message, never from command text. */
internal class AdminCommands(
  private val adminAci: String,
  private val configured: Boolean,
  private val repository: () -> GitHubCommandsRepository,
  private val currentGroupLink: (String) -> CommandLinkState,
  private val saveSnapshot: (CommandsSnapshot) -> Unit
) {
  fun reply(input: String, senderAci: String?, groupId: String?): CommandReply {
    if (!isAuthorized(adminAci, senderAci)) return plain("Error: only the configured admin ACI can use !admin commands.")
    val command = try {
      parse(input)
    } catch (e: IllegalArgumentException) {
      return plain("Error: ${e.message}")
    }
    if (groupId == null && command !is AdminCommand.SetActive && command !is AdminCommand.CreateCategory) return plain("Error: send group admin commands in a Signal V2 group chat.")
    if (!configured) return plain("Error: configure RESPONSE_XML and GITHUB_TOKEN_COMMANDS before using admin commands.")

    var addLink: String? = null
    return try {
      val source = repository()
      if (command == AdminCommand.GroupStatus) {
        val snapshot = source.fetch()
        val groups = CommandDocument.parse(snapshot.xml).listedGroups(requireNotNull(groupId))
        saveSnapshot(snapshot)
        plain(
          if (groups.isEmpty()) {
            "This group is not on the list. Autoupdate is not configured."
          } else {
            "This group is on the list:\n" + groups.joinToString("\n") { "${it.description}${if (it.silent) " [silent]" else ""}: autoupdate ${if (it.autoUpdate) "enabled (1)" else "disabled (0)"}." }
          }
        )
      } else {
        // Resolve once even if GitHub requires a conflict retry.
        val state = if (command is AdminCommand.EnableGroup) currentGroupLink(requireNotNull(groupId)) else CommandLinkState()
        var confirmation = ""
        val snapshot = source.update("Apply Signal admin command") { document ->
          val before = document.toXml()
          confirmation = when (command) {
            is AdminCommand.SetActive -> {
              val count = document.setCommandsActive(command.trigger, command.enabled)
              if (command.trigger == "all") org.thoughtcrime.securesms.gateway.GatewayConfiguration.setPaused(document, !command.enabled)
              val action = if (command.enabled) "Enabled" else "Disabled"
              if (command.trigger == "all") "$action all $count configured commands." else "$action ${command.trigger} and its aliases."
            }
            is AdminCommand.EnableGroup -> {
              document.enableGroupAutoUpdate(command.description, requireNotNull(groupId), state)
              "Enabled autoupdate for ${command.description} (1) and saved this group's ID. " +
                if (state.currentLink != null) "The listed link is up to date." else "Warning: the current invite link is unavailable; the configured link was retained."
            }
            AdminCommand.DisableGroup -> {
              document.disableGroupAutoUpdate(requireNotNull(groupId))
              "Disabled autoupdate for this group (0). Its ID and link were retained for outdated-link detection."
            }
            is AdminCommand.CreateCategory -> {
              document.createCategory(command.name)
              "Created category ${command.name}."
            }
            is AdminCommand.AddGroup -> {
              document.validateGroupAddition(command.category, command.description, requireNotNull(groupId))
              val link = addLink ?: run {
                val current = currentGroupLink(requireNotNull(groupId))
                val resolved = current.currentLink ?: throw AdminCommandException(
                  if (current.outdated == true) {
                    "This group's invite link is disabled. Enable it in Signal before adding the group."
                  } else {
                    "Unable to read this group's enabled invite link. Check membership and connectivity, then try again."
                  }
                )
                resolved.also { addLink = it }
              }
              document.addGroup(command.category, command.description, requireNotNull(groupId), link)
              "Added ${command.description} as the last entry in ${command.category}. Autoupdate is enabled (1) and the current invite link was saved."
            }
            is AdminCommand.MoveGroup -> {
              if (document.moveGroup(command.category, requireNotNull(groupId))) {
                "Moved this group to the last entry in ${command.category}."
              } else {
                "This group is already in ${command.category}."
              }
            }
            AdminCommand.GroupStatus -> error("Status does not write commands")
          }
          document.toXml() != before
        }
        // Confirm only after GitHub accepted the write (or the latest file already had this state).
        saveSnapshot(snapshot)
        plain(confirmation)
      }
    } catch (e: AdminCommandException) {
      plain("Error: ${e.message}")
    } catch (e: GitHubCommandsHttpException) {
      plain("Error: GitHub returned HTTP ${e.statusCode}. The admin command could not be completed; check repository access and try again.")
    } catch (e: Exception) {
      // Do not expose tokens, private URLs or server bodies in a group chat.
      plain("Error: the admin command could not be completed. Check connectivity and the commands XML, then try again.")
    }
  }

  companion object {
    fun isCandidate(input: String?): Boolean = input == "!admin" || input?.startsWith("!admin ") == true

    internal fun isAuthorized(configuredAci: String, senderAci: String?): Boolean =
      UUID_PATTERN.matches(configuredAci) && senderAci != null && UUID_PATTERN.matches(senderAci) && configuredAci.equals(senderAci, ignoreCase = true)

    internal fun parse(input: String): AdminCommand {
      require(input.length <= 4096 && input == input.trim() && input.none { it.isISOControl() || (it.isWhitespace() && it != ' ') }) { USAGE }
      return when {
        input == "!admin group status" -> AdminCommand.GroupStatus
        input == "!admin group disable autoupdate" -> AdminCommand.DisableGroup
        input.startsWith("!admin group category create ") -> AdminCommand.CreateCategory(categoryName(input.removePrefix("!admin group category create ")))
        input.startsWith("!admin group move ") -> AdminCommand.MoveGroup(categoryName(input.removePrefix("!admin group move ")))
        input.startsWith("!admin group add ") -> {
          val arguments = input.removePrefix("!admin group add ")
          val split = if (arguments.startsWith('"')) arguments.indexOf('"', startIndex = 1) + 1 else arguments.indexOf(' ')
          require(split > 0 && arguments.getOrNull(split) == ' ') { USAGE }
          val category = categoryName(arguments.substring(0, split))
          val description = arguments.substring(split + 1)
          require(description.isNotBlank() && description == description.trim()) { USAGE }
          AdminCommand.AddGroup(category, description)
        }
        input.startsWith("!admin group enable autoupdate ") -> {
          val description = input.removePrefix("!admin group enable autoupdate ")
          require(description.isNotBlank() && description == description.trim()) { USAGE }
          AdminCommand.EnableGroup(description)
        }
        input.startsWith("!admin enable ") || input.startsWith("!admin disable ") -> {
          val enabled = input.startsWith("!admin enable ")
          val trigger = input.removePrefix(if (enabled) "!admin enable " else "!admin disable ")
          require(trigger == "all" || (CommandDocument.isCommandCandidate(trigger) && !isCandidate(trigger))) { USAGE }
          AdminCommand.SetActive(trigger, enabled)
        }
        else -> throw IllegalArgumentException(USAGE)
      }
    }

    private fun plain(text: String) = CommandReply(text, emptyList(), emptyList())
    private fun categoryName(argument: String): String {
      require(argument.isNotBlank() && argument == argument.trim()) { USAGE }
      val name = if (argument.startsWith('"')) {
        require(argument.length > 2 && argument.endsWith('"')) { USAGE }
        argument.substring(1, argument.length - 1)
      } else {
        argument
      }
      require(name.isNotBlank() && name == name.trim() && !name.contains('"')) { USAGE }
      return name
    }
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val USAGE = "Use !admin enable all, !admin disable all, !admin enable <trigger>, !admin disable <trigger>, !admin group enable autoupdate <description>, !admin group disable autoupdate, !admin group status, !admin group category create <category>, !admin group add <category> <name>, or !admin group move <category>. Quote category names containing spaces when adding a group."
  }
}

internal sealed interface AdminCommand {
  data class SetActive(val trigger: String, val enabled: Boolean) : AdminCommand
  data class EnableGroup(val description: String) : AdminCommand
  data class CreateCategory(val name: String) : AdminCommand
  data class AddGroup(val category: String, val description: String) : AdminCommand
  data class MoveGroup(val category: String) : AdminCommand
  data object DisableGroup : AdminCommand
  data object GroupStatus : AdminCommand
}

/** Only these controlled, user-facing errors may be copied into a group reply. */
internal class AdminCommandException(message: String) : IllegalArgumentException(message)
