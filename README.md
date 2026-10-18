# CallTrack Pro

A native Android application for transparent business call-metadata monitoring, employee onboarding and company reporting. Kotlin, Jetpack Compose Material 3, Hilt, Room, DataStore, WorkManager and Firebase.

## Current release: 4.1.2 background sync and live dashboard

The complete Kotlin source replaces the old Java app on `main`. Company creation, employee invitations, join requests, administrator approval and dashboard reads now use direct Firestore with the legacy field names. **Publish the new rules and indexes before installing**; GitHub does not deploy Firebase automatically. See [Spark onboarding, callable audit and acceptance instructions](docs/SPARK-ONBOARDING.md). Google authentication and Firebase client configuration are preserved. Device registration, replacement, heartbeat and call uploads now also use direct Firestore. Publish the 4.1.1 rules even if you published the 4.1.0 rules earlier.

This is not a complete conversion of every feature to Spark: advanced employee actions, company settings edits, deletion/leave and notification registration still require the original server operations. The older architecture/deployment sections below describe that server design and must not be interpreted as instructions to enable billing or blindly deploy Functions with the Spark schema.

4.1.2 adds a persisted call-history content trigger that schedules sync without an open Activity, with periodic/reconnect reconciliation retained. Dashboard and call lists refresh automatically from Firestore change notifications. No additional rules/index changes are needed beyond 4.1.1. Android battery restrictions can delay work; force-stop requires reopening the app.

## Existing configuration preserved

- Application ID: `com.sharaco.callwebhook`
- Firebase project: `sharaco-call-logger`
- `app/google-services.json` is preserved byte-for-byte from the original project.
- Version: `4.1.2` / code `105`, above the original version code `31`.
- Existing GitHub signing secrets remain in repository settings. The release workflow references `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. No secret values are copied into source.
- The old Java implementation remains recoverable in Git history. This source update does not delete existing cloud data or deploy Firebase automatically.

## Build

Use Android Studio with Android Gradle Plugin 9.4 support, JDK 17, SDK platform `android-37.0` and build tools `37.0.0`. Minimum Android API is 26; target API is 37. The Gradle wrapper pins 9.6.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

For a signed release, use the **Build signed release** GitHub Actions workflow on this branch. It uses the existing signing-secret names and publishes APK and AAB artifacts. For a local signed build, set `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, then run:

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

The debug build uses the standard debug signing key and cannot replace an installed production APK signed with the original key. Use the signed release for upgrades. Never remove an app containing pending records before synchronizing them.

## Firebase setup and rollout

The client config is present, but a client JSON file is not an administrative deployment credential. A Firebase project administrator must perform the following in the existing project:

1. Enable Google sign-in in Firebase Authentication. Confirm the Android app has the **debug and release SHA-1 and SHA-256** fingerprints. Keep the web OAuth client in `google-services.json`; Credential Manager uses the generated `default_web_client_id`.
2. Enable Firestore, Cloud Functions v2, Cloud Scheduler and billing required by those services. Functions use **europe-west1**; Android calls the same region.
3. Register the Android app with App Check. Debug builds use the debug provider; register the debug token printed by Firebase in Logcat. Release uses Play Integrity. Configure integrity requirements for the intended enterprise/internal distribution channel. All callable functions enforce App Check.
4. Deploy indexes, functions and security rules during a coordinated rollout:

```sh
npm ci --prefix functions
npx firebase-tools@14.12.0 login
npx firebase-tools@14.12.0 use sharaco-call-logger
npx firebase-tools@14.12.0 deploy --only firestore:indexes,functions
# Wait for indexes to finish building before switching clients.
npx firebase-tools@14.12.0 deploy --only firestore:rules
```

5. Enable/confirm Crashlytics and Firebase Cloud Messaging for the existing app. No Analytics collection is added.
6. Register one owner and one employee on test devices. Complete the device acceptance checks in [docs/TESTING.md](docs/TESTING.md) before distributing broadly.

**Migration:** This is a fresh application schema, not an in-place conversion of the legacy collections. New rules deny legacy client writes. Back up the old Firestore database, schedule the client/rules cutover together, and enroll users through the new invitation flow. Existing historical cloud data is not deleted by building this branch. Do not deploy these rules while legacy clients must remain operational. `firebase deploy` may ask about obsolete functions; review those separately instead of deleting them indiscriminately.

## Features

- Google sign-in; owner company setup; owner, admin and employee roles.
- Expiring, revocable, single-use employee invitations with QR and manual code; optional Google-email binding; explicit monitoring consent.
- One primary device per employee; replacement revokes upload permission on the old device.
- Read-only Android call metadata. All known call types and a safe unknown fallback. No microphone, audio, contacts, SIM/OTP verification or direct-call permission.
- Initial collection starts at the enrollment day's midnight in the company timezone, or exact join time according to company policy.
- Durable Room upload queue; deterministic call identities; confirmation only after backend acknowledgment; periodic, foreground, manual, content-change and connectivity-triggered synchronization.
- Company and personal dashboards, real charts, date and multi-employee filters, sortable staff tables, paged call history and separate local/synced histories.
- CSV and PDF exports through Android Share Sheet; staff exports cover the selected report, call exports explicitly cover loaded rows. CSV formula injection is neutralized. No storage permission is needed.
- Device health, permissions, queue counts, actual WorkManager state, owner notifications, audit events, retention, employee status/role management, company and account deletion.
- Light, dark and system themes; screenshot-only fixtures are excluded from APKs and production data.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for data ownership, synchronization invariants, aggregate consistency and query limits.

```
app/src/main/java/com/sharaco/callwebhook/
  domain/   Shared models, filters, time formatting and identity
  data/     Firebase repository, Google auth, Room, preferences, DI
  sync/     Call-log reader, serialized engine, workers, notifications
  ui/       ViewModel, feature screens, charts, tables, exports and theme
functions/src/
  core.js         Auth, tenant access, validation, throttling, audit
  admin.js        Companies, employees, roles, invites, deletion
  sync.js         Device policy, call ingestion, idempotent aggregation
  reports.js      Bounded reports and cursor pagination
  maintenance.js  Retention, deletion jobs and health notifications
```

## Tests

```sh
npm ci --prefix functions
npm test --prefix functions
npx firebase-tools@14.12.0 emulators:exec --project demo-calltrack --only firestore 'cd functions && npm run test:rules && npm run test:integration'
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Use only `demo-calltrack` for emulator tests. The integration suite refuses to run without `FIRESTORE_EMULATOR_HOST`; callable `.run()` in tests checks application authorization but does not test the App Check transport layer.

Native Compose previews can be rendered without a physical device:

```sh
./gradlew :app:updateDebugScreenshotTest
```

Reference images are generated in `app/src/screenshotTestDebug/reference`. Preview fixtures live under `src/screenshotTest`, not production. Review changed images before accepting them.

## Operational behavior

WorkManager has a minimum periodic interval of 15 minutes. Battery optimization, Doze, manufacturer restrictions and force-stop can delay synchronization. There is no hidden endless service or invented “service running” flag. Reopening the app schedules catch-up work. The device screen exposes actual permission, pending queue, last success, last error and worker states.

Android does not reliably identify which calls are personal. Use dedicated business devices and disclose that all eligible call metadata within the monitoring period is synchronized. The app collects no audio. Company owners control retention; removing membership does not silently destroy historical business reports.

The application requests restricted `READ_CALL_LOG` access. Distribution must follow the selected channel's requirements; public Google Play approval is not assumed. See the official [Call Log permission policy](https://support.google.com/googleplay/android-developer/answer/10208820) and [WorkManager periodic work documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Rebranding

Change `app_name` and `tagline` in `app/src/main/res/values/strings.xml`, the vector in `res/drawable/ic_brand.xml`, and semantic colors in `ui/Components.kt`. Keep the application ID and signing key when upgrading existing installations.

## UI and authentication update (4.0.1)

The native screens follow the supplied [mobile UI reference](docs/ui-reference.html): purple summary cards, compact metric tiles, rounded surfaces, a white/purple navigation bar, and the illustrated Google welcome screen. Preview-only values never enter the APK.

Google credential selection, Firebase authentication, and workspace loading are separate failure stages. Sign-in errors persist on the welcome screen; an authenticated user whose workspace cannot load gets a retry/switch-account screen instead of a misleading login failure. A 25-second workspace timeout prevents an indefinite spinner. The app retains security checks and never bypasses authentication, Firestore rules, or App Check to hide an error.

The signed APK artifact includes `signing-fingerprints.txt`. Check this against Firebase Project settings and Google OAuth when diagnosing login. Verify Google sign-in is enabled and App Check is configured for the actual distribution channel. A successful APK build alone cannot confirm these live project settings.

### Google chooser recovery (4.0.2)

Some Google credential providers return a cancellation after account selection because consent or provider setup did not complete. Version 4.0.1 discarded this result silently. Version 4.0.2 displays it immediately, offers an explicit second Credential Manager account-selector flow (all accounts, auto-select disabled), shows the authentication stage, and bounds provider/network waits. It never retries cancelled consent automatically. The live Google provider authorization-URI endpoint accepted the preserved client configuration on 17 October 2026; this does not prove successful authentication on a particular device.

The 4.1.0 correction converts onboarding and dashboard reads to Spark, as documented at the top. Other server operations remain outstanding.

## Phase 2 development — Firebase Spark retained

Phase 2 now uses Firebase Spark directly with a static `web/` workspace on Firebase Hosting. No external Node server is required. The earlier `server/` prototype is retained as reference only. Foreground web-to-Android calling is in development version 4.2.0-dev; full Phase 2 is not complete. See [architecture, verified scope, remaining work and deployment prerequisites](docs/PHASE2-SPARK.md).
