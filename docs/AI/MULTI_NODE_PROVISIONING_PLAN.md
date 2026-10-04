# AgriTrace Multi-Node Provisioning Plan

**Purpose:** planning only. No databases, certificates, services, or application files were provisioned or changed while preparing this document.
**Evidence basis:** current `AGENTS.md`, `docs/AI/PROJECT_STATUS.md`, `docs/MULTI_NODE_ACCEPTANCE.md`, `docs/API.md`, and the source code named below.

## 1. Goal

Provision three independently operated AgriTrace nodes and execute the documented mTLS, transaction/block synchronization, shipment relay, fork, retry, disconnect/reconnect, and restart acceptance scenarios.

The current implementation supports significant parts of this topology, but a clean first-time bootstrap is not yet described or supported by a checked-in tool. In particular, startup requires the local peer to already be active in the canonical registry, while the documented governance APIs themselves require the application runtime to be started. Resolve the bootstrap decisions in §20 before treating the sequence in §14 as executable.

## 2. Node topology

```text
Browser / operator clients
     | HTTPS (browser-facing listener; no mandatory node client certificate)
     +--> Node A: WAR /AgriTrace --> MySQL instance A, schema agritrace
     +--> Node B: WAR /AgriTrace --> MySQL instance B, schema agritrace
     +--> Node C: WAR /AgriTrace --> MySQL instance C, schema agritrace

Node A <---- HTTPS + mTLS / P2P ----> Node B
   |  \                                  /  |
   |   +------ HTTPS + mTLS -----------+   |
   +------------- HTTPS + mTLS ------------+
```

| Node | Runtime identity | Advertised endpoint | Database | Values not specified in source |
|---|---|---|---|---|
| A | Unique `peerId` and one client PKCS#12 identity | `https://<A-host>:<A-TLS-port>/AgriTrace` by default WAR context | Its own MySQL instance and `agritrace` database (recommended to use the schema unchanged) | Host, TLS port, peer ID, organization, storage locations |
| B | Unique `peerId` and its own client PKCS#12 identity | `https://<B-host>:<B-TLS-port>/AgriTrace` | Its own MySQL instance and `agritrace` database | Same categories as A |
| C | Unique `peerId` and its own client PKCS#12 identity | `https://<C-host>:<C-TLS-port>/AgriTrace` | Its own MySQL instance and `agritrace` database | Same categories as A |

The WAR final name is `AgriTrace`, so a default Tomcat deployment context is `/AgriTrace`; a context override is possible outside the application and must be reflected in every peer endpoint and probe URL. HTTP port, HTTPS port, MySQL host/port, DNS names, and whether these instances share a physical machine are **UNKNOWN — MUST BE DECIDED**. Ports 8080 and 8443 were not reachable in the local preflight and are not selected by project configuration.

## 3. Node A configuration

Configure values independently for A; do not copy B/C credentials or identity files.

- WAR: current `target/AgriTrace.war`, deployed by the operator to a Tomcat instance.
- Context: `/AgriTrace` unless the operator intentionally configures another path.
- Database: unique MySQL instance; schema name `agritrace` works with the checked-in `schema.sql` as written.
- JDBC URL/username/password: local protected environment or JVM properties; exact host, port, account, and password are **UNKNOWN — MUST BE DECIDED**.
- `AGRITRACE_P2P_PEER_ID`: unique A peer ID.
- `AGRITRACE_P2P_KEYSTORE_PATH`: absolute protected path to A's PKCS#12 client identity.
- `AGRITRACE_P2P_KEYSTORE_PASSWORD`: supplied locally through a secret-safe mechanism; never place in this plan, command history, or repository.
- HTTPS endpoint in the canonical peer record: A's externally reachable HTTPS base URL including context path.
- TLS server keystore/trust and connector settings: configure in Tomcat/proxy, outside this repository.

## 4. Node B configuration

Same application requirements as A, with B's own database instance, distinct peer ID, own PKCS#12 client identity, own HTTPS endpoint, server identity, logs, and protected storage. B must not reuse A's mutable database or private key. Exact host, ports, organization, paths, and credentials are **UNKNOWN — MUST BE DECIDED**.

## 5. Node C configuration

Same application requirements as A/B, with C's own database instance, distinct peer ID, own PKCS#12 client identity, own HTTPS endpoint, server identity, logs, and protected storage. C must not reuse A/B mutable database or private keys. Exact host, ports, organization, paths, and credentials are **UNKNOWN — MUST BE DECIDED**.

## 6. Database layout

### FACTS from repository

- Runtime JDBC configuration is mandatory: `AGRITRACE_DB_URL`, `AGRITRACE_DB_USERNAME`, and `AGRITRACE_DB_PASSWORD` (or JVM properties `agritrace.db.url`, `.username`, and `.password`). There is no implicit local database default. JDBC URLs must start with `jdbc:mysql:`; JDBC sessions are set to UTC.
- `database/schema.sql` creates and selects a database literally named `agritrace`. It creates 14 tables, including `network_config`, transaction/block tables, pool/status tables, governance projections, local users, shipment drafts/signatures, and batch projections.
- The checked-in schema has no `INSERT` seed for `network_config` or `users`. `NetworkConfigDAO` requires exactly one valid row with `id=1`; startup also requires configured genesis timestamp and nonce.
- Each node's writes and local state must be isolated. All nodes in one network need the same immutable network/genesis configuration and converge through validated blocks. Local users, pending pool state, and shipment workflow records are node-local.
- `database/schema.sql` is the new-database schema. Migrations 001/002 target existing databases. Inspect migration state before any later deployment; never rerun blindly.

### Proposed isolation

Use one independent MySQL instance per node, each with a database named `agritrace`, and import the unchanged schema into each instance. This fits the hard-coded `CREATE DATABASE agritrace` / `USE agritrace` statements. The instances may be on one host only if each has isolated server state and a separately chosen listener/credential set.

Using three differently named databases on a single MySQL server is **not directly supported by the checked-in schema file**: each import selects the same `agritrace` name. A safe, supported alternate importer is not present. Do not edit schema SQL or execute a transformed import as part of this plan; decide whether to use separate instances or later authorize an import/configuration change.

Each database will need the same approved `network_config` row and the same validated canonical bootstrap ledger state. The exact secure provisioning mechanism for those values and chain state is **UNKNOWN**. The plan does not prescribe SQL or copying one node's live database to the others.

## 7. TLS/mTLS architecture

There are two TLS directions/configurations:

1. **Inbound:** Tomcat or a fronting proxy terminates HTTPS for the node. For `/api/v1/internal/p2p/*`, it must request and require a client certificate and expose the X.509 chain as `jakarta.servlet.request.X509Certificate`. The `PeerAuthenticationFilter` then computes/validates the leaf certificate fingerprint against the active canonical peer registry. Keep the browser listener separate and do not require consortium client certificates for browser users.
2. **Outbound:** `PeerIdentity` loads the configured PKCS#12 client key into a Java `SSLContext`; `PeerClient` and `PeerLedgerSynchronizer` use it for HTTPS. The code supplies default trust managers, so remote server certificates must chain to the JVM's normal trust configuration. HTTPS hostname verification must succeed for the registered endpoint host.

The repository contains no Tomcat connector, proxy, server keystore, or truststore configuration. Exact connector/proxy configuration and ports are **UNKNOWN — MUST BE DECIDED**.

The acceptance script uses Windows' normal server-certificate trust and intentionally does not disable TLS verification. Thus the script host also needs to trust each HTTPS server certificate chain.

## 8. Certificate requirements

### P2P client certificate / keystore per node

- One separate PKCS#12 (`.p12`/`.pfx`) identity per node.
- Contains exactly one private-key entry and its X.509 certificate; code discovers the alias and does not configure a required alias name.
- Password is required; path must be absolute. Keep the file and password outside the repository and protect both at rest.
- SHA-256 over the encoded leaf X.509 certificate must equal the lowercase 64-hex `tlsCertificateFingerprint` in that node's active peer registration.
- Peer authorization uses the certificate fingerprint, peer ID, active organization, and canonical registry. The application does not bind authorization to a certificate subject string.
- The certificate chain must be acceptable to the inbound Tomcat/proxy client-certificate trust configuration. Exact key usage/EKU policy is not enforced explicitly by application code; define it with the chosen CA/TLS deployment.

### HTTPS server certificate per node

- Tomcat/proxy must present a valid server certificate for its advertised DNS name or IP; its SAN must match the host clients use.
- The chain must be trusted by the other nodes' JVM trust configuration and by the Windows trust configuration used for the acceptance script.
- Each server's private key and keystore remain local and are not the node's P2P client keystore by application requirement. Separate identities are the safer planned arrangement; whether one certificate is reused is not decided by code and should be decided explicitly.

### CA and truststores

- If a private CA is used, its chain must be trusted by Tomcat/proxy for incoming client certificates and by the JVMs for outbound HTTPS server validation. The exact CA hierarchy and rotation process are **UNKNOWN — MUST BE DECIDED**.
- Java uses default trust configuration unless the operator configures a JVM truststore. No AgriTrace-specific truststore environment variable or JVM property is defined.
- Tomcat server-key aliases, Tomcat client-CA truststore aliases, truststore type, and paths are **container-specific / UNKNOWN**. For the PowerShell probe, install trust through normal Windows trust management.

## 9. Peer registration

1. Peer registrations are governance state in the canonical ledger and are projected into the `authorized_peers` table by canonical projection rebuild. They are not configured solely by local SQL or a peer's self-assertion.
2. A node identifies its local peer by configured `AGRITRACE_P2P_PEER_ID`; outbound mTLS uses the matching PKCS#12 leaf certificate.
3. A peer record contains `peerId`, `organizationId`, HTTPS `endpoint`, lowercase SHA-256 certificate fingerprint, and active/revoked state. Endpoint maximum length is 500 characters; fingerprint must be 64 lowercase hex characters.
4. Node A trusts B only when B's client TLS chain is accepted by A's server listener **and** B's fingerprint is uniquely registered as an active peer in A's canonical governance registry, and B's organization is active. B similarly needs A registered. TLS CA trust alone does not authorize application-level peers.
5. C needs an equivalent independently registered peer record. The active organization for each peer must exist and be active on the canonical chain.
6. Registration requires a signed governance `REGISTER_PEER` transaction approved by the configured genesis administrator. It is accepted into transaction processing and takes effect once included in the canonical chain. The organization must already be registered and active.
7. Local peer identity startup verifies that its configured peer record is already active in the local validated canonical chain and that the fingerprint matches. It also requires the peer's organization active.
8. All nodes need the same canonical organization/key/peer history, including A/B/C peer records, after convergence. Peer IDs, keystores, leaf fingerprints, and advertised endpoints must be unique/correct per node. Local users and shipment drafts remain node-local.

### Bootstrap blocker discovered in source

`NodeRuntimeListener` creates only the empty configured genesis when the chain is empty, then immediately calls `PeerIdentity.loadRequired`. That method aborts startup unless the configured local peer is already active in the canonical registry and its certificate fingerprint matches. Governance HTTP endpoints depend on the runtime being initialized. `schema.sql` has no seeded organizations, users, peers, or later governance blocks, and the repository has no supported offline ledger import/bootstrap command. The API deployment note likewise says to register a peer before starting it.

Therefore, a clean schema plus the current startup path cannot bootstrap the first registered peer using only the documented HTTP flow. All three databases need a validated common ledger that includes active organizations and all local peer registrations before those nodes can start, but the supported way to create/provision that ledger is **UNKNOWN**. Do not bypass this with ad hoc SQL or disable identity checks. Resolve this before following a first-time startup sequence.

There is also no checked-in first-admin account seed/bootstrap path: admin governance endpoints require an authenticated admin session, while user administration itself is admin-protected. Provisioning must decide how the first node-local admin account is created safely.

## 10. Genesis requirements

- `GenesisBlockFactory.configuredGenesis` builds height 0 with no parent and an empty transaction list. It uses network ID, UTC timestamp at millisecond precision, nonce, initial PoW difficulty, and the empty-transaction commitment.
- Startup recomputes the genesis header hash and proof of work and rejects a mismatch. It persists genesis only if the canonical chain is empty.
- Each node in one network must have identical `network_id`, `genesis_hash`, `initial_pow_difficulty`, `genesis_timestamp`, `genesis_nonce`, and Base64 SPKI genesis-admin public key in its single `network_config` row.
- Genesis is deterministically configured, not generated independently at every node. The timestamp, nonce, difficulty, network ID, admin public key, and resulting hash must be approved and calculated once for the staging network.
- Genesis itself cannot contain organization/peer registrations in this implementation. Those would be later governance transactions/blocks; no supported first peer bootstrap path is present (see §9).
- The genesis administrator private signing key is needed by an admin client for governance and must remain outside the application/database/repository. No private key is included here.

## 11. Required environment variables

Set per Tomcat process using the local service's protected configuration. Never place secret values in this file or shell command history.

| Variable | Required? | Per-node expectation |
|---|---|---|
| `AGRITRACE_DB_URL` | Yes | JDBC URL to that node's isolated MySQL instance/database. Scheme must be `jdbc:mysql:`. Host/port/DB not prescribed by source beyond schema name behavior. |
| `AGRITRACE_DB_USERNAME` | Yes | Least-privilege DB account selected by operator. |
| `AGRITRACE_DB_PASSWORD` | Yes, secret | Supplied through secret-safe runtime configuration. |
| `AGRITRACE_P2P_PEER_ID` | Yes | Unique peer ID already active in the node's canonical ledger. |
| `AGRITRACE_P2P_KEYSTORE_PATH` | Yes | Absolute path to that node's PKCS#12 client identity. |
| `AGRITRACE_P2P_KEYSTORE_PASSWORD` | Yes, secret | Password for that PKCS#12 file. |
| `AGRITRACE_PUBLIC_BASE_URL` | Optional for P2P | Trusted public app URL used to form QR links; choose per deployment if QR is tested. |
| `AGRITRACE_DB_INTEGRATION` | Test-only opt-in | Enables transaction MySQL integration test; use only an isolated test database. |
| `AGRITRACE_DB_BLOCK_INTEGRATION` | Test-only opt-in | Enables block MySQL integration test; test refuses non-empty state. Not needed to run nodes. |

No server TLS keystore/truststore environment variables are defined by AgriTrace source; configure those using the selected Tomcat/proxy mechanism.

## 12. Required JVM properties

The application accepts the following property alternatives to the runtime variables above:

| JVM property | Variable alternative |
|---|---|
| `agritrace.db.url` | `AGRITRACE_DB_URL` |
| `agritrace.db.username` | `AGRITRACE_DB_USERNAME` |
| `agritrace.db.password` | `AGRITRACE_DB_PASSWORD` |
| `agritrace.p2p.peer.id` | `AGRITRACE_P2P_PEER_ID` |
| `agritrace.p2p.keystore.path` | `AGRITRACE_P2P_KEYSTORE_PATH` |
| `agritrace.p2p.keystore.password` | `AGRITRACE_P2P_KEYSTORE_PASSWORD` |
| `agritrace.public.base.url` | `AGRITRACE_PUBLIC_BASE_URL` |

Standard JVM TLS truststore settings may be needed if the default JVM truststore does not trust the staging server CA; these are not AgriTrace-specific and exact values are deployment-dependent. No custom poll interval setting is present: source schedules synchronization 10 seconds after startup and then every 30 seconds.

## 13. Required files/directories

Per node, provision outside the repository:

- AgriTrace WAR deployed under the chosen Tomcat instance/context.
- Independent MySQL data directory/instance and schema database.
- Protected Tomcat server keystore/certificate and inbound client-CA trust configuration.
- Protected AgriTrace P2P PKCS#12 client keystore containing exactly one private-key entry.
- JVM truststore/CA material when default Java trust does not trust the other nodes' HTTPS servers.
- Node-specific external environment/secret configuration and logs.
- Separately stored genesis-admin signing key on the authorized governance operator's client; never on the web app node.

Exact filesystem roots, service identities, ACLs, hostnames, and retention are **UNKNOWN — MUST BE DECIDED**. No such directories or certificates were created in this task.

## 14. Startup order

### Required logical order (conditional)

The source imposes these preconditions, but a clean bootstrap procedure is missing:

1. Decide the first-admin and initial-ledger bootstrap method (required blocker; see §9 and §20).
2. Select unique node hosts, HTTPS ports, MySQL isolation, context paths, and protected storage locations.
3. Provision three independent MySQL instances/databases and import `database/schema.sql` into each. If existing DBs are used, inspect and apply only the required migrations through a separately approved process.
4. Provision the same approved network/genesis configuration in each `network_config` row and provision the same validated canonical governance/ledger history in each independent node database using a supported, verified mechanism. The latter mechanism is currently unknown; do not continue by manually inserting ledger rows.
5. Provision server certificates/Tomcat inbound TLS client-certificate trust, client PKCS#12 identities, outbound JVM trust, and OS trust for the probe. Do not start the nodes until the endpoint trust and names are decided.
6. Ensure the common canonical history contains active organizations and A/B/C peer records with correct HTTPS endpoints and exact client certificate fingerprints; ensure each organization's active status.
7. Ensure each node has its own local active administrator account through a decided safe bootstrap mechanism if admin operations are needed.
8. Configure each node's DB and P2P environment settings locally. Validate that each local peer ID and PKCS#12 fingerprint match its canonical peer entry.
9. Deploy the WAR to each Tomcat instance with the intended context and separate browser/P2P TLS listener behavior.
10. Start A, B, and C. Once every database already includes that node's active peer record, startup can proceed independently; there is no code-defined dependency requiring a particular order.
11. Verify startup logs and `GET /api/v1/network` on each node. Then run the directed mTLS/convergence probe.
12. Run staged transaction/block and shipment workloads, followed by the manual scenario matrix and record evidence.

**Do not treat steps 4, 6, or 7 as solved by this plan.** They require a user-approved bootstrap/provisioning mechanism not present in the repository.

## 15. Verification order

1. Confirm independent hosts/instances and distinct node/client identities without printing secrets.
2. Confirm `networkId` is identical from `GET /api/v1/network` on all nodes.
3. Confirm each advertised base endpoint reaches the intended node and HTTPS server certificate hostname/trust is valid.
4. Run `Test-MultiNodeP2P.ps1` with the three HTTPS base URLs and corresponding PFX files; enter PFX passwords only at its secure prompts. It tests all six directed client-to-peer connections, reads network/locator endpoints, and waits for canonical tip convergence.
5. If exercising pending sync, submit a valid event through the application, capture its lowercase transaction ID locally, run the probe with `-ExpectedPendingTransactionId` before mining, and verify all peers report it pending.
6. Confirm block sync by producing/confirming a valid event in the authorized staging workflow and rerunning the probe to compare tips; independently query trace on each node.
7. Execute manual unauthorized/revoked/missing-client-certificate, shipment duplicate, fault/reconnect, fork, restart, and persistence scenarios in §16.
8. Save run date, software/config version (excluding secrets), node names, sanitized logs, expected result, actual result, and operator for each scenario.

The existing probe is read-only: it does not create users, transactions, blocks, peer records, or DB rows. Workload creation and disruptive tests are separate actions requiring provisioned staging and explicit execution approval.

## 16. Acceptance scenarios

| # | Scenario | Current coverage | Execution class / missing evidence |
|---:|---|---|---|
| 1 | Node startup | Unit tests cover some component behavior; no live startup test. | INFRASTRUCTURE-DEPENDENT: start each configured Tomcat and verify runtime/genesis/peer initialization. |
| 2 | TLS handshake | Probe uses HTTPS and normal Windows trust. | INFRASTRUCTURE-DEPENDENT: probe exercises live server certificate and client TLS setup; no local server exists now. |
| 3 | mTLS authentication | `PeerAuthenticatorTest` and `PeerAuthenticationFilterTest`; probe sends each node's client certificate. | AUTOMATED logic tests + INFRASTRUCTURE-DEPENDENT live handshake. |
| 4 | Peer authorization | Unit tests cover valid/invalid/revoked/duplicate conditions; docs request an unregistered-cert negative check. | MANUAL/INFRASTRUCTURE-DEPENDENT negative handshake/authorization scenario not performed by the probe. |
| 5 | Transaction creation | Transaction/API unit tests exist. Probe creates nothing. | MANUAL workload setup on staging. |
| 6 | Transaction propagation | Synchronizer pulls paginated pending transactions and validates locally; optional probe checks a supplied pending ID on all nodes. | AUTOMATED implementation + INFRASTRUCTURE-DEPENDENT runtime test; probe requires user-created transaction. |
| 7 | Block propagation | Synchronizer pulls blocks after shared locator; probe checks tip convergence. | INFRASTRUCTURE-DEPENDENT: requires a valid block to be produced elsewhere. |
| 8 | Ledger synchronization | Locator, next-block, wire codec, and service tests exist. | AUTOMATED logic tests + INFRASTRUCTURE-DEPENDENT sync; no real-node result. |
| 9 | Convergence | Probe compares network ID and canonical height/hash until timeout. | AUTOMATED read-only probe, but requires three running nodes and mutual peer authorization. |
| 10 | Shipment relay | Shipment relayer/service tests exist; docs specify sender→carrier proposal and carrier→sender endorsement. | MANUAL/INFRASTRUCTURE-DEPENDENT; not exercised by convergence probe. |
| 11 | Fork handling | Fork-choice and blockchain unit tests. | MANUAL/INFRASTRUCTURE-DEPENDENT competing-branch scenario; acceptance probe only observes final tip. |
| 12 | Retry | Ledger polling repeats every 30 seconds after a 10-second startup delay; shipment delivery is synchronous with a 10-second request timeout. | MANUAL: ledger retry on later poll; repeat identical shipment request to verify idempotency. No separate shipment retry queue is evident in current source. |
| 13 | Node disconnect | Acceptance guide asks operators to isolate a peer. | MANUAL/INFRASTRUCTURE-DEPENDENT fault injection; no automated network partition harness. |
| 14 | Node reconnect | Scheduled ledger poll can run again after connectivity returns. | MANUAL/INFRASTRUCTURE-DEPENDENT: observe eventual convergence and logs. Shipment draft/endorsement relay may need a repeated request; it is not the ledger poll. |
| 15 | Node restart | Code initializes/replays canonical state on startup; no live restart test. | MANUAL/INFRASTRUCTURE-DEPENDENT. |
| 16 | Persistence after restart | MySQL integration tests exist but were skipped in current run. | AUTOMATED opt-in DB tests + MANUAL live-node restart check. |

### Current acceptance coverage classification

- **AUTOMATED:** unit tests for canonical codecs, peer certificate fingerprint authorization/filter behavior, ledger wire codecs, `PeerLedgerService`, fork choice, and parts of shipment service. Current clean suite was 174 total, 0 failures/errors, 2 DB integration tests skipped (from prior verified status; not rerun for this planning task).
- **AUTOMATED (runtime probe):** `scripts/acceptance/Test-MultiNodeP2P.ps1` checks six directed mTLS-protected reads, common network ID, canonical tip convergence, and optionally pending transaction replication. It does not create test workload.
- **MANUAL:** the scenario matrix includes distinct identities/stores, negative peer cert, pending replication, block convergence, shipment duplicate/retry, disconnect/reconnect, fork choice, and restart.
- **INFRASTRUCTURE-DEPENDENT:** all real TLS, multi-node convergence, DB persistence, service restart, and fault scenarios require provisioned staging.
- **NOT IMPLEMENTED:** a full automated three-node orchestrator, automatic transaction/block workload setup for the probe, browser E2E acceptance, and automated network fault/fork/restart harness.

## 17. Expected results

- Each node reports the same network ID and canonical genesis hash, then converges to the same canonical tip when the network is connected and no new work is being mined.
- Each directed request from an active registered client certificate is accepted; missing, untrusted, revoked, unregistered, wrong-fingerprint, or inactive-organization peers are rejected at TLS or application authorization.
- A pending transaction is independently revalidated and enters each receiving node's local pending pool; a block is independently validated and can change the canonical tip only by the configured cumulative-work/fork-choice rule.
- Repeating the same shipment proposal/endorsement is idempotent; two different shipment transaction IDs for the same already-submitted proposal are rejected.
- After network recovery/restart, ledger nodes resume scheduled synchronization and eventually converge without manual database edits. Record any shipment workflow item that requires explicit user retry separately.
- Exact HTTP status and state observations should be captured from the running build; the source/docs define contracts, but no staging run has established these outcomes yet.

## 18. Cleanup/reset procedure

No cleanup or reset was performed. No database was created, dropped, truncated, or changed.

For a future approved run, use disposable staging instances and keep run evidence separate from secrets. Stop only the specifically identified staging Tomcat services after preserving logs. Reset by retiring/recreating the specifically approved disposable MySQL instances through their operator-managed procedure and reprovisioning from the approved schema/genesis/ledger bootstrap artifact. Do not run blanket `DROP`, `TRUNCATE`, database reset commands, or reuse a developer/production database. If no supported ledger bootstrap artifact exists, stop and resolve §9/§20 instead of improvising SQL.

## 19. Risks

1. **Bootstrap deadlock:** a clean node creates empty genesis, then fails startup because its configured peer isn't active; the admin/governance API cannot run until startup succeeds. There is no documented supported way to seed first admin/organizations/peer governance and then supply that validated history to each node.
2. **Database import ambiguity:** the schema hard-codes database name `agritrace`, limiting straightforward three-schema provisioning on one MySQL server. No parameterized import tooling exists.
3. **TLS configuration is external:** wrong Tomcat client-auth, CA trust, hostname/SAN, context path, or proxy behavior can block the mTLS filter or break connection security.
4. **Separate trust layers:** server CA trust (JVM/Windows) and application peer fingerprint authorization are both required; satisfying one does not satisfy the other.
5. **Independent ledgers must start consistently:** network/genesis mismatch or missing common canonical history can prevent communication/startup or create divergent histories.
6. **Shipment delivery differs from ledger sync:** shipment relay is synchronous; periodic ledger synchronization does not automatically resend a lost local proposal/endorsement. Test retry behavior explicitly.
7. **DB tests are not current evidence:** the two opt-in integration tests were skipped in the last verified build. The service state showing MySQL running does not establish schema correctness or safe test-database selection.
8. **Identity/secret handling:** do not copy keystores, passwords, genesis-admin signing keys, or writable databases between nodes or into source control.

## 20. Unknowns requiring user decision

These cannot be derived from repository code or current local preflight:

1. **Existing network or new staging network?** If new, choose network ID, initial PoW difficulty, genesis timestamp/nonce/hash, and who controls the genesis-admin signing key.
2. **Supported bootstrap route:** how to create the first local admin account and a validated governance ledger containing active organizations and all peer registrations before each node's strict startup check. Is there an existing trusted ledger/consortium admin service/artifact, or must a separate bootstrap feature/tool be authorized later? Current source has no clean supported answer.
3. **Organization assignment:** organization IDs/types for nodes A/B/C; whether each node maps to a separate organization or multiple peers may be operated by one organization.
4. **Network topology:** hostnames/DNS, TLS ports, browser port, app context override, firewall paths, and whether nodes share a physical host.
5. **MySQL topology:** separate MySQL instances/hosts (fits unchanged schema) or one server with a future approved way to provision three distinct schema names; choose DB endpoints and least-privilege accounts locally.
6. **Certificate authority:** private CA or another trust model; server and client certificate issuance, SAN names, client-auth trust, server trust distribution, renewal/revocation, and key custody.
7. **Protected paths and service accounts:** Tomcat installation/context, server keystore/truststore locations, P2P PKCS#12 locations, DB service locations, log retention, and filesystem ACLs.
8. **Staging workload/acceptance operators:** who creates the valid transaction/block/shipment test fixtures and who records/signs off each manual scenario.

### Decisions already derivable

- Each node needs a unique configured peer ID and P2P client certificate whose leaf fingerprint is registered on the common active canonical ledger.
- Each node needs independent mutable MySQL state and local secrets; the unchanged schema uses the database name `agritrace`.
- All nodes need exactly matching network/genesis settings and a common canonical governance history that contains each active peer before that peer starts.
- Default WAR name implies `/AgriTrace`; actual context can be overridden but must match peer endpoints and script URLs.
- The P2P client keystore is PKCS#12, absolute-path configured, exactly one private-key entry; no alias name is configured in application code.

### Source references

- Startup, network/genesis and peer identity: `src/main/java/config/NodeRuntimeListener.java`, `NetworkConfiguration.java`, `PeerIdentityConfiguration.java`, `src/main/java/dal/NetworkConfigDAO.java`, `src/main/java/blockchain/GenesisBlockFactory.java`, `src/main/java/network/PeerIdentity.java`.
- TLS fingerprint authorization and Servlet certificate extraction: `src/main/java/network/PeerAuthenticator.java`, `src/main/java/security/PeerAuthenticationFilter.java`.
- Outbound HTTPS, ledger sync and P2P routes: `src/main/java/network/PeerClient.java`, `PeerLedgerSynchronizer.java`, `src/main/java/service/PeerLedgerService.java`, `src/main/java/controller/PeerLedgerServlet.java`.
- Shipment relay and peer admission: `src/main/java/controller/PeerShipmentProposalServlet.java`, `src/main/java/service/ShipmentProposalService.java`, `src/main/java/blockchain/GovernanceRegistry.java`, `src/main/java/controller/AdminGovernanceServlet.java`.
- Database isolation/schema and stored chain: `database/schema.sql`, `src/main/java/dal/BlockDAO.java`, `src/main/java/dal/CanonicalProjectionDAO.java`.
