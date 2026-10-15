# Company joining with six-character code

Status: specification, not yet implemented.

- Owner creates a company in the single Android app.
- Company generates a unique six-character uppercase alphanumeric joining code, e.g. A7K2M9.
- Users sign in and enter the code to submit a membership request. No email invitation is required.
- Code never grants immediate access to company data. Owner must approve before granting employee membership.
- Owner can copy, rotate, revoke and expire codes within the same app.
- Store codes as opaque, non-sequential identifiers, prohibit client editing, apply Firebase Rules and server-side enforcement for lookups and membership approval.
- Prevent brute-force enumeration with App Check, rate limiting and short code expiry; a trusted server-side Firebase function or equivalent is recommended for secure code redemption and authorization even though there is no custom server to maintain.
- Admin permissions and access to company call history are granted only after explicit owner assignment.
- User may join multiple companies; all call data must carry a company ID and be isolated by database rules.
