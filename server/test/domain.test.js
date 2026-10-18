import test from "node:test";
import assert from "node:assert/strict";
import {
  achievement,
  distribute,
  phone,
  canAccess,
  workingPace,
} from "../src/domain.js";
test("equal assignment handles remainder and excludes inactive staff", () => {
  const out = distribute(1003, [
    { uid: "a", approved: true },
    { uid: "b", approved: true },
    { uid: "x", approved: false },
  ]);
  assert.equal(out.filter((x) => x === "a").length, 502);
  assert.equal(out.filter((x) => x === "b").length, 501);
  assert.ok(!out.includes("x"));
});
test("weighted distribution follows explicit weights", () => {
  const out = distribute(
    1000,
    [
      { uid: "a", approved: true, weight: 3 },
      { uid: "b", approved: true, weight: 1 },
    ],
    "weighted",
  );
  assert.equal(out.filter((x) => x === "a").length, 750);
});
test("targets exceed 100% without overfilling visual progress", () =>
  assert.deepEqual(achievement(125, 100), {
    actual: 125,
    target: 100,
    percentage: 125,
    fill: 100,
    remaining: 0,
    variance: 25,
  }));
test("normalizes international phone and rejects invalid input", () => {
  assert.equal(phone("050 123 4567", "AE"), "+971501234567");
  assert.throws(() => phone("12"));
});
test("manager scopes and suspended membership are enforced", () => {
  assert.equal(
    canAccess(
      { approved: true, role: "supervisor", assignedUsers: ["a"] },
      "m",
      { assignedTo: "a" },
    ),
    true,
  );
  assert.equal(
    canAccess(
      { approved: true, role: "supervisor", assignedUsers: ["a"] },
      "m",
      { assignedTo: "b" },
    ),
    false,
  );
  assert.throws(() =>
    canAccess({ approved: false, role: "owner" }, "o", { assignedTo: "o" }),
  );
});
test("pace uses configured working weekdays and exclusive end date", () => {
  const d = (s) => Date.parse(s + "T00:00:00Z");
  const p = workingPace({
    from: d("2026-10-05"),
    to: d("2026-10-12"),
    now: d("2026-10-08"),
    zone: "UTC",
    workingDays: [1, 2, 3, 4, 5],
    target: 100,
  });
  assert.deepEqual(p, { workingDays: 5, elapsedWorkingDays: 3, expected: 60 });
});
