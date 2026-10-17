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
