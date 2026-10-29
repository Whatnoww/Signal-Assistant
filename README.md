# Signal Assistant 

Signal Assistant is a fork of Signal Android with some quality of life enhancements from Signal-JW that benefit auto-reply bots, besides a spam reporting feature. 0 guarantee's.
DO NOT USE THIS AS YOUR PERSONAL CLIENT YOUR SECURITY IS EXPLICITLY REDUCED.

## Current features

- Basically all from Signal-JW including the most important websocket force options and preventing arbitrary group adds.
- Spam reporting based off of a blocklist: https://github.com/Whatnoww/ACI-Blocklist
- Automatic blocklist updates when adding to an admin exclusive spam reporting group.
- Will now not notify for messages that don't contain ! so there's less overhead.
- Gateway screening for group join requests, with shared review rooms, configurable questions, reminders and administrator decisions.
- ACI-Blocklist scans in groups and DM, with native mentions and background refreshes.

## Gateway

Gateway reviews pending Signal Group V2 join requests. The **source group** is
the group an applicant wants to join. The **Gateway room** (or lobby) is where
the applicant answers a question and administrators review the request. Several
source groups can share one lobby. A source can also use itself as the lobby,
with the different admission behavior explained below.

Applicants are identified by ACI. A normal text answer, including a reply quoting
another message, completes screening for the outstanding questions in that lobby.
Relevant source administrators can then approve or deny. They can also decide
**before the applicant answers**. Passing screening never automatically approves
a source-group request.

### Initial setup

1. Configure the private commands XML and its GitHub read/write access using the
   [auto-reply setup instructions](docs/commands/README.md). `RESPONSE_XML` points
   to that file; `GITHUB_TOKEN_COMMANDS` authorizes configuration updates.
   `ADMIN_ACI` identifies the owner. Keep credentials in untracked
   `local.properties`, outside published XML.
2. Merge the universal `<gateway>` section, command-family entries and scoped
   help from [gateway.template.xml](docs/gateway/gateway.template.xml) into your
   existing `<commands>` document. Keep existing commands and custom responses.
   Replace the example IDs; keep one canonical group entry per Group V2 ID.
3. Add Signal Assistant to both groups. Make it a Signal administrator in the
   lobby, with permission to add applicants and send messages. Applicants must
   also be allowed to send messages there. Enable join-request approval in the
   source's Signal invite-link settings so there are pending requests to review.
4. Add the source's real Group V2 ID and nested Gateway settings to the XML.
   A current group administrator can use `!groupid` in either group to receive
   its ID privately. A source needs a configuration entry even if it is unlisted.
5. In the source group, send `!gateway group <lobbyGroupID>`. For automatic source
   membership actions, make the bot a Signal administrator in the source and send
   `!gateway admin enable`. To leave source membership actions to humans, use
   `!gateway admin disable` and a separate lobby.
6. Send `!gateway enable` in the source. The bot validates current permissions.
   Use `!gateway` to inspect status. In the lobby, optionally send
   `!gateway deletemedia enable`; media deletion starts disabled.

For example, a source entry can start with:

```xml
<group>
  <groupid>__signal_group__v2__!SOURCE_GROUP_ID_HEX</groupid>
  <description>Example source</description>
  <autoupdate>1</autoupdate>
  <link>https://signal.group/#SOURCE_INVITE</link>
  <outdated>0</outdated>
  <silent>0</silent>
  <gateway>
    <enabled>0</enabled>
    <groupid>__signal_group__v2__!LOBBY_GROUP_ID_HEX</groupid>
    <admin>0</admin>
    <spamtext>Please tell us briefly why you would like to join this group.</spamtext>
    <deletemedia>0</deletemedia>
    <mentionadmins>1</mentionadmins>
    <throttle>0</throttle>
  </gateway>
</group>
```

Replace the placeholders before use. `<autoupdate>` manages the listed invite
link; it does not enable Gateway. The source's `<deletemedia>` value only governs
media if this group itself serves as a lobby. For a separate lobby, configure
that lobby's own policy.

An unlisted source can use `<silent>1</silent>` beside its normal group fields,
outside `<gateway>`. It still works normally with Gateway, but its name and link
are excluded from public `!groups` listings and general known-membership summaries.
Its actual requests still identify the source in the review room. A silent entry
can omit the public invite link. There is no silence command; change the XML.
Private entries under `<commands><gateway><privategroups>` are also supported.
A referenced lobby need not be in the public list: its `deletemedia` command
creates a private room policy when necessary.

### Source and lobby settings

All Gateway booleans use `0` for off and `1` for on. **An omitted boolean is off**;
the template's explicit values are not automatically supplied to existing groups.

| Field inside a group's `<gateway>` | Default / template value | Meaning |
| --- | --- | --- |
| `enabled` | Off (`0`) | Process pending requests for this source. |
| `groupid` | No implicit destination | Persistent lobby's Group V2 ID. Several sources may share it. |
| `admin` | Off (`0`) | Allow Gateway to change membership/requests in this source, subject to actual Signal permissions. |
| `spamtext` | Inherit universal question when omitted | Source-specific question. The bot prepends a native applicant mention. |
| `deletemedia` | Off (`0`) | Delete active applicants' media in **this group's role as a lobby**. The destination room's value governs a shared lobby. |
| `mentionadmins` | Off when omitted; source template explicitly sets `1` | Mention this source's administrators after a qualifying applicant answer. |
| `throttle` | Off (`0`) | Owner-controlled silent pause of all Gateway functionality for this group. This also pauses sources using a throttled lobby. |
| `reminderhours` | Inherit universal value | Per-source unanswered-screening reminder period. |
| `removalhours` | Inherit universal value | Per-source unanswered-screening expiration period. |
| `admissionhours` | Inherit universal value | Time allowed for lobby membership/invitation acceptance before screening starts. XML only; no admission-hours command. |

The lobby's own `enabled=0` does not stop other sources using it as their lobby;
that flag controls requests **to the lobby itself as a source**. Its room media
policy and owner throttle still apply.

### Admin mode and adminless mode

`admin` is permission granted by configuration. It does **not** promote/demote the
bot in Signal, and it does not change which humans are group administrators.

| Behavior | `admin=0` — adminless source | `admin=1` — source actions enabled |
| --- | --- | --- |
| Add/invite applicant to a separate lobby, ask questions, remind and notify | Supported; bot must still administer the lobby. | Supported. |
| `!approve` / `!deny` source request | Refused; a human must act in Signal's source-group interface. | Bot acts after current source-admin authorization and permission checks. |
| ACI-Blocklist match at intake | Record rejection/manual action required; do not admit for normal screening or change the native source request. | Deny the native request and record the outcome when authorized. |
| Unanswered timeout or failed screening after a departure | Finish locally with manual source action required; leave the native request unchanged. | Deny the applicable source request when authorized. |
| Same-group screening | Unavailable; processing is suspended. | Supported with provisional admission as described below. |

Even if the bot is a Signal administrator in the source, `admin=0` forbids these
source mutations. Conversely, `admin=1` cannot bypass missing Signal permissions.
Before an action, the bot refreshes relevant group state and checks current human
and bot roles, request state and configuration. Losing permission prevents the
action; the audit does not claim a denial or approval that did not happen.
Lobby cleanup remains available in adminless mode for membership the bot created
there, once no active reviews remain.

Approval rechecks the ACI-Blocklist. A newly blocklisted applicant is denied
instead of approved. Blocklist downloads use the last valid cache on failure;
without a valid blocklist or XML cache, affected processing waits. Stale cached
permissions never authorize a membership change. A Gateway denial does not ban
the applicant or report them to the ACI-Blocklist.

### Administrator commands

Use the exact verbs `enable` and `disable`, not `enabled` or `disabled`.
`<...>` denotes an argument to replace. Run source configuration commands in
the source group as a **current administrator of that source**. Detailed Gateway
configuration/status replies arrive by DM, including the accepted setting's old
and new values. Existing contact state is preserved; there is no routine public
acknowledgement.

| Source-group command | Effect |
| --- | --- |
| `!gateway` | Show source status, destination, administrative-action state and applicable controls/media policy. Also usable by a lobby administrator in the lobby. |
| `!gateway enable` / `!gateway disable` | Start or pause screening for this source. Enabling validates current mode and permissions. |
| `!gateway group <groupID>` | Set the lobby. Resolve active reviews before changing the destination. |
| `!gateway admin enable` / `!gateway admin disable` | Allow/disallow source membership actions. Disable same-group screening before turning these actions off. |
| `!gateway mentionadmins enable` / `!gateway mentionadmins disable` | Enable/disable this source's post-answer administrator mentions. |
| `!gateway spamtext <text>` | Set this source's question. Multiline text is supported. Remove its XML override to inherit the universal question; there is no spamtext-reset command. |
| `!gateway reminder <hours>` / `!gateway reminder default` | Set a source reminder override, or remove it to inherit the universal value. |
| `!gateway removal <hours>` / `!gateway removal default` | Set a source expiration override, or remove it to inherit the universal value. |

| Lobby command | Who may use it / effect |
| --- | --- |
| `!gateway deletemedia enable` / `!gateway deletemedia disable` | Current **lobby** administrators, in the lobby. Applies to active applicants from all sources using that room. Source administration alone does not grant this room permission. |
| `!approve @user` / `!approve @user <number>` | A current administrator of the selected **source**, in its lobby. Requires source `admin=1`; may be used before the applicant answers. |
| `!deny @user` / `!deny @user <number>` | Same source authorization as approval. Denies only the selected request. |

Select the applicant with a real Signal mention; typing a display name is not
enough. Omit the number only when that applicant has one unresolved request in
this lobby. Use the displayed mapping otherwise. Numbers start at 1 separately
for each applicant/lobby session, stay stable while reviews remain active, and
reset for the next session. A lobby administrator cannot decide requests for a
source they do not administer.

| Related command | Availability / effect |
| --- | --- |
| `!groupid` | Current group administrator, in a group; sends that Group V2 ID by DM. |
| `!scan` in a group | Current group administrator; cached ACI-Blocklist results followed by one forced refresh and edits if changed. Results go only to current eligible group administrators within the original recipient audience. |
| `!scan` in DM | Scans locally known groups the caller currently administers, grouped by name. Noncontacts can use it. One shared blocklist refresh covers the invocation; the per-ACI cooldown is reported privately. |
| `!help`, `!list`, `!command`, `!commands` | Public help first in groups, followed by restricted administrator/owner edits. DM help shows applicable caller controls. |

Group help and scans use native sender keys where eligible, with Signal's normal
individual-send fallback. Selective delivery does not create a separate private
conversation. The owner can receive a group help edit only when in that group.

### Universal defaults and response configuration

Keep one universal `<gateway>` directly under `<commands>`, separate from the
individual groups' `<gateway>` blocks. Its `<defaults>` section enables the
configuration model; merge the full response/help definitions from the template
as well. The following timing/cooldown defaults also apply when those fields are
omitted from an existing `<defaults>` section:

| Universal XML path, relative to `<commands><gateway>` | Initial value | Behavior |
| --- | --- | --- |
| `paused` | `0` | Global emergency pause state; the owner's all-command controls update it. |
| `defaults/spamtext` | Template: `Please tell us briefly why you would like to join this group.` | Question inherited by sources without an override. If omitted, the built-in fallback is `Please tell us briefly why you would like to join.` |
| `defaults/reminderhours` | `72` | Reminder after a successfully sent question remains unanswered. |
| `defaults/removalhours` | `96` | Expiration after a successfully sent question remains unanswered. |
| `defaults/admissionhours` | `96` | Separate admission/invitation wait before the applicant becomes a full lobby member. |
| `defaults/joinpollseconds` | `30` | Background source-state discovery interval between passes; `0` disables this fallback, otherwise use 15–3600 seconds. Native group updates still trigger discovery. |
| `defaults/scancooldowns/dmminutes` | `15` | Per-caller DM scan cooldown. |
| `defaults/scancooldowns/groupminutes` | `0` | Per-caller, per-group scan cooldown; zero allows immediate repeat scans. |

Hours must be whole numbers from 1 to 87600. The effective reminder must precede
removal for each source. Scan cooldowns are whole minutes from 0 to 5256000.
Per-source timing overrides live inside that source's `<gateway>`; omit them to
inherit. Admission hours, polling, universal defaults and scan cooldowns are
configured in XML rather than through additional chat commands.

Reminder/removal time starts when the bot successfully sends that source's
question, not when the request is discovered or when the applicant reads it.
Timers are per source request and survive restarts. Existing requests retain
their recorded durations after a configuration change. Pauses freeze remaining
time. A qualifying answer ends the unanswered-screening timer; there is no
automatic decision deadline while waiting for an administrator.

The fallback checks enabled sources plus locally known groups administered by
the bot, including groups using only the existing ACI-Blocklist request checks.
It respects pause/throttle and checks shared XML freshness. Android scheduling,
network latency and server backoff can delay a pass; 30 seconds is not a hard
join-detection guarantee.

`<responses>` contains `<message id="..."><text>...</text></message>` entries.
The template documents the supported placeholders, including native applicant
and administrator mentions, source/group names, confirmed outcomes, scan results
and cooldown values. Keep placeholders when customizing wording. Available IDs:

- Greetings/status: `greeting`, `status`, `gateway_status`, `dm_status`, `groupid`.
- Errors: `error`, `permission_error`, `dm_delivery_failure`.
- Screening/audit: `spam_reminder`, `timeout`, `blocklist_rejection`, `approval`,
  `denial`, `manual_finish`, `administrator_notification`.
- Scans: `scan`, `scan_cooldown`, `scan_wait`, `scan_ready`, `scan_no_groups`.

`<help>` contains `<section scope="..."><text>...</text></section>` entries.
Scopes are `public`, `group-admin`, `group-scan`, `source-admin`, `gateway-admin`,
`review-admin`, `review-approve`, `review-deny`, `dm-group-admin`, `owner` and
`owner-group`. Keep public wording suitable for everyone; privileged commands
belong in their scoped sections. `owner` covers controls usable in DM or groups;
`owner-group` is added only to the owner's group help. Uploaded XML controls the
wording, so app updates alone do not expand an old abbreviated help section.

Keep exact command entries for `!gateway`, `!approve`, `!deny`, `!groupid`, `!scan`
and the help aliases. Their `<active>1</active>` / `<active>0</active>` values
control those command families; arguments do not need separate XML triggers.
These command switches are separate from a source's Gateway `enabled` setting.
Disabling the `!gateway` command family does not pause background screening;
use `!gateway disable` in the source or the owner's Gateway throttle for that.
XML publications preserve unrelated content, escape question text and use
two-space structural indentation. Success is confirmed after an accepted write;
failed writes do not produce cached success replies.

### Owner controls

Only the configured exact `ADMIN_ACI` can use `!admin` controls. Group administrator
status does not grant owner access. The following work in DM or groups:

| Command | Effect |
| --- | --- |
| `!admin disable all` | Emergency pause of Assistant automation and configured commands, except ACI-Blocklist reporting. |
| `!admin enable all` | Resume automation and enable all configured commands; remains available for recovery. |
| `!admin disable <trigger>` / `!admin enable <trigger>` | Toggle the command matching that exact trigger and all its aliases. |
| `!admin gateway throttle <groupID> enable` / `!admin gateway throttle <groupID> disable` | Silently pause/resume all Gateway functionality for the specified group. Throttle leaves unrelated commands and ACI-Blocklist reporting available. |
| `!admin group category create <category>` | Create a public-list category. |

These owner commands require the relevant group context; send them in that group:

| Command | Effect |
| --- | --- |
| `!admin group status` | Inspect the current group's list/autoupdate state; silent entries are marked `[silent]`. |
| `!admin group enable autoupdate <description>` | Associate the matching listed description with this group's ID and update its saved invite link when available. |
| `!admin group disable autoupdate` | Disable listed-link autoupdate, retaining the saved ID/link. |
| `!admin group add <category> <name>` | Add the current group to an existing category using its enabled invite link, with autoupdate enabled. |
| `!admin group move <category>` | Move the current group's existing entry to the end of another category. |

For example, use `!admin group add "Live Music" My group` for a category containing
spaces. These list commands do not change Signal's invite-link or group permissions.
For detailed list-management behavior, see [the command reference](docs/commands/README.md#admin-commands).

### Shared rooms, same-group mode and final outcomes

In a shared lobby, one subsequent qualifying answer satisfies all questions
already outstanding for that applicant. A later source request issues its own
question and needs a new answer. Only administrators of sources with
`mentionadmins=1` are pinged, without repeated pings for unchanged requests.
Known memberships are limited to groups the bot can establish from available
ACI-matched state; they are not a directory of all the applicant's Signal groups.

When source and lobby IDs are equal, `admin=1` and actual bot administration are
required. Admitting the applicant for screening consumes the native join request
and grants ordinary full membership. Signal has no quarantine role. `!approve`
confirms that membership; denial, a blocklist rejection or unanswered expiration
removes the provisional member. Approval prevents subsequent lobby cleanup from
removing that permanent member.

Each decision resolves only its source request. Manual source approval/denial or
request cancellation is reconciled on the next relevant state update. Separate
lobby cleanup waits until no active reviews remain and removes only membership
the bot created; preexisting lobby members stay.

A confirmed human administrator removal from the lobby finishes the associated
Gateway reviews without approving/denying the remaining native source requests.
The old request generations are not automatically re-added or prompted again.
A voluntary leave, or an unknown/unattributed departure, fails screening and
triggers source denial when permitted; adminless sources require human action.
A proven new join-request generation can start a new review.

With lobby media deletion on, active applicants' attachments, voice messages,
files and native link-preview messages are deleted where Signal permits it.
This includes previews without thumbnails. Media/captions, previews, reactions,
edits, commands, empty messages and quoted-only content do not pass screening.
A plain URL without native preview metadata remains text. Deletion is subject
to current Signal permissions and its deletion window; it is best effort.
Signal's long-text transport attachment is treated as text rather than media.

Gateway keeps current workflow/timer state in its separate encrypted database,
and the room chat supplies the human audit trail. Completed tracking is pruned
when no longer needed. For implementation details and recovery behavior, see
[the Gateway reference](docs/gateway/README.md) and
[the agreed behavior](docs/gateway/DECISIONS.md).


## Contributing Code

If you want additional features feel free to contribute code changes.

## Contributing Ideas
Have something you want to say about Open Whisper Systems projects or want to be part of the conversation? Get involved in the [community forum](https://community.signalusers.org).



### What we need
Group rebuild protection.
Automatic spam removal.
Built in auto-reply feature.


## Documentation
Looking for documentation? Check out the wiki!

https://github.com/signalapp/Signal-Android/wiki

# Legal things
## Cryptography Notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See <http://www.wassenaar.org/> for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which includes information security software using or performing cryptographic functions with asymmetric algorithms.
The form and manner of this distribution makes it eligible for export under the License Exception ENC Technology Software Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section 740.13) for both object code and source code.

## License

Copyright 2013-2023 Signal

Licensed under the GNU AGPLv3: https://www.gnu.org/licenses/agpl-3.0.html

Google Play and the Google Play logo are trademarks of Google LLC.
