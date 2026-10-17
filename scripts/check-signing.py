"""Compare public APK certificate fingerprints to the preserved Firebase client config."""
import json
import re
from pathlib import Path
report = Path("signing-fingerprints.txt").read_text()
match = re.search(r"certificate SHA-1 digest: ([0-9a-fA-F]+)", report)
if not match:
    raise SystemExit("APK signer verification did not return a SHA-1 fingerprint")
actual = match.group(1).lower()
config = json.loads(Path("app/google-services.json").read_text())
clients = [c for c in config["client"] if c["client_info"]["android_client_info"]["package_name"] == "com.sharaco.callwebhook"]
assert len(clients) == 1, "Expected the existing Firebase Android app registration"
assert any(c["client_type"] == 3 for c in clients[0].get("oauth_client", [])), "Web OAuth client is missing"
registered = {c.get("android_info", {}).get("certificate_hash", "").lower() for c in clients[0].get("oauth_client", []) if c["client_type"] == 1}
if actual in registered:
    result = "PASS: Signed APK certificate matches the preserved Firebase Android OAuth registration."
else:
    result = "WARNING: APK signing SHA-1 is absent from google-services.json. Check/add the SHA-1 and SHA-256 in Firebase Project settings, then download the updated config. Existing console registrations may differ from this file."
    print("::warning::" + result)
print(result)
with Path("signing-fingerprints.txt").open("a") as f:
    f.write("\n" + result + "\n")
