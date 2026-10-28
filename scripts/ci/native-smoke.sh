#!/usr/bin/env bash
set -euo pipefail
# All credentials and services in this file are disposable CI fixtures.
docker run -d --name blessrom-keycloak-ci --network host --memory 768m \
	-e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
	-e KC_BOOTSTRAP_ADMIN_PASSWORD=ci-keycloak-only \
	quay.io/keycloak/keycloak:26.8.0 start-dev --http-port=18080 >/dev/null
python3 - <<'PY'
import json, time, urllib.request, urllib.parse
base = "http://127.0.0.1:18080"
for attempt in range(90):
    try:
        urllib.request.urlopen(base + "/realms/master/.well-known/openid-configuration", timeout=3).close()
        break
    except (OSError, TimeoutError):
        time.sleep(2)
else:
    raise RuntimeError("CI Keycloak did not start")
data = urllib.parse.urlencode({
    "client_id": "admin-cli", "grant_type": "password",
    "username": "admin", "password": "ci-keycloak-only"
}).encode()
with urllib.request.urlopen(base + "/realms/master/protocol/openid-connect/token", data, timeout=10) as response:
    token = json.load(response)["access_token"]
request = urllib.request.Request(base + "/admin/realms",
    data=json.dumps({"realm": "blessrom", "enabled": True}).encode(),
    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}, method="POST")
urllib.request.urlopen(request, timeout=10).close()
PY
docker run -d --name blessrom-native-ci --network host --memory 512m \
	-e DB_URL=jdbc:postgresql://127.0.0.1:5432/blessrom_native \
	-e DB_USER=blessrom -e DB_PASSWORD=ci-native-only \
	-e OIDC_AUTH_SERVER_URL=http://127.0.0.1:18080/realms/blessrom \
	blessrom-native:ci >/dev/null
python3 - <<'PY'
import json, time, urllib.request, urllib.error
base = "http://127.0.0.1:8080"
def call(path, method="GET", token=None):
    headers = {"X-Cart-Token": token} if token else {}
    request = urllib.request.Request(base + path, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status, json.load(response)
for attempt in range(60):
    try:
        assert call("/q/health/ready")[1]["status"] == "UP"
        break
    except (OSError, TimeoutError, AssertionError):
        time.sleep(2)
else:
    raise RuntimeError("Native application did not become healthy")
assert call("/api/v1/system")[1]["currency"] == "PEN"
assert call("/api/v1/products")[1]["total"] == 0
status, cart = call("/api/v1/carts", "POST")
assert status == 201
assert call("/api/v1/carts/" + cart["id"], token=cart["accessToken"])[1]["status"] == "ACTIVE"
for path, expected in [("/api/v1/admin/products", 401), ("/api/v1/carts/" + cart["id"], 404)]:
    try:
        call(path)
    except urllib.error.HTTPError as error:
        assert error.code == expected, (path, error.code)
    else:
        raise AssertionError("Endpoint accepted an unauthorized request: " + path)
print("Native smoke test passed: PostgreSQL, migrations, OIDC discovery, cart and access controls.")
PY
