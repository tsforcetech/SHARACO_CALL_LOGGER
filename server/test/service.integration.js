import test from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { CrmService } from "../src/service.js";
if (!process.env.FIRESTORE_EMULATOR_HOST)
  throw new Error("Firestore emulator required; never run against production.");
const app = initializeApp({ projectId: "demo-calltrack-crm" }),
  db = getFirestore(app),
  service = new CrmService(db);
const cid = "integration";
const ref = (collection, id) => service.ref(cid, collection, id);
test("CRM transactions: imports, isolation, concurrency, retries and outcomes", async (t) => {
  await db.recursiveDelete(db.doc(`companies/${cid}`));
  await db
    .doc(`companies/${cid}`)
    .set({ name: "Emulator test only", ownerUid: "owner" });
  for (const [uid, role] of [
    ["owner", "owner"],
    ["a", "employee"],
    ["b", "employee"],
  ])
    await ref("members", uid).set({ uid, role, approved: true });
  await ref("settings", "profile").set({ timeZone: "Asia/Dubai" });
  await service.initialize(cid, "owner", "init");
  await t.test(
    "employees and outsiders cannot create targets or import",
    async () => {
      await assert.rejects(service.initialize(cid, "a", "bad"), {
        code: "ADMIN_REQUIRED",
      });
      await assert.rejects(service.list(cid, "outsider", "leads"), {
        code: "MEMBERSHIP_REQUIRED",
      });
    },
  );
  const input = {
    rows: [
      { customerName: "One", primaryPhone: "+971501234567" },
      { customerName: "Duplicate", primaryPhone: "0501234567" },
      { customerName: "Bad", primaryPhone: "12" },
      { customerName: "Two", primaryPhone: "+971551234567" },
    ],
    employees: [{ uid: "a" }, { uid: "b" }],
    country: "AE",
  };
  await t.test(
    "validation reports duplicate and invalid rows; persistent job is replay-safe",
    async () => {
      assert.deepEqual(await service.importLeads(cid, "owner", "job", input), {
        id: "job",
        valid: 2,
        invalid: 2,
      });
      assert.deepEqual(await service.importLeads(cid, "owner", "job", input), {
        id: "job",
        valid: 2,
        invalid: 2,
      });
      await Promise.all([
        service.processImport(cid, "job"),
        service.processImport(cid, "job"),
      ]);
      const job = (await ref("importJobs", "job").get()).data();
      assert.equal(job.status, "completed");
      assert.equal(job.created, 2);
      assert.equal(job.processed, 4);
      assert.equal(job.errors.length, 2);
    },
  );
  const a = (await service.list(cid, "a", "leads")).rows;
  await t.test("employee sees only own assigned lead", async () => {
    assert.equal(a.length, 1);
    assert.equal((await service.list(cid, "b", "leads")).rows.length, 1);
  });
  await t.test("idempotency key reuse with changed input fails", async () => {
    await assert.rejects(
      service.importLeads(cid, "owner", "job", { ...input, country: "IN" }),
      { code: "IDEMPOTENCY_CONFLICT" },
    );
  });
  const claim = await service.claim(cid, "a", "session", { leadId: a[0].id });
  await t.test("wrong employee cannot claim lead", async () => {
    await assert.rejects(
      service.claim(cid, "b", "wrong", { leadId: a[0].id }),
      { code: "LEAD_ACCESS_DENIED" },
    );
  });
  const outcome = {
    leadId: a[0].id,
    sessionId: claim.sessionId,
    version: 1,
    dispositionId: "interested",
    remarks: "Test",
    tags: [],
  };
  await t.test(
    "save retries create one attempt and never fabricate a verified call",
    async () => {
      const saved = await Promise.all([
        service.disposition(cid, "a", "attempt", outcome),
        service.disposition(cid, "a", "attempt", outcome),
      ]);
      assert.equal(saved[0].verificationStatus, "UNVERIFIED");
      assert.deepEqual(saved[0], saved[1]);
      const attempts = await service.list(cid, "a", "crmCallAttempts");
      assert.equal(attempts.rows.length, 1);
      assert.equal(attempts.rows[0].durationSeconds, null);
      const metrics = (
        await ref(
          "crmDailyMetrics",
          (await service.list(cid, "a", "crmDailyMetrics")).rows[0].id,
        ).get()
      ).data();
      assert.equal(metrics.crmAttempts, 1);
      assert.equal(metrics.interestedLeads, 1);
    },
  );
  await t.test(
    "repeat interested outcomes do not inflate unique interested leads",
    async () => {
      await service.claim(cid, "a", "session2", { leadId: a[0].id });
      await service.disposition(cid, "a", "attempt2", {
        ...outcome,
        sessionId: "session2",
        version: 2,
      });
      const metrics = (await service.list(cid, "a", "crmDailyMetrics")).rows[0];
      assert.equal(metrics.crmAttempts, 2);
      assert.equal(metrics.interestedLeads, 1);
      assert.equal(metrics.interestedOutcomes, 2);
      assert.equal(metrics.uniqueLeadsAttempted, 1);
    },
  );
  await t.test("revocation blocks old operation replay and list", async () => {
    await ref("members", "a").update({ approved: false });
    await assert.rejects(service.disposition(cid, "a", "attempt", outcome), {
      code: "MEMBERSHIP_REQUIRED",
    });
    await assert.rejects(service.list(cid, "a", "leads"), {
      code: "MEMBERSHIP_REQUIRED",
    });
  });
  await t.test(
    "daily and weekly targets coexist; overlapping daily targets are rejected",
    async () => {
      const target = {
        name: "Daily calls",
        employeeUid: "b",
        metric: "outgoingCalls",
        period: "daily",
        value: 100,
        effectiveFrom: Date.now(),
        effectiveTo: Date.now() + 86400000,
      };
      await service.target(cid, "owner", "daily", target);
      await service.target(cid, "owner", "weekly", {
        ...target,
        period: "weekly",
        value: 500,
      });
      await assert.rejects(service.target(cid, "owner", "overlap", target), {
        code: "TARGET_OVERLAP",
      });
      await assert.rejects(
        service.target(cid, "b", "employee-target", target),
        { code: "ADMIN_REQUIRED" },
      );
    },
  );
  await t.test(
    "1000-row import resumes across chunks and completes exactly once",
    async () => {
      const rows = Array.from({ length: 1000 }, (_, i) => ({
        customerName: `Emulator ${i}`,
        primaryPhone: `+1202555${String(i).padStart(4, "0")}`,
      }));
      await service.importLeads(cid, "owner", "large", {
        rows,
        employees: [{ uid: "b" }],
        country: "US",
      });
      for (let i = 0; i < 50; i++) await service.processImport(cid, "large");
      await service.processImport(cid, "large");
      const job = (await ref("importJobs", "large").get()).data();
      assert.equal(job.status, "completed");
      assert.equal(job.created, 1000);
      assert.equal(job.processed, 1000);
      const page = await service.list(cid, "b", "leads", { limit: 100 });
      assert.equal(page.rows.length, 100);
      assert.ok(page.next);
      const next = await service.list(cid, "b", "leads", {
        limit: 100,
        after: page.next,
      });
      assert.equal(next.rows.length, 100);
      assert.ok(!next.rows.some((n) => page.rows.some((p) => p.id === n.id)));
    },
  );
  await db.recursiveDelete(db.doc(`companies/${cid}`));
});
test.after(() => deleteApp(app));
