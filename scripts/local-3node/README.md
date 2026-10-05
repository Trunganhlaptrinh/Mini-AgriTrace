# Local three-node demo preparation

This directory prepares Node A (Farmer), Node B (Carrier), and Node C (Retailer). The scripts do not create certificates. Do not run the database-start or Tomcat-start commands until those infrastructure steps are explicitly approved.

## Files and generated state

Templates are tracked here. Runtime config, MySQL password files, certificates/private keys, truststores, Catalina bases, database volumes, manifests, and logs belong outside Git under `%LOCALAPPDATA%\AgriTrace\local-3node` or another reviewed external path. Never copy a private key or password into this directory.

Node defaults:

| Node | Role | App | P2P | DB host port | DB catalog |
|---|---|---:|---:|---:|---|
| A | FARMER | 8443 | 9443 | 3307 | agritrace in isolated instance A |
| B | CARRIER | 8444 | 9444 | 3308 | agritrace in isolated instance B |
| C | RETAILER | 8445 | 9445 | 3309 | agritrace in isolated instance C |

The separate MySQL instances/volumes never target either existing `agritrace` or `agritrace_test`. The schema file is mounted read-only and initializes only a new empty volume. Cleanup stops services but retains database volumes.

## Manifest workflow

1. Copy `manifest.descriptor.example.json` to the external state directory and replace all public-key/fingerprint placeholders with actual local-demo public data. Choose a network ID and approved UTC timestamps/difficulty. Create no production identity material.
2. Build the project; call the generator using the exec plugin override, e.g. `mvn exec:java "-Dexec.mainClass=bootstrap.ConsortiumManifestGenerator" "-Dexec.args=requests C:\path\descriptor.json C:\path\requests.json"`.
3. Send each `signingBytesBase64` request to the authorized external P-256 signer. Save the matching event IDs and 64-byte P1363 signature Base64 strings in an external `governance-signatures.json`.
4. Run `assemble <descriptor> <governance-signatures> <unsigned-manifest>` with the same main-class override.
5. Run the existing CLI `signing-bytes <unsigned-manifest> <bytes-file>`. Have the external admin signer sign the exact bytes; save the returned Base64 P1363 signature in an external text file.
6. Run generator `finalize <unsigned-manifest> <signature-file> <signed-manifest>`, then existing CLI `validate <signed-manifest>`. Distribute this same immutable signed bundle to all nodes.
7. Only after each isolated database is separately approved and provisioned, set that node's environment and run CLI `status` then `initialize` with the same manifest and a node-local admin username. The CLI asks for a new local admin password without terminal echo. The node's configured P2P peer identity/fingerprint must match the manifest.

For non-generator CLI commands, `mvn exec:java "-Dexec.args=validate C:\path\signed-manifest.json"` uses the configured `ConsortiumBootstrapCli` main class. See `docs/AI/CONSORTIUM_BOOTSTRAP_USAGE.md` for exact manifest signing semantics. The generator never loads private keys; preserve its signature inputs externally for repeatability.

## Prepare node config

After choosing an installed Tomcat 10.1 location (but before starting anything), `Initialize-Local3NodeConfig.ps1 -CatalinaHome <TomcatHome>` writes an external node config. Review it and set truststore passwords and local paths outside Git. `New-Local3NodeSecrets.ps1` writes six randomly generated MySQL passwords and Compose environment values outside Git; it does not start MySQL. `Prepare-TomcatBases.ps1` requires a built WAR and already existing external certificate/trust files, then prepares independent config/logs/webapps/work directories. It does not start Tomcat.

The configured P2P server connector requires client certificates. Its truststore trusts the local peer CA; Java outbound truststore trusts the local server CA. Each node needs a unique peer PKCS#12 private-key identity. Register the SHA-256 leaf fingerprint of that peer client certificate in the manifest. The app and P2P HTTPS server listener uses the node's server identity. Keep client peer identity and server TLS identity roles clear even if a local lab CA is used for both.

## Starting and observing (later approved operation)

- `Start-Local3Node.ps1 -DatabaseOnly -Node A` starts only the isolated Node A MySQL container. `-Node All` selects all. This is an infrastructure mutation; do not run without approval.
- After database bootstrap and Tomcat preparation, `Start-Local3Node.ps1 -Node A` starts selected DB + Tomcat. P2P identity passwords are prompted securely. The script refuses busy ports and missing prerequisites; it does not kill processes.
- `Get-Local3NodeStatus.ps1 -Node All` shows listener and container state.
- `Get-Local3NodeLogs.ps1 -Node A -Source tomcat|database` reads selected logs.
- `Stop-Local3Node.ps1 -Node All` stops selected Tomcats and demo database containers while retaining data.
- `Cleanup-Local3Node.ps1 -Node All` is a stop-only wrapper. It does not delete any data, key, certificate or log.

Recheck ports 8443–8445, 9443–9445, 3307–3309, and 8005–8007 immediately before a later start. The scripts do not terminate any process. Do not run the full multi-node acceptance matrix until all three nodes have been individually initialized and checked.
