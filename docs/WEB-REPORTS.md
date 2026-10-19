# Web call reporting update

Dashboard, Call history, Call reports, and Targets use live Firestore listeners.
Device history comes from the Android `call_logs` collection, not CRM attempts.
Employees query their own UID; owners/admins can filter the company by employee.
Reports include date filters, type and contact search, 50-row display pages,
CSV export, per-employee call totals, connection counts, talk time, missed calls,
and CRM interested/converted outcome counts.

Targets are created by admins for approved members, using the existing protected
targetDefinitions create rule. Daily, weekly and monthly targets cover one dated
period, not an automatically recurring schedule. Times use the creator's browser
timezone, stored with the target. Exact start/end timestamps determine progress.
The target table asks to load the full target period before displaying achievement.
Targets without dates from older versions are not silently interpreted.

Manual CRM outcomes count as outcomes; they are not verified connected phone calls.
Connection is inferred from incoming/outgoing device records with positive duration.
Web reporting cannot show calls that have not yet synced from Android.

Deploy the added employee outcome index from the repository root:

```sh
npx firebase-tools deploy --only firestore:indexes --project sharaco-call-logger
```

Keep existing indexes when prompted. The added composite index is COLLECTION scope:
`crmCallAttempts`: employeeUid ASCENDING, createdAt ASCENDING.
The existing call_logs index (uid ASCENDING, startedAt ASCENDING) is also required.
No rule changes are required beyond the previously supplied CRM rules.

Reports allow at most 93 days and fetch at most 10,001 records per collection.
At that threshold totals are suppressed and the user must narrow their filters.
Firestore listener reads count against Spark quotas; large team reports can consume
substantial reads. Historical periods update live while the page is open.

Verification: static web build/typecheck and metric unit test. Production sign-in,
membership and call/target data must still be checked using real company accounts.
