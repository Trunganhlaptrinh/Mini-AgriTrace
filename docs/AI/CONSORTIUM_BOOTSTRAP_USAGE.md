# Consortium Bootstrap CLI

The CLI is an offline operator command packaged in the current Maven project. Run it before starting Tomcat.
It has no HTTP endpoint. Apply the checked-in schema to a new isolated MySQL instance first; use the database
name `agritrace`. Each node has its own database and uses the same signed manifest.

## Commands

```text
mvn exec:java -Dexec.args="validate path/to/manifest.json"
mvn exec:java -Dexec.args="status path/to/manifest.json [local-admin-username]"
mvn exec:java -Dexec.args="signing-bytes path/to/unsigned-manifest.json path/to/signing-input.bin"
mvn exec:java -Dexec.args="initialize path/to/signed-manifest.json local-admin-name"
```

For `status`, omit the optional username to accept the one local ADMIN already stored; provide it to require
an exact username match. It prints a JSON result with `state`, present/expected block and transaction counts,
local ADMIN username, `localPeerVerified`, and a non-secret detail. States are `UNINITIALIZED`, `RESUMABLE`,
`INITIALIZED`, `INCONSISTENT`, `DIFFERENT_NETWORK`, and `UNEXPECTED_DATA`. The command is read-only.

The manifest signature is P-256 ECDSA/SHA-256 in standard Base64, encoded as the 64-byte IEEE P1363 form.
It covers canonical JSON for all manifest properties except `signature`, including governance envelopes,
timestamps, environment, network tuple, and expected block hashes. `signing-bytes` writes canonical UTF-8 bytes
for an external approved signer; the CLI never loads the manifest-signing private key. After signing, run
`validate` before initialization. Distribute the same immutable public-data bundle to all nodes.

The root object uses exactly these fields: `schemaVersion` (currently 1), `environment` (`development`,
`staging`, or `production`), `networkId`, `genesisAdminPublicKey` (Base64 P-256 SPKI), `genesisTimestamp`,
`genesisNonce`, `difficulty`, `genesisHash`, `initialBlocks`, and `signature`. Each initial block contains
`timestamp`, `transactions`, and expected `hash`; each governance transaction contains `eventId`, `eventType`,
`eventTime`, `data`, and `adminSignature`. The verifier computes canonical transaction IDs and deterministic
PoW and validates every governance transition and expected block hash before any database write.

## Initialize, inspect, and resume a node

Set `AGRITRACE_DB_URL`, `AGRITRACE_DB_USERNAME`, and `AGRITRACE_DB_PASSWORD` for the node. The JDBC connection
must select the `agritrace` catalog. Set `AGRITRACE_P2P_PEER_ID`, `AGRITRACE_P2P_KEYSTORE_PATH`, and
`AGRITRACE_P2P_KEYSTORE_PASSWORD` (or their supported JVM properties). The configured local peer and its
organization must be active in the validated manifest; the local PKCS#12 leaf certificate fingerprint must
match that peer's canonical registration. A first local ADMIN password is read with terminal echo disabled
and stored only as the existing PBKDF2 hash through `UserDAO`.

The read-only verifier reconstructs canonical state through `BlockDAO` and `Blockchain` and compares:

- Exact network/genesis configuration and each expected block header/hash and transaction ID/body.
- Canonical chain length, no unexpected fork blocks, exact ledger/inclusion/status/pending counts.
- Replayed organizations, organization keys, peers, projection values, and projection source transaction IDs.
- Local ADMIN cardinality, active state, organization consistency, and optionally the requested username.
- The manifest's active local peer registration and actual local PKCS#12 certificate fingerprint.

`initialize` proceeds only from `UNINITIALIZED` or an exact `RESUMABLE` prefix. It installs the network row if
absent, applies only the first missing deterministic block, and creates the ADMIN only if it is absent. A
completed exact persisted state is `INITIALIZED`; running `initialize` again is a no-op. MySQL's named lock
serializes bootstrap writers. A mismatch, corrupt chain, conflicting projection/peer certificate, or unrelated
application data fails closed. The tool never clears, repairs, or migrates a target database.

The existing schema does not store the full signed manifest digest. Verification therefore compares the
manifest's effective persisted state; it cannot distinguish two signed files whose non-ledger metadata differs
while network configuration and ledger state are identical. Persisting a full manifest digest would require a
separately approved schema migration; this task does not change the schema or migrations.

## Dedicated bootstrap integration database

`ConsortiumBootstrapMySqlIntegrationTest` is separately opt-in and never falls back to the application's normal
`AGRITRACE_DB_*` settings. It requires a dedicated disposable MySQL **instance** whose catalog is named
`agritrace` (the catalog name in `database/schema.sql`). Do not use a developer, staging, or production instance.
Apply `database/schema.sql` on that dedicated instance, then add this marker there:

```sql
CREATE TABLE _agritrace_bootstrap_it_guard (
  guard_id TINYINT PRIMARY KEY,
  marker VARCHAR(100) NOT NULL
);
INSERT INTO _agritrace_bootstrap_it_guard (guard_id, marker)
VALUES (1, 'agritrace-bootstrap-it-only-v1');
```

Configure the test shell below. The JDBC URL must not contain credentials; keep user/password in their separate
variables. The test fails clearly if the opt-in/confirmation is missing, the URL equals the normal application
URL, the catalog is not `agritrace`, the marker is missing/wrong, or the application tables are not empty at the
start of a scenario. It never creates/drops the database or schema. Cleanup deletes only IDs created by the
test; the isolation marker remains.

```powershell
$env:AGRITRACE_BOOTSTRAP_IT_ENABLED = 'true'
$env:AGRITRACE_BOOTSTRAP_IT_CONFIRM = 'USE_ONLY_DEDICATED_AGRITRACE_BOOTSTRAP_IT'
$env:AGRITRACE_BOOTSTRAP_IT_JDBC_URL = 'jdbc:mysql://DEDICATED-TEST-HOST:3306/agritrace'
$env:AGRITRACE_BOOTSTRAP_IT_USERNAME = 'test-user'
$env:AGRITRACE_BOOTSTRAP_IT_PASSWORD = '<set securely in the shell>'
mvn -Dtest=ConsortiumBootstrapMySqlIntegrationTest test
```

Without those settings the DB test is skipped; never substitute the normal application database. Scenarios
simulate interruptions after network configuration, genesis, each governance block (including peer state),
before ADMIN creation, and immediately after ADMIN creation. Each checks read-only status, resume, deterministic
final ledger, and repeated initialization. Negative checks cover a different network, corrupted block data,
modified peer certificate fingerprint, and unexpected/duplicate ADMIN data.
