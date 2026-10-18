# Phase 2 — Spark architecture and development status

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
