import { getApp, getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";
import {
  initializeAppCheck,
  ReCaptchaV3Provider,
  AppCheck,
} from "firebase/app-check";
let check: AppCheck | undefined;
export function firebase() {
  const apiKey = process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
    appId = process.env.NEXT_PUBLIC_FIREBASE_APP_ID;
  if (!apiKey || !appId)
    throw new Error(
      "WEB_CONFIGURATION_MISSING: Configure the Firebase web app before signing in.",
    );
  const app = getApps().length
    ? getApp()
    : initializeApp({
        apiKey,
        appId,
        projectId: "sharaco-call-logger",
        authDomain:
          process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN ||
          "sharaco-call-logger.firebaseapp.com",
      });
  if (
    typeof window !== "undefined" &&
    !check &&
    process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY
  )
    check = initializeAppCheck(app, {
      provider: new ReCaptchaV3Provider(
        process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY,
      ),
      isTokenAutoRefreshEnabled: true,
    });
  return { auth: getAuth(app), db: getFirestore(app) };
}
export type Row = Record<string, unknown> & { id: string };
export async function api(
  company: string,
  path: string,
  body?: unknown,
  key?: string,
) {
  const { sparkApi } = await import("./spark");
  return sparkApi(company, path, body, key);
}
