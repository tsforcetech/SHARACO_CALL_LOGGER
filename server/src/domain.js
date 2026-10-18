import { createHash } from "node:crypto";
import { parsePhoneNumberFromString } from "libphonenumber-js";
import { DateTime } from "luxon";
export class ApiError extends Error {
  constructor(code, message, status = 400) {
    super(message);
    this.code = code;
    this.status = status;
  }
}
export function requireValue(ok, code, message, status) {
  if (!ok) throw new ApiError(code, message, status);
}
export function id(value) {
  requireValue(
    typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value),
    "INVALID_ID",
    "Invalid record identifier.",
  );
  return value;
}
export function text(value, max = 1000) {
  requireValue(
    typeof value === "string" && value.length <= max,
    "INVALID_TEXT",
    `Text must be at most ${max} characters.`,
  );
  return value.trim();
}
export function phone(value, country = "AE") {
  const p = parsePhoneNumberFromString(text(value, 50), country);
  requireValue(
    p?.isValid(),
    "INVALID_PHONE",
    "Enter a valid phone number including country code.",
  );
  return p.number;
}
export function active(member) {
  requireValue(
    member?.approved === true && !member.suspended,
    "MEMBERSHIP_REQUIRED",
    "An approved company membership is required.",
    403,
  );
  return member;
}
export function isAdmin(m) {
  return ["owner", "admin"].includes(m.role);
}
export function manage(m) {
  active(m);
  requireValue(
    isAdmin(m),
    "ADMIN_REQUIRED",
    "Company administrator access required.",
    403,
  );
}
export function canAccess(m, uid, row) {
  active(m);
  return (
    isAdmin(m) ||
    row.assignedTo === uid ||
    row.employeeUid === uid ||
    (["manager", "supervisor"].includes(m.role) &&
      (m.assignedUsers || []).includes(row.assignedTo || row.employeeUid))
  );
}
export const hash = (value) => createHash("sha256").update(value).digest("hex");
export const DEFAULT_STATUSES = [
  ["interested", "Interested", true, false, false, false],
  ["not_interested", "Not interested", false, false, true, false],
  ["call_back", "Call back later", false, false, false, true],
  ["no_answer", "No answer", false, false, false, false],
  ["busy", "Busy", false, false, false, false],
  ["wrong_number", "Wrong number", false, false, true, false],
  ["unreachable", "Unreachable", false, false, false, false],
  ["qualified", "Qualified lead", false, false, false, false],
  ["appointment", "Appointment booked", false, false, false, true],
  ["converted", "Converted", false, true, true, false],
  ["do_not_call", "Do not call", false, false, true, false],
].map(
  (
    [
      statusId,
      label,
      countsAsInterested,
      countsAsConversion,
      isTerminal,
      requiresFollowUp,
    ],
    sortOrder,
  ) => ({
    statusId,
    label,
    countsAsInterested,
    countsAsConversion,
    isTerminal,
    requiresFollowUp,
    requiresRemarks: false,
    active: true,
    sortOrder,
  }),
);
export function distribute(count, employees, mode = "equal") {
  requireValue(
    Number.isInteger(count) && count >= 0 && count <= 10000,
    "INVALID_COUNT",
    "Invalid lead count.",
  );
  const list = employees.filter((e) => e.approved === true && !e.suspended);
  requireValue(
    list.length > 0,
    "NO_EMPLOYEES",
    "Select at least one active employee.",
  );
  const weights = list.map((e) => (mode === "weighted" ? e.weight : 1));
  requireValue(
    weights.every((w) => Number.isFinite(w) && w > 0 && w <= 100),
    "INVALID_WEIGHT",
    "Weights must be between 1 and 100.",
  );
  const scores = weights.map(() => 0),
    total = weights.reduce((a, b) => a + b, 0);
  return Array.from({ length: count }, () => {
    for (let i = 0; i < scores.length; i++) scores[i] += weights[i];
    const i = scores.indexOf(Math.max(...scores));
    scores[i] -= total;
    return list[i].uid;
  });
}
export const METRICS = [
  "outgoingCalls",
  "connectedCalls",
  "uniqueNumbers",
  "talkSeconds",
  "averageDuration",
  "connectionPercent",
  "crmAttempts",
  "uniqueLeadsAttempted",
  "connectedCrmCalls",
  "interestedLeads",
  "interestedOutcomes",
  "followUpsScheduled",
  "followUpsCompleted",
  "appointments",
  "qualifiedLeads",
  "conversions",
  "uniqueConvertedLeads",
  "conversionValue",
];
export function achievement(actual, target) {
  requireValue(
    Number.isFinite(actual) && Number.isFinite(target) && target > 0,
    "INVALID_TARGET",
    "Target must be greater than zero.",
  );
  return {
    actual,
    target,
    percentage: (actual / target) * 100,
    fill: Math.min(100, Math.max(0, (actual / target) * 100)),
    remaining: Math.max(0, target - actual),
    variance: actual - target,
  };
}
export function workingPace({ from, to, now, zone, workingDays, target }) {
  let d = DateTime.fromMillis(from, { zone }).startOf("day"),
    end = DateTime.fromMillis(to, { zone }).startOf("day");
  requireValue(
    d.isValid && end.isValid && end >= d && end.diff(d, "days").days <= 3660,
    "INVALID_PERIOD",
    "Invalid target period.",
  );
  requireValue(
    Array.isArray(workingDays) &&
      workingDays.length > 0 &&
      workingDays.every((n) => Number.isInteger(n) && n >= 1 && n <= 7),
    "INVALID_WORK_WEEK",
    "Choose working days.",
  );
  let total = 0,
    elapsed = 0;
  const today = DateTime.fromMillis(now, { zone }).startOf("day");
  for (; d < end; d = d.plus({ days: 1 }))
    if (workingDays.includes(d.weekday)) {
      total++;
      if (d < today) elapsed++;
    }
  return {
    workingDays: total,
    elapsedWorkingDays: elapsed,
    expected: total ? (target * elapsed) / total : 0,
  };
}
