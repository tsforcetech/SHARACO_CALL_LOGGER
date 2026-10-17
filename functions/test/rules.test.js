import test from "node:test";
import { readFile } from "node:fs/promises";
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
test("tenant boundaries, own calls and privileged writes", async () => {
  const env = await initializeTestEnvironment({
    projectId: "demo-calltrack",
    firestore: { rules: await readFile("../firestore.rules", "utf8") },
  });
  try {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      for (const [path, data] of Object.entries({
        "companies/a": { status: "ACTIVE" },
        "companies/b": { status: "ACTIVE" },
        "companies/a/members/owner": { role: "OWNER", status: "ACTIVE" },
        "companies/a/members/staff": { role: "EMPLOYEE", status: "ACTIVE" },
        "companies/a/calls/own": { employeeUid: "staff" },
        "companies/a/calls/other": { employeeUid: "someone" },
        "companies/b/calls/foreign": { employeeUid: "staff" },
        "invitations/secret": { status: "ACTIVE" },
      }))
        await setDoc(doc(db, path), data);
    });
    const staff = env.authenticatedContext("staff").firestore(),
      owner = env.authenticatedContext("owner").firestore();
    await assertSucceeds(getDoc(doc(staff, "companies/a/calls/own")));
    await assertFails(getDoc(doc(staff, "companies/a/calls/other")));
    await assertFails(getDoc(doc(staff, "companies/b/calls/foreign")));
    await assertFails(
      setDoc(doc(staff, "companies/a/members/staff"), { role: "OWNER" }),
    );
    await assertFails(
      setDoc(doc(staff, "companies/a/calls/fake"), { employeeUid: "someone" }),
    );
    await assertFails(getDoc(doc(staff, "invitations/secret")));
    await assertSucceeds(getDoc(doc(owner, "companies/a/calls/other")));
  } finally {
    await env.cleanup();
  }
});
