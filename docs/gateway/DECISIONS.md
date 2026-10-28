# Gateway: agreed behavior before implementation

This document records the user's decisions following the pre-implementation
review. The later decisions here supersede conflicting recommendations in that
review and the original configuration-only definition of throttle. Implementation,
configuration and verification notes are in [README.md](README.md).

## Throttle and emergency pause

- Throttle disables Gateway functionality for the particular group, silently. It
  is not merely a lock on configuration changes.
- For a throttled source, stop Gateway intake, prompts, administrator notifications,
  decision actions and deadline actions. Freeze its remaining grace periods.
- Other sources using the same Gateway remain independent. A throttle on the
  Gateway room itself must prevent Gateway activity in that room.
- Only the configured owner ACI controls throttle. Group administrators cannot
  bypass it. Unrelated commands such as group scans are not disabled by throttle.
- `!admin disable all` is an emergency pause of Assistant automation, including
  Gateway and ordinary command responses. It is not a shutdown of Signal's receive,
  storage or transport machinery.
- The ACI-Blocklist reporting workflow remains operational during emergency pause.
  The owner recovery control `!admin enable all` must remain reachable.
- The pause must be checked again before delayed actions; already queued work must
  not bypass it. Frozen work remains pending rather than being treated as denied.

## Validation and cached data

- Attempt current ACI-Blocklist and XML configuration checks.
- On refresh failure, use the last valid cache. Pause the affected operation only
  if the required cache does not exist.
- This fallback applies only to the blocklist and XML configuration. It does not
  authorize using stale Signal membership or administrator roles for mutations.
- Revalidate relevant Group V2 state, human authorization, bot permission and
  request applicability immediately before privileged actions and on conflict retries.
- Preserve the existing cache-first scan response followed by a forced background
  blocklist check on every successful scan. A DM multi-group scan uses one forced
  blocklist refresh per invocation, not one per group.

## Separate database and retention

- Gateway tracking must use a separate physical database with its own schema and
  migrations. Do not add Gateway tables or migrations to the main Signal database.
- Use encrypted local storage. No external tracking service is required.
- The Gateway group chat is the human-visible audit trail. Internal tracking exists
  only for reliable ongoing work, restart recovery, concurrency and cleanup.
- Retain only what remains necessary: active source-request generations, session
  membership ownership, outstanding prompts/batches, stable request mappings,
  operation claims and unresolved outcomes, deadlines/pause state, pending outgoing
  work, and unexpired cooldowns.
- A manually finished operation needs a minimal suppression marker while its old
  source request still exists or its generation cannot safely be reconciled.
  Otherwise rediscovery after restart could re-add the applicant against the
  user's explicit instruction.
- A confirmed terminal operation can be pruned after its outstanding audit/send
  and cleanup work is complete and no suppression reference is needed. Do not keep
  a permanent local applicant/action history merely for auditing.
- Remove expired cooldown records. Internal request IDs are never reused.
  Displayed request numbers are local to each applicant's session in each Gateway:
  start at 1, remain stable while any of that applicant's reviews remain active,
  and reset after all finish. Preserve existing active legacy mappings. Reject
  commands received before the current review was created when numbers are reused.
- Do not copy whole chat histories, profile keys, attachments, credentials or full
  group snapshots into Gateway tracking.
- Existing Signal tables remain available through supported read/transport APIs;
  separate storage avoids Gateway schema coupling, not every integration merge.

## Screening and membership semantics

- Current source-group administrators can approve active requests before any
  screening response, prompt or Gateway admission. Retain all current role,
  permission, configuration, request-generation and blocklist checks. Screening
  is required for automatic review notification, not for an administrator's
  explicit approval. Completed/manual-finished reviews remain closed.
- Same-group Gateway is accepted as ordinary admission before screening. Accepting
  consumes the native pending request. Signal has no quarantine member role.
- Require source administrative action enabled and current bot administrator
  permission for same-group mode.
- In that mode approval completes local review; denial, blocklist rejection and
  unsatisfied timeout remove the provisionally admitted member when authorized.
- With `admin=0`, never mutate source-group membership or pending requests. Screen
  and notify as applicable; describe remaining manual action accurately. Local
  timeout completion does not mean the native request was denied.
- Allow invitation fallback if direct Gateway addition is unavailable. Wait for
  confirmed full membership before sending a screening prompt.
- Provide a configurable admission/invitation wait timeout in Gateway configuration,
  initially 96 hours, separate from the prompt-response grace periods.
- Do not automatically unban applicants. Gateway denial/removal does not create
  a native group ban or append to the global ACI-Blocklist.
- Preserve members who were already in Gateway before the workflow. Automatic
  separate-room cleanup removes only membership created by Gateway automation and
  only when no other active request requires that membership.

## Departures and generations

- Stop handling after any unplanned Gateway departure, whether known manual removal,
  voluntary leave or unknown cause. Cancel/disable associated work, keep the reason
  where known, and perform no source-group action because of that departure.
- Do not re-add for the same old native source request.
- A genuinely new source request may start a new Gateway operation. Missing history
  or an uncertain snapshot difference is not sufficient proof of a new generation.
- Correlate bot-initiated cleanup with durable operation state. Do not label an
  uncertain departure as a proven human administrator removal.

## Shared rooms, answers and media

- Shared-room visibility of subsequent screening and audit activity is accepted.
- Display known memberships only within the agreed configured public/source group
  set; never imply knowledge of all Signal groups a person belongs to.
- Reviewing administrators join Gateway themselves; do not automatically add them.
- Use ACI and native mention metadata for applicant identity and decision commands.
- Count new nonempty text from the applicant, including replies that quote the
  prompt or any other message. Count only the applicant's own text, never the
  quoted content. Exclude commands, media and captions, and edits. Administrator
  status in some other group does not disqualify an applicant's own answer.
- One reply satisfies the current outstanding prompt batch; a later prompt requires
  another reply. Keep numbers stable and notify only newly satisfied requests.
- The Gateway room's own media setting is authoritative for that room. Enqueue
  best-effort administrator deletion immediately, subject to Signal permissions and
  deletion limits. Failed deletion does not automatically remove the applicant.
- Native link-preview messages count as media, with or without a thumbnail. Delete
  the entire preview message when the room policy is enabled for an active
  applicant; never count it as a screening answer. Plain URLs without previews
  remain normal text.
- Expose `!gateway deletemedia enable/disable` to current Gateway-room
  administrators before source processing is enabled. Show its state and scoped
  controls in Gateway status. Configuration writes keep two-space structural XML
  indentation while preserving authored text and response formatting.

## Time, DM responses and cooldowns

- Retain event-driven join detection and add a configurable source-state refresh
  fallback, initially 30 seconds between passes (`joinpollseconds` in universal
  defaults). Use persistent, bounded background work; skip pause/throttle, avoid
  routine GitHub/blocklist downloads, and respect server/network/Android delays.
  This is request discovery, not a continuous permission/blocklist cache poll.
- Initial prompt-response defaults are reminder 72 hours and removal 96 hours.
- Start grace periods on successful prompt sending, not merely local queuing and
  not an assumption that the applicant received/read the message.
- Snapshot effective durations per request. Freeze remaining time during pause and
  resume the remaining period instead of immediately acting on elapsed paused time.
- Gateway configuration and group-ID responses use a scoped non-contact DM path
  that preserves contact/profile-sharing state. Do not add/remove system contacts
  or hide recipients as cleanup.
- Do not send a routine in-group acknowledgement. A minimal configurable DM-delivery
  failure notice may be used. Throttled Gateway attempts remain silent.
- Initial DM scan cooldown is 15 minutes per caller ACI. Initial group scan cooldown
  is zero, configurable per caller/group. Reserve cooldowns atomically, consume them
  on the first successfully sent result, and release failures before any result.

## Implementation constraints retained from the review

- Use persistent background jobs and bounded concurrency. No UI/receive-thread
  networking, long-lived sleeping threads, or blocking database transactions.
- Only one terminal decision may win per source-request generation. Unknown remote
  outcomes must be reconciled before retrying, not converted into opposite actions.
- Use the designated primary bot instance for Gateway mutations.
- Keep owner ACI authorization separate from source and Gateway administrator roles.
- Keep ordinary exact XML trigger matching unchanged; use dedicated argument-aware
  handlers and context-specific help rendering.
- Preserve safe latest-file/SHA XML publication, conflict revalidation, unrelated
  configuration, existing FCM values and existing blocklist reporting boundaries.
- Keep the proposed owner Gateway status command out unless explicitly requested.
