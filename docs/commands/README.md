<!-- Whatnoww added file. AI DOCUMENTATION HERE -->

# GitHub auto replies

Copy `commands.xml` from this directory to your private repository. The seed contains all 46 supplied groups, in their original order. All IDs and auto-update settings start at `0` because real group IDs were not supplied. Known outdated entries use `<outdated>1</outdated>`.

Set these in your untracked `local.properties`, then rebuild:

```properties
GITHUB_TOKEN_COMMANDS=your_commands_repository_token
RESPONSE_XML=https://api.github.com/repos/OWNER/REPOSITORY/contents/commands.xml?ref=main
```

Use a token with Contents read and write access to that repository. `GITHUB_TOKEN` remains the separate blocklist token. Raw URLs such as `https://raw.githubusercontent.com/OWNER/REPOSITORY/main/commands.xml` and `https://raw.githubusercontent.com/OWNER/REPOSITORY/refs/heads/main/commands.xml` also work; the app converts them to the Contents API. For branch names containing `/`, use the API URL with `?ref=your/branch`. Without `ref`, GitHub's default branch is used.

An incoming message whose entire body is exactly `!groups` receives a reply in the same eligible chat. For 1:1 chats, the recipient must be a registered phone contact or an accepted Signal-only contact (profile sharing enabled), and Signal must consider the message request accepted. Having a group in common or a previous outgoing message alone does not qualify someone as a contact. Hidden/removed contacts, blocked chats, unaccepted groups, inactive groups and announcement groups where self cannot post are skipped. Eligibility is checked before downloading commands and again immediately before sending. The existing automatic group-leaving behavior is unchanged. Case, extra words, leading/trailing spaces and newlines do not match. Attachments, quotes, stories, reactions, edits, duplicates and messages from self do not trigger replies. A missing commands token or URL disables this feature. The XML is fetched on demand with ETags; a validated atomic disk cache provides a fallback when GitHub is unavailable. The message uses the chat's disappearing-message timer and Signal's ordinary sending pipeline, including long-text attachments when necessary.

## Group entries

```xml
<category name="Tech related">
  <group>
    <groupid>__signal_group__v2__!YOUR_GROUP_ID_HEX</groupid>
    <description>Example group</description>
    <autoupdate>1</autoupdate>
    <link>https://signal.group/#YOUR_INVITE</link>
    <outdated>0</outdated>
  </group>
</category>
```

`groupid` is the full encoded Signal V2 group ID (`GroupId.toString()`), not a recipient ID, thread ID, master key or the invite URL fragment. The account running this app must be an active member with the group locally available to retrieve its current invite link. The app refreshes that group from Signal before reading the enabled link and password; it never rotates a password or enables a disabled link.

With `autoupdate=1` and a nonzero ID, an enabled current link replaces the configured link in the reply and is written back to GitHub. The publisher refetches the file and changes only entries whose ID, old link and opt-in still match. It includes the current file SHA and retries a concurrent-write conflict once. A failed write leaves the usable reply intact; the next request can try publishing again. This runs when commands are requested, not as a periodic task.

With `autoupdate=0`, the configured link is retained. If it differs from the current group link, or the link is disabled, the name is shown as `Example group (outdated link):`.

With `groupid=0` (or an omitted ID), the entry is manual and is never updated, even if `autoupdate=1`. Its configured invite may still be checked against Signal for validity. A confirmed inactive link gets the same outdated label. For unknown groups, the invite can be validated but cannot be used to discover a replacement password. Network errors, missing membership and unknown invite versions leave the last known status intact. A successful validity check clears a prior outdated label in the reply. Optional `<outdated>1</outdated>` supplies an initial status when verification is unavailable.

Category headings use native Signal **bold**, descriptions are plain text and links use native Signal *italic*. No Unicode pseudo-bold or visible Markdown markers are sent.

## More commands and formatting

Add another `<command>` under `<commands>`, with one or more exact `<trigger>` values and a `<response>`. Triggers begin with `!`, contain no whitespace and are at most 64 characters. Additional commands require no new receive-path code:

Set `<active>1</active>` to enable a command or `<active>0</active>` to disable it, including all of its trigger aliases. Disabled commands produce no reply, skip group-link checks and cannot publish group-link updates. An omitted tag defaults to enabled for existing XML; an empty or unrecognized value disables the command. Change the value in GitHub without rebuilding the app. It takes effect on the next successful XML refresh; if GitHub is unavailable, the app uses the last cached configuration.

```xml
<command>
  <active>1</active>
  <triggers><trigger>!help</trigger><trigger>!commands</trigger></triggers>
  <response><text><b>Commands</b><br/><i>Example</i> and <spoiler>hidden text</spoiler></text></response>
</command>
```

Supported response tags are `<text>`, `<br/>`, `<p>`, `<b>`/`<bold>`/`<strong>`, `<i>`/`<italic>`/`<em>`, `<s>`/`<strikethrough>`, `<code>`/`<mono>`, `<spoiler>`, `<groups>` and `<category name="…">`. Nested styles use UTF-16 offsets as required by Signal. Use XML escapes such as `&amp;` in text. Existing plain responses and CDATA remain plain text. Formatting tags inside CDATA are literal text.

The reply model separates text and formatting from transport; the send path already builds an attachment list for long messages. A future image implementation can add media descriptors and downloads there. `<image src="…"/>` is reserved and currently causes the response to be rejected; image sending is not enabled by this change. Unknown tags fail explicitly so content is never silently omitted.

## Device check

After uploading the XML and configuring/rebuilding the app, send `!groups` from another account to an accepted contact chat or an accepted group this account can post in. Check heading/link styling, the full long message, and the known outdated names. Confirm that phone contacts and accepted Signal-only contacts receive replies, while pending message requests and noncontacts who only share a group do not. Confirm `!groups extra` and a message with surrounding whitespace do not reply. Then set a known group ID and `autoupdate=1`, rotate that group's link through Signal's existing UI, request `!groups` again and check both the reply and the resulting GitHub commit. Repeat with `autoupdate=0` to check that the configured link remains and receives the outdated label.
