# Local three-node demo

This directory supports Node A (Farmer), Node B (Carrier), and Node C (Retailer). The databases, local development PKI, signed manifest, bootstrap state, and Tomcat bases are provisioned outside Git. Latest local runtime and product acceptance (2026-10-06) passed HTTPS, all six mTLS directions, the Farmer → Carrier shipment proposal/endorsement → Retailer provenance flow, public trace/QR, canonical convergence, and Node C interruption/recovery. Runtime state can change; inspect status before relying on a previous run.

## Files and generated state

Repository files here are templates and operator scripts. Runtime config and Compose environment live under `%LOCALAPPDATA%\AgriTrace\local-3node`; MySQL password files live outside the repository under `%USERPROFILE%\Desktop\agritrace-local3node-secrets`. Certificates/private keys, truststores, Catalina bases, database volumes, manifests, credentials, and logs must remain outside Git. Never copy private keys or passwords into this directory.

| Node | Role | App HTTPS | P2P HTTPS | MySQL host port | Catalog |
|---|---|---:|---:|---:|---|
| A | Farmer/Producer | 8443 | 9443 | 3307 | `agritrace` in isolated instance A |
| B | Carrier/Logistics | 8444 | 9444 | 3308 | `agritrace` in isolated instance B |
| C | Retailer | 8445 | 9445 | 3309 | `agritrace` in isolated instance C |

Each node has its own MySQL instance/volume, Catalina base, app/P2P identities and trust configuration. These demo instances are isolated from the development and integration-test MySQL catalogs on port 3306. Never use `down -v` or remove volumes as part of ordinary stop/cleanup.

## Manifest and bootstrap

The common signed local manifest was generated and validated with `ConsortiumManifestGenerator` and `ConsortiumBootstrapCli validate`; it was then used to initialize all three nodes. It includes the network/genesis, governance transactions, organizations/keys, peer registrations, and signatures. Manifest signing identities and password files stay outside Git. See `docs/AI/CONSORTIUM_BOOTSTRAP_USAGE.md` for the exact offline workflow. Each node's local ADMIN credential is stored in the current Windows user's Credential Manager, not in the manifest or repository; never export or print credential values.

## Provisioning and runtime scripts

- `Initialize-Local3NodeConfig.ps1 -CatalinaHome <TomcatHome>` writes external per-node configuration.
- `New-Local3NodeCertificates.ps1` creates the demo CA, unique node identities/truststores and external password files.
- `New-Local3NodeSecrets.ps1` creates isolated MySQL passwords and Compose environment outside Git.
- `Prepare-TomcatBases.ps1` prepares separate Catalina configuration, logs, webapps, and work directories; it does not start Tomcat.
- `Start-Local3Node.ps1 -Node A|B|C|All` starts selected DB services and Tomcat. It checks prerequisites and refuses occupied ports; it does not kill existing processes.
- `Get-Local3NodeStatus.ps1 -Node All` reports listeners/container status.
- `Get-Local3NodeLogs.ps1 -Node A -Source tomcat|database` reads selected logs.
- `Stop-Local3Node.ps1 -Node A|B|C|All` stops selected Tomcat/database services but retains volumes.
- `Cleanup-Local3Node.ps1 -Node A|B|C|All` is stop-only; it does not delete data, keys, certificates, or logs.

Recheck ports 8443–8445, 9443–9445, 3307–3309, and shutdown ports immediately before startup. Do not start or stop the demo without the operator's instruction.

## Acceptance evidence

The latest recorded local acceptance found all three MySQL containers healthy; app HTTPS 8443–8445 returned HTTP 200; P2P HTTPS 9443–9445 was listening; mTLS passed 6/6 peer directions; the locator and common network identity passed; all nodes converged at canonical height 7 after the real batch/shipment flow. Public trace and QR for the accepted batch passed. `Test-Local3NodeInterruption.ps1` interrupted/restarted Node C while A/B remained available and verified convergence afterward.

Browser-driven UI E2E (`Test-BrowserE2E.js`), negative/unregistered client certificate rejection, duplicate proposal/endorsement relay idempotency, and cumulative-work fork choice resolution (`Test-MP01Matrix.ps1`) are verified by automated test suites. Production operations (multi-host deployment, secrets, and TLS infrastructure) are maintained separately in operations runbooks. See `docs/MULTI_NODE_ACCEPTANCE.md` and `docs/AI/PROJECT_STATUS.md` for verification details.
