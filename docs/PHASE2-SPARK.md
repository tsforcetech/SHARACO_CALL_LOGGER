# Phase 2 — Spark architecture and development status

## Current decision: Firebase only, no separately hosted server

The user accepted foreground Android availability for web calling on 18 October 2026. This supersedes the external Node architecture below. `web/lib/spark.ts` now talks directly to Firestore and the web exports static files for Firebase Hosting (`web/out`). The `server/` prototype remains in source for reference and is not used by the web or Android runtime. Do not deploy it or Cloud Functions for the new flow.

The current development update adds:

- Direct Firestore company-scoped CRM reads, campaign creation, disposition setup, lead claims, atomic outcomes/follow-ups, and retry-safe Save & Next.
- Native Android assigned-lead queue (up to 200 loaded records), user-initiated dialer, outcome entry, follow-up scheduling and Save & Next using the same atomic protocol.
- Live first-page table updates with Firestore listeners. Pagination explicitly loads additional records.
- Browser Excel/CSV import (template headers, 1,000-row/5 MB limits, preview, invalid-row download, equal staff distribution, skip existing company phone numbers). Keep the page open. This is not yet the full column-mapping/campaign wizard.
- Foreground Android web calling, explicitly enabled by the employee. Only the active registered device is accepted. Readiness expires after 80 seconds without heartbeat; pairing expires within eight hours. Each phone request expires after 55 seconds and requires a user tap on Android. Consumed, expired, wrong-number and revoked-device requests fail security rules.
- No FCM sender, Cloud Functions, hidden dialing or external API URL dependency.
- Updated Firestore rules and a callRequests composite index. Existing Phase 1 rules and schema remain intact.
- Android development version `4.2.0-dev`, code 106. This is not a complete Phase 2 release.

Deploy prerequisites: publish the updated rules and indexes, configure the existing project's Firebase web app public config and App Check site key, build the static web app, then deploy **Hosting only**. Authenticate Firebase CLI through the normal account workflow; never paste service-account secrets into the web app or chat.

```sh
npm ci --prefix web
npm run build --prefix web
firebase deploy --project sharaco-call-logger --only firestore:rules,firestore:indexes,hosting
```

Wait until the new index is ready. On the registered Android phone, sign in and tap **Enable web calling**. Keep the app visible. In the same account/company on web, open your assigned lead and send the call request; confirm **Open dialer** on the phone. Phone request status is not proof of a completed/connected call.

Still pending: native queue pagination/session restoration, verified CRM call matching, comprehensive target management/analytics/reports, full import wizard, follow-up completion/conflict workflow, offline outcome queue, manager scope expansion and physical-device acceptance testing. Google login was preserved in source; it has not been interactively tested in this development update. Do not claim production readiness.

The historical architecture and foundation notes below describe the earlier Node prototype, not the current deployment requirement.

Decision: 18 October 2026. Keep `sharaco-call-logger` on Firebase Spark.

## Architecture

- Native Kotlin Phase 1 remains unchanged, including package `com.sharaco.callwebhook`, Google OAuth identity and call synchronization.
- `web/`: Next.js client using the existing Firebase Auth account and `users/{uid}/companies` membership index.
- `server/`: separately hosted Node.js API using Firebase Admin SDK. This is **not a Cloud Function** and must not be deployed with `firebase deploy --only functions`.
- Same Firestore database. Additive CRM collections under `companies/{cid}`. Existing documents and security rules are unchanged.
- Client CRM writes remain denied by the existing default-deny rules. All new access currently uses the authenticated API; the API checks company membership itself because Admin SDK bypasses rules.
- Firebase Admin credentials exist only on the server. Never put a service-account key in the Android app, web bundle, repository or chat.
- Spark quotas still apply to database use. External hosting has its own operating costs. This architecture does not make Firebase limits unlimited.

## Implemented development foundation (not a production Phase 2 release)

Server:
- Revocation-checked Firebase ID token authentication; mandatory App Check in production; explicit CORS origins, JSON body limits and per-process rate limiting.
- Membership re-read inside each mutation transaction. Pending/revoked/suspended users denied; owner/admin checked for administrative mutations.
- Idempotency ledger rejects reuse of a key for a changed payload.
- Default disposition initialization preserves existing definitions.
- Campaign creation/update.
- Up to 1,000 normalized lead rows per durable import job; row errors, file duplicates, skip/update company duplicate policies; existing outcome and assignment history preserved on update.
- Equal/round-robin and weighted distribution to approved employees; inactive assignees reported as failed rows.
- Twenty-row transactional import chunks, safe concurrent workers, resumable progress and immutable import activities.
- Lead claims with expiry and optimistic version checks.
- Transactional manual disposition/attempt/activity/follow-up creation; explicit UNVERIFIED state, never false connected-call evidence.
- Daily employee CRM counters with unique first interested/conversion milestones per employee and lead. Daily unique attempts use the attempt day. These are not yet the full period report engine.
- Immutable employee target definitions; overlapping effective dates for the same metric/period rejected. Daily/weekly/monthly remain independent.
- Paginated reads with employee ownership filtering. Manager cross-team read expansion remains pending; current behavior is restrictive.

Web:
- Google authentication, company selection, live membership revocation handling.
- Responsive sidebar, real paginated records, campaign creation, default-outcome setup.
- Lead workspace, outcome controls, follow-up date, retry-safe Save & Next.
- Visible connection and backend errors. No fake data or fabricated phone-call status.

## Remaining work — do not describe Phase 2 as complete

- Full Excel/CSV upload wizard, template, mapping, row error download, campaign-link duplicate policy and bulk reassignment UI.
- Android CRM workspace, offline disposition queue and conflict reconciliation.
- Device pairing and FCM delivery; short-lived single-use commands, acknowledgement and supported Android call action.
- Deterministic actual-call matching and correction handling.
- Custom status/tag management; complete follow-up lifecycle; configurable queue ordering and persisted session restoration.
- Manager team/campaign scope configuration and reports.
- Target templates, team/company/department/campaign scopes, edit history and recalculation policy.
- Aggregate company/campaign reports, time-window distinct metrics, all filters and exports, realtime CRM dashboard updates.
- Suppression/calling hours, retention, deletion, production abuse controls, complete migration/rollback validation.
- Physical-device Google sign-in, cellular calling, notification delivery and end-to-end acceptance scenario.

Existing Android 4.1.2 is still the last delivered APK. No Phase 2 APK is ready.

## Local validation

Use Node 22 and Java 21 (Firestore emulator).

```sh
npm ci --prefix server
npm test --prefix server
npx firebase-tools@14.12.0 emulators:exec --project demo-calltrack-crm --config firebase.crm-emulators.json --only firestore 'node --test server/test/service.integration.js'
npm ci --prefix web
npm run build --prefix web
```

Integration tests are hard-guarded against running on production. The emulator tests use synthetic records only and delete them afterward.

## Deployment prerequisites (configure when production features are complete)

Node host:
- HTTPS API domain and a process supervisor/container restart policy.
- Outbound access to Firebase Auth, Firestore, App Check and later FCM.
- `GOOGLE_CLOUD_PROJECT=sharaco-call-logger`
- `GOOGLE_APPLICATION_CREDENTIALS` pointing at a protected server-only credential file, or an appropriate workload identity.
- `REQUIRE_APP_CHECK=true`
- `WEB_ORIGINS=https://your-web-domain` (comma-separated exact origins)
- `PORT=8080`

Web build-time variables:
- `NEXT_PUBLIC_FIREBASE_API_KEY`
- `NEXT_PUBLIC_FIREBASE_APP_ID` from a Firebase **web** app in the existing project
- `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` (defaults to existing project's firebaseapp.com domain)
- `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`
- `NEXT_PUBLIC_CRM_API_URL=https://your-api-domain`

Authorize the web domain for Google sign-in in Firebase Authentication. Register the web app with App Check. Do not replace Android `google-services.json` or signing secrets.

The worker's collection-group query requires an ascending collection-group single-field index on `importJobs.status`; the additive definition is in `server/firestore.phase2.indexes.json`. Merge it with current indexes when deploying, rather than replacing existing call-log indexes. No new client CRM allow-write rule is required for this API-only foundation.

## Rollback

Stop the Node/web services to disable the new CRM foundation. Phase 1 keeps using its existing Firebase paths and rules. No destructive migration or backfill has run. Keep new CRM data for recovery; do not delete collections as a routine rollback. Revert the source commit if necessary. Android signing, Firebase project, auth accounts and Phase 1 records are unaffected.
