# Local three-node demo preparation

This directory prepares Node A (Farmer), Node B (Carrier), and Node C (Retailer). `New-Local3NodeCertificates.ps1` creates local development PKI material outside Git. It does not create databases, bootstrap nodes, or start Tomcat.

## Files and generated state

Templates are tracked here. Runtime config and Compose environment file are under `%LOCALAPPDATA%\AgriTrace\local-3node`; MySQL password files are under `%USERPROFILE%\Desktop\agritrace-local3node-secrets` so Docker Desktop can bind-mount them. Certificates/private keys, truststores, Catalina bases, database volumes, manifests, and logs stay outside Git. Never copy a private key or password into this directory. Certificate state is stored under `%LOCALAPPDATA%\AgriTrace\local-3node\identity`, `trust`, and `secrets`.

Node defaults:

| Node | Role | App | P2P | DB host port | DB catalog |
|---|---|---:|---:|---:|---|
| A | FARMER | 8443 | 9443 | 3307 | agritrace in isolated instance A |
| B | CARRIER | 8444 | 9444 | 3308 | agritrace in isolated instance B |
| C | RETAILER | 8445 | 9445 | 3309 | agritrace in isolated instance C |

The separate MySQL instances/volumes never target either existing `agritrace` or `agritrace_test`. The schema file is mounted read-only and initializes only a new empty volume. Cleanup stops services but retains database volumes.

## Manifest workflow

A complete signed local demo manifest has been generated outside Git at `%LOCALAPPDATA%\AgriTrace\local-3node\manifest\consortium-manifest.json` and passed `ConsortiumBootstrapCli validate` on 2026-10-05. It contains network `agritrace-local-3node-v1`, genesis, two deterministic initial governance blocks, the three organization and peer registrations, six governance signatures, and the outer P-256 signature. Its digest is `08ee7b56612620a4889fd4a16fbdae01724f585b7f881fa725b88c59f11220bb`; genesis hash is `0ea9d0a9afb5d48326149fd65a40f829556398a4ff724a2fbed3ebfb3087a77e`; initial tip is `01a6f85031cc8a13d790aaa2c2f47f1c5e805dd6efda1d22fc7ef056b62ab77f`.

The artifact was built from a copy of `manifest.descriptor.example.json` with the external certificate fingerprints and public keys. The four P-256 PKCS#12 signer identities and their password files are outside Git under `%LOCALAPPDATA%\AgriTrace\local-3node\manifest-signing\`. The helper is external and does not expose private keys. Do not copy any signer material into the repository.

To reproduce or create another bundle, copy the example descriptor outside the repository; provide P-256 public keys and peer client-certificate fingerprints; run the built generator directly using `java -cp "target/classes;target/AgriTrace/WEB-INF/lib/*" bootstrap.ConsortiumManifestGenerator <requests|assemble|finalize> ...`; sign each exact governance request and the outer manifest with the authorized P-256 identities; then run `bootstrap.ConsortiumBootstrapCli validate <manifest>`. The Maven exec plugin hard-codes the CLI main class, so do not rely on `-Dexec.mainClass` to invoke the generator. See `docs/AI/CONSORTIUM_BOOTSTRAP_USAGE.md` for signature details.

The validated bundle is public network configuration and can be reused unchanged for all three nodes. Per-node `status`/`initialize` is a later, separately approved bootstrap phase after each isolated DB and Tomcat base are ready. No node was bootstrapped in this manifest task.
## Prepare node config

After choosing an installed Tomcat 10.1 location (but before starting anything), `Initialize-Local3NodeConfig.ps1 -CatalinaHome <TomcatHome>` writes an external node config. Run `New-Local3NodeCertificates.ps1` to create the local CA, six unique identities, six per-node truststores, password files, and public inventory outside Git. `New-Local3NodeSecrets.ps1` writes six randomly generated MySQL passwords and Compose environment values outside Git; it does not start MySQL. `Prepare-TomcatBases.ps1` requires a built WAR and already existing external certificate/trust files, then prepares independent config/logs/webapps/work directories. It does not start Tomcat.

The configured P2P server connector requires client certificates. Its truststore trusts the local peer CA; Java outbound truststore trusts the local server CA. Each node needs a unique peer PKCS#12 private-key identity. Register the SHA-256 leaf fingerprint of that peer client certificate in the manifest. The app and P2P HTTPS server listener uses the node's server identity. Keep client peer identity and server TLS identity roles clear even if a local lab CA is used for both.

## Starting and observing (later approved operation)

- `Start-Local3Node.ps1 -DatabaseOnly -Node A` starts only the isolated Node A MySQL container. `-Node All` selects all. This is an infrastructure mutation; do not run without approval.
- After database bootstrap and Tomcat preparation, `Start-Local3Node.ps1 -Node A` starts selected DB + Tomcat. P2P keystore and outbound truststore passwords are read from external files. The script refuses busy ports and missing prerequisites; it does not kill processes.
- `Get-Local3NodeStatus.ps1 -Node All` shows listener and container state.
- `Get-Local3NodeLogs.ps1 -Node A -Source tomcat|database` reads selected logs.
- `Stop-Local3Node.ps1 -Node All` stops selected Tomcats and demo database containers while retaining data.
- `Cleanup-Local3Node.ps1 -Node All` is a stop-only wrapper. It does not delete any data, key, certificate or log.

Recheck ports 8443–8445, 9443–9445, 3307–3309, and 8005–8007 immediately before a later start. The scripts do not terminate any process. Do not run the full multi-node acceptance matrix until all three nodes have been individually initialized and checked.

## Verified MySQL provisioning — 2026-10-05

Docker Engine (client/server 29.7.2, context `desktop-linux`) is available. These three isolated MySQL 8.4.11 services are currently running and healthy:

| Node | Container | Host mapping | Database | Persistent volume |
|---|---|---|---|---|
| A | `agritrace-local3node-db-a` | `127.0.0.1:3307` → `3306` | `agritrace` | `agritrace-local3node-node-a-db` |
| B | `agritrace-local3node-db-b` | `127.0.0.1:3308` → `3306` | `agritrace` | `agritrace-local3node-node-b-db` |
| C | `agritrace-local3node-db-c` | `127.0.0.1:3309` → `3306` | `agritrace` | `agritrace-local3node-node-c-db` |

Read-only checks through each host mapping confirmed 14 tables from the current `database/schema.sql`, including all required ledger/governance/user tables. All 14 tables in each instance contain zero rows. The schema was applied on first initialization of each fresh volume; no migrations or demo/bootstrap data were applied. MySQL80 and its existing `agritrace` / `agritrace_test` catalogs on port 3306 were not connected to or targeted. The Docker services use separate volumes; do not use `down -v` or remove those volumes.

Docker Desktop did not mount secret files from `%LOCALAPPDATA%` as regular files. The helper now writes them to `%USERPROFILE%\Desktop\agritrace-local3node-secrets`, outside the repository, with local ACLs. Credentials are not recorded in this document. Certificates/truststores are now provisioned; Tomcat, consortium bootstrap, and multi-node acceptance remain unperformed.