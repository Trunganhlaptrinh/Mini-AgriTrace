# AgriTrace Project Status

**Status date:** 2026-10-05

**Authority:** Current repository files, this session's clean Maven verification, and the local infrastructure preflight below. Historical Copilot statements are included only as development context and are not treated as current runtime evidence.

## 1. Project Overview

### FACT

AgriTrace is a Java web application for recording and looking up agricultural batch traceability. The domain includes farmers, carriers, warehouses, and retailers; local administrators manage accounts and submit governance operations. The browser/API supports batch lifecycle events, shipment proposals and carrier endorsement, transaction status, and public trace/QR lookup.

Confirmed batch events and governance are signed ledger transactions. Shipment drafts/signatures, user accounts, transaction pool/status, stored chain data, and rebuildable projections are persisted in MySQL on each node. Public trace returns a deliberately allowlisted record.

### INFERENCE

The product is intended for a consortium where organizations operate independently administered nodes and need a shared verifiable event history without publishing private account/workflow data on-chain. This follows from the architecture and current feature set; no independent production requirements document is present in this repository.

### UNKNOWN

Production consortium membership, throughput/retention targets, public hosting model, operator support model, and deployment topology have not been established by the current repository.

## 2. Current Technology Stack

- Java source/bytecode target: Java 17 (`pom.xml`); this session used a Java 25 runtime to invoke Maven.
- Maven project: `com.trunganh:AgriTrace:1.0-SNAPSHOT`, `war` packaging.
- Jakarta EE 10 API with annotation-mapped Servlets, Filters, and Listeners. The exact Tomcat version is not specified.
- MySQL JDBC Connector/J 8.4.0 dependency. The source does not pin a MySQL server version.
- Gson, Java JSON Canonicalization Scheme library, ZXing Core, and JUnit Jupiter 5.11.4.
- Browser frontend: HTML, CSS, JavaScript, and Web Crypto; no frontend framework or build pipeline is configured.
- Deployment artifacts/configuration: WAR packaging exists. No AgriTrace Dockerfile, Compose deployment, or hosting-platform configuration is present.

## 3. Current Architecture

### Application

Servlets in `src/main/java/controller` map `/api/v1` HTTP routes. Services enforce workflows and coordinate blockchain/persistence. DAOs use JDBC/MySQL. Domain types are in `model`; `blockchain` implements encoding, signatures, transaction/block validation, PoW, governance state, mining, and fork choice; `network` implements peer authentication/client, wire codecs, shipment relaying, and ledger synchronization; `security` implements browser and peer filters. The single-page browser app is `src/main/webapp/index.html`, `css/app.css`, and `js/app.js`.

Servlet annotations are the route mapping mechanism; no `web.xml` or Tomcat configuration is present in the project source tree. `NodeRuntimeListener` validates network/genesis configuration, reconstructs the runtime chain, and starts peer synchronization.

An offline operator path exists in `bootstrap/ConsortiumBootstrapCli` and `ConsortiumBootstrapService`. It verifies the signed public manifest and deterministic genesis/initial blocks; `BootstrapStateVerifier` reads and validates the canonical ledger, transaction/status counts, governance projections/provenance, local ADMIN, and stored manifest digest/environment. It classifies a database as uninitialized, exact-prefix resumable, initialized, inconsistent, different network/manifest, or unexpected data. `initialize` uses a MySQL named lock, checks the local peer/certificate before writes, resumes only a verified prefix through `Blockchain`/`BlockDAO`, and creates the local ADMIN through `PasswordHasher`/`UserDAO` once. BOOT-IT-01 passed against the dedicated marked `agritrace_test` catalog. The test exposed and led to a fix for initial-block validation starting without the configured genesis parent.

### Database

`database/schema.sql` defines network configuration, transactions, blocks and block inclusions, pending pool, node transaction status, organizations, organization keys, authorized peers, local users, shipment proposals/signatures, batches, and batch events. SQL constraints and DAO transactions protect key relationships and canonical updates.

`blockchain_transactions` and `blockchain_blocks` persist ledger data. `transaction_pool` and `node_transaction_status` reflect node-local admission/confirmation. `organizations`, keys, peers, batches, and events are rebuildable projections. `users` and shipment proposal/signature records are node-local; user organization references intentionally do not cascade with projection rows. Block storage/reorganization and projection replacement are handled transactionally by `BlockDAO` and `CanonicalProjectionDAO`.

Migrations 001, 002, and 003 are present. Migration 001 removes foreign keys that would couple local records to re-buildable canonical projections and adds `users.organization_canonical`; `schema.sql` already contains that resulting shape for new databases. Migration 002 adds genesis timestamp/nonce fields to existing network configuration. Migration 003 adds nullable bootstrap manifest digest/environment identity. Do not rerun migrations blindly. The schema does not seed the immutable network/genesis row; nodes need externally provisioned identical network and genesis values.

### Blockchain

`LedgerTransaction` has batch-event and governance forms. `TransactionCodec`, `LedgerTransactionCodec`, and `GovernanceCodec` use canonical JSON and SHA-256-derived IDs/hashes; signatures use ECDSA P-256/SHA-256. `Block`/`BlockHeader` commit to ordered transactions, previous hash, height, timestamp, difficulty, nonce, and transaction commitment. `ProofOfWork` enforces the configured bounded difficulty.

`BlockValidator` validates blocks and all transactions against a branch context; `BusinessRuleValidator` applies batch lifecycle and organization/key rules; `GovernanceRegistry` applies governance state. `Blockchain` replays stored canonical/branch data. `BlockDAO` persists validated blocks, selects canonical tip using cumulative work with deterministic tie-break, returns displaced transactions to pending, updates confirmation state, and atomically rebuilds projections. These behaviors have unit tests and limited MySQL integration tests, which passed in the current build (see Test Status).

### P2P

`PeerIdentity` loads local TLS client identity and matches its fingerprint/peer identity to canonical peer registration. `PeerAuthenticationFilter` and `PeerAuthenticator` protect `/api/v1/internal/p2p/*` based on the container-provided TLS client certificate. `PeerClient` and `PeerLedgerSynchronizer` communicate over HTTPS/mTLS. `PeerLedgerServlet`/`PeerLedgerService` expose pending transaction pages, chain locator, next block, and candidate block/transaction admission. Synchronization is pull-based and remote data is independently validated before use; the scheduled synchronizer starts after startup and polls registered peers.

`ShipmentProposalRelayer` and `ShipmentEndorsementRelayer` relay local proposal/endorsement workflow records. Duplicate/retry and disconnection behavior is specified for real-node acceptance, but has not been verified between deployed nodes. The read-only PowerShell probe checks mTLS requests, network ID, tip convergence, and optionally pending-transaction replication; the scenario matrix separately covers shipment relay, fork, outage/retry, and restart.

### Security

Browser authentication uses node-local users, salted password hashing, sessions, CSRF protection, and role filters. Session cookies are configured by `SessionCookieConfiguration`; admin/node routes require an admin session and peer P2P routes require peer certificate authorization. Batch and governance operations use registered organization/admin public keys. Browser signing imports PKCS#8 into non-extractable Web Crypto memory for the current page and sends signatures rather than private key bytes.

The peer TLS/server trust configuration belongs to the Servlet container/deployment and is not defined in this repository. DB credentials, genesis private signing key, and peer private identities must be provisioned outside the repository. The public QR URL uses an explicit trusted base URL rather than the request `Host` header.

### UI

The UI includes login/session management, key import, batch event signing/submission, shipment proposal creation, carrier inbox/endorsement, transaction status polling, public trace lookup, and QR display. Implementations are in `index.html`/`app.js`; there is no browser end-to-end test suite or live node smoke-test evidence in the current project.

## 4. Current Request / Response Flows

- **Login/session:** browser `app.js` → `POST /api/v1/auth/login` (`LoginServlet`) → `AuthenticationService` → `UserDAO` → local user table; response establishes a session and CSRF token. `/api/v1/auth/*` is protected by authentication/CSRF filters as appropriate.
- **Batch event:** browser canonicalizes and signs event using Web Crypto → `POST /api/v1/batches/*` (`BatchEventServlet`) → `BatchService`/`TransactionService` → transaction validation/pool and `TransactionDAO`; accepted status is pending until a block confirms it.
- **Governance:** admin browser/API → governance Servlet (`AdminGovernanceServlet` / `AdminOrganizationServlet`) with admin session, CSRF, and genesis-admin signature → `GovernanceService` and codec/validator → transaction pool/DAO → canonical block changes registry projection.
- **Shipment:** browser → `ShipmentServlet` → `ShipmentProposalService` / `ShipmentProposalDAO` stores local draft and endorsements; `PeerShipmentProposalServlet` plus shipment wire codecs/relayers handle peer transfer. Final signed `SHIPPED` transaction is independently admitted into transaction processing; block confirmation updates canonical batch state.
- **Ledger sync:** scheduled `PeerLedgerSynchronizer` → authenticated `PeerClient` → remote `PeerLedgerServlet` routes → `PeerLedgerService`/DAOs. Pending transactions are paged; blocks are pulled after a common locator and processed by local `Blockchain`/`BlockValidator`/`BlockDAO`.
- **Transaction status:** browser → `/api/v1/transactions/*` (`TransactionStatusServlet`) → transaction status service/DAO → pending/confirmed/rejected state with canonical block metadata when confirmed.
- **Public trace/QR:** anonymous browser → `PublicTraceServlet`/`PublicTraceQrServlet` → `TraceabilityService` and canonical snapshot/projection → allowlisted trace response or locally generated SVG QR.

These flows are traced from current route annotations, services, and DAOs. They are code-path descriptions, not proof of production deployment behavior.

## 5. Current Blockchain State

| Component | State | Evidence / limit |
|---|---|---|
| Canonical transaction JSON, SHA-256 IDs/hashes, signatures | VERIFIED by unit tests | `TransactionCodecTest`, `LedgerTransactionCodecTest`, `SignatureUtilTest`, protocol vectors. |
| Block structure, PoW, parent linkage, commitments, validation | VERIFIED by unit tests | `BlockValidatorTest`, `BlockCodecTest`, `BlockchainTest`; not a multi-node consensus test. |
| Genesis/network configuration and startup validation | IMPLEMENTED — UNVERIFIED in deployment | `NetworkConfigDAO`, `GenesisBlockFactory`, `NodeRuntimeListener`; no provisioned node was started here. |
| Canonical replay and cumulative-work fork choice | VERIFIED by unit tests and opt-in DB integration | `ChainForkChoiceTest`, `BlockchainTest`, `BlockMySqlIntegrationTest`. |
| Canonical projection rebuild and transaction status effects | VERIFIED by opt-in DB integration | `BlockMySqlIntegrationTest` passed. |

## 6. Current P2P State

| Component | State | Evidence / limit |
|---|---|---|
| Peer identity and canonical registry matching | VERIFIED by unit tests | `PeerAuthenticatorTest`; does not establish real container TLS behavior. |
| P2P Servlet filter authorization | VERIFIED by unit tests | `PeerAuthenticationFilterTest`; no real certificate handshake run. |
| Wire codecs and locally validated pending transactions/blocks | VERIFIED by unit tests | `LedgerWireCodecTest`, `PeerLedgerServiceTest`. |
| Scheduled locator/block/pending pull synchronization | IMPLEMENTED — UNVERIFIED across deployed peers | `PeerLedgerSynchronizer`, `NodeRuntimeListener`, API routes; no real peer pair configured. |
| Shipment proposal/endorsement relay | IMPLEMENTED — UNVERIFIED across deployed peers | Service/relayer and service tests exist; duplicate retry/disconnect scenarios remain manual. |
| Three-node convergence, fork, outage/reconnect, and restart acceptance | BLOCKED | Requires three independent Tomcat deployments, databases, server/client identities, trust, and active peer registrations. `docs/MULTI_NODE_ACCEPTANCE.md` records the scenario matrix; not run. |

## 7. Current Security State

- Password hashing, login/session flows, session cookie configuration, authentication/role/CSRF filters, and their unit tests are implemented.
- Organization key signing and admin governance signatures are implemented; private keys are meant to stay with the user/operator.
- Peer authorization checks certificate fingerprints against canonical peer registration; actual mTLS handshakes and deployment trust chains are unverified.
- Request bodies have per-Servlet bounds in state-changing/API routes; public trace/QR behavior has focused tests.
- No credentials are configured in this session. Never copy credentials from historical inputs to reports or source.
- Risks: there is no observed production TLS configuration, secrets provisioning, key rotation/recovery procedure, browser E2E run, or security assessment. These are verification/operational gaps, not confirmed vulnerabilities.

## 8. Current UI State

| UI area | State | Evidence / limit |
|---|---|---|
| Login and session display/logout | IMPLEMENTED — UNVERIFIED in browser against running node | `index.html`, `app.js`; API/service tests exist, no browser E2E. |
| Batch event signing/submission | IMPLEMENTED — UNVERIFIED end-to-end | Web Crypto signing and API route exist; no live chain/browser run. |
| Shipment proposal/inbox/endorsement | IMPLEMENTED — UNVERIFIED across nodes | Browser and Servlet flow exist; peer runtime acceptance pending. |
| Transaction status polling | IMPLEMENTED — UNVERIFIED in browser | UI polls status; Servlet and JSON tests exist. |
| Public trace and QR | IMPLEMENTED; focused server tests pass, browser/scanner not verified | trace and QR Servlets/tests exist; live URL, browser, and scan not exercised here. |

## 9. Database State

- Database name in schema: `agritrace`.
- Important tables: `network_config`, `blockchain_transactions`, `blockchain_blocks`, `block_transactions`, `transaction_pool`, `node_transaction_status`, `organizations`, `organization_keys`, `authorized_peers`, `users`, `shipment_proposals`, `shipment_proposal_signatures`, `batches`, `batch_events`.
- Canonical chain data and local application/workflow data are separated; canonical organization/batch data is rebuildable from validated chain history.
- Migrations 001, 002, and 003 exist; none were executed in this task. Migration 003 adds nullable bootstrap manifest digest/environment columns and intentionally does not backfill old network rows.
- The development catalog `agritrace` was not contacted or modified during the BOOT-IT-01/DB-01 runs.
- The dedicated local test catalog `agritrace_test` was created by applying the current schema table definitions without the schema file's development `CREATE DATABASE/USE agritrace` header, then adding the bootstrap isolation marker.
- BOOT-IT-01, `BlockMySqlIntegrationTest`, and `TransactionMySqlIntegrationTest` ran separately against `agritrace_test` and passed. A final exact count found zero rows across application tables; the isolation marker remained.
- Migration 003 was not executed as a migration; the test catalog was built from the current `schema.sql`, which already contains its new columns.

## 10. Test Status

**Latest full safe build (2026-10-05):** `mvn clean verify` completed successfully and packaged `target/AgriTrace.war`. Per-suite Surefire reports total **181 tests, 0 failures, 0 errors, 3 skipped**. In this full-suite invocation all database opt-in flags were disabled, so no database was contacted.

- **Automated unit/Servlet/service tests:** 178 passed, including initial-block-from-genesis validation, manifest identity, signature, genesis, malformed manifest, exact-prefix, and certificate-fingerprint unit tests.
- **Manifest generator tests:** 3 passed, 0 failures/errors/skips; final local manifest passed the DB-free ConsortiumBootstrapCli validate command on 2026-10-05.
- **Automated MySQL integration:** BOOT-IT-01 passed (1 test); DB-01 passed (`BlockMySqlIntegrationTest`: 1; `TransactionMySqlIntegrationTest`: 1). All used only `agritrace_test`; both DB-01 tests passed in sequence. Exact post-run cleanup verification found zero application-table rows, and the marker remained. The full safe build skipped the three DB suites by design.
- **Automated multi-node tests:** no live-node acceptance run. On 2026-10-05, preflight confirmed Tomcat10 is stopped, no listener was found on the expected local app/P2P ports, and no AGRITRACE/Tomcat environment settings were present. MySQL80 service is running, but that alone does not provide three isolated node databases. The read-only probe script parses without PowerShell syntax errors; it was not run because no node applications were started. Peer PFX identities are now provisioned externally, but there are no active bootstrapped peer registrations yet. Full disruption scenarios remain manual/infrastructure-dependent.
- **Browser/manual acceptance:** `node --check src/main/webapp/js/app.js` passed on 2026-10-05. No browser-to-API workflow was executed: Tomcat is stopped and no browser automation/browser binary is configured in this environment. Servlet unit tests run as part of the Maven suite, but they do not verify the browser UI.
- **WAR build:** passed under Maven; the build target is Java 17, though this session's runtime was Java 25.

Earlier sessions reported different build totals and MySQL outcomes. Current target isolation for BOOT-IT-01 and DB-01 is verified; the full clean build remains a separate no-DB test run.

## 11. Deployment State

- A Maven WAR is produced. A Tomcat deployment guide or pinned container configuration is not present.
- Local execution requires an initialized MySQL schema plus a populated, consistent `network_config` genesis record and secure DB settings. New schema creation alone does not create valid consensus values.
- Every consortium node requires its own DB, server TLS identity, P2P client PKCS#12 identity, trust configuration, and active canonical peer registration. All nodes must share network/genesis settings.
- Docker, Render, or another production hosting deployment is not implemented/verified for AgriTrace.

## 12. Completed Features

The following have meaningful automated verification in the current run: canonical transaction/block encoding and validation; business/governance validation; fork-choice logic; password hashing, login/security filters, peer authorization logic; Servlet/service tests for admin, batch, shipment, status, trace, and QR; wire codec and peer service tests; BOOT-IT-01 recovery/identity cases; DB-01 block reorganization/projection and transaction persistence; WAR compilation/package.

“Completed” here means the specified code path has automated tests or build verification. It does not mean deployment or production acceptance.

## 13. Implemented But Unverified

- End-to-end first boot using a real provisioned genesis/network row.
- Persistence after a live Tomcat restart remains unverified; isolated DB tests covered transaction persistence, block reorganization/projection rebuild, and bootstrap recovery.
- Scheduled P2P pending-transaction/block sync and shipment relay between real Tomcat nodes.
- Browser workflows against a running app, including browser-side signing and transaction confirmation.
- Real mTLS trust and certificate registration/revocation behavior at the container/network layer.
- Actual public URL and QR scan behavior after deployment.

## 14. Partial / Incomplete Features

- Multi-node acceptance has a read-only convergence/pending probe and a detailed scenario matrix, but fork, shipment duplicate retry, network partition/reconnect, and restart procedures remain manual and unexecuted.
- UI implementation exists. JavaScript syntax is verified, but browser/API E2E automation, accessibility review, and responsive behavior verification remain absent.
- Bootstrap recovery interruption/resume and unsafe-state scenarios passed on the dedicated marked test DB. Live Tomcat startup from a bootstrapped database remains unverified.
- Deployment/provisioning documentation for Tomcat, TLS, initial network configuration, secrets, backups, restore, and upgrades is incomplete.

## 15. Not Implemented

- A dedicated automated browser end-to-end test harness is not present.
- AgriTrace-specific Docker/Compose or hosting-platform deployment configuration is not present; the project currently builds a WAR.
- The development database was not migrated. Existing installations still need migration 003 and deliberate review of legacy NULL manifest identities before bootstrap status can match them.

These are repository absences, not claims that a production system cannot provide them externally.

## 16. Blocked Features

- Real three-node acceptance remains blocked pending Tomcat CATALINA_BASE preparation/start and per-node bootstrap/active peer registrations. A common local signed consortium manifest was generated and passed DB-free CLI validation on 2026-10-05. Three isolated MySQL 8.4.11 node databases were provisioned on 2026-10-05 at host ports 3307/3308/3309 with separate volumes. MP-01-CERT generated a local-only CA, six unique RSA leaf identities, per-node truststores and a public certificate inventory outside Git. The app/P2P ports have not been started, and live TLS/mTLS, peer synchronization, and shipment flows remain unverified. The existing MySQL80 catalogs on port 3306 were not targeted by the node provisioning.
- No current blocker remains for BOOT-IT-01 or DB-01; both passed on the dedicated marked `agritrace_test` database and fixture cleanup was verified.

## 17. Known Risks and Gaps

1. The bootstrap integration test injects a local peer verifier; production local PKCS#12 certificate validation and startup against a running Tomcat node remain separately unverified.
2. No runtime evidence confirms servlet-container mTLS setup, real peer authorization, polling/retry convergence, or restart recovery.
3. No automated browser E2E test confirms the complete UI/API/event-signing flow.
4. Bootstrap manifest identity persistence passed on the isolated test DB. The development database was not migrated; migration 003 leaves legacy identities NULL, so existing rows need deliberate operator handling before bootstrap status can match them.
5. Existing installations require careful, one-time migrations; schema.sql is for new DBs and migrations must not be rerun against a schema that already has their effects.
6. There is no checked-in operational configuration for Tomcat/TLS, deployment secrets, backups, restore, or production monitoring. Do not infer a production-ready posture from a successful WAR build.

No additional defect is asserted from these gaps alone; the listed items need verification or operational work.

## 18. Remaining Roadmap

| ID | Task | Priority / category | Status | Why / missing work | Likely files/modules | Dependencies | Verification | Risk |
|---|---|---|---|---|---|---|---|---|
| MP-01-BOOTSTRAP | Bootstrap the three isolated nodes from the shared signed manifest | P1 — Core functionality / infrastructure | READY AFTER APPROVAL | The common manifest is validated; each node still needs a prepared Tomcat base and its own matching peer identity/config, then offline bootstrap against its own isolated database. | `ConsortiumBootstrapCli`, `ConsortiumBootstrapService`, `scripts/local-3node/` | Tomcat 10.1 installation, per-node base/config, isolated node DBs and local identities | Run `validate`, `status`, and `initialize` separately for A/B/C; verify each resulting local ledger and ADMIN | Wrong node identity/database selection must fail safely; do not target the development or test DB. |
| MP-01-ACCEPT | Execute three-node mTLS and ledger synchronization acceptance matrix | P2 — Integration / infrastructure | BLOCKED | No real nodes have established convergence, pending sync, fork choice, outage/retry, shipment relay, or restart behavior. | `docs/MULTI_NODE_ACCEPTANCE.md`, `Test-MultiNodeP2P.ps1`, P2P classes | Three bootstrapped Tomcat deployments and active peer registrations | Run read-only probe and record every scenario outcome/log/version | Consensus/security behavior can differ from unit mocks. |
| UI-01 | Add/run browser-to-API smoke/E2E coverage | P2 — Testing | PARTIAL | UI and unit tests exist, but full login, Web Crypto signing, shipment, status, public trace/QR aren't exercised in a browser against a node. | `src/main/webapp/*`, Servlets, `docs/API.md` | Running provisioned app and browser test setup | Exercise role-appropriate flows, inspect network/console, verify QR URL/scan | Requires secure browser context and usable account/key fixtures. |
| SEC-01 | Complete production security/configuration review | P3 — Security | PARTIAL | Code-level filters have tests, but container TLS trust, certificate lifecycle, secrets, threat model, and live attack scenarios lack evidence. | `security/`, `network/`, container deployment, docs | Target deployment/container design | Review config; test missing/untrusted/revoked certs, CSRF/auth boundaries, key custody, request limits | Do not weaken authentication or copy live secrets into fixtures. |
| OPS-01 | Document and validate provisioning, deployment, upgrade, backup, and restore | P4 — Infrastructure / documentation | PARTIAL | WAR builds; no deployment recipe/config pins Tomcat or proves genesis/DB/TLS setup and recovery. | `pom.xml`, `database/`, `docs/`, external deployment config | Chosen hosting/Tomcat/MySQL topology and operator-owned secrets | Follow clean install/upgrade/backup/restore rehearsal on staging | Incorrect genesis/migration/restore can strand or fork a network. |
| DOC-01 | Maintain contracts and status from verified changes | P5 — Documentation | ONGOING | API, architecture, acceptance, and AI status docs exist and must track future implementation/runtime evidence. | `docs/API.md`, `docs/ARCHITECTURE.md`, `docs/MULTI_NODE_ACCEPTANCE.md`, this file | Each feature/verification result | Review docs against code and test output per change | Historical claims can become stale if not dated and rechecked. |

BOOT-02 and BOOT-03 are implemented; BOOT-IT-01 and DB-01 have now passed against `agritrace_test`. The development `agritrace` database was not used.

## 19. NEXT RECOMMENDED TASK

**MP-01-BOOTSTRAP — Bootstrap Nodes A, B, and C from the same validated signed manifest.**

The local consortium manifest is signed and validated, and the three isolated node databases plus local TLS identities are provisioned. The next dependency is preparing each Tomcat base and running the offline bootstrap separately against its own isolated node database using the same unchanged manifest and its matching peer identity. This establishes peer registrations before live mTLS and convergence acceptance. Never use `agritrace` or `agritrace_test` for node bootstrap.

## 20. Verification Checklist

- [x] `mvn clean verify` succeeds (181 tests; 178 passed, 0 failures/errors, 3 DB tests skipped because DB opt-ins were disabled for the full run).
- [x] Bootstrap codec, initial block linkage, signature, recovery, identity mismatch, and unsafe-state DB scenarios pass on `agritrace_test`.
- [x] Block and transaction persistence integration tests pass on `agritrace_test`.
- [x] `agritrace_test` cleanup check reports zero application rows and retains the isolation marker.
- [ ] Verify startup and genesis/network configuration on a provisioned Tomcat node.
- [ ] Run mTLS probe against three independent nodes.
- [ ] Record pending transaction and block convergence.
- [ ] Record shipment duplicate/retry, disconnect/reconnect, fork-choice, and restart scenarios.
- [x] Check UI JavaScript syntax with Node (`node --check src/main/webapp/js/app.js`).
- [ ] Run browser smoke/E2E coverage for login, signing, shipment, status, public trace, and QR.
- [ ] Verify deployment TLS/secrets/provisioning and backup/restore procedures on staging.

## 21. Documentation Maintenance Rules

After a meaningful feature or verification result, update implementation/test status, blockers, risks, roadmap, and the single next task. Update architecture and API docs when routes, payloads, persistence, trust, or request flows change. Mark automated, manual, and infrastructure-dependent evidence separately. Current code and reproducible results take precedence over this document and historical Copilot statements; correct this status document when they diverge. Never include passwords, private keys, certificate private material, or temporary credential values.

## History vs. current repository

- **UI:** earlier Copilot messages said there was no UI; current `index.html`, `app.js`, and CSS implement the listed workflows. They remain unverified in a live browser/node.
- **Ledger P2P:** earlier history said general transaction/block synchronization was absent. Current `PeerLedgerServlet`, `PeerLedgerService`, codecs, and `PeerLedgerSynchronizer` implement pull synchronization; real-node convergence is unverified.
- **MySQL migration 001:** history records an attempted failure because a foreign key was already absent after the current schema had included the migration's result. Current `schema.sql` has `organization_canonical` and lacks those old coupling constraints. Do not rerun migration 001 without inspecting the target DB.
- **MySQL outcome:** earlier history contained differing outcomes and uncertain isolation. In the current run BOOT-IT-01 and DB-01 passed on the confirmed `agritrace_test` target; exact fixture cleanup was verified.
- **Test totals:** history's last shared result was 173 tests passing. The latest full safe build reports 181 tests, 0 failures, 0 errors, 3 DB suites skipped. Targeted BOOT-IT-01 and DB-01 each passed separately afterward.
- **Three-node acceptance:** history and current acceptance docs both identify it as unexecuted. This remains the principal runtime validation gap.

## Historical-input handling

The Copilot export was read from outside the repository for this analysis only. It is not part of AgriTrace and must not be copied, referenced by application code, documented, committed, or treated as implementation evidence.
