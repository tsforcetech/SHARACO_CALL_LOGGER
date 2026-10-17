# Architecture and invariants

## Trust boundaries

Every business mutation uses an authenticated, App-Check-enforced callable function. Firestore client writes are denied. Company access requires an active member and active company. Employees are constrained to their UID in server reporting even if a manipulated request supplies other employee IDs. Rules separately protect direct Firestore reads. Owners manage the company; admins view reports and may manage employees only when explicitly granted permission.

Invitation tokens contain 192 bits of randomness. Only the SHA-256 hash is stored in the invitation document ID. Validation checks status, expiry, company state, employee state, optional verified email and existing company membership. Joining consumes the token and creates membership atomically. Regeneration revokes the previous invitation. QR codes contain the token, never a raw company ID.

## Collections

| Path | Purpose |
|---|---|
| `users/{uid}` | Current company link and registered FCM installation ID |
| `companies/{cid}` | Owner, profile, timezone, settings and status |
| `companies/{cid}/members/{uid}` | Role, status, consent, monitoring boundary, primary device |
| `companies/{cid}/employees/{id}` | Staff profile and invitation state, including unjoined staff |
| `companies/{cid}/devices/{id}` | Ownership, active state, permissions and measured sync health |
| `companies/{cid}/calls/{id}` | Canonical call metadata |
| `companies/{cid}/daily/{day}_{uid or ALL}` | Derived daily counters and hourly buckets |
| `companies/{cid}/daily/{id}/numbers/{hash}` | Reference counts for exact distinct numbers |
| `companies/{cid}/contributions/{callId}` | Last applied source state for idempotent aggregation |
| `companies/{cid}/audit/{id}` | Administrative audit events |
| `companies/{cid}/notifications/{id}` | Company notification feed |
| `invitations/{tokenHash}` | Single-use invite state; never directly client-readable |
| `deletionJobs/{cid}` | Resumable company deletion queue |
| `rateLimits/{key}` | Per-minute sensitive-operation counters; TTL cleanup |

Timestamps are UTC milliseconds. `callDate` and hourly buckets use the company's IANA timezone. Timezone is fixed after creation to avoid silently changing historical aggregate boundaries. Changing initial-sync policy applies to future enrollments.

## Synchronization

1. Check configured UID against Firebase Auth. An installation/account-derived UUID isolates accounts sharing a phone.
2. Refresh server retention and device authorization when reachable. Offline collection remains possible.
3. Read Android calls from the later of the monitoring boundary, retention cutoff and incremental marker minus 48 hours. Once a week rescan the retained monitoring period to reconcile older OEM changes.
4. Persist new/changed records in Room before moving the scan marker. Cursor reads run on IO dispatchers.
5. Upload at most 100 records per transaction. The server derives tenant, employee, normalization, date and call ID itself. It checks membership and primary-device state inside the same transaction as call writes.
6. Mark only acknowledged matching local versions as synced. If an OEM changes a record while an upload runs, the newer local version remains pending.
7. Record success only after all uploads and the final heartbeat succeed. Failed records remain queued; retries use exponential backoff.

ID = SHA-256(installation/account device UUID + Android call row ID + timestamp). Including the timestamp prevents collisions when OEMs recycle row IDs. The same record re-uploaded any number of times remains one document. A genuinely new installation is a new device identity; deduplication does not attempt to guess whether two independently restored devices contain the same call.

Sign-out/leave/revocation cancels work and clears local account data. Sign-out warns about pending records. An eligible call still present in Android history can be recollected when the same account registers again. No app can recover a call that Android/OEM has deleted before it was collected.

## Aggregation and reporting

A retryable Firestore trigger reads current raw state and the last contribution inside a transaction. It applies old-to-new deltas to employee/company daily counters and number reference counts. Using current source state, rather than the triggering event's data, makes retries and out-of-order events converge. Raw call data remains authoritative; summaries are eventually consistent.

Connected means incoming or outgoing with positive duration. Average duration uses connected-call talk time / connected count. Outgoing connection rate is connected outgoing / outgoing. Incoming answer rate is answered incoming / (answered incoming + missed + rejected). Unknown/withheld numbers are excluded from distinct counts. Short codes remain distinct from normalized international numbers.

Whole-day unfiltered dashboards use daily summaries and union daily hashed-number sets for exact period distinct counts. Filters requiring raw dimensions use bounded server queries. Clients receive summaries, not raw collections for analytics.

Operational limits are explicit: 92-day UI date range; at most 100 selected employees; 50 returned call rows per page; up to 2,000 scanned candidates per call page; up to 50,000 raw calls for a filtered report; up to 10,000 daily documents and 100,000 number references for a summary report. Large requests fail with a narrow-range message instead of returning misleading partial totals. Tables render 50 rows at a time; local device history loads 100 records at a time. A future warehouse/export job can handle larger analytical windows without changing call ingestion.

## Retention and deletion

Hourly maintenance removes expired raw calls in bounded batches (up to 10,000 per company per run). Triggered deltas then remove their contributions and empty summaries. A retention reduction may take multiple hourly runs for a large backlog. Local retention is refreshed before uploads so old queued rows do not permanently block the queue.

Company deletion marks the company DELETING immediately, rejects access, then a scheduled job unlinks accounts, deletes invitations and recursively removes company data. Jobs remain until successful so partial execution can retry. Personal account deletion requires recent Google reauthentication; historical business records follow company retention. Owners must delete their company before their account.

Notifications are limited to one health alert per device per day. Delivery uses data messages scoped to the intended UID; the Android service checks the signed-in account before displaying them. Feed records remain available to managers.
