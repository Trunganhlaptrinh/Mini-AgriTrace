# AgriTrace architecture and execution flows

## 1. MVP node model

Each participating organization runs one AgriTrace application instance and one blockchain node on the LAN. A node owns its local MySQL database, serves its organization's browser clients, validates the same chain rules as its peers, and can mine blocks. A node is not trusted merely because it belongs to a known organization.

```text
Farmer browser ──HTTPS── Farmer node ──mTLS/P2P── Warehouse node
                                  ├────mTLS/P2P── Carrier node
                                  └────mTLS/P2P── Retailer node
```

The blockchain is the shared source of truth for confirmed batch events and governance. MySQL stores local copies of blocks and transactions, the pending pool, node-local accounts, shipment workflow drafts, and rebuildable read projections. Shipment proposals and their signatures are node-local workflow records relayed between the sender and selected carrier over authenticated mutual TLS; the carrier-endorsed event is independently validated and submitted by both parties' nodes. Only the final two-signature `SHIPPED` event is ledger data. No node may accept a write based only on a projection or on a peer's claim that data is valid.

## 2. Package and folder responsibilities

```text
AgriTrace/
├── database/
│   └── schema.sql
├── docs/
│   ├── API.md
│   └── ARCHITECTURE.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── model/
│   │   │   ├── dal/
│   │   │   ├── service/
│   │   │   ├── blockchain/
│   │   │   ├── network/
│   │   │   ├── security/
│   │   │   └── util/
│   │   └── webapp/
│   │       ├── css/
│   │       ├── js/
│   │       ├── META-INF/
│   │       └── WEB-INF/
│   └── test/
│       └── java/
└── pom.xml
```

### `controller/`

Servlets translate HTTP into typed inputs, enforce session/role requirements, call services, and serialize the standard response envelope. They do not execute SQL, hash blocks, validate signatures, or implement supply-chain rules.

- `AuthController`: login, current account, logout, and password changes.
- `AdminController`: local user administration and submission of signed governance operations.
- `BatchController`: authenticated batch reads and event submissions.
- `ShipmentController`: create proposals, list a carrier's inbox, and submit endorsements.
- `PublicTraceController`: public QR trace endpoint with an explicit output allowlist.
- `TransactionController`: transaction status queries.
- `NodeController`: protected chain validation/status diagnostics.
- `PeerController`: internal peer-only P2P routes; never used by browser pages.

### `model/`

Plain Java request/response DTOs and domain types: `Batch`, `BatchEvent`, `Organization`, `OrganizationKey`, `AppUser`, `ShipmentProposal`, `SignatureEnvelope`, `LedgerTransaction`, `Block`, and response DTOs. Keep wire DTOs separate from database row mapping where their fields or validation differ.

### `dal/`

JDBC persistence using prepared statements and try-with-resources. Suggested DAOs: `UserDAO`, `BlockDAO`, `TransactionDAO`, `TransactionPoolDAO`, `OrganizationProjectionDAO`, `BatchProjectionDAO`, `ShipmentProposalDAO`, and `PeerProjectionDAO`. DAO methods expose persistence operations and do not decide whether a transaction is valid.

`TransactionDAO` stores a previously validated transaction, its transaction-pool entry, and its node-local `PENDING` status in one database transaction. It recomputes canonical IDs/hashes before writing and treats an exact repeat as idempotent; any partial failure rolls back all three writes. `TransactionStatusDAO` reads status and canonical block metadata and atomically rejects pending transactions/removes them from the pool. `BlockDAO` performs chain confirmation and reorganization status changes in its own transaction so they commit atomically with block links and canonical projection updates. JDBC timestamp conversion and the MySQL session are pinned to UTC. MySQL access is configured with `AGRITRACE_DB_URL`, `AGRITRACE_DB_USERNAME`, and `AGRITRACE_DB_PASSWORD`, or their `agritrace.db.url`, `agritrace.db.username`, and `agritrace.db.password` JVM properties. No credentials or implicit local database defaults are stored in source.

`TransactionPool` loads only locally `PENDING` transactions still present in `transaction_pool`, decodes and verifies their canonical IDs/hashes, then orders candidates by transaction ID. Admission and selection replay the whole sorted candidate set against the latest canonical snapshot without mining; invalid candidates are reported in the selection's deferred-reason map and remain pending for later reevaluation. `BlockProducer` mines only a non-empty selected set, chooses a UTC timestamp strictly later than its parent, and sends the result through `Blockchain` for fresh validation and persistence.

`BlockDAO` atomically stores block headers, transaction bodies and ordered inclusions. It chooses a canonical tip by cumulative work/hash tie-break, changes canonical flags, confirms newly canonical transactions, returns orphaned confirmations to the pending pool, and replaces organization, key, peer, batch, and event projections in the same SQL transaction. `loadCanonicalChain()` and `loadBranch()` return decoded transactions only after checking the stored hashes, commitments, work, linkage, duplicate IDs, and monotonic timestamps. Callers replay the returned blocks through `BlockValidator` to recreate each branch's `BlockValidationContext`; a validation result must include the complete transaction history before it can be persisted or used to rebuild projections.

`PeerLedgerServlet` exposes mTLS-protected pending transaction, canonical locator, block retrieval, and candidate block/transaction admission routes. `PeerLedgerSynchronizer` periodically pulls peer canonical blocks after the best shared locator entry and submits them through `Blockchain.processBlock`; it separately pulls paginated pending transactions through `TransactionPool` validation. Synchronization is pull-based and each node retains independent validation and fork choice. It does not assume that a trusted peer's block or transaction is valid.

Organization references in local users and shipment drafts are intentionally not foreign keys to rebuildable chain projections. During a canonical-chain rebuild, `users.organization_canonical` is recalculated without changing the administrator-controlled `is_active` flag; authentication must require both flags. Shipment drafts and signatures are retained across a reorganization but must be revalidated against the current canonical registry before they are endorsed or submitted. Apply `database/migrations/001_decouple_local_records_from_chain_projections.sql` once to existing databases before deploying this version. New databases receive the equivalent schema directly from `database/schema.sql`.

`network_config` is the immutable-at-runtime source of the network ID, expected genesis hash, proof-of-work difficulty, genesis timestamp/nonce, genesis administrator public key, and (after migration 003) bootstrap manifest digest/environment identity. `NetworkConfigDAO.installForBootstrap` writes the network configuration and signed-manifest identity as part of the one-shot bootstrap path; migration 003 adds the identity columns but deliberately does not infer values for an existing database. `NetworkConfigDAO` refuses missing or malformed configuration. On web application startup, `NodeRuntimeListener` reconstructs the configured empty genesis header, verifies its hash and proof of work, stores it only when the chain is empty, replays the stored canonical chain, and exposes the resulting transaction services to Servlets. Startup fails explicitly if configuration is missing, the genesis does not match, or stored chain replay fails. For an existing database, apply `database/migrations/002_add_genesis_header_configuration.sql` and populate the two new genesis-header columns from the already-approved network genesis before deploying; the migration intentionally leaves them NULL rather than inventing consensus values. New databases require those values in `network_config` from provisioning.

Every node in one network must be provisioned with the same `network_id`, `genesis_hash`, `initial_pow_difficulty`, `genesis_timestamp`, `genesis_nonce`, and Base64 SPKI genesis administrator public key. The matching private key must remain outside the node and source repository; it is needed by the administrator client to sign governance requests.

`TransactionMySqlIntegrationTest` runs against the configured MySQL database only when `AGRITRACE_DB_INTEGRATION=true`; it verifies insert, exact-repeat idempotency, conflicting event-ID rejection, status read/rejection, transaction-pool removal, and cleanup. `BlockMySqlIntegrationTest` is a separate opt-in test (`AGRITRACE_DB_BLOCK_INTEGRATION=true`) and refuses to run unless the configured dedicated database has no blocks, transactions, chain projections, users, or shipment drafts. Ordinary `mvn verify` skips both opt-in integration tests unless their respective flags are set. The bootstrap recovery suite is additionally hard-locked to `127.0.0.1:3306/agritrace_test` with its own marker; never point it at the development database.

### `service/`

Application use cases and orchestration:

- `AuthenticationService`: password verification and local account/session context.
- `GovernanceService`: construct and submit administrator-signed registry transactions.
- `BatchService`: validate and submit batch lifecycle events.
- `ShipmentService`: create immutable proposals, verify endorsements, and assemble `SHIPPED`.
- `TransactionService`: delegates batch/governance admission to `TransactionPool`, transaction status reads to `TransactionStatusDAO`, and explicit block-production requests to `BlockProducer`. HTTP controllers should call this boundary rather than DAOs or blockchain internals directly.
- `TraceabilityService`: public-field projection and chain verification summary.
- `ProjectionService`: rebuild batch and registry read models from the selected canonical chain.
- `ChainSyncService`: coordinate synchronization requests with peers.

### `blockchain/`

Deterministic ledger rules, independent from HTTP and JDBC:

- `CanonicalJson`: canonical payload serialization shared with the browser protocol.
- `SignatureUtil`: verify ECDSA signatures and encode/decode wire-format signatures.
- `HashUtil`: SHA-256 and fixed-width lowercase hexadecimal encoding.
- `TransactionValidator`: envelope, signature, key-height, role, and idempotency checks.
- `BusinessRuleValidator`: deterministic state transitions for batch lifecycle events.
- `BlockCodec` and `ProofOfWork`: canonical block/transaction commitments, header hashes, mining, and fixed-target verification.
- `BlockValidator`: parent linkage, configured genesis, difficulty, work, duplicate detection, signed batch/governance transaction validation, and sequential organization/key/peer and batch-state replay from a parent snapshot. Its result contains the complete transaction history and can provide the next child context.
- `ChainForkChoice`: compare already validated tips by cumulative work and deterministic hash tie-break.
- `Blockchain`: reloads and revalidates the canonical chain at startup, reconstructs the exact parent state for a candidate (including a non-canonical fork), validates it, persists it through `BlockRepository`, and returns the newly replayed canonical snapshot.
- `BlockDAO` and `CanonicalProjectionDAO`: atomic block/fork persistence, canonical branch selection, transaction confirmation/pool changes, and chain-derived projection rebuild.
- `TransactionPool`: validated batch/governance admission, deterministic pending selection, and temporary deferral of transactions that do not fit the current canonical state.
- `BlockProducer`: mine selected transactions and revalidate/persist the candidate through `Blockchain`.
- HTTP/network admission, scheduled mining/production policy, and peer broadcasting are still pending.

### `network/`

Peer transport and synchronization only:

- `PeerManager`: maintains connections to currently authorized peers.
- `PeerAuthenticator`: maps the presented mutual-TLS certificate fingerprint to an active peer registration.
- `PeerClient`: invokes authenticated peer endpoints.
- `PeerBroadcaster`: relays complete signed transactions and candidate blocks.
- `ChainSynchronizer`: exchanges locators and downloads missing blocks.
- `PeerMessage`: versioned P2P request/response envelopes.

Network messages are untrusted input until independently validated by `blockchain/`.

### `security/` and `util/`

`security/` contains servlet filters and security primitives: `AuthenticationFilter`, `RoleAuthorizationFilter`, `CsrfFilter`, `PasswordHasher`, and `SessionUtil`. The implemented login path queries `users` through `UserDAO`, verifies PBKDF2 credentials through `AuthenticationService`, checks both local and canonical activation flags, invalidates any existing session before creating a new one, and creates a random per-session CSRF token. Routes under `/api/v1/admin/*` and `/api/v1/node/*` require the `ADMIN` role. Password changes require the existing password and use a conditional update against the previously-read hash and active flags; the current session receives a new CSRF token after success. Existing other sessions are not revoked because session identifiers are not centrally persisted. API mutating requests are checked against `X-CSRF-Token`. Session cookies are configured HttpOnly, Secure, and SameSite=Lax. `util/` contains shared infrastructure such as `DBConnection`, `JsonUtil`, `Validation`, and configuration loading. Browser sessions and users remain local to their node; organization signatures are separate from login credentials.

`AdminUserServlet` implements local account creation and activation changes behind the authenticated ADMIN and CSRF filters. Non-admin account creation locks and checks the canonical organization projection and requires role/type agreement before inserting the PBKDF2 hash. Organization state may still be changed by a later canonical reorganization; authentication always checks `organization_canonical` again.

`AdminOrganizationServlet` accepts the genesis-admin-signed registration request, constructs the canonical governance transaction, and submits it through `GovernanceService` and `TransactionService` to the transaction pool. The pool replays it against the latest canonical state before persistence. The API response is only `PENDING`; registry projections change only after the transaction is included in a validated canonical block.

`AdminGovernanceServlet` handles the remaining signed registry operations: registering/revoking organization keys, suspending/revoking organizations, and registering/revoking peers. The genesis administrator signs the exact flat governance `data` fields for each operation. State and identifier rules are enforced again by `GovernanceRegistry` during admission and block replay; the HTTP layer does not mutate registry projections directly.

`BatchEventServlet` accepts `HARVESTED` at `/api/v1/batches` and subsequent lifecycle events at `/api/v1/batches/{batchCode}/events`. It parses event data as JSON values without converting quantities to floating point, constructs the signed transaction envelope, and delegates admission to `BatchService` and the transaction pool. `BatchService` requires the logged-in organization to be one of the signers. The pool validates signatures, canonical keys, event business rules, and lifecycle state against the current chain before persisting the event as `PENDING`.

`TransactionStatusServlet` exposes the local transaction lifecycle through `GET /api/v1/transactions/{txId}`. It returns block metadata only for transactions confirmed in the current canonical chain and exposes a rejection code only for locally rejected transactions. The DAO rechecks status and canonical block metadata together; unknown IDs return `404`.

`PasswordHasher` stores versioned PBKDF2-HMAC-SHA-256 hashes using a fresh 16-byte random salt and 600,000 iterations. Verification rejects malformed hashes and iteration counts outside the supported range before deriving a key. Password hashing does not make an account authenticated by itself; the login flow must still check both local account activation and canonical organization availability.

## 3. Deterministic signed-data protocol

All nodes must hash and verify identical bytes. Before implementation, the following protocol is the common contract for browser signing and Java validation:

1. Serialize JSON using RFC 8785 JSON Canonicalization Scheme (JCS); reject duplicate keys, non-finite numbers, unsupported fields, and out-of-range values before canonicalization.
2. Represent quantity values as decimal strings with a documented scale, rather than relying on JavaScript floating-point formatting.
3. Include `networkId`, `eventId`, transaction type, and the complete immutable payload in the signed envelope. Encode event times as UTC with exactly millisecond precision so Java and browser serialization produce the same string.
4. Organization signatures include a domain-separation purpose such as `FARMER_HARVEST`, `SHIPMENT_SENDER`, or `SHIPMENT_CARRIER`; an event signed for one purpose cannot be replayed as another.
5. Use Web Crypto ECDSA P-256/SHA-256. Web Crypto emits a 64-byte IEEE P1363 `r || s` signature; the API encodes these bytes as Base64. Java converts to/from the provider's ECDSA signature encoding as required and verifies against the registered SPKI public key.
6. `payloadHash` is SHA-256 of the canonical unsigned event payload. Each signature adds its purpose, signer organization, and key ID to that same payload before signing. `txId` is SHA-256 of the canonical complete transaction envelope, including the sorted signature list. Repeated submission of the same transaction therefore has the same ID; `eventId` is also unique on a node/network and prevents replay with a different signature.
7. A shipment proposal is immutable once the sender signs it. The carrier signs the same canonical `SHIPPED` payload with the carrier purpose; the expiry timestamp is included in the transaction data. The final `SHIPPED` transaction contains both signatures; changing any signed field invalidates both signatures.
8. Governance changes are signed by the genesis administrator key. Organization keys remain in the registry after revocation so historical signatures can be checked at their inclusion height.

Block hashing uses one specified byte representation:

- Sort the block's transaction IDs lexicographically and serialize the ordered array with JCS.
- `transactionsHash = SHA-256(JCS(ordered transaction ID array))`.
- The canonical block-header object includes `networkId`, `height`, `previousHash`, UTC block timestamp with exactly millisecond precision, `nonce`, `difficulty`, and `transactionsHash`.
- `blockHash = SHA-256(JCS(canonical block-header object))`.
- For the MVP, difficulty is fixed by the genesis/network configuration (1 through 16) and means the required number of leading zero hexadecimal characters in `blockHash`; each block contributes `16^difficulty` work. There is no automatic difficulty adjustment or Merkle tree.

Block validation checks network, parent linkage, height, monotonically increasing UTC timestamp, fixed difficulty, proof of work, transaction commitment/order, duplicate transaction/event IDs, signatures/key validity, and batch transitions in block order. Fork choice selects the valid tip with greatest cumulative work; equal-work tips are ordered by lexicographically smaller block hash. Chain state passed to block validation must be reconstructed at the candidate's parent, not read from a mutable canonical projection.

Governance transactions are signed by the configured genesis administrator key and replayed against the parent branch's organization/key/peer registry. A registry update takes effect in block transaction-ID order; later transactions in that same block see it, earlier transactions do not. Block validation is independent of the local wall clock (timestamps must advance from the parent only), so nodes do not diverge solely because their clocks differ.

The genesis block and genesis administrator public key are identical on every node in one network. A node must refuse a peer's chain with a different network ID or genesis hash.

## 4. State transition and authorization checks

Each candidate event is evaluated against the state reconstructed at its parent block, followed by earlier transactions in the candidate block. A node checks, in order:

1. The transaction belongs to this network, has a valid ID/payload hash, and has not already been included or replayed.
2. Every signer key was registered and active at the relevant chain height; signatures and domain purposes match the immutable payload.
3. The organization was active and the signer role matches the organization type.
4. The event is valid for the batch's current lifecycle state and holder.
5. For `SHIPPED`, exactly the current holder and selected active carrier signed the same proposal; the designated recipient is active.
6. For `RECEIVED`, the signer is the recipient of the latest shipment and the batch is in transit.
7. For `CORRECTION`, the signer organization signed the referenced original event; a correction appends information and never removes the original.
8. For governance, the genesis administrator signature is valid and the requested registry operation satisfies its rules.

Invalid candidates are rejected explicitly. A batch's current-holder/status tables are updated only after transactions become canonical and are always rebuildable from the chain.

## 5. Sequence: submit a normal event

Example: Farmer submits `HARVESTED` or `PACKAGED`.

```text
Farmer browser       Farmer node/API      BatchService       Blockchain/P2P       Peer nodes
      |                     |                   |                    |                 |
      | create event, sign  |                   |                    |                 |
      | POST signed JSON -->|                   |                    |                 |
      |                     | authenticate      |                    |                 |
      |                     |------------------>| validate role,     |                 |
      |                     |                   | state, signature   |                 |
      |                     |                   |------------------->| add to pool     |
      |                     |                   |                    |-- announce tx ->|
      |<-- 202 + txId ------|                   |                    |                 |
      |                     |                   |                    |                 |
      | GET tx status ---------------------------------------------->|                 |
      |<-- PENDING / CONFIRMED --------------------------------------|                 |
```

Admission returns `202 PENDING`, not a success claim that the event is final. Peers independently validate each transaction. A miner selects eligible transactions, orders them deterministically by `txId`, builds a candidate block, and searches nonces until the proof target is met.

## 6. Sequence: two-party shipment

```text
Holder browser     Holder node        Carrier node/browser      Peer network       Receiver
      |                  |                     |                      |                |
      | sign proposal    |                     |                      |                |
      | POST proposal -->| verify holder/state |                      |                |
      |                  | persist sender-signed proposal              |                |
      |                  | carrier retrieves inbox on this node        |                |
      |                  |                     |                      |                |
      |                  |                     | carrier reviews and signs exact payload
      |                  |<-- signed proposal / endorsement ---------|                |
      |                  | verify both signatures and expiry          |                |
      |                  | create SHIPPED(tx) and add to pool          |                |
      |                  |---------------- announce transaction ------>|                |
      |                  |                     |                      |                |
      |                  | candidate block mined and independently validated            |
      |                  |                     |                      |                |
      |                  |                     |                      |  receiver logs |
      |                  |                     |                      |  RECEIVED after |
      |                  |                     |                      |  confirmed ship |
```

The `SHIPPED` event does not become effective merely because a proposal exists or one party signed. The holder remains the confirmed holder until a valid `SHIPPED` is canonical; the batch is then `IN_TRANSIT`. The recipient becomes the confirmed holder only after its `RECEIVED` event is canonical.

The carrier needs an authenticated inbox endpoint and a local account for its operator. The proposal's sender signature is checked against the organization key registry before the carrier is allowed to endorse it. Peer-to-peer proposal relay is a follow-up; until then both accounts must use the node that stored the proposal.

## 7. Sequence: mining, validation, and fork resolution

1. The miner obtains a snapshot of the canonical parent and selects pending transactions.
2. The miner runs the full transaction and business-rule validator against that parent state. Transactions that conflict with the selected state are excluded.
3. The miner sorts candidate transaction IDs, computes `transactionsHash`, and searches a nonce for the configured target.
4. It stores the candidate block and its ordered transaction links, then broadcasts the block to authorized peers over mutual TLS.
5. Each peer validates network/genesis, parent, height, monotonically increasing timestamp, transaction order/root, recomputed hash, proof target, signatures, governance, and all state transitions. A peer does not trust the miner's local validation.
6. A node stores valid competing blocks as forks. It selects the valid tip with the greatest cumulative work. With fixed difficulty, this is equivalent to greatest height. For equal work, use the lexicographically smallest tip hash as the deterministic tie-break.
7. If the selected tip changes, the node rebuilds projections from the common ancestor and replays the new canonical branch. Previously confirmed transactions removed by a reorganization return to `PENDING` if still valid; conflicting ones are marked `REJECTED` with a reason.
8. The node continues announcing the selected tip and syncing missing blocks until peers converge.

The tie-break provides a deterministic local rule but does not prevent temporary forks or guarantee immediate convergence during a network partition. The MVP does not claim production-grade public-chain consensus or economic Sybil resistance.

## 8. Sequence: public QR trace

```text
Consumer browser       PublicTraceController       TraceabilityService       Local chain/projection
       |                         |                          |                           |
       | GET /public/.../trace ->|                          |                           |
       |                         | no login required        |                           |
       |                         |------------------------->| load canonical history    |
       |                         |                          | check chain validity       |
       |                         |                          | apply public-field allowlist
       |<-- public trace + verification status ------------|                           |
```

The endpoint returns only explicitly selected batch fields, public event fields, organization display name/role, and verification metadata. It never serializes database rows directly. If the local node detects an invalid canonical chain, it reports verification failure and does not return a success-shaped `valid: true` result.

## 9. Local projections and recovery

- `blockchain_blocks`, block/transaction links, and complete signed transactions form the local chain copy, including valid forks.
- `transaction_pool` and `node_transaction_status` describe this node's local pending/observed transaction lifecycle; they are not consensus state.
- `organizations`, `organization_keys`, `authorized_peers`, `batches`, and `batch_events` are projections rebuilt from the selected canonical chain.
- `users` and login sessions are local node data and are not propagated.
- Shipment proposals are local workflow records relayed to the designated carrier; the signed endorsement is relayed back to the sender. Only the final two-signature `SHIPPED` transaction is chain data.
- Recovery procedure: validate the selected chain from genesis, select the canonical tip, clear/rebuild projections, then resume P2P sync and mining. Never repair a chain by editing confirmed transaction contents.

## 10. Delivery status reference

The numbered build sequence below was the original design plan and is retained only as historical context; it is not a statement that those tasks are still pending. For verified implementation, test evidence, blockers, and the current prioritized roadmap, see [AgriTrace Project Status](AI/PROJECT_STATUS.md).

## 11. Offline consortium bootstrap

New network creation is an explicit, offline operator action before the WAR starts. `bootstrap.ConsortiumBootstrapCli` provides `validate`, `status`, `signing-bytes`, and `initialize` commands; bootstrap is not exposed as an HTTP endpoint. The production service is restricted to the `agritrace` catalog. Integration tests inject an expected catalog, while the recovery suite is separately hard-locked to `127.0.0.1:3306/agritrace_test` with its isolation marker. This test override does not change the production guard.

`BootstrapManifestCodec` verifies the versioned manifest, network/genesis parameters, signed governance transactions, initial blocks, and deterministic block/transaction content. Before initialization, `ConsortiumBootstrapService` checks the configured local peer ID and PKCS#12 leaf-certificate SHA-256 fingerprint against the active peer registration in the signed ledger. The private key remains in the operator-managed keystore and is not copied into the manifest or repository.

`BootstrapStateVerifier` is read-only and classifies a target as uninitialized, an exact resumable manifest prefix, initialized, inconsistent, unexpected data, or a different network/manifest. It checks the expected catalog, stored manifest identity, validated canonical chain, governance projections/provenance, transaction statuses/counts, and the single local active ADMIN. Initialization obtains a MySQL named lock, accepts only empty or exact-prefix state, stores network configuration plus manifest digest/environment, and replays genesis and initial blocks through the normal `Blockchain`/`BlockDAO` validation and persistence path. It then creates one local ADMIN through `PasswordHasher` and `UserDAO`; the credential is local and never part of the signed manifest. Repeating an already completed matching manifest is idempotent; an exact verified prefix can resume after interruption; unrelated or inconsistent state is refused rather than overwritten.

The recovery integration suite covers database isolation, initialization, status/repeat behavior, interruption and resume, mismatched identity, and unsafe database states on the dedicated test catalog. This is automated database evidence; it does not verify production PKCS#12 handling or startup in a live Tomcat container. See [consortium bootstrap usage](AI/CONSORTIUM_BOOTSTRAP_USAGE.md) for operator commands and limitations and [project status](AI/PROJECT_STATUS.md) for current verification and remaining work.
