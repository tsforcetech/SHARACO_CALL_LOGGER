# QR company onboarding

Status: specification only; not implemented or compiled.

1. User signs in to the single Android app and creates a company.
2. The company owner displays a QR code with a random, revocable, expiring join token (not privileged credentials or a raw company ID alone).
3. The employee signs in, scans the code within the same app, sees the company name, and enters their company SIM telephone number in international format.
4. The app submits a pending membership request with employee Firebase UID, company ID, claimed SIM number, and consent acknowledgement.
5. Owner approves or rejects from the same Android app, optionally assigns role and permissions, and confirms SIM ownership. The QR grants no read/write access itself.
6. Once approved, employee explicitly enables device call-log permissions; logs are saved to local Room storage and uploaded to the authorized company Firestore path.
7. The owner can rotate or revoke join QR codes. Rate limits and trusted verification of join tokens are required to prevent brute-force misuse; Firebase Cloud Functions is an option.
8. Number input is not proof of ownership. Avoid attributing calls to claimed SIM before approval and device/SIM mapping validation; dual-SIM support requires explicit SIM mapping.
9. All read/write access and reports must be company-scoped and enforced by Firestore Security Rules.
