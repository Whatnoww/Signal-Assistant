<!-- Whatnoww added file. AI DOCUMENTATION HERE -->

# GitHub auto replies

`commands.template.xml` is the public, sanitized example configuration. It contains fictional commands, placeholder group IDs and placeholder invite links. Copy it to a private deployment configuration and replace the placeholders there.

The local `docs/commands/commands.xml` is private and explicitly Git-ignored. Keep real group lists, command catalogs, responses and credentials out of this application's source repository. Tests use synthetic inline fixtures and the public template only; they must never read the private XML or list its commands. The app obtains its deployed configuration through `RESPONSE_XML`; there is no bundled private catalog.

Ordinary responses are configured text; `!scan` appends generated results to its configured heading. Empty ordinary responses send no message, and commands with `<active>0</active>` are disabled. Native formatting tags are demonstrated in the template.

Set these in your untracked `local.properties`, then rebuild:

```properties
GITHUB_TOKEN_COMMANDS=your_commands_repository_token
RESPONSE_XML=https://api.github.com/repos/OWNER/REPOSITORY/contents/commands.xml?ref=main
ADMIN_ACI=aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee
```

Use a token with Contents read and write access to that repository. `GITHUB_TOKEN` remains the separate blocklist token. Raw URLs such as `https://raw.githubusercontent.com/OWNER/REPOSITORY/main/commands.xml` and `https://raw.githubusercontent.com/OWNER/REPOSITORY/refs/heads/main/commands.xml` also work; the app converts them to the Contents API. For branch names containing `/`, use the API URL with `?ref=your/branch`. Without `ref`, GitHub's default branch is used.

An incoming message whose entire body is exactly `!groups` receives a reply in the same eligible chat. With the category browser configured below, `!groups 1`, `!groups 2`, and so on show individual categories. For 1:1 chats, the recipient must be a registered phone contact or an accepted Signal-only contact (profile sharing enabled), and Signal must consider the message request accepted. Having a group in common or a previous outgoing message alone does not qualify someone as a contact. Hidden/removed contacts, blocked chats, unaccepted groups, inactive groups and announcement groups where self cannot post are skipped. Eligibility is checked before downloading commands and again immediately before sending. The existing automatic group-leaving behavior is unchanged. Case, extra words, leading/trailing spaces and newlines do not match. Attachments, quotes, stories, reactions, edits, duplicates and messages from self do not trigger replies. A missing commands token or URL disables this feature. The XML is fetched on demand with ETags; a validated atomic disk cache provides a fallback when GitHub is unavailable. The message uses the chat's disappearing-message timer and Signal's ordinary sending pipeline, including long-text attachments when necessary.

## Scanning group members

`!scan` works only in eligible Signal V2 group chats and only for a current administrator of that group. Direct chats, ordinary members, pending members and nonmembers receive no response. The configured `ADMIN_ACI` does not grant scan access unless that account is also an administrator in the requesting group. The sender's role is checked before reading commands and again before sending or editing.

Add this entry to your private commands XML:

```xml
<command>
  <active>1</active>
  <triggers><trigger>!scan</trigger></triggers>
  <response><text><b>Members found on the ACI-Blocklist:</b></text></response>
</command>
```

The `<response>` supplies the configurable start text, including native styles. An empty response omits the heading. `<active>0</active>`, `!admin disable !scan`, `!admin enable !scan` and the corresponding `all` commands use the usual controls. Keep the exact `!scan` trigger; the scan behavior is reserved to that trigger.

The first response reads the cached command configuration and cached ACI-Blocklist without an online blocklist check. Each matching full member, including administrators and self, appears on a separate line as `ACI @member`, with a native Signal mention that opens the member's details. Invited, requesting and banned accounts are excluded. No matches produces an explicit empty result. An unpopulated cache is reported as unavailable rather than interpreted as an empty blocklist. The blocklist cache is currently in memory, so it starts unavailable after an app restart. Without a cached command configuration, the configuration must first be downloaded.

Long scans use continuation messages below Signal's inline byte limit so every member mention remains tappable. Background updates edit existing pages, add continuations when necessary and replace obsolete continuations with an empty-result notice. If any original page was deleted or manually edited, the update is skipped.

Every successful `!scan` schedules a background check of the ACI-Blocklist, even immediately after another scan. This check bypasses the blocklist's five-minute freshness interval and requests HTTP cache revalidation so manual edits can be detected. The background reply job also refreshes the commands XML when its one-minute freshness window expires, rebuilds the scan from current membership and edits the same reply only if the text, formatting or mentions changed. It waits for the initial reply to send and uses a separate queue so the online blocklist check does not hold up new command replies. Ordinary join-request blocklist lookups retain their five-minute cache interval. A failed refresh retains the last good snapshot. Deleted or manually edited replies are left alone, and a sender who loses their group administrator role receives no edit.

## Browsing group categories

Use `<groups view="categories">` to make `!groups` show a numbered category index instead of every invite link. Configure its heading with native bold formatting:

```xml
<response>
  <text><b>Explore public Signal groups</b></text><br/><br/>
  <groups view="categories">
    <category name="Music"/>
  </groups>
  <br/><br/>
  <text><i>Send !groups &lt;number&gt; to view a category.</i></text>
</response>
```

Each row uses the XML category order and its current number of direct `<group>` entries, for example `1. Music (5 groups)`. Empty categories are included with zero groups. Instructions after the index come entirely from the XML: edit or remove the footer `<text>` element to change them. The code appends no default instructions. Use explicit `<br/>` tags for spacing; XML indentation does not create visible line breaks. Literal angle brackets in text must be escaped as `&lt;` and `&gt;`. The footer appears only on the index, not numbered category replies, and its wording is shared by configured trigger aliases. The index does not resolve or publish invite links.

`!groups 1` renders only the first category, with its bold heading, group descriptions, italic links and outdated-link labels. Only that category's links are checked and eligible for automatic updates. An empty category returns a short empty-state message. Zero, negative, overflowing and out-of-range numbers return guidance to request the index again; non-numeric arguments such as `!groups extra` remain unmatched. Configured aliases support the same numbered browsing, and exact configured multi-word commands keep their existing precedence.

Numbers, counts and numbered replies are generated from the current XML, including after admin category creation, group additions and moves. Silent groups are excluded from counts and replies. Categories containing only silent groups are omitted, and numbered selection uses that same visible category order; genuinely empty categories retain the existing zero-group behavior. Do not add separate commands or store numbers and counts in the file. Disabling the parent command disables its index and all generated numbered replies. Existing lists without `view="categories"` retain their full-list behavior and also omit silent groups.

## Cache and background edits

When a cached response exists, the app sends it immediately without waiting for GitHub or Signal group-link checks. If the cache needs revalidation, a separate background job waits until that original message has sent, checks for updates and edits the same reply only if its text or formatting changed. It also catches new/outdated group links. An unchanged response produces no edit or duplicate message. Deleted or manually edited replies are left alone; contact and group eligibility is checked again before any automatic edit.

GitHub checks use a one-minute freshness window, followed by an ETag conditional request. Repeated commands inside that window do not contact GitHub. Failed attempts are throttled for the same interval, while the last good response remains available. A successful link write updates the cache immediately. The disk cache survives app restarts and is revalidated in the background on its first use. With no cache, the first request must download and resolve the response before sending.

Remote edits, including `<active>0</active>`, are detected on a command-triggered background refresh after the freshness window expires. An already-sent cached reply is retained if the refreshed command is disabled; future requests use the disabled configuration. No periodic polling or webhook is required. Code can call `GitHubAutoReply.invalidateCache()` to expire freshness immediately, or `refreshFromGithub(context)` to force a conditional fetch. Publishing link updates still fetches the latest file before writing to preserve concurrent edits.

## Admin commands

Set `ADMIN_ACI` to the administrator's Signal account UUID in untracked `local.properties`, then rebuild. An empty value disables administrative access; a malformed UUID fails the build. Authorization uses the incoming message sender's ACI, never a profile name, phone number, group admin role or UUID supplied in the message. Send these commands as plain text from that account in an accepted Signal V2 group where this app can post. Existing attachment, quote, self-message and blocked-chat restrictions apply.

| Command | Action |
| --- | --- |
| `!admin disable all` | Set every configured command's `<active>` to `0`. |
| `!admin enable all` | Set every configured command's `<active>` to `1`. |
| `!admin disable !groups` | Disable the command matching the exact trigger, including all its aliases. |
| `!admin enable !groups` | Enable that command, including all its aliases. Hashtag and multi-word triggers also work. |
| `!admin group enable autoupdate Example group` | Find the unique entry whose description is exactly `Example group`, save the current chat's group ID, set `<autoupdate>1</autoupdate>`, and replace its link with the current enabled link. |
| `!admin group disable autoupdate` | Set `<autoupdate>0</autoupdate>` for entries matching the current chat's saved group ID. Retain IDs and links for outdated-link detection. |
| `!admin group status` | Fetch the latest configuration and report whether the current chat's group ID is listed, with each matching description and its autoupdate setting. |
| `!admin group category create Music` | Append a new empty `Music` category to the `!groups` response. |
| `!admin group add Music #NowPlaying Music` | Add the current chat as `#NowPlaying Music`, last in `Music`, with its group ID, current enabled invite link, `<autoupdate>1</autoupdate>` and `<outdated>0</outdated>`. A disabled or unavailable invite link returns an error. |
| `!admin group move Music` | Move the current chat's existing entry to the end of `Music`, preserving its name, link, autoupdate setting and other metadata. |

Descriptions follow the enable command directly, without quote marks, and can exceed the normal 64-character trigger limit (admin messages allow up to 4096 characters). Matching is case-sensitive. Missing arguments, unknown commands, missing or ambiguous descriptions, unlisted group disable requests, unauthorized senders and repository failures receive an error reply without a success confirmation. If Signal cannot supply an enabled invite link, enabling autoupdate still saves the group ID and opt-in, retains the configured link, and includes a warning. A confirmed disabled invite is marked outdated. Admin commands only change the XML configuration; they never enable, disable or rotate Signal invite links or change Signal group permissions.

Category creation, addition and movement operate on the response of the unique command with the `!groups` trigger, even when that command is disabled. Create a category before adding or moving groups into it. For category names containing spaces, use `!admin group category create Live Music`, `!admin group add "Live Music" #NowPlaying Music`, and `!admin group move Live Music`. Quotes are optional for create and move. Everything after the category in an add command is the desired display name. Names and descriptions are safely escaped in XML. Duplicate category names, duplicate group IDs and duplicate display names in this list are rejected. Moving to the current category confirms its existing location without changing its position. Moving to another category keeps the old category, even if it becomes empty.

Before adding, the app validates the latest XML, refreshes the current group from Signal and reads its existing enabled invite link. A disabled or unavailable link prevents the list addition. Enable a disabled invite through Signal's own group settings before trying again. The assistant preserves its password and approval setting and needs no Signal group administrator role to add an entry with an existing enabled link. GitHub conflict retries reuse the resolved invite link and revalidate the destination.

Admin commands operate on disabled commands too. They are independent of XML `<active>` flags, so `!admin enable all` remains available after disabling all replies. ACI reporting responses are handled separately and are unaffected. Each write fetches the latest XML, preserves unrelated entries, uses its SHA, and retries a concurrent-write conflict once. Success replies follow the accepted GitHub write; the in-memory and disk caches are updated immediately. Status reads also refresh the cache. Admin replies do not use cached response text or background edits, and GitHub failures never fall back to a cached success confirmation.

Published XML uses line breaks and two-space indentation for structural entries, including newly created categories and added or moved groups. Reply text, inline spaces, authored line breaks and CDATA remain unchanged. Emoji references split into surrogate pairs by older Android writes are repaired when reading and writing the XML.

## Group entries

```xml
<category name="Example category">
  <group>
    <groupid>__signal_group__v2__!YOUR_GROUP_ID_HEX</groupid>
    <description>Example group</description>
    <autoupdate>1</autoupdate>
    <link>https://signal.group/#YOUR_INVITE</link>
    <outdated>0</outdated>
    <silent>0</silent>
  </group>
</category>
```

`groupid` is the full encoded Signal V2 group ID (`GroupId.toString()`), not a recipient ID, thread ID, master key or the invite URL fragment. The account running this app must be an active member with the group locally available to retrieve its current invite link. The app refreshes that group from Signal before reading the enabled link and password; it never rotates a password or enables a disabled link.

Set `<silent>1</silent>` on a normal group entry to keep it out of public group
lists while retaining its ID, metadata and Gateway functionality. Missing or `0`
means public; other values are invalid. Silent entries never trigger public link
lookups, and their names, links and counts are not included in list replies.
Owner status marks these entries `[silent]`, and existing owner XML edits preserve
the flag. An invite link is optional for a silent group; a provided link must
still use the normal Signal invite format. See [Gateway configuration](../gateway/README.md)
for a complete unlisted-source example and its review-room visibility.
Install the updated app before adding silent entries to the normal list, since
older builds ignore the new flag. Visibility updates follow the existing XML
cache and refresh behavior.

With `autoupdate=1` and a nonzero ID, an enabled current link replaces the configured link in the reply and is written back to GitHub. The publisher refetches the file and changes only entries whose ID, old link and opt-in still match. It includes the current file SHA and retries a concurrent-write conflict once. A failed write leaves the usable reply intact; the next request can try publishing again. In browser mode, this runs when a numbered category is requested; the index does not check group links. Other full-list commands still check their rendered groups. There is no periodic task.

With `autoupdate=0`, the configured link is retained. If it differs from the current group link, or the link is disabled, the name is shown as `Example group (outdated link):`.

With `groupid=0` (or an omitted ID), the entry is manual and is never updated, even if `autoupdate=1`. Its configured invite may still be checked against Signal for validity. A confirmed inactive link gets the same outdated label. For unknown groups, the invite can be validated but cannot be used to discover a replacement password. Network errors, missing membership and unknown invite versions leave the last known status intact. A successful validity check clears a prior outdated label in the reply. Optional `<outdated>1</outdated>` supplies an initial status when verification is unavailable.

Category headings use native Signal **bold**, descriptions are plain text and links use native Signal *italic*. No Unicode pseudo-bold or visible Markdown markers are sent.

## More commands and formatting

Add another `<command>` under `<commands>`, with one or more exact `<trigger>` values and a `<response>`. Triggers begin with `!` or `#` and are at most 64 characters. Internal spaces are supported for exact multi-word triggers such as `!example locale`. Leading/trailing whitespace, tabs and line breaks do not match. Matching is case-sensitive; explicit aliases such as `!Example locale` are supported. A configured multi-word trigger does not make prefix matches possible: `!groups extra` still does not match `!groups`. Additional commands require no new receive-path code:

Set `<active>1</active>` to enable a command or `<active>0</active>` to disable it, including all of its trigger aliases. Disabled commands produce no reply, skip group-link checks and cannot publish group-link updates. An omitted tag defaults to enabled for existing XML; an empty or unrecognized value disables the command. Change the value in GitHub without rebuilding the app. It takes effect on the next successful XML refresh; if GitHub is unavailable, the app uses the last cached configuration.

```xml
<command>
  <active>1</active>
  <triggers><trigger>!help</trigger><trigger>!commands</trigger></triggers>
  <response><text><b>Commands</b><br/><i>Example</i> and <spoiler>hidden text</spoiler></text></response>
</command>
```

Supported response tags are `<text>`, `<br/>`, `<p>`, `<b>`/`<bold>`/`<strong>`, `<i>`/`<italic>`/`<em>`, `<s>`/`<strikethrough>`, `<code>`/`<mono>`, `<spoiler>`, `<groups>`, `<group>` and `<category name="…">`. Nested styles use UTF-16 offsets as required by Signal. Use XML escapes such as `&amp;` in text. Existing plain responses and CDATA remain plain text. Formatting tags inside CDATA are literal text.

The reply model separates text and formatting from transport; the send path already builds an attachment list for long messages. A future image implementation can add media descriptors and downloads there. `<image src="…"/>` is reserved and currently causes the response to be rejected; image sending is not enabled by this change. Unknown tags fail explicitly so content is never silently omitted.

## Device check

After configuring the deployed XML and rebuilding the app, send `!groups` from another account to an accepted contact chat or an accepted group this account can post in. Check heading/link styling, the full long message, and outdated markers using your own configuration. Confirm that phone contacts and accepted Signal-only contacts receive replies, while pending message requests and noncontacts who only share a group do not. Confirm `!groups extra` and a message with surrounding whitespace do not reply. Then set a known group ID and `autoupdate=1`, rotate that group's link through Signal's existing UI, request `!groups` again and check both the reply and the resulting GitHub commit. Repeat with `autoupdate=0` to check that the configured link remains and receives the outdated label.

From the configured admin account, disable and re-enable one command by an alias, then all commands, checking the confirmation and subsequent ordinary replies. Confirm ACI reporting continues while commands are disabled. In a listed group, enable autoupdate using its description, check status, rotate the invite through Signal's UI, and request its listing again. Disable autoupdate and verify status and outdated-link detection. Repeat an admin request from a different account and check that it returns an authorization error without changing GitHub.

Create a category, then send `!admin group add Music #NowPlaying Music` from an unlisted group with an enabled invite link. Confirm that its ID, description, link and autoupdate flag are saved and that it appears last in that category. Confirm that an invite requiring approval retains that setting, and that a disabled invite returns an error and stays disabled. Move the entry to a different category and verify its final position and unchanged metadata. Check errors for missing categories, duplicate entries and unavailable links.
