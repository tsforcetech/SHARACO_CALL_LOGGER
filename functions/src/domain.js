import { createHash } from "node:crypto";
import { DateTime } from "luxon";
import { parsePhoneNumberFromString } from "libphonenumber-js";
export const TYPES = [
  "INCOMING",
  "OUTGOING",
  "MISSED",
  "REJECTED",
  "BLOCKED",
  "VOICEMAIL",
  "ANSWERED_EXTERNALLY",
  "UNKNOWN",
];
export const hash = (s) => createHash("sha256").update(String(s)).digest("hex");
export const callId = (device, row, time) => hash(`${device}:${row}:${time}`);
export function normalize(value, country = "AE") {
  const s = String(value ?? "").trim();
  if (
    !s ||
    ["-1", "-2", "-3", "unknown", "private", "restricted"].includes(
      s.toLowerCase(),
    )
  )
    return "";
  const clean = s.replace(/[\s().-]/g, "");
  if (/^\d{1,6}$/.test(clean)) return `short:${country}:${clean}`;
  const n = parsePhoneNumberFromString(clean.replace(/^00/, "+"), country);
  return n?.isValid() ? n.number : `raw:${country}:${clean}`;
}
export function initialBoundary(now, zone, policy) {
  const d = DateTime.fromMillis(now, { zone });
  if (!d.isValid) throw new Error("Invalid time zone");
  return policy === "JOIN_TIME" ? now : d.startOf("day").toMillis();
}
export const connected = (c) =>
  ["INCOMING", "OUTGOING"].includes(c.callType) && c.durationSeconds > 0;
export function counters(c) {
  const out = c.callType === "OUTGOING",
    inc = c.callType === "INCOMING",
    conn = connected(c);
  return {
    total: 1,
    outgoing: +out,
    incoming: +inc,
    missed: +(c.callType === "MISSED"),
    rejected: +(c.callType === "REJECTED"),
    connected: +conn,
    connectedOutgoing: +(out && conn),
    answeredIncoming: +(inc && conn),
    talk: c.durationSeconds,
    connectedTalk: conn ? c.durationSeconds : 0,
  };
}
export function finish(m) {
  return {
    ...m,
    average: m.connected ? m.connectedTalk / m.connected : 0,
    outgoingRate: m.outgoing ? (100 * m.connectedOutgoing) / m.outgoing : 0,
    incomingRate:
      m.answeredIncoming + m.missed + m.rejected
        ? (100 * m.answeredIncoming) /
          (m.answeredIncoming + m.missed + m.rejected)
        : 0,
  };
}
export const empty = () => ({
  total: 0,
  outgoing: 0,
  incoming: 0,
  missed: 0,
  rejected: 0,
  connected: 0,
  connectedOutgoing: 0,
  answeredIncoming: 0,
  talk: 0,
  connectedTalk: 0,
  unique: 0,
});
export function metrics(calls) {
  const m = empty(),
    numbers = new Set();
  for (const c of calls) {
    for (const [k, v] of Object.entries(counters(c))) m[k] += v;
    if (c.normalizedNumber) numbers.add(c.normalizedNumber);
  }
  m.unique = numbers.size;
  return finish(m);
}
export function matches(c, f) {
  return (
    (!f.employees?.length || f.employees.includes(c.employeeUid)) &&
    (!f.department || c.department === f.department) &&
    (!f.types?.length || f.types.includes(c.callType)) &&
    (!f.connection ||
      f.connection === (connected(c) ? "CONNECTED" : "UNCONNECTED")) &&
    c.durationSeconds >= (f.minDuration ?? 0) &&
    c.durationSeconds <= (f.maxDuration ?? 604800)
  );
}
export function health(d, now = Date.now()) {
  if (!d.active || !d.callLogPermission || d.lastSyncError) return "PROBLEM";
  if (!d.lastSuccessfulSyncAt || now - d.lastSuccessfulSyncAt > 3600000)
    return "DELAYED";
  return "HEALTHY";
}
