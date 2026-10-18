import { getApp, getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";
import {
  initializeAppCheck,
  ReCaptchaV3Provider,
  getToken,
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
  const { auth } = firebase();
  if (!auth.currentUser) throw new Error("UNAUTHENTICATED: Sign in again.");
  const base = process.env.NEXT_PUBLIC_CRM_API_URL;
  if (!base)
    throw new Error(
      "BACKEND_NOT_CONFIGURED: The CRM server URL has not been configured.",
    );
  const headers: Record<string, string> = {
    Authorization: `Bearer ${await auth.currentUser.getIdToken()}`,
  };
  if (check) headers["X-Firebase-AppCheck"] = (await getToken(check)).token;
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers["Idempotency-Key"] = key || crypto.randomUUID();
  }
  const response = await fetch(
    `${base.replace(/\/$/, "")}/v1/companies/${encodeURIComponent(company)}/${path}`,
    {
      method: body === undefined ? "GET" : "POST",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    },
  );
  const result = await response.json();
  if (!response.ok)
    throw new Error(
      `${result.error?.code || response.status}: ${result.error?.message || "Request failed"}`,
    );
  return result;
}
