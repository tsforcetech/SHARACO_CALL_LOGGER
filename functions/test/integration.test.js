import test from "node:test";
import assert from "node:assert/strict";
if (!process.env.FIRESTORE_EMULATOR_HOST)
  throw new Error("Integration tests must use Firestore emulator.");
process.env.GCLOUD_PROJECT = "demo-calltrack";
const api = await import("../src/index.js");
const { db } = await import("../src/core.js");
const { DateTime } = await import("luxon");
function req(uid, data, email = `${uid}@example.com`) {
  return {
    auth: {
      uid,
      token: {
        name: uid,
        email,
        email_verified: true,
        auth_time: Date.now() / 1000,
      },
    },
    data,
  };
}
test("onboarding, invite binding, device replacement, idempotent uploads and reporting", async () => {
  const owner = `owner${Date.now()}`,
    staff = `staff${Date.now()}`;
  const { companyId } = await api.createCompany.run(
    req(owner, { name: "Test company", country: "AE", timeZone: "Asia/Dubai" }),
  );
  try {
    const { employeeId } = await api.saveEmployee.run(
      req(owner, {
        companyId,
        name: "Employee",
        code: "E1",
        email: `${staff}@example.com`,
      }),
    );
    const { token } = await api.createInvite.run(
      req(owner, { companyId, employeeId }),
    );
    await assert.rejects(
      api.joinCompany.run(req("wrong", { token, consent: true })),
      /Google account/,
    );
    await api.joinCompany.run(req(staff, { token, consent: true }));
    await assert.rejects(
      api.joinCompany.run(req("other", { token, consent: true })),
      /already used/,
    );
    await assert.rejects(
      api.saveEmployee.run(
        req(staff, { companyId, name: "Illegal", code: "E2" }),
      ),
      /not permitted/,
    );
    await api.registerDevice.run(
      req(staff, { companyId, deviceId: "phone1", callLogPermission: true }),
    );
    const timestamp = Date.now(),
      call = {
        androidCallLogId: "1",
        phoneNumber: "0501234567",
        callType: "OUTGOING",
        callTimestamp: timestamp,
        durationSeconds: 45,
      };
    const payload = { companyId, deviceId: "phone1", calls: [call] };
    const first = await api.ingestCalls.run(req(staff, payload));
    await api.ingestCalls.run(req(staff, payload));
    assert.equal(
      (await db.collection(`companies/${companyId}/calls`).get()).size,
      1,
    );
    await api.aggregateCall.run({
      params: { cid: companyId, callId: first.confirmedIds[0] },
    });
    await api.aggregateCall.run({
      params: { cid: companyId, callId: first.confirmedIds[0] },
    });
    const daily = (
      await db
        .collection(`companies/${companyId}/daily`)
        .where("employeeUid", "==", "ALL")
        .get()
    ).docs[0].data();
    assert.equal(daily.total, 1);
    assert.equal(daily.talk, 45);
    assert.equal(daily.unique, 1);
    call.durationSeconds = 60;
    await api.ingestCalls.run(req(staff, payload));
    await api.aggregateCall.run({
      params: { cid: companyId, callId: first.confirmedIds[0] },
    });
    const day = DateTime.now().setZone("Asia/Dubai").startOf("day");
    const report = await api.getReport.run(
      req(owner, {
        companyId,
        filters: { from: day.toMillis(), to: day.plus({ days: 1 }).toMillis() },
      }),
    );
    assert.equal(report.metrics.total, 1);
    assert.equal(report.metrics.talk, 60);
    assert.equal(report.metrics.unique, 1);
    const filters = {
      from: day.toMillis(),
      to: day.plus({ days: 1 }).toMillis(),
    };
    await assert.rejects(
      api.ingestCalls.run(
        req(staff, {
          ...payload,
          calls: [
            { ...call, callTimestamp: day.minus({ days: 1 }).toMillis() },
          ],
        }),
      ),
      /monitoring period/,
    );
    const many = Array.from({ length: 60 }, (_, i) => ({
      ...call,
      androidCallLogId: String(i + 2),
      durationSeconds: i,
      callTimestamp: timestamp + i,
    }));
    await api.ingestCalls.run(req(staff, { ...payload, calls: many }));
    const page1 = await api.listCalls.run(
      req(owner, { companyId, filters, sort: "LONGEST" }),
    );
    assert.equal(page1.calls.length, 50);
    assert.ok(
      page1.calls.every(
        (c, i, a) => i === 0 || a[i - 1].durationSeconds >= c.durationSeconds,
      ),
    );
    const page2 = await api.listCalls.run(
      req(owner, { companyId, filters, sort: "LONGEST", cursor: page1.cursor }),
    );
    assert.equal(page2.calls.length, 11);
    assert.equal(
      new Set([...page1.calls, ...page2.calls].map((c) => c.id)).size,
      61,
    );
    const forcedOwn = await api.listCalls.run(
      req(staff, {
        companyId,
        filters: { ...filters, employees: ["someone-else"] },
      }),
    );
    assert.ok(forcedOwn.calls.every((c) => c.employeeUid === staff));
    await api.deviceHeartbeat.run(
      req(staff, { companyId, deviceId: "phone1", callLogPermission: false }),
    );
    assert.equal(
      (await db.doc(`companies/${companyId}/devices/phone1`).get()).data()
        .callLogPermission,
      false,
    );
    const inviteEmployee = await api.saveEmployee.run(
      req(owner, { companyId, name: "Invite checks", code: "E2" }),
    );
    const revocable = await api.createInvite.run(
      req(owner, { companyId, employeeId: inviteEmployee.employeeId }),
    );
    await api.revokeInvite.run(
      req(owner, { companyId, employeeId: inviteEmployee.employeeId }),
    );
    await assert.rejects(
      api.inspectInvite.run(req(staff, { token: revocable.token })),
      /invalid, expired/,
    );
    const expired = await api.createInvite.run(
      req(owner, { companyId, employeeId: inviteEmployee.employeeId }),
    );
    const { hash } = await import("../src/domain.js");
    await db
      .doc(`invitations/${hash(expired.token)}`)
      .update({ expiresAt: Date.now() - 1 });
    await assert.rejects(
      api.inspectInvite.run(req(staff, { token: expired.token })),
      /invalid, expired/,
    );
    await assert.rejects(
      api.registerDevice.run(req(staff, { companyId, deviceId: "phone2" })),
      /Another device/,
    );
    await api.registerDevice.run(
      req(staff, { companyId, deviceId: "phone2", replace: true }),
    );
    await assert.rejects(api.ingestCalls.run(req(staff, payload)), /revoked/);
    await assert.rejects(
      api.listCalls.run(
        req("outsider", {
          companyId,
          filters: {
            from: day.toMillis(),
            to: day.plus({ days: 1 }).toMillis(),
          },
        }),
      ),
      /inactive/,
    );
    await api.employeeAction.run(
      req(owner, { companyId, employeeId, action: "INACTIVE" }),
    );
    await assert.rejects(
      api.ingestCalls.run(req(staff, { ...payload, deviceId: "phone2" })),
      /inactive/,
    );
  } finally {
    await db.recursiveDelete(db.doc(`companies/${companyId}`));
  }
});
