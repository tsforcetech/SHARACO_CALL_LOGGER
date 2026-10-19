# Android 4.3.0-dev (107)

Additional screen: More/Profile → Targets & lead reports. Administrators can also open it from the existing Reports screen. Existing staff reports, call history, authentication and sync are preserved.

- Live dated call/outcome totals and employee performance, CSV/PDF export.
- Current assigned workload and status breakdown, searchable paginated leads, administrator reassignment with approved-member and optimistic-version checks. Active claims block reassignment; historical outcomes and follow-ups retain their original employee attribution.
- Daily, weekly or monthly dated targets shared with the web app. Full-period progress, percentage and remaining goal. Targets are individual periods, not recurring schedules.
- Follow-up list and CRM outcome history. Manual outcomes do not count as verified calls.
- Employee queries are restricted to their UID. Firestore rules remain authoritative and unchanged.
- Server-confirmed complete data required for totals; cached/pending data and 10,000-record saturation withhold totals. Narrow the dates or employee filter for large workspaces.

Deploy the existing firestore.indexes.json to sharaco-call-logger if not already deployed, especially crmCallAttempts(employeeUid ASC, createdAt ASC). Existing published CRM rules are required. No Cloud Functions or billing upgrade is introduced.

Verification: run Android unit tests, lint, debug build and existing rules tests in GitHub Actions. Real-device Google sign-in, live Firebase membership and phone sync require device acceptance testing. The new screen does not change those implementations.
