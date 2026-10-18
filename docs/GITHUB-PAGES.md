# Caller Pro web on GitHub Pages

This deploys a static browser app. No Node.js server, Cloud Functions deployment,
Firebase billing upgrade, or Android authentication changes are required.

## One-time setup

1. In Firebase project `sharaco-call-logger`, open Project settings → Your apps.
   Select or register a **Web** app. Copy its public `apiKey` and `appId`.
   Do not use an Android app ID or upload a service-account/private key.
2. In repository Settings → Secrets and variables → Actions → Variables, add:
   - `NEXT_PUBLIC_FIREBASE_API_KEY`: the web configuration's `apiKey`.
   - `NEXT_PUBLIC_FIREBASE_APP_ID`: the web configuration's `appId` (`1:…:web:…`).
   These are public browser configuration, not server credentials. The workflow
   also accepts the same names as Actions secrets for existing configurations.
3. Firebase Authentication → Settings → Authorized domains: add
   `tsforcetech.github.io`. Keep existing Android and Firebase settings unchanged.
4. If Firestore App Check enforcement is enabled, register the web app with
   reCAPTCHA v3, authorize `tsforcetech.github.io` in the provider settings, and add
   Actions variable `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`. Do not disable enforcement.
5. Repository Settings → Pages → Build and deployment → Source: **GitHub Actions**.
   GitHub's plan must support Pages for this repository's visibility. Do not make
   a private repository public just to enable hosting.
6. Actions → **Deploy Caller Pro Web** → Run workflow on `main`.

Expected address after a successful deployment:
https://tsforcetech.github.io/SHARACO_CALL_LOGGER/

The workflow builds and uploads static output, then refuses deployment if the
required Firebase web configuration is absent. Configuration changes require a
new build because Next.js embeds public environment values at build time.
Keep the latest Firestore rules and indexes published in Firebase; Pages does
not deploy or weaken them.

## Verification

- Confirm the Pages workflow completes and the URL loads without missing assets.
- Sign in with Google, select an approved company, and check realtime lead updates.
- Confirm a non-member cannot access company data.
- For web calling, enable web calling on the paired Android device and keep it
  foregrounded. Confirm a request appears once and opens the dialer only after
  the phone user accepts it.

Local static build: `NEXT_PUBLIC_BASE_PATH=/SHARACO_CALL_LOGGER npm run build --prefix web`.
Without this environment variable, builds retain the root path for Firebase Hosting.
