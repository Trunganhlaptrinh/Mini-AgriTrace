# Multi-Node Provisioning Plan

**Scope:** local three-node MP-01 preparation. MySQL database-only provisioning for Nodes A/B/C completed and was verified on 2026-10-05. Local demo certificates/truststores were provisioned on 2026-10-05. Consortium bootstrap and Tomcat instances have not been run or started.

## 1. Target topology

| Node | Organization role | App HTTPS | P2P HTTPS/mTLS | MySQL host port | Peer ID |
|---|---|---:|---:|---:|---|
| A | Farmer / `FARMER` | 8443 | 9443 | 3307 | `peer-node-a-farmer` |
| B | Carrier / `CARRIER` | 8444 | 9444 | 3308 | `peer-node-b-carrier` |
| C | Retailer / `RETAILER` | 8445 | 9445 | 3309 | `peer-node-c-retailer` |

Ports are defaults. The start script checks selected database, app, P2P and Tomcat shutdown ports immediately before starting and fails without stopping an existing listener. App and P2P connectors bind to loopback for this same-machine local demo. The default deployed WAR context is `/AgriTrace`.

## 2. Verified bootstrap implementation

Offline consortium bootstrap **does exist**:

- `bootstrap.ConsortiumBootstrapCli` offers `validate`, `status`, `signing-bytes`, and `initialize`.
- `bootstrap.ConsortiumBootstrapService` verifies a signed manifest, initializes/resumes an exact expected ledger, and creates a node-local ADMIN only through an interactive console. It refuses unexpected/conflicting database state; it does not clear data or apply schema migrations.
- `bootstrap.BootstrapManifestCodec` defines and verifies the manifest format and canonical signature bytes. `BootstrapStateVerifier` verifies persisted state. The schema requires the manifest identity migration for an existing schema that lacks its identity columns.
- The existing bootstrap CLI does not have a `generate` command. `bootstrap.ConsortiumManifestGenerator` fills that gap for the local A/B/C topology.
- `bootstrap.ConsortiumManifestGenerator` now fills that gap for the specific local A/B/C topology. It emits signed governance requests, assembles and verifies the deterministic initial blocks from externally returned signatures, and finalizes/verifies the signed bundle. It loads no private keys and does not change the existing CLI.

### Exact manifest content and signatures

The generator uses the current codec/schema rather than an invented manifest shape. The bundle includes schema/environment, network ID, genesis administrator public key, genesis timestamp/nonce/difficulty/hash, two deterministic initial blocks, three organization registration transactions (with each initial organization public key), three peer registrations (HTTPS endpoint and lowercase SHA-256 certificate fingerprint), governance signatures, and the required outer manifest signature.

Both governance events and the outer manifest use P-256 ECDSA/SHA-256 with 64-byte IEEE P1363 signatures encoded as standard Base64. The outer signature covers JCS canonical JSON of every manifest property except `signature`. Governance signatures cover the current `GovernanceCodec` signing bytes. Timestamps use UTC with exactly millisecond precision. The generator sorts organization/peer requests and transaction IDs and uses the existing deterministic proof-of-work and validators. Reproducibility assumes identical descriptor, timestamp and signature bytes; ECDSA signatures can differ between independently signing runs, so preserve the returned signature files for byte-for-byte repeatability.

The generator accepts only a development descriptor with exactly one `FARMER`, `CARRIER`, and `RETAILER`, and one peer per organization. It validates P-256 public keys, endpoint/fingerprint values through the current governance validators, signature sizes and cryptographic matches, block hashes, governance transitions, and the final bundle. Private keys must be held by the approved external signer. Do not place signer key files, generated secret material, or live certificates in Git.

### Manifest creation, signing, validation and node bootstrap

1. Prepare a descriptor JSON outside the repository containing the network ID, approved difficulty and UTC millisecond timestamps, Base64 P-256 public keys for the genesis administrator and three organizations, organization/key IDs and names, peer IDs/org mappings, each HTTPS endpoint (including `/AgriTrace`), and the SHA-256 fingerprint of each peer client certificate. Do not use real production identities for this local demo.
2. Build the application and run `bootstrap.ConsortiumManifestGenerator requests <descriptor.json> <requests.json>` using the project classpath (`target/classes;target/AgriTrace/WEB-INF/lib/*`). The Maven exec plugin pins the CLI main class, so overriding `exec.mainClass` is not a reliable way to invoke the generator. Each request contains the exact Base64 signing bytes. Have the authorized external admin signer sign each request; write `schemaVersion: 1` and a `signatures` list mapping each exact `eventId` to its Base64 64-byte P1363 signature.
3. Run generator `assemble <descriptor.json> <governance-signatures.json> <unsigned-manifest.json>`. It verifies the governance signatures and deterministic ledger before emitting an unsigned bundle.
4. Run `bootstrap.ConsortiumBootstrapCli signing-bytes <unsigned-manifest.json> <manifest-signing-bytes.bin>`. Have the authorized external admin signer sign those bytes and save the Base64 P1363 output outside the repo. Run generator `finalize <unsigned-manifest.json> <signature.txt> <signed-manifest.json>`; this verifies the signature and complete manifest.
5. Run `bootstrap.ConsortiumBootstrapCli validate <signed-manifest.json>` as a DB-free manifest validation. Retain the signed manifest as public network configuration and distribute the **same unchanged file** to all three isolated nodes.
6. After separately approved infrastructure provisioning and applying the schema to each fresh node database, set that node's DB/P2P environment and run `status <manifest> <local-admin-name>` then `initialize <manifest> <local-admin-name>` against that node only. `initialize` requires the configured local peer ID and PKCS#12 identity to match that peer's manifest registration, asks for the new local ADMIN password via an echo-disabled interactive console, and verifies the resulting DB. Repeat separately for A/B/C. Never point these commands at `agritrace` development or `agritrace_test` integration databases.

Use the existing Maven exec plugin only for `ConsortiumBootstrapCli` commands. Invoke the generator directly with Java and the built classpath as shown in `scripts/local-3node/README.md`. For this approved local demo, a separate external helper holds four P-256 PKCS#12 keys and signs the six governance requests plus the outer manifest; it is not part of the repository. Keep all key/password material and manifest artifacts outside Git.

## 3. Reusable runtime and security components

- `NodeRuntimeListener` loads the configured local peer and fails startup unless it is active in the canonical ledger and its certificate fingerprint matches.
- `PeerIdentity` loads the local PKCS#12 identity; `PeerAuthenticationFilter` requires a client certificate on protected internal P2P endpoints and checks it against the canonical peer registry.
- `PeerClient` and `PeerLedgerSynchronizer` already implement authenticated HTTPS calls, ledger/block and pending-transaction synchronization, retries through scheduled polling, and the existing peer routes.
- `ConsortiumBootstrapService` provides the required offline pre-start initialization path. Each independent node is initialized with the same signed manifest, then starts with its own matching peer ID and client PFX.
- The prepared Tomcat template has a browser-facing HTTPS connector and a separate P2P HTTPS connector configured to require client certificates. Server identities are per node; a local peer CA is trusted by the P2P connector; the JVM truststore is supplied for outbound server TLS. Fingerprint authorization remains an application-level check in addition to TLS trust.

## 4. Prepared files and operator scripts

The scripts/config under `scripts/local-3node/` are prepared templates. The Compose DB services were invoked for Nodes A/B/C; only those three MySQL containers and their dedicated volumes are running. The secret helper generated credentials outside the repository. Tomcat/config preparation scripts were not executed.

- `compose.yaml`: three independent MySQL 8.4 containers, each with its own named volume and host port. Each initializes the unchanged schema into its own `agritrace` catalog. Docker secrets are supplied from external password files.
- `nodes.example.psd1`: A/B/C role, port, peer/org ID and external path template.
- `Initialize-Local3NodeConfig.ps1`: writes an external user config under `%LOCALAPPDATA%\AgriTrace\local-3node`; does not generate keys/certificates.
- `New-Local3NodeSecrets.ps1`: writes independent random MySQL app/root password files and Compose env outside Git; does not start/create DB instances.
- `server.xml.template` and `Prepare-TomcatBases.ps1`: define and render isolated Catalina bases/config/logs/webapps after certificates exist and WAR is built; preparation does not start Tomcat.
- `Start-Local3Node.ps1`: checks listeners and prerequisites; `-DatabaseOnly` starts only selected MySQL demo containers. Without that switch it starts those DB containers and selected prepared Tomcat instances. Do not run before explicit infrastructure approval.
- `Stop-Local3Node.ps1`: stops selected Tomcat and DB services; preserves DB volumes.
- `Get-Local3NodeStatus.ps1`, `Get-Local3NodeLogs.ps1`: inspect selected listener/container/Tomcat status and logs.
- `Cleanup-Local3Node.ps1`: stops selected services only. It deliberately does not delete volumes, identities, logs, or Catalina bases.

All node state is intended to stay outside the repository under `%LOCALAPPDATA%\AgriTrace\local-3node`, with separate `tomcat\node-a|b|c`, logs/webapps within each Catalina base, `identity`, `trust`, `secrets`, and `database` state. Do not copy private keys or DB volumes into the repository.

## 5. Database isolation

The project schema explicitly creates/selects a catalog named `agritrace`; therefore this topology uses **three separate MySQL instances**, not three schemas on the existing MySQL server. Each instance maps only to loopback host port 3307, 3308, or 3309 and has a separate named volume/container and app/root password. This is distinct from both existing databases `agritrace` and `agritrace_test`; provisioning scripts reference neither. The schema is mounted read-only and runs only when a newly created Docker data volume is initialized. MySQL's image initialization is not a migration mechanism for an existing volume.

On 2026-10-05, three isolated MySQL 8.4.11 containers were provisioned and verified: `agritrace-local3node-db-a` on 127.0.0.1:3307 with volume `agritrace-local3node-node-a-db`, node B on :3308 with `agritrace-local3node-node-b-db`, and node C on :3309 with `agritrace-local3node-node-c-db`. Each uses its own catalog named `agritrace`, the unchanged `database/schema.sql`, and a separate persistent volume. Each catalog has 14 tables, including the six required core tables, and every one of the 14 tables has zero rows. No migration was applied. MySQL80 and its :3306 databases were not connected to or targeted; `agritrace_test` was not connected to or targeted. Container/volume inspection verifies the new containers use only their own named volumes. The configured app URL remains `127.0.0.1:<node-port>/agritrace`. Cleanup/stop retain the three volumes; no reset/drop was run.

## 6. Certificate and key strategy

MP-01-CERT generated a local-only RSA-3072 development CA and six RSA-2048 leaf identities on 2026-10-05; all private material and passwords are outside Git. Each node has its own server PFX and P2P client PFX. Its server identity is used on app and mTLS P2P connectors. Each node has distinct peer-client and outbound-server PKCS#12 truststores containing the local CA. Server leaf SANs are DNS:localhost and IP:127.0.0.1; peer client certificates have no SAN because the implementation authorizes their SHA-256 certificate fingerprint against the active peer registry. The public inventory is external at `%LOCALAPPDATA%\AgriTrace\local-3node\identity\certificate-inventory.json`.

- Per node: one P2P client PKCS#12 with exactly one private-key entry, a server certificate/key for the local app/P2P HTTPS listeners, and password files outside Git.
- Trust: import the local peer CA into each node's P2P client-certificate truststore; import the local server CA into the Java outbound truststore. Keep the trust layers distinct.
- Manifest: register each P2P client leaf certificate SHA-256 fingerprint, not the server certificate fingerprint.
- Signer: keep genesis-admin and organization private keys in an external signer or protected location; only public keys and signatures enter the public manifest.
- Never use production certificates, reuse one peer private key across nodes, commit a key/password, or print secret values.

Local CA is not installed into Windows root trust. CA/PFX/truststore passwords are kept in external secret files. Certificate rotation/revocation and production PKI remain outside this local demo.

## 7. Port preflight and environment state

At pre-provisioning preflight, ports 3307–3309, 8443–8445, 9443–9445, and 8005–8007 were free; Docker 29.7.2 client/server on context `desktop-linux` was available. After provisioning, only 3307–3309 are listening and map to the three MySQL demo containers; MySQL80 remains running and Tomcat10 remains stopped. Recheck all target ports immediately before any later Tomcat start. Scripts fail on occupied ports and never kill a process.

Runtime variables are node-specific: `AGRITRACE_DB_URL`, `AGRITRACE_DB_USERNAME`, `AGRITRACE_DB_PASSWORD`, `AGRITRACE_P2P_PEER_ID`, `AGRITRACE_P2P_KEYSTORE_PATH`, and `AGRITRACE_P2P_KEYSTORE_PASSWORD`. Do not store them in this repo. The local secret helper handles DB passwords; P2P password is requested interactively by the start script. Confirm Java's truststore is configured for outbound TLS before starting.

## 8. Provisioning sequence after approvals

This is the prepared flow, not an assertion that infra has been provisioned:

1. External node configuration and isolated databases are already prepared; retain their separate volumes and recheck ports before starting any application process.
2. Local demo CA/certificates and peer fingerprints are already provisioned outside Git. The shared signed manifest is generated and validated; do not regenerate it unless the registered identity/endpoints change.
3. Review the external signed manifest and confirm the exact same file is selected for all three nodes.
4. Build the WAR, confirm Tomcat 10.1 installation, and prepare A/B/C Catalina bases from the external certificates/config. This does not start Tomcat.
5. With separate approval, run `validate`, `status`, and `initialize` for each node against only its isolated DB, using the same signed manifest and the matching node peer identity. Bootstrap is the supported pre-start ledger path; no manual ledger SQL is needed.
6. After explicit approval, start Tomcat nodes. Check status/logs and verify each node reports identical network/genesis and expected active peer records.
7. Only after all three nodes are safely provisioned and individually smoke-checked, run the separately planned multi-node acceptance matrix. This task has **not** run that matrix.

Do not use `agritrace` or `agritrace_test` in any of these steps. Do not apply migrations to demo containers unless the database state and migration need are separately reviewed; fresh containers use `database/schema.sql`.

## 9. Current scope and remaining MP-01 verification

### Prepared in repository

- Deterministic local descriptor-to-manifest workflow that reuses existing codec, governance validation, PoW, and signed bootstrap CLI.
- Three-node Compose configuration, external secret/config preparation, per-node Tomcat template/base preparation, and start/stop/status/log/cleanup scripts.
- Documentation of per-node DB/key/identity separation and operator sequence.

### Verification completed and remaining

- Focused generator unit tests passed on 2026-10-05: 3 tests, 0 failures/errors/skips.
- Local development descriptor, six governance signatures, outer manifest signature, and signed manifest were generated outside Git and validated by the existing DB-free CLI on 2026-10-05.
- Repeat port preflight before any later app/Tomcat start.
- Live mTLS handshakes, Java outbound trust, fingerprint registration, and peer synchronization still require verification after signed-manifest bootstrap and Tomcat start.
- Consortium bootstrap of each isolated node using the validated shared manifest, after per-node config/Tomcat preparation.
- Confirm Tomcat 10.1 installation, prepare bases, then an explicitly approved start.
- MP-01 acceptance: live HTTPS/mTLS, all directed peer links, ledger/pending synchronization, shipment workflow, disconnect/reconnect, restart and persistence.

`scripts/acceptance/Test-MultiNodeP2P.ps1` remains the read-only convergence probe; it is not a workload generator and does not replace the acceptance matrix in `docs/MULTI_NODE_ACCEPTANCE.md`.

## 10. Safety rules

- Do not connect to, modify, migrate, reset or drop `agritrace` or `agritrace_test`.
- Do not use any development or integration database as a node DB.
- Do not run database, certificate or Tomcat provisioning before the corresponding explicit approval.
- Do not kill a listener; stop only services/container names created for this demo.
- Keep all keys, certificates, passwords, secret env files, DB volumes, manifests containing sensitive internal topology, and generated logs outside Git unless reviewed and explicitly approved as public artifacts.
- Do not run full MP-01 acceptance until provisioned identities and nodes are verified.

## 11. MP-01-INFRA-01 verified run — 2026-10-05

- Docker Desktop Engine was reachable through context `desktop-linux` (client/server 29.7.2). Initial container and volume inventory was empty for AgriTrace.
- Created exactly three running MySQL containers from `mysql:8.4` (resolved version 8.4.11), each reporting healthy and bound only to loopback ports 3307, 3308, and 3309.
- Persistent Docker volumes are distinct: `agritrace-local3node-node-a-db`, `agritrace-local3node-node-b-db`, and `agritrace-local3node-node-c-db`.
- Read-only MySQL client queries through each mapped host port confirmed `DATABASE() = agritrace`, MySQL 8.4.11, 14 base tables, and zero rows in every schema table. Required tables `blockchain_blocks`, `blockchain_transactions`, `network_config`, `users`, `organizations`, and `authorized_peers` exist.
- The existing Windows `MySQL80` service remained Running and was not stopped or queried. No operation in this provisioning targeted port 3306 or either existing catalog; consequently this run made no changes to `3306/agritrace` or `3306/agritrace_test`. Their row contents were not independently snapshotted or compared.
- Docker Desktop did not mount secret files correctly from `%LOCALAPPDATA%` (it presented the file path as a directory); MySQL containers exited before schema initialization. The local helper/config was adjusted to store credentials under `C:\Users\Admin\Desktop\agritrace-local3node-secrets`, outside Git, with ACLs restricted to the current user, SYSTEM, and Administrators. Compose was revalidated and the same named volumes were reused; the final DB services became healthy. Secret values were never printed.
- No Tomcat, application, certificate, truststore, consortium bootstrap, or acceptance workload was started or created. No volume was deleted and no database reset/drop was run.