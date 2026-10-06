# AgriTrace — Reproducible 3-Node Docker Demo Environment

This document guides any developer through cloning the repository, launching a full 3-node AgriTrace consortium demo environment with Docker Compose, running automated acceptance tests, and inspecting the blockchain ledger.

---

## 1. Overview & Architecture

The demo environment runs **three isolated consortium nodes** plus **three dedicated MySQL databases**, reproducing a real-world multi-organization consortium:

| Node | Organization | Role | Web UI / HTTPS Port | P2P mTLS Port | Admin User | DB Host Port |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Node A** | `org-farmer-a` | `FARMER` | `https://localhost:8443/AgriTrace` | `9443` | `admin-a` | `3317` |
| **Node B** | `org-carrier-b` | `CARRIER` | `https://localhost:8444/AgriTrace` | `9444` | `admin-b` | `3318` |
| **Node C** | `org-retailer-c` | `RETAILER` | `https://localhost:8445/AgriTrace` | `9445` | `admin-c` | `3319` |

### Key Architectural Properties
- **Isolated Databases:** Each node connects strictly to its own dedicated database container (`db-a`, `db-b`, `db-c`). No database sharing between consortium members.
- **Mutual TLS (mTLS) for P2P:** Node-to-node replication endpoints (`/api/v1/internal/p2p/*`) on ports `9443`, `9444`, and `9445` strictly require valid X.509 client certificates issued by the demo consortium CA and matching canonical peer fingerprints registered in governance blocks.
- **Deterministic Genesis & Signed Manifest:** Node A automatically acts as the bootstrap coordinator upon initial launch, generating the demo CA, certificates, P-256 EC keys, and deterministically mining and signing the consortium manifest block. Nodes B and C initialize automatically against this verified manifest.
- **Zero Framework Magic:** Pure Java Servlet (Jakarta EE 10), JDBC, and Apache Tomcat 10.1 in Docker without Spring or external magic.

---

## 2. Quick Start

### Prerequisites
- Docker Engine 24+ & Docker Compose v2+ (e.g., Docker Desktop on Windows/macOS or Docker Engine on Linux)
- Git

### Launch the 3-Node Environment
From the repository root directory:

```bash
docker compose up --build -d
```

Docker Compose will:
1. Build the multi-stage Tomcat runtime image (`AgriTrace.war`).
2. Start the 3 MySQL 8.4 database instances and wait for healthy status.
3. Automatically execute consortium PKI generation, manifest signing, and node bootstrap.
4. Launch the Tomcat 10.1 instances with HTTPS and mTLS connectors.

To follow the startup progress:
```bash
docker compose logs -f node-a
```

Once you see:
```text
[Node A] === AgriTrace Demo Node A Starting ===
...
[Node A] Node A bootstrap completed.
[Node A] Starting Tomcat (node A) ...
```
All 3 nodes are ready!

---

## 3. Running Acceptance Tests

A comprehensive acceptance test script is provided to automatically prove that all nodes, network identities, admin logins, mTLS security guards, and public APIs are operational.

### On Linux / macOS / Git Bash:
```bash
bash docker/demo/test-demo.sh
```

### On Windows PowerShell:
```powershell
powershell -ExecutionPolicy Bypass -File docker/demo/Test-DockerDemo.ps1
```

### Verified Checks:
1. **Endpoint Reachability:** Probes `https://localhost:8443`, `8444`, and `8445`.
2. **Consortium Network Identity:** Verifies `GET /api/v1/network` returns `agritrace-docker-demo-v1`.
3. **Local Administrator Authentication:** Tests `POST /api/v1/auth/login` for `admin-a`, `admin-b`, and `admin-c`.
4. **P2P mTLS Security:** Probes P2P port `9443` without a certificate, verifying immediate rejection (handshake failure / 401 / 403).
5. **Public Traceability QR:** Verifies `GET /api/v1/public/trace-qr/:batchCode` returns valid SVG image data.

---

## 4. Interacting via Browser

Open your browser and navigate to any of the nodes:

- **Node A (Farmer):** [https://localhost:8443/AgriTrace](https://localhost:8443/AgriTrace)
- **Node B (Carrier):** [https://localhost:8444/AgriTrace](https://localhost:8444/AgriTrace)
- **Node C (Retailer):** [https://localhost:8445/AgriTrace](https://localhost:8445/AgriTrace)

*(Note: Because the demo uses a local self-signed CA, your browser will show a certificate warning. Click "Advanced" -> "Proceed to localhost".)*

### Demo Credentials:
| Node | Username | Password | Organization ID | Registered Key ID |
| :--- | :--- | :--- | :--- | :--- |
| Node A | `admin-a` | `AdminA@123456` | `org-farmer-a` | `key-farmer-a-v1` |
| Node B | `admin-b` | `AdminB@123456` | `org-carrier-b` | `key-carrier-b-v1` |
| Node C | `admin-c` | `AdminC@123456` | `org-retailer-c` | `key-retailer-c-v1` |

### Browser Client-Side Signing Keys:
Under the project's zero-trust security architecture, organization private signing keys **never leave the client browser**. They are stored in the demo shared volume and can be copied to your local machine for use in the UI:

```bash
# Copy signing keys from the shared container volume to your host:
docker cp agritrace-node-a:/data/shared/certs/node-a/org-signing-key.pkcs8.pem ./farmer-key.pem
docker cp agritrace-node-b:/data/shared/certs/node-b/org-signing-key.pkcs8.pem ./carrier-key.pem
docker cp agritrace-node-c:/data/shared/certs/node-c/org-signing-key.pkcs8.pem ./retailer-key.pem
```

In the Web UI:
1. Log in (e.g. as `admin-a`).
2. Under **Organization Key Management**, select the downloaded `farmer-key.pem` and enter Key ID `key-farmer-a-v1`.
3. Submit signed harvest, shipment, and receipt events!

---

## 5. Teardown & Reset

To stop the containers:
```bash
docker compose down
```

To completely reset all databases, ledgers, and generated PKI certificates back to a clean state:
```bash
docker compose down -v
```
*(The next `docker compose up` will re-initialize the consortium from a clean state.)*
