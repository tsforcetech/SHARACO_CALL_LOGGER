import { DateTime } from "luxon";
import {
  active,
  manage,
  canAccess,
  isAdmin,
  id,
  text,
  phone,
  hash,
  requireValue,
  DEFAULT_STATUSES,
  METRICS,
  distribute,
} from "./domain.js";

// All privileged writes re-read membership inside the same transaction as the write.
// Admin SDK bypasses Firestore rules, so authorization belongs here, not in the UI.
export class CrmService {
  constructor(db, clock = () => Date.now()) {
    this.db = db;
    this.clock = clock;
  }
  ref(cid, collection, key) {
    return this.db.doc(`companies/${id(cid)}/${collection}/${id(key)}`);
  }
  async member(tx, cid, uid) {
    return active((await tx.get(this.ref(cid, "members", uid))).data());
  }
  async mutate(cid, uid, key, operation, input, fn) {
    id(key);
    const fingerprint = hash(JSON.stringify({ operation, input }));
    return this.db.runTransaction(async (tx) => {
      const m = await this.member(tx, cid, uid);
      const r = this.ref(cid, "crmOperations", hash(`${uid}:${key}`)),
        prior = await tx.get(r);
      if (prior.exists) {
        requireValue(
          prior.data().fingerprint === fingerprint,
          "IDEMPOTENCY_CONFLICT",
          "This operation key was already used for another action.",
          409,
        );
        return prior.data().result;
      }
      const result = await fn(tx, m);
      tx.create(r, {
        employeeUid: uid,
        operation,
        fingerprint,
        result,
        createdAt: this.clock(),
      });
      return result;
    });
  }
  async initialize(cid, uid, key) {
    return this.mutate(cid, uid, key, "initialize", {}, async (tx, m) => {
      manage(m);
      const refs = DEFAULT_STATUSES.map((s) =>
        this.ref(cid, "dispositionStatuses", s.statusId),
      );
      const existing = await tx.getAll(...refs);
      existing.forEach((s, i) => {
        if (!s.exists) tx.create(refs[i], DEFAULT_STATUSES[i]);
      });
      return { initialized: true };
    });
  }
  async list(cid, uid, collection, { after = "", limit = 50 } = {}) {
    const allowed = [
      "leads",
      "campaigns",
      "crmCallAttempts",
      "leadActivities",
      "followUps",
      "targetDefinitions",
      "callingSessions",
      "callRequests",
      "dispositionStatuses",
      "tags",
      "importJobs",
      "webDevicePairings",
      "crmDailyMetrics",
    ];
    requireValue(
      allowed.includes(collection),
      "INVALID_COLLECTION",
      "Unknown CRM collection.",
    );
    requireValue(
      Number.isInteger(limit) && limit >= 1 && limit <= 100,
      "INVALID_LIMIT",
      "Page size must be 1–100.",
    );
    return this.db.runTransaction(async (tx) => {
      const m = await this.member(tx, cid, uid);
      let q = this.db.collection(`companies/${id(cid)}/${collection}`);
      if (["importJobs", "campaigns"].includes(collection)) manage(m);
      if (
        !isAdmin(m) &&
        !["dispositionStatuses", "tags"].includes(collection)
      ) {
        const field = collection === "leads" ? "assignedTo" : "employeeUid";
        // Manager scoped expansion is deliberately not inferred from an arbitrary client filter.
        q = q.where(field, "==", uid);
      }
      q = q.orderBy("__name__").limit(limit + 1);
      if (after) q = q.startAfter(id(after));
      const docs = (await tx.get(q)).docs,
        more = docs.length > limit,
        page = docs.slice(0, limit);
      return {
        rows: page.map((s) => ({ ...s.data(), id: s.id })),
        next: more ? page.at(-1).id : null,
      };
    });
  }
  async campaign(cid, uid, key, input) {
    const campaignId = input.id ? id(input.id) : id(key),
      name = text(input.name, 160);
    requireValue(name.length > 0, "NAME_REQUIRED", "Enter a campaign name.");
    const status = input.status || "draft";
    requireValue(
      ["draft", "active", "paused", "completed", "archived"].includes(status),
      "INVALID_STATUS",
      "Invalid campaign status.",
    );
    return this.mutate(cid, uid, key, "campaign", input, async (tx, m) => {
      manage(m);
      const r = this.ref(cid, "campaigns", campaignId),
        old = await tx.get(r);
      tx.set(r, {
        campaignId,
        companyId: cid,
        name,
        status,
        description: text(input.description || ""),
        instructions: text(input.instructions || ""),
        createdAt: old.data()?.createdAt || this.clock(),
        updatedAt: this.clock(),
        createdBy: old.data()?.createdBy || uid,
      });
      return { id: campaignId };
    });
  }
  async importLeads(cid, uid, key, input) {
    requireValue(
      Array.isArray(input.rows) &&
        input.rows.length > 0 &&
        input.rows.length <= 1000,
      "IMPORT_SIZE",
      "Upload between 1 and 1,000 rows per job.",
    );
    const mode = input.duplicatePolicy || "skip";
    requireValue(
      ["skip", "update"].includes(mode),
      "INVALID_POLICY",
      "Choose skip or update duplicates.",
    );
    const campaignId = input.campaignId ? id(input.campaignId) : "";
    const rows = input.rows.map((r, index) => {
      try {
        const customerName = text(r.customerName, 160);
        requireValue(
          customerName,
          "NAME_REQUIRED",
          "Customer name is required.",
        );
        return {
          row: index + 1,
          customerName,
          primaryPhone: phone(r.primaryPhone, input.country || "AE"),
          email: text(r.email || "", 254),
          companyName: text(r.companyName || "", 160),
          remarks: text(r.remarks || ""),
          priority: ["low", "normal", "high"].includes(r.priority)
            ? r.priority
            : "normal",
        };
      } catch (e) {
        return {
          row: index + 1,
          error: e.code || "INVALID_ROW",
          message: e.message,
        };
      }
    });
    const seen = new Set();
    for (const r of rows)
      if (!r.error) {
        if (seen.has(r.primaryPhone)) {
          r.error = "DUPLICATE_IN_FILE";
          r.message = "Phone number is repeated in this file.";
        } else seen.add(r.primaryPhone);
      }
    return this.mutate(cid, uid, key, "import", input, async (tx, m) => {
      manage(m);
      if (campaignId)
        requireValue(
          (await tx.get(this.ref(cid, "campaigns", campaignId))).exists,
          "CAMPAIGN_NOT_FOUND",
          "Campaign does not exist.",
        );
      const employees = [];
      for (const e of input.employees || []) {
        const employee = await this.member(tx, cid, id(e.uid));
        employees.push({ ...employee, uid: e.uid, weight: e.weight || 1 });
      }
      requireValue(
        employees.length > 0 && employees.length <= 50,
        "EMPLOYEES_REQUIRED",
        "Select 1–50 approved employees.",
      );
      const valid = rows.filter((r) => !r.error),
        assignments = distribute(
          valid.length,
          employees,
          input.assignmentMode || "equal",
        );
      valid.forEach((r, i) => (r.assignedTo = assignments[i]));
      // Chunk payloads are durable, bounded documents. Worker resumes after process restart.
      const chunks = [];
      for (let i = 0; i < valid.length; i += 20)
        chunks.push(valid.slice(i, i + 20));
      chunks.forEach((items, i) =>
        tx.create(
          this.db.doc(`companies/${cid}/importJobs/${key}/chunks/${i}`),
          { rows: items },
        ),
      );
      tx.create(this.ref(cid, "importJobs", key), {
        companyId: cid,
        employeeUid: uid,
        status: chunks.length ? "queued" : "completed",
        campaignId,
        duplicatePolicy: mode,
        total: rows.length,
        processed: rows.length - valid.length,
        created: 0,
        updated: 0,
        skipped: 0,
        failed: rows.length - valid.length,
        errors: rows
          .filter((r) => r.error)
          .map(({ row, error, message }) => ({ row, error, message })),
        nextChunk: 0,
        chunkCount: chunks.length,
        createdAt: this.clock(),
      });
      return {
        id: key,
        valid: valid.length,
        invalid: rows.length - valid.length,
      };
    });
  }
  async processImport(cid, jobId) {
    return this.db.runTransaction(async (tx) => {
      const ref = this.ref(cid, "importJobs", jobId),
        snap = await tx.get(ref);
      if (!snap.exists) return;
      const job = snap.data();
      if (!["queued", "running"].includes(job.status)) return;
      const membership = (
        await tx.get(this.ref(cid, "members", job.employeeUid))
      ).data();
      if (
        !membership?.approved ||
        membership.suspended ||
        !isAdmin(membership)
      ) {
        tx.update(ref, {
          status: "blocked",
          error: "Administrator access revoked.",
        });
        return;
      }
      const chunk = await tx.get(
        this.db.doc(
          `companies/${cid}/importJobs/${jobId}/chunks/${job.nextChunk}`,
        ),
      );
      requireValue(
        chunk.exists,
        "IMPORT_CHUNK_MISSING",
        "Import chunk is missing.",
        500,
      );
      const rows = chunk.data().rows;
      const all = await Promise.all(
        rows.map(async (r) => {
          const identity = this.ref(
            cid,
            "leadIdentities",
            hash(r.primaryPhone),
          );
          const identitySnap = await tx.get(identity);
          const leadId =
              identitySnap.data()?.leadId || hash(`${jobId}:${r.row}`),
            leadRef = this.ref(cid, "leads", leadId);
          return {
            r,
            identity,
            identitySnap,
            leadId,
            leadRef,
            lead: await tx.get(leadRef),
            member: await tx.get(this.ref(cid, "members", r.assignedTo)),
          };
        }),
      );
      let created = 0,
        updated = 0,
        skipped = 0,
        failed = 0;
      const errors = [];
      for (const {
        r,
        identity,
        identitySnap,
        leadId,
        leadRef,
        lead,
        member,
      } of all) {
        if (!member.data()?.approved || member.data().suspended) {
          failed++;
          errors.push({
            row: r.row,
            error: "EMPLOYEE_INACTIVE",
            message: "Assigned employee is no longer active.",
          });
          continue;
        }
        if (lead.exists) {
          if (job.duplicatePolicy === "skip") {
            skipped++;
            continue;
          }
          // Preserve ownership, outcomes, remarks and history during an identity update.
          tx.update(leadRef, {
            customerName: r.customerName,
            email: r.email,
            companyName: r.companyName,
            updatedAt: this.clock(),
            version: (lead.data().version || 1) + 1,
          });
          updated++;
        } else {
          const { row, ...fields } = r;
          tx.create(leadRef, {
            ...fields,
            leadId,
            companyId: cid,
            campaignId: job.campaignId,
            status: "new",
            tags: [],
            terminal: false,
            doNotCall: false,
            version: 1,
            createdBy: job.employeeUid,
            createdAt: this.clock(),
            updatedAt: this.clock(),
            lastContactAt: null,
            nextFollowUpAt: null,
          });
          if (!identitySnap.exists)
            tx.create(identity, { leadId, primaryPhone: r.primaryPhone });
          created++;
        }
        tx.create(
          this.ref(cid, "leadActivities", hash(`import:${jobId}:${r.row}`)),
          {
            leadId,
            employeeUid: r.assignedTo,
            actorUid: job.employeeUid,
            type: lead.exists ? "IMPORT_UPDATE" : "IMPORTED",
            createdAt: this.clock(),
            jobId,
          },
        );
      }
      const next = job.nextChunk + 1;
      tx.update(ref, {
        status: next === job.chunkCount ? "completed" : "running",
        nextChunk: next,
        processed: job.processed + rows.length,
        created: job.created + created,
        updated: job.updated + updated,
        skipped: job.skipped + skipped,
        failed: job.failed + failed,
        errors: [...job.errors, ...errors],
        updatedAt: this.clock(),
      });
    });
  }
  async claim(cid, uid, key, input) {
    const leadId = id(input.leadId);
    return this.mutate(cid, uid, key, "claim", input, async (tx, m) => {
      const r = this.ref(cid, "leads", leadId),
        s = await tx.get(r),
        lead = s.data();
      requireValue(
        s.exists && canAccess(m, uid, lead),
        "LEAD_ACCESS_DENIED",
        "Lead unavailable or reassigned.",
        403,
      );
      requireValue(
        !lead.terminal && !lead.doNotCall,
        "LEAD_CLOSED",
        "This lead is closed or marked Do Not Call.",
        409,
      );
      requireValue(
        !lead.claimExpiresAt ||
          lead.claimExpiresAt <= this.clock() ||
          lead.claimedBy === uid,
        "LEAD_BUSY",
        "Another agent is working on this lead.",
        409,
      );
      const sessionId = key,
        expiresAt = this.clock() + 15 * 60 * 1000;
      tx.update(r, {
        claimedBy: uid,
        claimSessionId: sessionId,
        claimExpiresAt: expiresAt,
      });
      tx.create(this.ref(cid, "callingSessions", sessionId), {
        employeeUid: uid,
        leadId,
        status: "READY",
        createdAt: this.clock(),
        lastActivityAt: this.clock(),
        expiresAt,
      });
      return { sessionId, lead: { ...lead, id: leadId }, expiresAt };
    });
  }
  async disposition(cid, uid, key, input) {
    const leadId = id(input.leadId),
      statusId = id(input.dispositionId),
      sessionId = id(input.sessionId);
    return this.mutate(cid, uid, key, "disposition", input, async (tx, m) => {
      const leadRef = this.ref(cid, "leads", leadId),
        lead = (await tx.get(leadRef)).data();
      requireValue(
        lead && canAccess(m, uid, lead),
        "LEAD_ACCESS_DENIED",
        "Lead unavailable or reassigned.",
        403,
      );
      const status = (
        await tx.get(this.ref(cid, "dispositionStatuses", statusId))
      ).data();
      const session = (
        await tx.get(this.ref(cid, "callingSessions", sessionId))
      ).data();
      requireValue(
        session?.employeeUid === uid &&
          session.leadId === leadId &&
          lead.claimSessionId === sessionId &&
          lead.claimedBy === uid &&
          lead.claimExpiresAt > this.clock(),
        "SESSION_EXPIRED",
        "Reopen this lead before saving.",
        409,
      );
      requireValue(
        input.version === lead.version,
        "LEAD_CONFLICT",
        "Lead changed. Refresh before saving.",
        409,
      );
      requireValue(
        status?.active,
        "INVALID_STATUS",
        "Choose an active outcome.",
      );
      const remarks = text(input.remarks || "");
      requireValue(
        !status.requiresRemarks || remarks,
        "REMARKS_REQUIRED",
        "Remarks are required.",
      );
      const nextFollowUpAt = input.nextFollowUpAt || null;
      requireValue(
        !status.requiresFollowUp || nextFollowUpAt,
        "FOLLOW_UP_REQUIRED",
        "Choose a follow-up date.",
      );
      requireValue(
        nextFollowUpAt === null ||
          (Number.isSafeInteger(nextFollowUpAt) &&
            nextFollowUpAt > this.clock()),
        "INVALID_FOLLOW_UP",
        "Follow-up must be in the future.",
      );
      const tags = input.tags || [];
      requireValue(
        Array.isArray(tags) &&
          tags.length <= 20 &&
          tags.every((t) => typeof t === "string" && t.length <= 50),
        "INVALID_TAGS",
        "Choose up to 20 short tags.",
      );
      const profile =
        (await tx.get(this.ref(cid, "settings", "profile"))).data() || {};
      const day = DateTime.fromMillis(this.clock(), {
        zone: profile.timeZone || "UTC",
      }).toISODate();
      const metricRef = this.ref(cid, "crmDailyMetrics", `${uid}_${day}`),
        metrics = (await tx.get(metricRef)).data() || {};
      const milestoneRef = this.ref(
          cid,
          "leadMilestones",
          hash(`${leadId}:${uid}`),
        ),
        milestone = (await tx.get(milestoneRef)).data() || {};
      const attempt = {
        attemptId: key,
        companyId: cid,
        leadId,
        employeeUid: uid,
        campaignId: lead.campaignId,
        sessionId,
        dispositionId: statusId,
        remarks,
        tags,
        nextFollowUpAt,
        createdAt: this.clock(),
        updatedAt: this.clock(),
        verificationStatus: "UNVERIFIED",
        matchedCallId: null,
        durationSeconds: null,
        connectionStatus: "UNKNOWN",
      };
      // Manual outcome is never evidence that a cellular call connected.
      tx.create(this.ref(cid, "crmCallAttempts", key), attempt);
      tx.create(this.ref(cid, "leadActivities", key), {
        ...attempt,
        type: "DISPOSITION",
      });
      tx.update(leadRef, {
        status: statusId,
        terminal: !!status.isTerminal,
        doNotCall: statusId === "do_not_call",
        tags,
        remarks,
        nextFollowUpAt,
        lastContactAt: this.clock(),
        updatedAt: this.clock(),
        version: lead.version + 1,
        claimedBy: null,
        claimSessionId: null,
        claimExpiresAt: null,
      });
      tx.update(this.ref(cid, "callingSessions", sessionId), {
        status: "DISPOSITION_SAVED",
        lastActivityAt: this.clock(),
      });
      if (nextFollowUpAt)
        tx.create(this.ref(cid, "followUps", key), {
          leadId,
          employeeUid: uid,
          campaignId: lead.campaignId,
          dueAt: nextFollowUpAt,
          status: "pending",
          remarks,
          version: 1,
          createdAt: this.clock(),
        });
      const add = (k, n = 1) => (metrics[k] || 0) + n;
      tx.set(
        metricRef,
        {
          employeeUid: uid,
          day,
          crmAttempts: add("crmAttempts"),
          uniqueLeadsAttempted: add(
            "uniqueLeadsAttempted",
            milestone.lastAttemptDay === day ? 0 : 1,
          ),
          interestedOutcomes: add(
            "interestedOutcomes",
            status.countsAsInterested ? 1 : 0,
          ),
          interestedLeads: add(
            "interestedLeads",
            status.countsAsInterested && !milestone.interested ? 1 : 0,
          ),
          conversions: add("conversions", status.countsAsConversion ? 1 : 0),
          uniqueConvertedLeads: add(
            "uniqueConvertedLeads",
            status.countsAsConversion && !milestone.converted ? 1 : 0,
          ),
          followUpsScheduled: add("followUpsScheduled", nextFollowUpAt ? 1 : 0),
          updatedAt: this.clock(),
        },
        { merge: true },
      );
      tx.set(milestoneRef, {
        leadId,
        employeeUid: uid,
        attempted: true,
        lastAttemptDay: day,
        interested: !!milestone.interested || !!status.countsAsInterested,
        converted: !!milestone.converted || !!status.countsAsConversion,
      });
      return {
        attemptId: key,
        status: "DISPOSITION_SAVED",
        verificationStatus: "UNVERIFIED",
      };
    });
  }
  async target(cid, uid, key, input) {
    requireValue(
      METRICS.includes(input.metric),
      "INVALID_METRIC",
      "Choose a supported metric.",
    );
    requireValue(
      ["daily", "weekly", "monthly", "custom"].includes(input.period),
      "INVALID_PERIOD",
      "Choose a target period.",
    );
    requireValue(
      Number.isFinite(input.value) && input.value > 0,
      "INVALID_TARGET",
      "Target must be greater than zero.",
    );
    requireValue(
      Number.isSafeInteger(input.effectiveFrom) &&
        Number.isSafeInteger(input.effectiveTo) &&
        input.effectiveTo > input.effectiveFrom,
      "INVALID_PERIOD",
      "Provide a valid effective date interval.",
    );
    const employeeUid = id(input.employeeUid);
    return this.mutate(cid, uid, key, "target", input, async (tx, m) => {
      manage(m);
      await this.member(tx, cid, employeeUid);
      const existing = await tx.get(
        this.db
          .collection(`companies/${cid}/targetDefinitions`)
          .where("employeeUid", "==", employeeUid),
      );
      requireValue(
        !existing.docs.some((s) => {
          const t = s.data();
          return (
            t.enabled &&
            t.metric === input.metric &&
            t.period === input.period &&
            t.effectiveFrom < input.effectiveTo &&
            t.effectiveTo > input.effectiveFrom
          );
        }),
        "TARGET_OVERLAP",
        "A target for this metric and period already covers these dates.",
        409,
      );
      // Immutable definitions: changing a value creates a new effective-date version.
      const r = this.ref(cid, "targetDefinitions", key);
      tx.create(r, {
        targetId: key,
        name: text(input.name, 160),
        employeeUid,
        metric: input.metric,
        period: input.period,
        value: input.value,
        effectiveFrom: input.effectiveFrom,
        effectiveTo: input.effectiveTo,
        enabled: true,
        createdBy: uid,
        createdAt: this.clock(),
        reason: text(input.reason || ""),
      });
      return { id: key };
    });
  }
}
