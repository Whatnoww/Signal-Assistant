# Signal Assistant Gateway

Gateway reviews native Group V2 join requests in a persistent room. Identity is
always the applicant ACI. Each source request has a durable generation and stable
number; one applicant can have several requests in the same room. One new plain
text answer satisfies the prompts already issued for that batch. A later prompt
requires another answer.

## Configuration

Merge [gateway.template.xml](gateway.template.xml) into the private commands XML.
It is an example, not a replacement for the existing command catalogue. Replace
its group-ID/link placeholders and keep a single canonical configuration entry
per group ID. Add the nested `<gateway>` block below the existing source fields.
The template starts each source disabled and administrative source actions off.

For a source that should not be published, put `<silent>1</silent>` beside its
normal group fields, outside `<gateway>`. It can live in the normal `!groups`
configuration and still use the same Gateway commands, permissions, timers and
review workflow. Its name and link are omitted from public replies, it is not
counted, and categories containing only silent groups are hidden. An invite link
may be omitted for a silent group. Absent `<silent>` or `<silent>0</silent>` keeps
the existing public behavior. Private status replies label it `[silent]`.
Deploy the updated app before adding silent entries to the normal list: older
builds ignore this flag. Existing XML cache/refresh timing still applies. The
older `<gateway><privategroups>` container remains supported and private.

Silent groups are excluded from the general known-membership summary in Gateway
messages, as private room records already are. An actual request to a silent
source still names that source in its configured review room; administrators need
that context to decide the request. Keep one canonical group entry per ID.

Keep exactly one universal `<commands><gateway>` section. It contains defaults,
response templates, scoped help, emergency pause and private room policies. A
room already in the public/source list uses that entry; otherwise Gateway creates
its policy under `<gateway><privategroups>`. Private room records are excluded
from public group listings and known-membership announcements. `deletemedia`
belongs to the destination room's own policy, even when several sources use it.
A referenced lobby does not need an entry in the public group list: its
administrator can run `!gateway deletemedia enable` in that lobby to create the
private room policy when absent. Use the verb `enable`, not `enabled`. There is
currently no silence command; `<silent>1</silent>` is the XML visibility option.

Missing booleans are disabled. Omit timing overrides to inherit universal values.
Initial defaults are reminder 72 hours, removal 96 hours, admission/invitation wait
96 hours, DM scan cooldown 15 minutes and group scan cooldown zero. Reminder must
precede removal. Durations are snapshotted per request; changing configuration
does not shorten an existing applicant's allotted time.

The exact command-family triggers `!gateway`, `!groupid`, `!approve`, `!deny` and
`!scan` remain XML enable/disable switches. Arguments are handled separately;
unrelated command matching stays exact. Keep public help wording public: replace
old static privileged-command listings with the scoped XML help sections.
Approval and denial help are separate so disabling one family hides that command.
Gateway XML writes use two spaces per nesting level and put structural settings
on separate lines. Authored spam text, response formatting and inline spaces are
preserved.

In the Gateway room, current room administrators can use
`!gateway deletemedia enable` and `!gateway deletemedia disable`. These controls
appear in scoped help and the private `!gateway` status reply before source
processing is enabled. Status also shows the effective room media policy.
Source administrators must use those commands in the destination room and hold
its current administrator role; source administration alone is insufficient.

Gateway configuration, status and group IDs are sent by DM. The scoped transport
does not add/remove contacts, hide recipients or enable profile sharing for a
noncontact. Existing contacts retain their state. No routine group acknowledgement
is sent. Owner access continues to require the configured `ADMIN_ACI`; ordinary
group administrator status never grants owner access.

## Membership and permission semantics

The bot must be a current administrator of the Gateway room, and applicants must
be allowed to post there. Announcement-only rooms cannot support screening. For
source administrative actions the source's `admin` option and the bot's actual
current administrator role must both permit the action. With `admin=0`, outcomes
requiring source mutation remain manual; local expiration does not claim that a
native request was denied. ACI reporting groups cannot be Gateway destinations.
An explicit manual `admin=0` edit in same-group mode suspends that mode rather
than falling back to a previously cached `admin=1` policy. Removing an active
source's configuration or changing its destination directly in XML pauses its
existing reviews; configuration commands reject destination changes while active.

An administrator of the applicable source group can approve an active request at
any stage, including before Gateway admission, before the question is sent, or
before the applicant replies. Screening controls the automatic review
notification, not the administrator's ability to decide. Every approval still
requires current human and bot permissions, source `admin=1`, current request
state and the ACI-Blocklist check. A blocklist match causes denial instead of
approval. If human authorization disappears, restore the previous screening
stage without creating a reply or a successful-screening notification.

Direct addition uses native Group V2 credentials when available and otherwise may
create an invitation. Screening starts only after full membership is confirmed.
No automatic unban is performed. Gateway denials/removals do not ban people or add
them to the ACI-Blocklist. Cleanup removes only bot-created room membership, and
only after every active request using it has finished. Preexisting members remain.

In same-group mode, admission consumes the native join request and gives ordinary
full membership before screening. Approval confirms the local review; denial,
blocklist rejection and unanswered expiration remove that provisional member.
Approval makes membership permanent, including when another source still uses
this room. A removal from such a shared room also ends its remaining reviews
without mutating their other source requests. Signal provides no quarantine role.

Any unplanned departure ends the room's associated reviews. It is not attributed
to a particular human when the snapshot cannot prove the actor. The old native
request generation remains suppressed. A proven new request can start again.
Bot cleanup and source-decision removals carry durable correlation state. If a
restart leaves an interrupted admission with neither membership nor invitation,
Gateway ends that uncertain attempt locally and leaves the source request alone.
It does not guess whether admission failed or someone subsequently removed them.

Media uses this Signal revision's administrator-delete support, subject to its
current permissions and deletion window. Deletion is best effort, not a promise
that every recipient will erase a message. Failed deletion does not remove an
applicant. Text replies count whether they quote the prompt or any other message;
only the applicant's own text counts. An empty reply with only quoted content does
not pass screening. Quoted media does not count as media sent by the applicant.
Native link-preview messages are media for this policy, even without a thumbnail;
they do not pass screening and are deleted when applicant media deletion is on.
Plain text URLs without preview metadata remain text. Media/captions, edits,
reactions and commands do not pass screening. Signal's
long-text transport attachment is treated as text.

## Jobs, races and storage

Incoming processing only classifies events and enqueues immutable identifiers.
Configuration publication, scans, permissions, mutations, replies and timers run
on the existing bounded JobManager workers. Gateway command work has its own
queues, so GitHub writes do not occupy the ordinary cached-reply queue. Work for
a room is serialized; SQL version/phase comparisons also protect decisions from
other command/event queues. Timer jobs have separate queues and enqueue immediate
work when due, so a delayed reminder cannot hold up an approval.
An in-flight admission cannot overwrite a later administrative decision. Pending
spam prompts/reminders retain minimal cancellation markers until native
transport consumes them, even after completed request tracking is pruned.
At most two expensive Gateway workers run together; busy work yields without
blocking a worker thread. Network retries use separate wakeups so they cannot
hold the room queue ahead of a manual departure or another request.

The separate SQLCipher file is `signal-assistant-gateway.db`, schema version 1.
There are no Gateway tables or migrations in Signal's primary database. Tracking
holds active work, session ownership, pending transport metadata, cooldowns and
minimal suppression markers. Completed transport records are compacted; finished
requests are pruned when native-generation suppression and cleanup are no longer
needed. Internal request IDs use SQLite AUTOINCREMENT and are never recycled.
Displayed numbers start at 1 separately for each applicant in each Gateway room.
They remain stable while any of that applicant's reviews are active, including
after a higher-numbered request finishes or the process restarts. The next session
starts at 1 after all those reviews finish. Existing active reviews from older
builds retain their already-posted numbers until that session finishes. A command
received before a review was created cannot act on it after a number is reused.
Gateway chat messages provide the human audit trail rather than a permanent local
ledger. No XML or main Signal database migration is needed for these counters.

Every privileged mutation refreshes relevant native state and checks current
human/bot roles, configuration and target applicability. Native conflict retries
run the guard again. An approval forces a blocklist check before admission, using
the last valid cache if the download fails. Missing blocklist/XML cache pauses the
operation. Membership/permission failures never fall back to stale authorization.
Unknown remote results are reconciled before another operation is attempted.

Grace periods start on successful native prompt sending, after every page sends.
Send receipts, pending answer identifiers and job state survive process restart.
Deadline jobs revalidate current state when they run; network/OS availability can
delay execution. Owner throttle silently pauses all Gateway work for that group.
Emergency `!admin disable all` pauses Assistant automation except the existing
ACI-Blocklist reporting path; `!admin enable all` remains available for recovery.
Pauses freeze remaining time. Queued Assistant sends and Gateway media deletes
check pause state again on their transport workers. An operation already accepted
by the remote service cannot be retroactively cancelled.

DM scans aggregate all locally known groups the caller administers, reserve one
per-ACI cooldown, send cached results, then force one shared online blocklist
refresh. Later native membership-read retries do not repeat that blocklist
download. Refreshed results are rebuilt with current authorization and edited only
while the original replies remain eligible. Group scans preserve the previous
cache-first, forced-refresh, native-mention and pagination behavior.

## Code map and verification

| Component | Responsibility |
| --- | --- |
| `gateway/GatewayConfiguration.kt` | XML snapshots, defaults and safe DOM mutations |
| `gateway/GatewayCommands.kt`, `GatewayController.kt` | Argument parsing, authorization, help and private command results |
| `gateway/GatewayStore.kt` | Separate encrypted store and atomic transitions |
| `gateway/GatewayEngine.kt` | Intake, batches, decisions, reminders and cleanup |
| `gateway/SignalGatewayPlatform.kt`, `GatewayRuntime.kt` | Native Signal effects, send receipts and reconciliation |
| `jobs/Gateway*Job.kt` | Persistent command, workflow and DM scan workers |
| `groups/GatewayGroupActions.java` | Guarded native actions without bans/unbans |

Run the focused Gateway tests alongside the existing Assistant, blocklist,
Group V2 processor and incoming-message regressions. The tests cover shared and
same-group rooms, batch generations, permission loss and retry guards, blocklist
changes, manual/external resolution, pause/throttle, contact preservation, scan
refreshes/cooldowns, native mentions, media policy, restart and competing outcomes.
Live server/device verification is still needed for admission/invitation delivery,
administrator deletion, actual network conflict/retry timing and background
scheduling under Android process and power constraints.

No repository credentials, deployment XML or FCM values are included in these
examples or changed by Gateway configuration commands.

Verified on 2026-10-28: `:Signal-Android:assemblePlayProdRelease` succeeded and the
focused unit suite passed 317 tests, with no failures, errors
or skips. `git diff --check` passed. This is build/unit validation; it does not
replace the live-device checks above.

The response-template placeholder pattern escapes both braces. Android's ICU
regex engine rejects the previous unescaped closing brace even though the host
JVM accepted it; the old and corrected patterns were checked with native ICU.
The optimized release DEX was also checked for the corrected pattern and the
absence of the invalid one. The current release build configuration produces an
unsigned APK; sign it using the installation's existing key before deployment.
