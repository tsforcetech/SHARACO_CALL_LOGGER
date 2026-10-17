# Spark onboarding — 4.1.0

Target: `sharaco-call-logger`, `com.sharaco.callwebhook`. GoogleAuth.kt and google-services.json are unchanged.

## Required live deployment

Publish the repository's **firestore.rules** to the existing Firestore database and deploy **firestore.indexes.json**. This does not deploy Functions or enable billing:

```
firebase deploy --only firestore:rules,firestore:indexes --project sharaco-call-logger
```

Alternatively paste the entire rules file into Firebase Console → Firestore Database → Rules → Publish. The employee call-log date query requires the `call_logs` composite index on `uid ASC, startedAt ASC`. Existing data is not deleted or migrated. The old shared joinToken remains a legacy company field but is no longer accepted for new enrollment. New invitations must be generated in this app.

This session has no configured Firebase administrator credential; publishing to GitHub does **not** publish Firestore rules. Live device acceptance cannot be claimed before deployment. If App Check enforcement is enabled, register the debug installation token using Firebase's supported debug setup; do not disable enforcement. Debug builds use the existing signing key in trusted CI so the Google OAuth certificate remains stable. No signing key or client credential is changed.

## Data and transitions

- Atomic owner batch: `companies/{cid}` (`name, ownerUid, createdAt, joinToken`), `members/{uid}` (`uid, role: owner, approved: true, name, email, sim`), `users/{uid}/companies/{cid}` (`name, role, status: approved, updatedAt`), plus company settings in `settings/profile`.
- Email-bound employee drafts precede invitations. Codes have 128 random bits, displayed as eight groups of four hexadecimal characters. QR and typed codes use identical payloads. Six-digit codes are deliberately rejected; client-side Firestore cannot safely rate-limit guesses.
- Invitation exact-document reads require a verified matching email or company admin. Listing invitations is denied. Codes expire, can be revoked, and can be claimed once. Regenerating revokes the previous code.
- The join transaction claims the invitation, creates a `join_requests/{uid}` with `status: pending`, and creates the user's pending company index. It creates no membership. A pending user cannot read the company, calls, employees or devices.
- Admin approval atomically creates an approved `employee` membership, updates the user index, marks the request approved and links the employee record. Rules enforce every linked write using `getAfter`; no self-approval or role escalation. Changing invite email/code or revoking/expiring it prevents approval.
- Session restoration reads the legacy user-company index; role values are lowercase in Firestore and normalized only for UI. Approval listeners move employees to the dashboard. The root `/users/{uid}` document is not required.
- Dashboard/report reads use `call_logs`, `uid`, `startedAt`, `durationSec` and `type`. Reports refuse >5,000 records rather than silently truncate.

## Callable audit

All 16 previously referenced callable names have exports in `functions/src`; none are missing from source. A public endpoint probe on 18 October 2026 returned HTTP 404 for all 16 configured `europe-west1` endpoints. This establishes endpoint unavailability, not a privileged inventory of the Firebase project.

Now implemented directly in Firestore: **createCompany, saveEmployee, createInvite, inspectInvite, joinCompany, revokeInvite, getReport, listCalls**. **approveJoin** is a new direct Firestore operation, never a callable.

Still require the legacy server implementation: **registerDevice, deviceHeartbeat, ingestCalls, employeeAction, updateCompany, leaveCompany, requestDeletion, saveNotificationToken**. They are outside this onboarding correction and are not Spark-converted. Do not deploy the old Functions blindly alongside this schema: its uppercase/status-based workspace assumptions differ. The app no longer auto-calls notification registration during dashboard entry. Errors show the operation and actual `FUNCTIONS_*` or `FIRESTORE_*` code; sensitive tokens and raw authentication payloads are not logged.

Reproduce the audit: `NODE_USE_ENV_PROXY=1 node scripts/audit-callables.mjs --live`.

## Verification and device acceptance

The Firestore emulator suite exercises real Firebase client writes through these rules: atomic owner creation, absent partial data, email binding, invitation lookup/list denial, expiry, revocation, replay, pending access denial, administrator approval and approved dashboard reads, self-promotion and cross-user index denial. Kotlin tests check invitation normalization, malformed inputs, QR encode/decode round-trip and Google error recovery. CI compiles, tests, lints and renders the Android UI.

Physical-device acceptance after rules/index deployment: install CI's debug APK, log in with owner Google account, create company, add employee with their Google email, share QR/code, log in on employee device, verify/scan, submit request, confirm dashboard is blocked, approve from owner's Team screen, confirm employee reaches dashboard and cannot access another company. Repeat expired/wrong-account invitation. Actual Google OAuth and camera interaction require devices and cannot be replaced by emulator auth claims.
