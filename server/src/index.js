import { initializeApp, applicationDefault } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getAppCheck } from "firebase-admin/app-check";
import { getFirestore } from "firebase-admin/firestore";
import { CrmService } from "./service.js";
import { createApi } from "./http.js";
const projectId = process.env.GOOGLE_CLOUD_PROJECT || "sharaco-call-logger";
const emulator = !!process.env.FIRESTORE_EMULATOR_HOST;
if (emulator && !projectId.startsWith("demo-"))
  throw new Error("Emulator mode requires a demo- project ID.");
if (!emulator && process.env.REQUIRE_APP_CHECK !== "true")
  throw new Error("Set REQUIRE_APP_CHECK=true for production.");
const app = initializeApp(
  emulator ? { projectId } : { projectId, credential: applicationDefault() },
);
const db = getFirestore(app),
  service = new CrmService(db);
const server = createApi({
  service,
  verifyToken: (t) => getAuth(app).verifyIdToken(t, true),
  verifyAppCheck: emulator ? undefined : (t) => getAppCheck(app).verifyToken(t),
  origins: (process.env.WEB_ORIGINS || "").split(",").filter(Boolean),
});
server.requestTimeout = 30000;
server.headersTimeout = 15000;
server.listen(Number(process.env.PORT || 8080), "0.0.0.0", () =>
  console.log("CallTrack CRM API listening"),
);
let running = false;
const timer = setInterval(async () => {
  if (running) return;
  running = true;
  try {
    const jobs = await db
      .collectionGroup("importJobs")
      .where("status", "in", ["queued", "running"])
      .limit(5)
      .get();
    for (const job of jobs.docs)
      await service.processImport(job.ref.parent.parent.id, job.id);
  } catch (e) {
    console.error(
      JSON.stringify({ worker: "import", code: e.code || "INTERNAL" }),
    );
  } finally {
    running = false;
  }
}, 15000);
const stop = () => {
  clearInterval(timer);
  server.close(() => process.exit(0));
};
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
