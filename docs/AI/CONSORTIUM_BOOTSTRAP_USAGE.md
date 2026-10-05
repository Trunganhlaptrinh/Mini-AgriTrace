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
mvn exec:java -Dexec.args="initialize path/to/signed-manifest.json admin-a --credential-target AgriTrace/Local3Node/admin-a"
```

For `status`, omit the optional username to accept the one local ADMIN already stored; provide it to require
an exact username match. It prints a JSON result with `state`, present/expected block and transaction counts,
local ADMIN username, `localPeerVerified`, and a non-secret detail. States are `UNINITIALIZED`, `RESUMABLE`,
`INITIALIZED`, `INCONSISTENT`, `DIFFERENT_NETWORK`, and `UNEXPECTED_DATA`. The command is read-only.

The optional `--credential-target` mode is for the local A/B/C demo accounts only. It accepts a Credential Manager target name, never a password. Supported mappings are `admin-a` → `AgriTrace/Local3Node/admin-a`, `admin-b` → `AgriTrace/Local3Node/admin-b`, and `admin-c` → `AgriTrace/Local3Node/admin-c`. On Windows, the CLI reads a Generic Credential Manager entry into a temporary `char[]`; it validates the target, account, and generated-secret format, then clears native and Java buffers. Keep the existing interactive console path for manual accounts. The CLI does not create or rotate credential entries.

For provisioning A/B/C, use `scripts/local-3node/Initialize-Local3NodeBootstrap.ps1`. It preflights all selected nodes before writes, creates 256-bit independent CSPRNG secrets directly in Credential Manager only when a target is absent, invokes the CLI without putting those secrets in arguments/environment/stdout/files, and resumes without rotating an existing entry. If a later node fails, earlier initialized nodes and credentials are retained; there is no destructive rollback. `-PreflightOnly` performs no credential or database write.

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

`network_config` stores the SHA-256 digest of the manifest's canonical signing bytes and its environment label.
The digest covers every signed field except the signature bytes themselves. Bootstrap status requires both values
to match; changing environment or any other signed manifest field makes an existing DB report
`DIFFERENT_NETWORK`, even when the effective ledger is identical. The signature is always revalidated from the
manifest supplied to the CLI.

New databases receive these columns from `database/schema.sql`. Existing databases must first apply
`database/migrations/003_add_bootstrap_manifest_identity.sql`. The migration leaves old rows NULL; it does not
infer or backfill an identity from historical state. Consequently, an existing initialized row without a stored
digest cannot be accepted by the bootstrap verifier as a matching bundle. Review and provision existing network
rows deliberately before using this bootstrap flow. This project change adds the migration but does not apply it.

## Dedicated bootstrap integration database

`ConsortiumBootstrapMySqlIntegrationTest` is separately opt-in and never falls back to the application's normal
`AGRITRACE_DB_*` settings. It is hard-locked to `jdbc:mysql://127.0.0.1:3306/agritrace_test`; it rejects all
other hosts/catalogs. Do not point it at `agritrace`, staging, or production. The repository's
`database/schema.sql` intentionally creates/uses the development catalog `agritrace`; do not execute that file
unchanged for these tests. Apply its current table definitions to the already-created `agritrace_test` catalog
(omit/replace the leading `CREATE DATABASE ... agritrace` and `USE agritrace` directives), then add this marker
there:

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
URL, the catalog is not `agritrace_test`, the marker is missing/wrong, or the application tables are not empty at the
start of a scenario. It never creates/drops the database or schema. Cleanup deletes only IDs created by the
test; the isolation marker remains.

```powershell
$env:AGRITRACE_BOOTSTRAP_IT_ENABLED = 'true'
$env:AGRITRACE_BOOTSTRAP_IT_CONFIRM = 'USE_ONLY_DEDICATED_AGRITRACE_BOOTSTRAP_IT'
$env:AGRITRACE_BOOTSTRAP_IT_JDBC_URL = 'jdbc:mysql://127.0.0.1:3306/agritrace_test'
$env:AGRITRACE_BOOTSTRAP_IT_USERNAME = 'test-user'
$env:AGRITRACE_BOOTSTRAP_IT_PASSWORD = '<set securely in the shell>'
mvn -Dtest=ConsortiumBootstrapMySqlIntegrationTest test
```

Without those settings the DB test is skipped; never substitute the normal application database. Scenarios
simulate interruptions after network configuration, genesis, each governance block (including peer state),
before ADMIN creation, and immediately after ADMIN creation. Each checks read-only status, resume, deterministic
final ledger, and repeated initialization. Negative checks cover a different network, corrupted block data,
modified peer certificate fingerprint, and unexpected/duplicate ADMIN data.
