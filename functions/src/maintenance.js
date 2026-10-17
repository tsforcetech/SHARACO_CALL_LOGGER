import { onSchedule } from "firebase-functions/v2/scheduler";
import { getMessaging } from "firebase-admin/messaging";
import { db, REGION, companyRef } from "./core.js";
import { health } from "./domain.js";
export const maintenance = onSchedule(
  { schedule: "every 60 minutes", region: REGION, timeoutSeconds: 540 },
  async () => {
    const jobs = await db.collection("deletionJobs").limit(10).get();
    for (const job of jobs.docs) {
      const cid = job.data().companyId,
        users = await db
          .collection("users")
          .where("companyId", "==", cid)
          .get();
      for (const u of users.docs)
        await u.ref.set({ companyId: "" }, { merge: true });
      const invites = await db
        .collection("invitations")
        .where("companyId", "==", cid)
        .get();
      for (const i of invites.docs) await i.ref.delete();
      await db.recursiveDelete(companyRef(cid));
      await job.ref.delete();
    }
    const companies = await db
      .collection("companies")
      .where("status", "==", "ACTIVE")
      .get();
    for (const cs of companies.docs) {
      const c = cs.data(),
        cutoff = Date.now() - c.retentionDays * 86400000;
      for (let page = 0; page < 25; page++) {
        const old = await cs.ref
          .collection("calls")
          .where("callTimestamp", "<", cutoff)
          .limit(400)
          .get();
        if (old.empty) break;
        const b = db.batch();
        old.docs.forEach((s) => b.delete(s.ref));
        await b.commit();
        if (old.size < 400) break;
      }
      if (c.notificationsEnabled === false) continue;
      const devices = await cs.ref
        .collection("devices")
        .where("active", "==", true)
        .get();
      for (const ds of devices.docs) {
        const d = ds.data();
        if (
          health(d) === "HEALTHY" ||
          Date.now() - (d.lastAlertAt ?? 0) < 86400000
        )
          continue;
        const title = "Device needs attention",
          body = `${d.employeeName}: ${!d.callLogPermission ? "call history permission is disabled" : "synchronization is delayed"}.`;
        await cs.ref
          .collection("notifications")
          .add({ title, body, timestamp: Date.now() });
        await ds.ref.update({ lastAlertAt: Date.now() });
        const user = (await db.doc(`users/${c.ownerId}`).get()).data();
        if (user?.fcmFid) {
          try {
            await getMessaging().send({
              // Admin SDK 13 token field accepts registered FIDs during the FCM migration.
              token: user.fcmFid,
              data: { title, body, recipientUid: c.ownerId },
            });
          } catch (e) {
            console.warn("Notification delivery failed", e.code);
          }
        }
      }
    }
  },
);
