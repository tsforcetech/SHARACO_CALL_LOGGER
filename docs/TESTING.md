# Verification and device acceptance

## Automated coverage

- Domain tests: centralized metrics, empty reports, phone normalization, short/withheld numbers, company midnight and DST boundaries, exact joining time, deterministic identities, intersecting filters, health states.
- Firestore emulator rules: employee reads own calls; other employee and cross-company reads denied; privilege escalation/raw writes denied; invitations hidden; owner reads company calls.
- Emulator integration: create company and employee, email-bound invite, wrong-account rejection, consume invite, reuse rejection, employee privilege rejection, register/replace devices, repeated uploads, aggregate retries, duration corrections, exact report counts, cross-company access and deactivation.
- Kotlin unit tests: identity collision prevention, unknown call types, connection proxy, timezone day boundaries.
- Build checks: debug APK, unit tests and Android Lint through GitHub Actions. Native preview rendering checks dashboard light/dark, larger text, sign-in and report components.

## Before production distribution

These checks require real Android devices and the configured Firebase project. Emulator function `.run()` tests do not prove Google sign-in, App Check, OEM call-log behavior or battery scheduling on a physical phone.

1. Register debug/release signing fingerprints and the appropriate App Check provider. Confirm Google sign-in with owner and employee accounts.
2. Create a company in the intended timezone. Add an email-bound employee and scan the QR on another device. Test invalid, expired, revoked and consumed codes.
3. Read the disclosure, deny Call Log permission, retry, then grant it. Test permanently denied permission through App Settings. Verify camera is requested only for scanning and microphone/contacts/SIM permissions are absent.
4. Create incoming, outgoing, missed and rejected calls. Verify no calls before the enrollment boundary are uploaded. Test enrollment near midnight and a DST timezone.
5. Disable internet, create calls and confirm Room pending count increases. Restore network; verify acknowledgments clear only confirmed records. Repeat manual sync and confirm report totals do not duplicate.
6. Replace the primary device. Confirm the old device cannot upload. Revoke permission after enrollment and verify warning/health behavior. Test sign-out, another account on the same installation and re-login.
7. Close, reboot and force-stop the application. Understand WorkManager recovery versus force-stop: reopen after a force-stop. Test manufacturer battery policies on actual fleet devices.
8. Verify employee isolation using both app UI and direct rule tests. Deactivate, suspend, remove and promote/demote staff while they are signed in.
9. Check report filters together, multi-employee selection, company timezone, exact phone search, duration sorting, cursor pagination, chart drill-downs and zero-result states.
10. Export CSV/PDF, open them on another app and verify columns and row counts. Call exports cover loaded rows; staff exports cover the selected complete staff report.
11. Check 360dp and larger screens, light/dark mode, 130–200% font scaling, keyboard in forms, back navigation, loading, offline retry and destructive confirmations. Check talk-time values and long employee names for clipping.
12. Test company deletion only in a disposable test company. Confirm access stops immediately and maintenance removes the nested collections. Never run destructive acceptance tests against an operating company.

Production Firebase deployment and real-device acceptance are separate from a successful source build.
