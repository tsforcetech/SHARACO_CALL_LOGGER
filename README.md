# SHARACO Call Logger — Android Firebase MVP

Single Android application for company owners and employees, with Firebase Email/Password Authentication and Cloud Firestore.

## Included in current debug builds
- Create company as owner, show company QR, scan QR and submit join request with claimed company SIM number
- Owner approval of joining employees
- Basic owner team management and admin-to-employee UID assignments
- Opt-in Android READ_CALL_LOG and READ_CONTACTS requests
- Local SQLite call history with duplicate prevention per device call-log ID
- Periodic WorkManager scan (minimum interval ~15 minutes, not instant) and Firestore uploads
- Local call history, basic company call list and summary by call type/duration
- Company-scope Firestore rules in [firestore.rules](firestore.rules) (deploy manually in Firebase Console)
- GitHub Actions debug APK build

## Not production ready
- Not device tested. Android may deny restricted call-log access on non-default phone apps.
- Android normal call history may not reveal the actual own SIM number or which of two SIMs handled a call. Employee-supplied SIM values are not independently verified.
- Reporting is currently a capped preview (1000 calls), not complete analytics/exports.
- Assignments currently managed through raw Firebase user UIDs; needs friendlier administration and stronger validation.
- Automatic background scan is periodic and may be delayed; user must grant call-log access.
- QR join token rotation/expiry and rate-limiting need a trusted backend/service; static QR token in MVP is long-lived.
- Multi-company membership discovery is incomplete; users can return to last joined company.
- Never distribute the debug APK as a production enterprise release.
- Firestore rules must be deployed and tested in Firebase before uploading live call data.
- Collect work-related call metadata only with clear employee consent, lawful basis and retention controls.

## APK
Open GitHub Actions > Build Android APK > most recent successful run > Artifacts > CSM-Call-Webhook-debug-APK. Extract the ZIP to install the APK.

## Firebase
Package ID: com.sharaco.callwebhook
Project ID: sharaco-call-logger

Do not set Firestore rules to allow read/write for all users.
