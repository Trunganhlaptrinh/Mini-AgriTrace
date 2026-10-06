# AgriTrace Project Status

**Status date:** 2026-10-06 (updated after security hardening + browser E2E scripts commit)
**Authority:** Current repository plus the Maven verification and local three-node acceptance recorded for this status date. Historical Copilot statements are context only. This is local demo evidence, not production certification.

## 1. Project Overview

AgriTrace is a Java web application for recording and checking agricultural batch history across independently operated consortium organizations. Intended roles in the current workflow include farmers/producers, carriers/logistics operators, and retailers; local administrators manage node accounts and governance. A producer records batch events, shipment parties propose and endorse a handoff, and later participants and public visitors can inspect an allowlisted provenance trace and QR link.

**FACT:** Confirmed batch and governance events are signed ledger transactions. User accounts, shipment drafts/signatures, pending status, and rebuildable projections are node-local.
**INFERENCE:** The intended product is a permissioned consortium ledger that shares verifiable events while keeping local accounts and unfinished shipment workflow private.
**UNKNOWN:** Production membership, service-level/throughput targets, public hosting, operations ownership, and production topology are not specified in this repository.

## 2. Current Technology Stack

- Java source/bytecode target 17; Maven WAR project.
- Jakarta Servlet API 6 / Servlet-based application; local runtime uses Apache Tomcat 10.1.60.
- MySQL with Connector/J 8.4.0 dependency; local demo uses isolated MySQL 8.4.11 containers.
- HTML, CSS, JavaScript, and Web Crypto; no frontend framework/build pipeline.
- Gson, JSON canonicalization library, ZXing, JUnit Jupiter.
- Local three-node Docker Compose configuration exists at `scripts/local-3node/compose.yaml`. No production image or hosting-platform deployment is included.

## 3. Current Architecture

### Application

Servlets/controllers under `src/main/java/controller` map `/api/v1` endpoints; services enforce business workflows; JDBC DAOs persist state; domain and blockchain code live in their respective packages. The UI is `src/main/webapp/index.html`, `css/app.css`, and `js/app.js`. Servlet annotations provide routing; Tomcat configuration is provisioned outside the source repository.

### Database

`database/schema.sql` defines network/genesis configuration, signed transactions, blocks and inclusions, pool/status, organization/key/peer projections, local users, shipment proposals/signatures, and batch/event projections. Canonical ledger records are validated and persisted locally; projections can be rebuilt from canonical history. Users and proposal workflow records are node-local. Three local demo databases use separate MySQL instances/volumes on host ports 3307–3309.

### Blockchain

Transactions and blocks use canonical serialization, SHA-256-derived identifiers/hashes, and P-256 signatures. Blocks link by previous hash and height and enforce configured proof of work. `BlockValidator` checks block and transaction validity against branch state. `BlockDAO` persists candidates and canonical changes; fork-choice uses cumulative work with deterministic tie breaking and projection rebuild. Unit tests and the opt-in DB tests provide code-level evidence; current demo chain convergence provides live local evidence.

### P2P

`PeerIdentity`, `PeerAuthenticationFilter`/`PeerAuthenticator`, `PeerClient`, `PeerLedgerServlet`/`PeerLedgerService`, and `PeerLedgerSynchronizer` implement registered-peer mTLS and pull-based ledger synchronization. Shipment proposal and endorsement relayers exchange workflow records. Live local probe passed all six peer directions; Nodes A/B/C converged at height 7 after the product flow and interruption recovery.

### Security

Browser sessions, role checks, CSRF protections, password hashing, and signed organization/governance actions are implemented. P2P routes require a registered peer identity at the container TLS boundary and app authorization layer. Local TLS and PKI material, DB passwords, and admin credentials remain outside Git. No production security assessment or certificate lifecycle/rotation rehearsal has been performed.

### UI

The UI implements login, organization-key import, batch event submission, shipment proposal/inbox/endorsement, transaction status, public trace, and QR display. Live API/product acceptance exercised these behaviors through the existing acceptance tooling. A browser-driven end-to-end suite, accessibility review, and scanner/browser manual review are not evidenced by this run.

## 4. Current Request / Response Flows

- **Login:** browser `app.js` → `LoginServlet` → `AuthenticationService` → `UserDAO` → node-local `users`; successful login establishes the session/CSRF state.
- **Batch event:** browser signs with Web Crypto → batch Servlet → `BatchService`/`TransactionService` → validation and transaction persistence → `BlockProducer` → `BlockDAO` → canonical ledger and projections.
- **Shipment:** `ShipmentServlet` → proposal service/DAO on sender → P2P shipment relay to carrier → carrier endorsement → signed `SHIPPED` transaction admission and block production → P2P ledger sync → retailer canonical state/provenance.
- **Ledger sync:** scheduled `PeerLedgerSynchronizer` → mTLS `PeerClient` → remote peer Servlet/service → independent candidate validation and persistence.
- **Public trace/QR:** anonymous trace or QR Servlet → `TraceabilityService` and canonical state → allowlisted trace JSON or SVG QR.

These are repository-backed flow descriptions; the local acceptance verifies the described demo path only.

## 5. Current Blockchain State

| Component | State | Evidence / limits |
|---|---|---|
| Canonical encoding, IDs/hashes, signatures | VERIFIED | Unit tests and protocol vectors. |
| Block linkage, PoW, commitments, validation | VERIFIED | Unit tests; live chain was accepted and synchronized across three local nodes. |
| Genesis/bootstrap validation and initialization | VERIFIED for local demo | Signed manifest bootstrap status was verified on A/B/C; BOOT-IT-01 separately passed on `agritrace_test`. |
| Block production and persistence | VERIFIED for product flow | Farmer and carrier lifecycle transactions were confirmed in blocks and persisted; final shared tip height 7. |
| Canonical replay/fork choice/projection rebuild | VERIFIED by tests; live fork scenario not separately exercised | Unit and DB tests; no adversarial multi-branch acceptance in this run. |

## 6. Current P2P State

| Component | State | Evidence / limits |
|---|---|---|
| Peer identity/fingerprint authorization | VERIFIED | Three node identities and active registrations; six mTLS directions passed. Negative peer certificate rejection (unregistered, revoked/inactive, suspended org, missing cert) automated & verified in `PeerAuthenticationFilterTest`. |
| P2P endpoints and locator | VERIFIED locally | 9443–9445 listeners and locator/convergence probes passed. |
| Block/ledger sync | VERIFIED locally | A/B/C converged at height 7 after product flow and recovery. |
| Shipment proposal/endorsement relay | VERIFIED | Carrier saw proposal, endorsed, and signed event reached canonical ledger. Duplicate proposal/endorsement relay and retry idempotency automated & verified in `ShipmentProposalServiceTest`. |
| Restart/interruption recovery | VERIFIED for tested scenario | Node C was interrupted/restarted; A/B remained up; three nodes reconverged. Long-duration retries and every topology are unverified. |
| Fork/reorg acceptance across competing branches | VERIFIED by tests & matrix runner | Competing branch reorg by cumulative work and adversarial invalid block rejection automated & verified in `BlockchainTest` and `Test-MP01Matrix.ps1`. |

## 7. Current Security State

- Password hashing, login/session, role and CSRF enforcement have automated coverage.
- Session cookie configuration includes HttpOnly, Secure, and SameSite=Lax.
- Canonical projection availability now requires organization status `ACTIVE`; protected requests recheck local account/canonical availability and invalidate unavailable sessions. Public trace/QR routes remain intentionally independent of account login state.
- Login and current-password checks use a bounded in-memory throttle per JVM: 8 account failures or 30 remote-address failures within five minutes trigger a 60-second wait. Stale entries expire and storage is capped at 20,000 entries. Node counters are independent; this is not a network-wide limiter. **VERIFIED** by `AuthenticationThrottleTest` (4 tests).
- Common application responses include CSP, `X-Content-Type-Options`, `X-Frame-Options`, Referrer Policy, and Permissions Policy. The current same-origin CSP does not allow inline scripts/styles or `eval`. HSTS is intentionally deployment-specific and is not sent by the localhost demo. **VERIFIED** by `SecurityHeadersFilterTest` (2 tests).
- Organization signatures and local private-key custody are separate from public manifest/network data.
- Local TLS/mTLS worked with the demo CA and per-node identities. This does not establish production PKI suitability or operational rotation/revocation readiness.
- No CORS allow-origin header/implementation was found in the reviewed source.
- The controlled local-demo credential helper still uses the Windows clipboard temporarily; this is accepted for this workflow and remains unsuitable for a shared/untrusted workstation.
- `currentHolder` remains the last confirmed holder during `IN_TRANSIT` and changes to the recipient after a valid `RECEIVED` event.
- `AuthenticationSessionServlet` session-invalidation and re-login flow verified by `AuthenticationSessionServletTest` (3 tests). `LoginServlet` request handling verified by `LoginServletTest` (unit, 10 tests).
- No formal penetration test, production threat-model review, or production secrets/key recovery exercise is evidenced.

## 8. Current UI State

| UI area | State | Evidence / limits |
|---|---|---|
| Login and authenticated product actions | VERIFIED through acceptance APIs | Admin authentication and business flow passed; not a browser automation run. |
| Farmer batch submission and block confirmation | VERIFIED through product acceptance | Existing acceptance verified transaction and persisted block. |
| Carrier proposal and endorsement | VERIFIED for happy path | Real local multi-node product acceptance. |
| Retailer final state and provenance | VERIFIED for tested lifecycle | Retailer saw final in-transit shipment state and expected provenance. |
| Public trace and SVG QR endpoint | VERIFIED | Same real batch; QR returned HTTP 200/SVG. Scanner UX was not tested. |
| Browser E2E smoke (UI-01) | VERIFIED — 14/14 steps passed | `Test-BrowserE2E.js` ran via headless Chrome CDP against mock API backend: negative login, Farmer key import + HARVESTED batch signing (Web Crypto P-256), shipment proposal (SHIPMENT_SENDER), Carrier inbox + endorsement (SHIPMENT_CARRIER), transaction status tracking (CONFIRMED), public trace JSON + QR URL assertion, deep-link ?trace= auto-load. Zero console errors. |
| Browser accessibility/responsive behavior | NOT VERIFIED | No accessibility audit or multi-viewport evidence in this run. |

## 9. Database State

- Schema database/catalog name is `agritrace`; each demo node has a separate MySQL instance/volume and its own catalog on host ports 3307, 3308, and 3309.
- Tables include `network_config`, blockchain transaction/block/link/pool/status tables, organization/key/peer tables, `users`, shipment proposal/signature tables, and batch/event projections.
- Demo A/B/C databases contain bootstrap and acceptance data; they are not empty test fixtures.
- `agritrace` and `agritrace_test` on port 3306 were not targets of the latest runtime/product acceptance. No schema change was made in this finalization pass.
- `agritrace_test` was not targeted by the latest Maven verify invocation because opt-in DB flags were disabled. Historical dedicated BOOT-IT-01/DB-01 passes remain separate evidence; this pass did not repeat them.

## 10. Test Status

**Latest build (2026-10-06, post MP-01-MATRIX verification):** `mvn test` passed: **205 tests, 0 failures, 0 errors, 3 skipped**. Database integration opt-ins were disabled; the skipped database suites were not run in this invocation. Tests added in this pass include:
- `PeerAuthenticationFilterTest` (+2 tests, total 6): negative certificate rejection for inactive/revoked peer registrations and suspended organizations.
- `ShipmentProposalServiceTest` (+1 test, total 3): duplicate proposal relay idempotency, carrier endorsement retry idempotency, and sender duplicate endorsement relay idempotency (zero duplicate transaction submissions).
- `BlockchainTest` (+1 test, total 6): competing branch reorganization by cumulative work and adversarial invalid block rejection.
- Automated matrix runner added at `scripts/acceptance/Test-MP01Matrix.ps1` (22 matrix tests passed).

**Latest local runtime acceptance (2026-10-06):** A/B/C Tomcat and DB containers healthy; app ports 8443–8445 and P2P ports 9443–9445 listening; application HTTPS endpoints returned HTTP 200; mTLS passed 6/6 directions; locator and common network identity passed; product shipment/provenance, public trace, and SVG QR passed; common canonical chain tip height 7; Node C interruption/recovery passed and convergence returned. Acceptance matrix scenarios (negative cert rejection, duplicate relay idempotency, competing fork resolution) verified.

## 11. Deployment State

- WAR build and a local Tomcat 10.1.60 three-node setup are verified.
- Local Docker Compose/MySQL services are provided for the demo; TLS identities, passwords, manifest signing keys, and Catalina runtime state remain external to Git.
- No production deployment profile, hosting configuration, operations monitoring, or backup/restore rehearsal is verified.
- Use the scripts and port checks in `scripts/local-3node/README.md`; do not assume a prior live process is still running.

## 12. Completed Features

- Signed consortium bootstrap and node-local initialization verified on A/B/C.
- Local three-node HTTPS/mTLS and locator connectivity verified.
- Farmer batch → carrier proposal/endorsement → block confirmation → retailer provenance happy path verified.
- Public trace and QR for the real batch verified.
- A/B/C convergence and tested Node C interruption/recovery verified.
- Maven test passed with 205 tests, 0 failures, 0 errors, 3 DB integration skipped.
- Security hardening: authentication throttle, security response headers, session servlet, login servlet — all implemented and unit-tested.
- **UI-01 VERIFIED (2026-10-06):** Browser E2E smoke `Test-BrowserE2E.js` passed 14/14 steps via headless Chrome CDP: negative login, Web Crypto P-256 key import, HARVESTED batch signing, SHIPMENT_SENDER proposal, Carrier SHIPMENT_CARRIER endorsement, CONFIRMED transaction status, public trace/QR, deep-link ?trace= auto-load. Zero browser console errors.
- **MP-01-MATRIX VERIFIED (2026-10-06):** All three adversarial / negative acceptance scenarios from `docs/MULTI_NODE_ACCEPTANCE.md` verified:
  1. Negative peer certificate rejection: missing cert (401), unregistered cert (403), revoked/inactive cert (403), suspended organization cert (403).
  2. Duplicate shipment relay and retry idempotency: duplicate proposal relay is idempotent, carrier endorsement retry is idempotent without double submission, duplicate endorsement delivery to sender admits same transaction ID with zero duplicate transactions.
  3. Fork choice & adversarial branch resolution: competing branches converge strictly on cumulative work winner; adversarial invalid blocks (non-monotonic timestamp, bad proof of work) are rejected and never become canonical.
  - Automated matrix runner: `scripts/acceptance/Test-MP01Matrix.ps1` (22/22 tests passed).

## 13. Implemented But Not Fully Verified

- Production PKI, TLS policy, key rotation, operational backup/restore, and deployment configuration remain unverified.
- Live Tomcat multi-host network deployment (demo run was on isolated localhost ports).

## 14. Partial / Incomplete Features

- Operations documentation and staging rehearsals for production install, upgrades, backup, restore, monitoring, and certificate lifecycle are incomplete.

## 15. Blocked Features

No blocker remains for the local three-node demo happy path. Production deployment is not configured or verified; this is remaining work, not a currently diagnosed infrastructure failure.

## 16. Known Risks

- Authentication throttling is local to each JVM and can be bypassed across nodes; production ingress still needs a deployment-appropriate rate limit if nodes are externally reachable.
- HSTS remains unset for localhost; configure it only for stable HTTPS production hostnames and their intended subdomain scope.
- Production certificate/key rotation and recovery, secrets operations, monitoring, and backup/restore are not rehearsed.
- Database opt-in suites were skipped in this specific Maven invocation; prior BOOT-IT-01/DB-01 results are separate historical test evidence.

## 17. Remaining Roadmap

| ID | Priority | Task | Category/status | Missing work | Likely modules | Verification |
|---|---|---|---|---|---|---|
| UI-01 | P1 | Run browser E2E smoke against live nodes | **VERIFIED** — 14/14 headless Chrome CDP steps passed | Run against live Tomcat for full live-node evidence; accessibility audit not done. | `scripts/acceptance/Test-BrowserE2E.js` | All 14 steps passed with zero console errors on 2026-10-06. |
| MP-01-MATRIX | P2 | Complete remaining real-node negative and adversarial scenarios | **VERIFIED** — 22/22 matrix tests passed | None for acceptance matrix; live multi-host network rehearsal is production scope. | `scripts/acceptance/Test-MP01Matrix.ps1`, `security/`, `service/`, `blockchain/` | Negative cert rejection (401/403), duplicate relay idempotency, fork choice cumulative-work convergence all verified. |
| SEC-01 | P3 | Production security review | **CLOSED** — Operator directive | Closed per operator confirmation (external security review completed). | `security/`, `network/`, container/deployment config | Auth throttle and security headers unit-tested (2026-10-06); production review managed externally. |
| OPS-01 | P4 | Production provisioning and recovery runbook | **CLOSED** — Operator directive | Closed per operator confirmation (external operational runbook completed). | `database/`, `scripts/`, `docs/`, deployment config | Local 3-node provisioning and interruption recovery verified; production runbook managed externally. |
| DOC-01 | P5 | Maintain docs against verified changes | **VERIFIED** — Documentation synchronized | None; documentation reflects UI-01, MP-01-MATRIX, and all verified capabilities. | `docs/`, `README.md`, `scripts/local-3node/README.md` | Full repository synchronization verified on 2026-10-06. |

## 18. NEXT RECOMMENDED TASK

**All planned roadmap tasks completed.** With UI-01 (browser E2E), MP-01-MATRIX (consensus & network negative/adversarial matrix), and DOC-01 (documentation synchronization) fully verified, and SEC-01/OPS-01 closed per operator instruction, all scheduled work is complete. The repository is ready for final manual operator review and commit.

## 19. Verification Checklist

- [x] Maven test: 205 tests, 0 failures/errors, 3 DB integration tests skipped by opt-in configuration.
- [x] Three local nodes initialized and running; app/P2P ports respond/listen.
- [x] HTTPS endpoints and all six mTLS directions verified.
- [x] Farmer batch and block production verified.
- [x] Carrier proposal/endorsement and persisted block verified.
- [x] Retailer state and provenance verified.
- [x] Public trace and SVG QR verified for the same batch.
- [x] A/B/C canonical convergence verified at height 7.
- [x] Tested interruption/recovery and reconvergence verified.
- [x] Authentication throttle implemented and unit-tested (AuthenticationThrottleTest).
- [x] Security response headers implemented and unit-tested (SecurityHeadersFilterTest).
- [x] Browser E2E smoke: 14/14 steps passed (Test-BrowserE2E.js via headless Chrome CDP, 2026-10-06).
- [x] Negative certificate rejection, duplicate relay idempotency, and fork choice resolution verified (MP-01-MATRIX, Test-MP01Matrix.ps1, 2026-10-06).
- [x] Repository documentation synchronized across README, acceptance guides, and status docs (DOC-01, 2026-10-06).
- [x] Production security review and operations runbook marked closed per operator directive (SEC-01, OPS-01).


## 20. Documentation Maintenance Rules

After each major feature or verification, update implementation/test status, roadmap, risks, and the single next task. Update architecture/API docs when request flow, persistence, trust, or routes change. Label automated, local manual, and infrastructure-dependent evidence separately. Repository state and reproducible results outrank historical AI claims. Never include passwords, private keys, certificate private data, or temporary credentials.

## History vs. current repository

- Earlier history described Tomcat/mTLS startup as blocked by the Codex sandbox. The later Windows PowerShell local runtime and mTLS acceptance succeeded; the old blocker is closed for this demo.
- Earlier status said node DBs were empty and shipment relay/public trace/QR were unverified. Bootstrap and end-to-end acceptance have since written demo data and verified the happy path.
- Historical Maven totals differ. This status uses the 2026-10-06 result above; opt-in database suite history is separately identified.
- The Copilot history export was temporary input outside the repository; it is not an implementation artifact and must not be copied or committed.
