# AgriTrace — Instructions for AI Agents

This file contains durable working rules for agents contributing to AgriTrace. The current implementation and outstanding work are tracked in [docs/AI/PROJECT_STATUS.md](docs/AI/PROJECT_STATUS.md).

## 1. Purpose and product model

AgriTrace is a consortium traceability application for agricultural batches. Farmers, carriers, warehouses, and retailers record signed lifecycle events. Consortium nodes validate and replicate the ledger; public users can look up an allowlisted trace record. See `docs/API.md` and `docs/ARCHITECTURE.md` for the current contracts and workflows.

## 2. Technology and architecture

- Keep the Java Servlet + JDBC + MySQL + WAR architecture. The Maven build targets Java 17 and Jakarta EE 10 APIs. Do not introduce Spring or replace the architecture with a framework.
- `controller/` contains Servlet endpoints and HTTP mapping; `service/` owns workflow and application rules; `dal/` owns JDBC persistence; `model/` holds domain records; `blockchain/` owns consensus and validation; `network/` owns peer wire formats, identity, and synchronization; `security/` owns filters, sessions, hashing, and authorization; `src/main/webapp/` contains the browser application.
- Preserve the request path and security filters. Understand the route, caller role, service, DAO, and persistence effects before changing a workflow.
- Keep browser and Java canonical JSON/signing formats compatible. Protocol serialization, transaction IDs, payload hashes, and signatures are consensus/API contracts.

## 3. Database rules

- Do not change `database/schema.sql`, add/edit migrations, or run DDL unless the user explicitly asks for that database/schema work.
- Never drop, truncate, recreate, or overwrite a database. Use only an explicitly provisioned, isolated test database for integration tests; `BlockMySqlIntegrationTest` requires its configured test database to be empty.
- Existing databases may need migrations 001 and 002, but do not assume they have or have not been applied. Inspect the actual target schema and the migration state before proposing database actions. `schema.sql` describes new-database structure and already includes the changes represented by migration 001; it does not make migration 001 safe to rerun.
- Database credentials must come from `AGRITRACE_DB_URL`, `AGRITRACE_DB_USERNAME`, and `AGRITRACE_DB_PASSWORD` or their documented JVM properties. Never print, log, commit, or copy secrets into documentation.
- Treat `organizations`, `organization_keys`, `authorized_peers`, batches, and events as rebuildable canonical projections where documented. Do not make local account activation or local shipment drafts canonical ledger state.

## 4. Blockchain and transaction invariants

- Every node independently validates transactions and blocks. Never trust a peer's validity claim or a local SQL projection as consensus truth.
- Preserve configured network identity/genesis, monotonic block timestamps, previous-hash linkage, transaction commitments, proof-of-work bounds, canonical transaction encoding, signature purpose, duplicate-ID checks, governance ordering, and cumulative-work fork choice.
- Changes to canonical branches must preserve atomic block/canonical-projection updates, pending/confirmed/rejected transaction status, orphan handling, and full branch replay. Add focused tests for any changed invariant.
- Do not alter genesis values, difficulty, signature semantics, event state transitions, or fork-choice rules without an explicit requirement and a compatibility analysis.

## 5. Governance, shipment, and peer rules

- Governance is signed by the configured genesis administrator and becomes effective through the canonical chain. Organization, key, and peer identifiers are append-only; revocations and status changes preserve history.
- Shipment proposals and endorsements are node-local workflow records relayed between organizations. Revalidate them against the current canonical registry before endorsement/submission. The final shipment event and required organization signatures are ledger data.
- P2P endpoints are under `/api/v1/internal/p2p/*` and must remain protected by peer authentication. Use the registered HTTPS endpoint and certificate fingerprint; do not weaken certificate validation or accept an unregistered/revoked peer.
- Ledger synchronization is pull-based. Incoming transactions and blocks must pass local canonical decoding, validation, and fork choice. Preserve bounded payloads, pagination, timeouts, and scheduled synchronization behavior.
- Each deployed node needs its own database and node identity. Never copy another node's private key or writable database.

## 6. Authentication and security

- Local user accounts are not blockchain transactions. Enforce active account state and canonical organization availability/role matching.
- Preserve session rotation, HttpOnly/SameSite cookies, HTTPS Secure-cookie behavior, CSRF checks for browser writes, and role authorization for `/api/v1/admin/*` and `/api/v1/node/*`.
- Organization signing private keys stay client-side. Browser Web Crypto imports the user's PKCS#8 P-256 key as non-extractable for the current page session and sends signatures, never key bytes. Do not add server-side private-key storage.
- Never print or commit passwords, private keys, PFX/PKCS#12 contents, API keys, certificate private material, or credential-bearing connection strings. Use placeholders in examples and reports.
- Bound and validate request bodies and preserve the public trace output allowlist. Do not derive trusted public QR URLs from an untrusted `Host` header.

## 7. Coding and change scope

- Make small, focused changes that satisfy the requested task. Do not perform unrelated refactors, rename packages, or change existing business rules without authorization.
- Match the existing Java style: explicit immutable domain data where used, clear validation errors, JDBC resource management, UTC timestamps, and JUnit 5 tests.
- Do not change application behavior as part of documentation-only work. Do not add migrations or seed values by assumption.
- Do not claim a feature is complete from a previous AI report alone. Verify the current repository, tests, docs, and—where relevant—runtime evidence. Label inference and unknowns separately from facts.

## 8. Testing and verification

- Run the relevant tests after implementation and report exact totals, failures, errors, skips, and build result. Do not conceal skipped integration tests.
- MySQL integration tests are opt-in with `AGRITRACE_DB_INTEGRATION=true` and `AGRITRACE_DB_BLOCK_INTEGRATION=true`; confirm an isolated test database is intentionally configured before enabling them.
- Unit tests and a WAR build do not establish real Tomcat, browser, TLS, database, or multi-node acceptance. Record those separately and do not mark them verified without execution evidence.
- For UI changes, verify JavaScript syntax and exercise browser/API flows where an appropriate running environment is available. For peer changes, use the multi-node scenarios in `docs/MULTI_NODE_ACCEPTANCE.md` when nodes are provisioned.

## 9. Deployment and documentation

- The deliverable is a WAR. Tomcat, TLS termination, server certificate trust, MySQL provisioning, network genesis, peer registration, and per-node credentials must be configured outside source control and verified for the target environment. Do not invent a Tomcat version or deployment platform.
- Update `docs/API.md` when HTTP contracts change and `docs/ARCHITECTURE.md` when design/flow changes. Update `docs/AI/PROJECT_STATUS.md` after meaningful implementation, verification, blocker, risk, or roadmap changes.
- Keep this `AGENTS.md` focused on durable contribution rules. Do not copy historical Copilot conversations or temporary environment values into it.

## 10. Agent workflow

1. Inspect repository status and preserve unrelated user changes.
2. Read the relevant documentation, route, service, persistence code, and tests before editing.
3. Confirm the requested scope and identify stateful or external effects before acting.
4. Implement only the authorized change; add or update focused tests when implementation work requires them.
5. Run appropriate verification and state limits clearly.
6. Review the diff for secrets, schema changes, unrelated edits, and unsupported documentation claims.
7. Update the project status when the verified project state changes.
