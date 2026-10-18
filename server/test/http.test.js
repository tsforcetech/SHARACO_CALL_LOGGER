import test from "node:test";
import assert from "node:assert/strict";
import { createApi } from "../src/http.js";
test("HTTP requires identity, App Check, approved origins and explicit actions", async () => {
  const api = createApi({
    service: { list: async () => ({ rows: [] }) },
    verifyToken: async (token) => {
      if (token !== "valid") throw Error();
      return { uid: "u", email_verified: true };
    },
    verifyAppCheck: async (token) => {
      if (token !== "app") throw Error();
    },
    origins: ["https://crm.example.com"],
  });
  await new Promise((r) => api.listen(0, "127.0.0.1", r));
  const url = `http://127.0.0.1:${api.address().port}/v1/companies/c/leads`;
  try {
    assert.equal((await fetch(url)).status, 401);
    assert.equal(
      (await fetch(url, { headers: { Authorization: "Bearer valid" } })).status,
      403,
    );
    const headers = {
      Authorization: "Bearer valid",
      "X-Firebase-AppCheck": "app",
    };
    assert.equal(
      (
        await fetch(url, {
          headers: { ...headers, Origin: "https://evil.example" },
        })
      ).status,
      403,
    );
    assert.equal((await fetch(url, { headers })).status, 200);
    assert.equal(
      (
        await fetch(url, {
          method: "POST",
          headers: {
            ...headers,
            "Content-Type": "application/json",
            "Idempotency-Key": "key",
          },
          body: "{",
        })
      ).status,
      400,
    );
  } finally {
    await new Promise((r) => api.close(r));
  }
});
