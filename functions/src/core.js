import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
initializeApp();
export const db = getFirestore();
export const REGION = "europe-west1";
export const fail = (code, message) => {
  throw new HttpsError(code, message);
};
export const requireThat = (v, message, code = "invalid-argument") => {
  if (!v) fail(code, message);
};
export const text = (v, max = 160) =>
  String(v ?? "")
    .trim()
    .slice(0, max);
export const id = (v) => {
  const x = text(v);
  requireThat(/^[a-zA-Z0-9_-]{1,128}$/.test(x), "Invalid identifier.");
  return x;
};
export const companyRef = (cid) => db.doc(`companies/${id(cid)}`);
export const memberRef = (cid, uid) =>
  companyRef(cid).collection("members").doc(id(uid));
export function callable(fn) {
  return onCall(
    {
      region: REGION,
      enforceAppCheck: true,
      timeoutSeconds: 120,
      memory: "512MiB",
    },
    async (r) => {
      requireThat(r.auth, "Please sign in.", "unauthenticated");
      try {
        return await fn(r, r.data ?? {});
      } catch (e) {
        if (e instanceof HttpsError) throw e;
        console.error("Operation failed", e.code ?? e.name);
        fail("internal", "Unable to complete this action. Please retry.");
      }
    },
  );
}
export async function access(r, cid, manage = false, owner = false) {
  const [cs, ms] = await Promise.all([
    companyRef(cid).get(),
    memberRef(cid, r.auth.uid).get(),
  ]);
  const c = cs.data(),
    m = ms.data();
  requireThat(
    c?.status === "ACTIVE" && m?.status === "ACTIVE",
    "Your company access is inactive.",
    "permission-denied",
  );
  if (owner)
    requireThat(
      m.role === "OWNER",
      "Only the owner can do this.",
      "permission-denied",
    );
  if (manage)
    requireThat(
      m.role === "OWNER" || (m.role === "ADMIN" && m.manageEmployees),
      "Employee management is not permitted.",
      "permission-denied",
    );
  return { c, m };
}
export function audit(tx, cid, uid, action, targetId = "") {
  tx.set(companyRef(cid).collection("audit").doc(), {
    actorUid: uid,
    action,
    targetId,
    timestamp: Date.now(),
  });
}
export async function throttle(uid, action, limit = 20) {
  const ref = db.doc(
    `rateLimits/${id(uid)}_${action}_${Math.floor(Date.now() / 60000)}`,
  );
  await db.runTransaction(async (tx) => {
    const s = await tx.get(ref);
    const n = s.data()?.count ?? 0;
    requireThat(
      n < limit,
      "Too many attempts. Please wait a minute.",
      "resource-exhausted",
    );
    tx.set(ref, {
      count: n + 1,
      expiresAt: Timestamp.fromMillis(Date.now() + 3600000),
    });
  });
}
export const recent = (r) =>
  requireThat(
    Date.now() / 1000 - (r.auth.token.auth_time ?? 0) < 300,
    "Sign in again before deleting.",
    "failed-precondition",
  );
