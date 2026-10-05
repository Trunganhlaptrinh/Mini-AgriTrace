# AgriTrace Consortium Bootstrap Design

**Status:** design only; no code, database, schema, account, key, or certificate was created or changed.

**Scope:** establish a repeatable, controlled way to start a new AgriTrace network and the future three-node demo before proceeding with MP-01.

## 1. Problem

A fresh installation has a bootstrap chicken-and-egg dependency. `NodeRuntimeListener` can create an empty genesis block from `network_config`, but before publishing the runtime it calls `PeerIdentity.loadRequired`. That method refuses startup unless the configured local `peerId` already has an active canonical `PeerRegistration` whose fingerprint matches the local PKCS#12 certificate and whose organization is active.

The API for registering an organization or peer is served by the runtime and requires an authenticated `ADMIN` session plus a governance signature from the genesis administrator. The schema contains no seed row for network configuration, organization, peer, or first administrator. Therefore a new network cannot be initialized through the current documented HTTP flow, and inserting arbitrary rows directly into MySQL is not an acceptable bootstrap design.

## 2. Current startup dependency

Current path in `NodeRuntimeListener.contextInitialized`:

1. Load the singleton row from `network_config` through `NetworkConfigDAO.loadRequired`.
2. Reconstruct and validate configured genesis via `GenesisBlockFactory.configuredGenesis`.
3. If the canonical chain is empty, store that empty genesis through `Blockchain`/`BlockDAO`.
4. Load the node's P2P identity through `PeerIdentity.loadRequired`.
5. `PeerIdentity` reads the PKCS#12 certificate, requires exactly one private-key alias, fingerprints its leaf certificate, replays the chain, and requires an active matching peer and active organization.
6. Only after those steps does the listener construct `NodeRuntime`, exposing the governance/account APIs and starting ledger synchronization.

On a clean database, step 4 fails because the ledger contains only empty genesis. A peer registration requires an already active organization. The first local admin account also has no bootstrap path: `schema.sql` does not seed one, and `AdminUserService`/`AdminUserServlet` is protected by an existing ADMIN session. This is a concrete design gap, not an infrastructure-only problem.

## 3. Bootstrap goals

- Start from an empty, explicitly identified database without ad hoc SQL data writes.
- Establish a unique network identity and one deterministic, verifiable empty genesis.
- Establish a trusted genesis-admin public key, first organization and organization signing key, first peer identity, and initial governance history.
- Give each node its own local first-administrator account without putting passwords on-chain or in a manifest.
- Validate all generated/imported state through existing codecs, validators, block processing, DAOs, and database constraints.
- Produce the same immutable initial ledger on each node, with independently maintained DBs, local admin credentials, peer certificates, and node configuration.
- Make dry-run, verification, safe retry after interruption, and an auditable record possible; fail closed on unexpected pre-existing state.
- Keep development/staging networks separate from production identity, credentials, and databases.

## 4. Proposed architecture

### Recommended: offline bootstrap CLI plus a domain bootstrap service

Create an explicit, offline `ConsortiumBootstrap` command line entry point and a small `ConsortiumBootstrapService` in a dedicated package. The CLI is an operator tool run before the web application starts. The service composes existing domain code and DAO boundaries; it does not enable a temporary unauthenticated Servlet route and does not write ledger/projection rows with ad hoc SQL.

The CLI should have distinct conceptual operations:

1. **Create/validate a signed bootstrap bundle once.** Collect approved network/genesis parameters, public identities, endpoint/fingerprint registrations, and ordered initial governance transactions. Use the existing canonical governance codec/validator and genesis/block logic. The admin signature is produced by a separately controlled signer; never accept a private key as a command-line argument or persist it in the bundle.
2. **Initialize one node from that bundle.** Check the target DB is empty or already exactly at a valid prefix of the same bundle, install network config and blocks via dedicated DAO methods and normal validation, and provision only that DB's first local admin using `PasswordHasher` and `UserDAO`.
3. **Verify.** Read back config, validated canonical chain, governance registry, local peer/certificate match, and local admin presence without changing state.

The existing project is a single Maven WAR and has no CLI module/runner. A future implementation must choose a maintainable packaging/invocation (a dedicated Maven CLI module/artifact sharing domain code is preferred if that avoids shipping operator-only bootstrap behavior in the runtime WAR). Do not add a bootstrap Servlet or implicit startup fallback.

### Manifest/bundle properties

- Immutable, versioned, canonical manifest contains network ID, genesis tuple/hash, genesis-admin public key, ordered signed governance transactions and their exact signed bytes, block headers/transaction ordering/hashes, organizations and public signing-key IDs, peer IDs/endpoints/client-certificate fingerprints, and environment label.
- Does not contain any private key, PKCS#12 file, password, DB credential, admin password, or secret URL.
- Include a digest and an offline genesis-admin signature over the manifest. Independently verify signer public key/fingerprint with the out-of-band consortium ceremony; a key cannot establish its own trust merely by signing itself.
- Signatures and event timestamps are part of canonical transaction IDs. ECDSA signatures may be randomized, so create them once and distribute the immutable signed bundle; do not regenerate transactions separately on each node and expect identical IDs.
- Each node runs local block/transaction validation while applying the exact same bundle. Record bundle digest and resulting canonical tip in an audit record outside secrets.

This separates consortium creation from runtime peer use, maintains the Servlet architecture for normal operation, and addresses the startup dependency with an explicit one-time operator action.

## 5. Bootstrap sequence

The exact initial installation sequence should be:

1. Approve whether this is a new development, staging, or production network; select a unique `networkId` and designate the genesis administrator custodian(s).
2. Provision/verify a secure genesis-admin signing key and initial organization public signing keys outside the server/database. The chosen method (offline file, smart card, or HSM) is not currently integrated and must be decided.
3. Provision HTTPS server certificates and per-node P2P client certificates out of band; record the exact leaf certificate SHA-256 fingerprints and final HTTPS base endpoints.
4. Author the versioned manifest with all initial organizations, first keys, and node peer registrations. Generate and sign the governance envelopes once; keep private signing operations in the approved signer.
5. Calculate deterministic genesis parameters/hash and ordered governance block data; verify them in a no-write validation/dry-run.
6. Provision three independent empty MySQL instances/databases. For each node, the CLI checks it is the intended empty target using the actual configured connection, schema version/required tables, and bootstrap preconditions. It must not clear or repair an existing DB.
7. Apply the checked-in schema through an approved schema provisioning step. Seed/configure network row, genesis, and initial ledger only through bootstrap DAO/service APIs—not hand-written INSERT statements.
8. Install the same bundle to each independent DB. The tool locally revalidates every block and governance transition through `Blockchain`, `BlockValidator`, `GovernanceValidator`, `BlockDAO`, and canonical projection rebuild.
9. On each DB, interactively provision that node's first local ADMIN account. Hash the password using `PasswordHasher`; never put it in the network manifest or chain. A unique account/password per environment/node is preferred.
10. Verify each node's network/genesis, full canonical tip, active peer registry, local configured peer ID/fingerprint, organization state, and local ADMIN account.
11. Configure Tomcat inbound HTTPS/client-certificate behavior plus DB/P2P runtime settings outside the repository.
12. Only then start each AgriTrace WAR. With every node's own active peer entry already in its canonical state, A/B/C can start independently; there is no code-level startup ordering between them after bootstrap.
13. Run the multi-node probe and acceptance scenarios only after this verification passes.

## 6. Genesis sequence

Current `GenesisBlockFactory` reconstructs genesis; it does not choose the network settings. Genesis is an empty block: height zero, no parent, empty transaction list, timestamp at millisecond precision, configured network ID/difficulty/nonce, and empty-transaction hash. `BlockValidator` prohibits transactions in genesis.

Recommended ceremony:

1. Choose a unique network ID, difficulty (current range 1–16), and fixed UTC millisecond timestamp.
2. Fix the genesis administrator's P-256 SPKI public key and validate its format.
3. Use deterministic PoW search from nonce zero for the fixed network ID/timestamp/difficulty and empty transaction commitment; record the accepted nonce and resulting lowercase SHA-256 genesis hash. `ProofOfWork.mine` searches nonce values in ascending order; the generated tuple can be independently recomputed.
4. Put identical `network_id`, `genesis_hash`, `initial_pow_difficulty`, `genesis_timestamp`, `genesis_nonce`, and `genesis_admin_public_key` in every node's `network_config`.
5. Sign the manifest out of band and pin its digest/public-key fingerprint in the ceremony record.

Important current limitation: genesis header hashing includes network ID, timestamp, nonce, difficulty, and transaction commitment, but **does not include** `genesis_admin_public_key`. `NetworkConfigDAO` validates that key is a P-256 public key, but two databases could have the same genesis hash and different administrator keys. The bootstrap manifest must bind the admin public key to the network through a separately trusted signature/checksum and every node's verifier must check the same full tuple, not genesis hash alone. Whether to change the consensus header in a future network version is a separate protocol decision; this design does not change it.

## 7. First organization

The first organization is created by the first canonical governance transaction `REGISTER_ORGANIZATION`. It includes organization ID, organization type, name, optional province, initial key ID, algorithm, and Base64 public key. The governance registry marks it active and registers its initial key at that block height. The organization private key stays with the organization/user; only the public key goes into the signed transaction/ledger.

For a multi-node demo, add the organizations required by all three demonstration roles before registering their peers. IDs and names are environment-specific decisions. Organization registration is ledger data, while local user accounts are not.

## 8. First administrator

There are two distinct admin concepts:

- **Genesis governance signer:** the private half of `network_config.genesis_admin_public_key`, which signs `GENESIS_ADMIN_GOVERNANCE` transactions. It must be controlled outside the AgriTrace web runtime. Current governance is signed by one configured genesis-admin key; no multi-signature quorum or key-rotation policy is implemented.
- **Local ADMIN login:** a node-local `users` row with role `ADMIN` and null organization. It authenticates to the admin Servlet APIs and does not become an on-chain identity.

The bootstrap CLI should create the first local account only as an explicit one-time operation after it has validated the target database and bootstrapped canonical ledger. It should use `UserDAO` and `PasswordHasher`, receive a password interactively/through a secret-safe prompt (never command-line args), and never display/store the password. Current login password requirements are 12–1024 characters. Since no forced password-change field exists, decide whether the operator supplies a unique durable password at bootstrap or a separate follow-up feature is authorized to force rotation; do not claim forced rotation exists.

Every other node needs its own local admin account. Admin account replication does not happen through P2P.

## 9. First node

The bootstrapper should provision the first local peer's organization, peer ID, endpoint, and client certificate fingerprint in the shared signed ledger before the application starts. The actual certificate file remains local and is not included in the bundle. The node's runtime configuration then supplies `AGRITRACE_P2P_PEER_ID`, `AGRITRACE_P2P_KEYSTORE_PATH`, and `AGRITRACE_P2P_KEYSTORE_PASSWORD` (or corresponding JVM properties).

The runtime independently verifies the certificate file fingerprint and canonical active registry match at startup. A bootstrap tool must not disable or weaken that check.

## 10. Peer registration

`REGISTER_PEER` requires `peerId`, `organizationId`, HTTPS `endpoint`, and lowercase 64-hex SHA-256 fingerprint. `GovernanceRegistry` rejects duplicate peer IDs, invalid HTTPS URLs, or a peer attached to an unknown/inactive organization. Registration is a genesis-admin-signed governance transaction and only becomes active when included in canonical block history.

For a new consortium, the bootstrap bundle should contain peer registration transactions for the first authorized node(s). For the three-node demo it must include peers A/B/C and their organization associations before any corresponding node is started. Each node receives the same canonical governance history; each node retains a different local certificate/key and admin account. Later onboarding/revocation uses existing governance APIs once a trusted node is running.

## 11. Governance initialization

The initial registry should be minimal and explicit:

1. `REGISTER_ORGANIZATION` for each approved initial organization; this also registers that organization's first signing key.
2. `REGISTER_PEER` for each initial node, linked to an already active organization and containing its final HTTPS endpoint and client leaf fingerprint.
3. Any additional public organization keys only if required; do not include local users, passwords, private keys, or demo batch events in governance.

Because governance transactions are applied in block transaction order and `TransactionPool` candidate selection orders by transaction ID, do not rely on a convenient transaction ID order to satisfy dependencies. Bootstrap generation should put dependent state transitions in separate sequential blocks, or otherwise explicitly validate the exact manifest block ordering before installation. Separate blocks are easier to review and audit.

The initial ledger is shared; node-local accounts, transaction pool, status rows, and shipment drafts are not part of that shared governance bootstrap.

## 12. Key initialization

Keep three key purposes separate:

- Genesis governance signing key: signs initial and later governance; public key is configured identically in each DB. Production private key should be offline/HSM controlled if the chosen operations support it; no HSM integration exists today.
- Organization signing keys: ECDSA P-256/SHA-256 public keys registered by governance. Organization private keys are held by authorized operators/users and used client-side in browser Web Crypto; never stored on server.
- Node TLS identities: per-node X.509 client certificate/private key in a PKCS#12 store containing exactly one private-key entry. Its certificate fingerprint maps to the peer registration. Tomcat also has a server TLS identity; trust configuration is managed outside the application.

No key material should be embedded in the bootstrap bundle, repository, database seed, logs, or chat. Key rotation/recovery procedures are not currently defined and require a separate operational decision.

## 13. Security model

- Bootstrap is an explicit privileged local operation, not a public HTTP endpoint. Restrict CLI execution and DB credentials to designated operators.
- Verify the signed manifest, network ID, genesis tuple, transaction signatures, canonical IDs, all block PoW/linkage, governance transitions, peer fingerprints, and active organization relationships before committing each step.
- Require the manifest signer to match the approved genesis-admin public key verified out of band. Do not accept a public key because the same untrusted bundle claims it.
- Refuse unexpected existing data. Support dry-run and verification without writes. Do not expose a generic reset mode that clears arbitrary databases.
- Keep DB password, local admin password, organization private keys, genesis private key, and PKCS#12 passwords outside args, manifest, logs, SQL exports, source control, and support reports.
- Use least-privilege DB credentials; if the schema creation needs elevated DDL rights, separate one-time schema operator from runtime DB accounts.
- Audit the action: operator, timestamp, environment, manifest digest, node identifier, DB target identifier with credentials removed, canonical tip, and verification result.
- Current implementation has single-genesis-admin governance signatures, so compromise of that private key allows valid governance proposals. Production key custody and incident response are launch blockers; quorum authorization is not present.

## 14. Local development

- Use a dedicated development network ID and dedicated DB instances/data directories. Never connect a dev bootstrapper to a production URL.
- Use throwaway genesis-admin, organization, and TLS identities; use local-only trust roots and localhost server SANs. Do not reuse production credentials or trust stores.
- Bootstrap the network once from a development bundle, then preserve each node's independent state during a run.
- Developers can use the first local admin account created interactively by bootstrap to create organization-role accounts through the existing admin workflow.
- A deliberate reset should require an explicit development environment marker, exact target confirmation, and a disposable DB. No such reset command currently exists; no reset is performed by normal startup.

## 15. Staging

- Give staging a distinct network ID, genesis tuple/admin key, organizations, peer certificates, accounts, and databases from production.
- Use three independent MySQL instances and Tomcat deployments for the demo, with final DNS/HTTPS endpoints and trusted CA chains before peer registration is finalized.
- Create one reviewed signed bundle and install/verify the same initial canonical history independently into A/B/C. Do not copy writable DBs or private identity files.
- Provision local admins individually. Create role accounts through the normal admin API with unique temporary passwords delivered through an approved secret channel; do not put demo passwords in code or docs.
- Run bootstrap verification before startup, then `Test-MultiNodeP2P.ps1`, then each scenario in `docs/MULTI_NODE_ACCEPTANCE.md`; save sanitized evidence.

## 16. Production

- Require a reviewed consortium/network identity and an out-of-band trust ceremony for the genesis-admin public key and signed bootstrap manifest.
- Use operator-approved CA/HSM/key-custody practices, distinct server and peer identities, protected truststores, least-privilege DB roles, independent databases, controlled change approvals, backup/restore rehearsal, monitoring, and incident response.
- Do not include demo users/data or development certificates in production.
- Current governance is single-key; decide whether that trust model is acceptable before production. Bootstrap must not silently imply multi-party approval.
- Keep genesis-admin private signing material away from node runtime. Application changes to support production key services are out of scope of this design.

## 17. Demo environment

### Topology and domain constraints

The code defines organization types `FARMER`, `CARRIER`, `WAREHOUSE`, and `RETAILER`. A reasonable conceptual assignment is:

- Node A: one Farmer organization and a peer associated with it.
- Node B: one Carrier organization and a peer associated with it.
- Node C: one Retailer organization and a peer associated with it.

This is a proposed demo mapping, not an existing requirement. `SHIPPED` requires three distinct parties: current holder/sender, carrier signer, and designated warehouse/retailer recipient. Batch rules require a `PACKAGED` state before shipping. The recipient organization must later sign `RECEIVED` to become the current holder; `SOLD` is possible after retailer receipt. Thus the actual tested story should be Farmer harvest → package → sender/carrier signed shipment → ledger confirmation and sync → Retailer receipt → public trace/QR (and optionally sale). There is no separate on-chain “batch create” step; `HARVESTED` creates the batch state.

### Data boundaries

Use a separate development network and fictional batch/product/organization data. Governance registrations and batch lifecycle events enter the canonical ledger; local accounts and proposal inboxes remain per-node. Do not use real farmer/customer PII or production signing keys in a demo.

## 18. Demo accounts

Recommended approach:

1. Bootstrap only the first local ADMIN account through the explicit one-time CLI operation.
2. After node startup, use the existing authenticated admin account-management workflow (`POST /api/v1/admin/users`) to create local Farmer, Carrier, and Retailer users on the node they use. The selected role must match that node user's active canonical organization type.
3. Use synthetic usernames and unique temporary passwords, supplied through a local secrets mechanism; require the operator to deliver passwords out of band and change them promptly through `/api/v1/auth/password`. Current code supports password change but does not force it.
4. Store no demo password in SQL source, manifest, code, Git, or public documentation. Do not create demo accounts in production.

There is no development profile or demo-account seeding tool currently. The normal admin API is preferable to hard-coded seeds because accounts are intentionally node-local and already have password hashing, role checks, and active organization validation. If repeatable ephemeral demo fixtures become necessary, add an explicitly development-only fixture command later; do not silently seed accounts from `schema.sql` or production startup.

Organization private keys are separate from account passwords and should be imported by each demo operator into the browser only. Node peer certificate private keys are separate again.

## 19. Recovery/reset

- **Verification:** re-run a no-write bundle verification and validated-chain replay; compare the full network tuple, governance transaction/block IDs, and canonical tip against the signed manifest.
- **Interrupted initialization:** design the CLI to be idempotent only for a recognized valid prefix of the same bundle. On retry, verify existing rows/blocks and resume at the next validated step. If state differs, stop and require operator review; never silently overwrite or repair.
- **Audit/recovery:** retain the signed public manifest and sanitized per-node result with normal protected backup procedures. The manifest is not a database backup and does not include local accounts or private identities.
- **Development reset:** only in explicitly disposable local DB instances, through a separately authorized and target-checked procedure. No reset code or action is part of this design task.
- **Staging/production reset:** no in-place reset. Preserve evidence/backups, revoke compromised peers/keys through governance when available, or create a new approved network with a new network ID/genesis if the consortium deliberately starts over. Database destruction requires a separate explicit approval and operator procedure.

## 20. Risks

1. Startup chicken-and-egg and missing first-admin path block first-time network creation.
2. `genesis_admin_public_key` is stored alongside genesis config but is not committed by the genesis header hash; identical genesis hashes alone do not prove identical governance authority.
3. No current `NetworkConfigDAO` create/initialize method exists; it only loads and validates configuration.
4. No supported canonical-ledger export/import/bundle installer or bootstrap CLI exists.
5. Multi-step initialization crosses DAOs/connections and is not one existing atomic operation; future code must handle interruption and concurrent invocation safely.
6. ECDSA signature randomness and time-dependent block production can make independently regenerated startup histories differ; sign/build once and distribute the immutable verified artifact.
7. Initial governance ordering is dependency-sensitive; random transaction ID sorting must not place `REGISTER_PEER` before organization registration.
8. Single genesis-admin key centralizes governance authority; current code has no quorum/multisig.
9. Local first-admin passwords and node identities differ by node and are not replicated; failures here are operationally distinct from chain bootstrap.
10. Schema hard-codes DB name `agritrace`, making same-server multi-schema provisioning awkward without an approved importer/design change.

## 21. Alternatives considered

| Alternative | Assessment |
|---|---|
| Arbitrary SQL INSERTs for network/org/peer/user/block state | Rejected. Bypasses canonical validators, DAO contracts, transaction signatures, projection rebuild, and audit checks; risks invalid or inconsistent chain state. |
| Temporary unauthenticated Servlet/startup bootstrap mode | Rejected. Expands remote attack surface and adds special runtime states to authentication/startup; conflicts with peer identity validation and normal governance authorization. |
| Put organization/peer transactions inside genesis | Rejected for current network version. `BlockValidator` requires genesis to contain no transactions; changing genesis semantics is a protocol migration, not a setup shortcut. |
| Add a flag that skips local peer validation on first boot | Rejected. It weakens the explicit local identity trust invariant and still leaves secure first-admin/peer enrollment unresolved. |
| External standalone generator that writes database tables directly | Rejected unless it only produces a signed artifact and installation still goes through application validation/DAO boundaries. Direct ledger writes would bypass `BlockDAO` invariants. |
| Offline CLI plus bootstrap domain service and immutable signed manifest | Recommended. It runs before runtime, uses the current canonical domain validation/persistence path, creates the local first admin through password-hashing/user-DAO mechanisms, and can install identical validated initial state into independent DBs. Requires new code and focused tests later. |

## 22. Recommended approach

Implement an **offline CLI + `ConsortiumBootstrapService` + immutable signed bootstrap bundle**. Keep the service narrow and reusable from the CLI; use existing `GenesisBlockFactory`, `ProofOfWork`, codecs, `BlockValidator`/`Blockchain`, `BlockDAO`, `NetworkConfigDAO` (extended with guarded creation), `UserDAO`, and `PasswordHasher`. Bootstrap governance must become ordinary signed governance transactions in post-genesis blocks, validated and persisted through normal block processing. The web runtime must start only after local peer registration and fingerprint match are true.

Make installation fail closed by default. Support `validate/dry-run`, `initialize-empty-db`, and `verify` semantics (names illustrative, not existing commands). Validate target identity, require exact approved manifest signer and bundle digest, reject non-empty/unexpected data, and make a partial valid bootstrap safely resumable. Do not add a general destructive reset command. Keep secrets interactive/external and all logs sanitized.

The manifest should be created once and imported unchanged into each node; do not independently regenerate signatures or mining timestamps per node. Each DB receives the same network/genesis/governance history and independently created local ADMIN credentials.

## 23. Files/modules likely affected

Likely implementation surface, after explicit approval:

- New `src/main/java/bootstrap/` or a separate Maven CLI module: argument parsing, environment guardrails, bundle parsing/signature verification, dry-run/install/verify commands.
- New bootstrap DTO/manifest codec and `ConsortiumBootstrapService` using canonical existing representations.
- `src/main/java/dal/NetworkConfigDAO.java`: guarded insert/initialize and read-back verification; current class only loads a required row.
- `src/main/java/dal/BlockDAO.java` / `src/main/java/blockchain/Blockchain.java`: validated ordered chain initialization using existing persistence/replay behavior; no direct ledger-row writes.
- `src/main/java/dal/UserDAO.java`, `src/main/java/security/PasswordHasher.java`, and possibly a narrowly scoped first-admin provisioner: local admin account creation under bootstrap-only preconditions.
- `src/main/java/blockchain/GenesisBlockFactory.java`, `ProofOfWork.java`, `GovernanceCodec.java`, `GovernanceValidator.java`, and `BlockValidator.java`: reuse/validation; avoid changing consensus rules unless a separate protocol change is approved.
- `pom.xml` only if a dedicated CLI launcher/module is chosen; no WAR startup bypass.
- New tests under `src/test/java/bootstrap/`, DAO integration test fixture support, plus documentation `API.md`, `ARCHITECTURE.md`, and `PROJECT_STATUS.md` after implementation.
- No schema change is currently assumed. If an immutable bootstrap digest/state marker is found necessary and cannot be derived safely from existing config/ledger rows, stop and propose a narrowly scoped schema migration separately.

## 24. Tests required

### Unit tests

- Deterministic genesis tuple/hash/proof verification; invalid network ID, admin key, timestamp precision, difficulty, nonce, or hash rejected.
- Manifest canonicalization, version/unknown-field rejection, digest verification, signer key match/proof, duplicate organization/key/peer IDs, malformed endpoint/fingerprint rejected.
- Governance initial state applies in dependency order and every transaction signature, event/transaction ID, block linkage, PoW, and cumulative work is verified.
- Wrong manifest/network/admin key, wrong local peer ID/certificate fingerprint, inactive organization, missing peer, and malformed PKCS#12 cause failure before runtime starts.
- First-admin creation validates role/null organization/password rules, hashes rather than stores raw password, refuses an already-present admin, and does not emit credentials to logs/manifest.
- Idempotency: same bundle against its completed DB is verify/no-op; recognized valid prefix resumes; different bundle, partial corruption, or unrelated rows fail closed.
- Concurrent bootstrap invocations cannot both initialize the same empty DB; use a guarded lock/unique state mechanism.
- Error paths do not leak credentials, signatures/private data, DB URLs with secrets, or stack traces to console output.

### MySQL integration tests

**Execution record (2026-10-05):** BOOT-IT-01 passed on the dedicated marked `agritrace_test` database, including interruption/resume boundaries, repeat initialization, unsafe-state rejection, and metadata-only environment mismatch. Exact post-run verification found zero rows in all application tables; the marker remained. The development catalog was not used.

- On an explicitly dedicated empty MySQL test DB, initialize config/genesis/governance, replay the complete chain, verify projections and the first local admin.
- Initialize two/three independent DBs from the same immutable bundle; assert equal network tuple, block hashes/transaction IDs/governance registry, while local account credentials and node identity remain independent.
- Exercise interrupted bootstrap/retry, duplicate execution, non-empty DB refusal, wrong DB/config refusal, and safe rollback/resume semantics.
- Test only with dedicated opt-in DB environment settings; never against user/prod data.

### Runtime/integration acceptance

- Start a node from bootstrap result and verify `NodeRuntimeListener` passes without bypassing peer validation.
- Verify first peer and all subsequently provisioned nodes can start with their own matching PKCS#12 identity and canonical registry entry.
- Run existing mTLS probe and manual network, shipment, fork, reconnect, restart, and public trace acceptance after bootstrap.
- Add a negative test showing a fresh unbootstrapped DB fails with actionable guidance and makes no partial unsafe state.

## 25. Migration implications, if any

The preferred design reuses existing tables (`network_config`, canonical blockchain tables/projections, and `users`) and therefore should not require a schema migration:

- The bootstrap tool needs a guarded way to create the initial `network_config` row because `NetworkConfigDAO` currently only reads.
- Genesis/governance blocks should persist through existing `Blockchain`/`BlockDAO` validation and atomic canonical projection path.
- First local admin should persist through `UserDAO` with `PasswordHasher`.
- The bootstrap bundle/audit manifest can remain an external signed artifact; do not store private values or add schema columns by default.

Implementation later established the need for a durable manifest identity: BOOT-03 added `bootstrap_manifest_digest` and `bootstrap_environment` to `network_config` through migration 003 and the new-database schema. The migration is additive and leaves legacy rows NULL; it was not applied to the development database. The dedicated integration database was built from the current schema and BOOT-IT-01 verified manifest-identity mismatch behavior there. Preserve migration 001/002 handling and do not rerun any migration automatically.

## 26. Explicit non-goals

- No code, schema, migration, database, account, genesis, peer, or certificate creation in this design task.
- No unauthenticated bootstrap endpoint, startup bypass, direct arbitrary SQL seed procedure, or production demo accounts.
- No changes to current empty-genesis consensus format, transaction/signature format, PoW, fork choice, or peer authentication rules.
- No automatic generation/storage of production private keys, server certificates, organization private keys, passwords, or DB secrets.
- No implementation or execution of MP-01 three-node acceptance yet.
- No claim that a bootstrap bundle, CLI, forced password change, HSM/KMS integration, multisig governance, or reset command exists today.

