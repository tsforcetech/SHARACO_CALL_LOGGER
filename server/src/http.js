import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { ApiError, id, requireValue } from "./domain.js";
export function createApi({
  service,
  verifyToken,
  verifyAppCheck,
  origins = [],
}) {
  const buckets = new Map();
  return createServer(async (req, res) => {
    const requestId = randomUUID(),
      origin = req.headers.origin;
    const respond = (status, body) => {
      res.writeHead(status, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "X-Request-Id": requestId,
      });
      res.end(JSON.stringify(body));
    };
    try {
      if (origin) {
        requireValue(
          origins.includes(origin),
          "ORIGIN_DENIED",
          "This website is not allowed.",
          403,
        );
        res.setHeader("Access-Control-Allow-Origin", origin);
        res.setHeader("Vary", "Origin");
      }
      if (req.method === "OPTIONS") {
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.setHeader(
          "Access-Control-Allow-Headers",
          "Authorization, Content-Type, X-Firebase-AppCheck, Idempotency-Key",
        );
        res.writeHead(204);
        res.end();
        return;
      }
      const url = new URL(req.url, "http://localhost");
      if (req.method === "GET" && url.pathname === "/health") {
        respond(200, { status: "ok" });
        return;
      }
      const token = req.headers.authorization?.match(/^Bearer (\S+)$/)?.[1];
      requireValue(token, "UNAUTHENTICATED", "Sign in to continue.", 401);
      let user;
      try {
        user = await verifyToken(token);
      } catch {
        throw new ApiError(
          "UNAUTHENTICATED",
          "Your session expired. Sign in again.",
          401,
        );
      }
      requireValue(
        user.email_verified,
        "EMAIL_NOT_VERIFIED",
        "Use a verified account.",
        403,
      );
      if (verifyAppCheck) {
        try {
          await verifyAppCheck(req.headers["x-firebase-appcheck"]);
        } catch {
          throw new ApiError(
            "APP_CHECK_FAILED",
            "App verification failed. Reload or update the app.",
            403,
          );
        }
      }
      const now = Date.now(),
        bucket = buckets.get(user.uid) || { start: now, count: 0 };
      if (now - bucket.start > 60000) {
        bucket.start = now;
        bucket.count = 0;
      }
      requireValue(
        ++bucket.count <= 120,
        "RATE_LIMITED",
        "Too many requests. Try again shortly.",
        429,
      );
      buckets.set(user.uid, bucket);
      if (buckets.size > 10000)
        for (const [k, v] of buckets)
          if (now - v.start > 60000) buckets.delete(k);
      const match = url.pathname.match(/^\/v1\/companies\/([^/]+)\/([^/]+)$/);
      requireValue(match, "NOT_FOUND", "Endpoint not found.", 404);
      const cid = id(match[1]),
        action = match[2];
      if (req.method === "GET") {
        respond(
          200,
          await service.list(cid, user.uid, action, {
            after: url.searchParams.get("after") || "",
            limit: Number(url.searchParams.get("limit") || 50),
          }),
        );
        return;
      }
      requireValue(
        req.method === "POST",
        "METHOD_NOT_ALLOWED",
        "Method not allowed.",
        405,
      );
      requireValue(
        req.headers["content-type"]?.split(";")[0] === "application/json",
        "JSON_REQUIRED",
        "Send JSON.",
      );
      let size = 0,
        chunks = [];
      for await (const chunk of req) {
        size += chunk.length;
        requireValue(
          size <= 2 * 1024 * 1024,
          "PAYLOAD_TOO_LARGE",
          "Upload is too large.",
          413,
        );
        chunks.push(chunk);
      }
      let body;
      try {
        body = JSON.parse(Buffer.concat(chunks).toString());
      } catch {
        throw new ApiError("INVALID_JSON", "Request contains invalid JSON.");
      }
      requireValue(
        body && typeof body === "object" && !Array.isArray(body),
        "INVALID_BODY",
        "Expected a JSON object.",
      );
      const key = id(req.headers["idempotency-key"]);
      const handlers = {
        initialize: "initialize",
        campaigns: "campaign",
        imports: "importLeads",
        claim: "claim",
        dispositions: "disposition",
        targets: "target",
      };
      requireValue(handlers[action], "NOT_FOUND", "Endpoint not found.", 404);
      const result = await service[handlers[action]](cid, user.uid, key, body);
      respond(200, result);
    } catch (e) {
      // Do not log customer payloads, bearer tokens or service-account credentials.
      console.error(
        JSON.stringify({
          requestId,
          code: e.code || "INTERNAL",
          status: e.status || 500,
        }),
      );
      respond(e.status || 500, {
        error: {
          code: e.code || "INTERNAL",
          message:
            e instanceof ApiError
              ? e.message
              : "The operation could not be completed. Retry using the same request.",
          requestId,
        },
      });
    }
  });
}
