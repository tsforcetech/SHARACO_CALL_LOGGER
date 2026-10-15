# Local history and webhook sync specification

Status: specification only; not yet implemented or compiled.

## Core storage
Keep a Room (SQLite) database within the Android app. Every detected call event is saved locally before a webhook POST is attempted. Store:
- event_id (stable UUID, used for idempotency)
- Android call-log row ID where available
- from_number and to_number (employee's own SIM number configured in app)
- remote_number and contact_name (nullable)
- direction/type (incoming, outgoing, missed, rejected, blocked, other)
- started_at, duration_seconds, employee_code, SIM identifier where accessible
- selected_webhook_url snapshot, sync_status (PENDING, SYNCED, FAILED), last_http_status, retry_count, last_attempt_at

## App screens
- Call history (newest first, searchable by number/contact)
- Filters for call type, date and sync status
- Call details including retry information
- Settings for employee code, own SIM number, URL per event, and consent controls
- Retry failed and pending events button

## Sync semantics
- Preserve locally even if internet is offline.
- Retry with exponential backoff when network is available.
- Do not delete successful call rows automatically.
- Send stable event_id with each POST; server should deduplicate by ID.
- Never change already-queued webhook destinations when configuration changes.
- Do not silently access contacts or call history; use explicit Android runtime permission prompts.
- Stop logging/syncing when employee turns off monitoring.

## Limitations
Android call-log permissions and background service rules vary by Android version. App may require default dialer role or special approval and may not operate reliably as a non-default dialer on some devices. Own SIM number is not reliably available automatically. Local app history is removed when app data is cleared or app is uninstalled unless exported/backed up separately. No call recordings in this scope.
