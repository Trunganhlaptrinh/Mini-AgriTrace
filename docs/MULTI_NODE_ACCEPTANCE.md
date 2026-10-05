# Multi-node mTLS acceptance

This procedure exercises three independently configured AgriTrace nodes (sender, carrier, and another consortium peer). Each node must have its own MySQL database, HTTPS server identity, P2P client PKCS#12 identity, and active governance peer registration. Never copy a node database or private key between nodes, and do not put credentials or key material in this repository.

## Read-only mTLS and convergence probe

Use PowerShell 7 from the project root. Supply each node's P2P client identity and its outbound server truststore. Passwords can be entered at hidden prompts or read from protected files outside the repository with the corresponding `*-PasswordFile` parameters. The probe uses the truststore CA as a custom trust anchor while retaining certificate-chain, server-auth EKU, and hostname validation; it does not bypass HTTPS validation.

```powershell
.\scripts\acceptance\Test-MultiNodeP2P.ps1 `
  -SenderUrl "https://sender.example/AgriTrace" `
  -SenderPfx "C:\secure\sender-peer.p12" `
  -SenderTrustStore "C:\secure\sender-server-trust.p12" `
  -CarrierUrl "https://carrier.example/AgriTrace" `
  -CarrierPfx "C:\secure\carrier-peer.p12" `
  -CarrierTrustStore "C:\secure\carrier-server-trust.p12" `
  -OtherUrl "https://other.example/AgriTrace" `
  -OtherPfx "C:\secure\other-peer.p12" `
  -OtherTrustStore "C:\secure\other-server-trust.p12"
```

For pending-transaction replication, submit a valid event while it remains pending and pass its lowercase transaction ID using `-ExpectedPendingTransactionId`. Run the probe before a block producer confirms the transaction. A successful probe is runtime evidence for the environment in which it was executed; it is not a substitute for the disruption scenarios below.

The probe is read-only. It does not create users, transactions, blocks, peer registrations, or database records. On Windows, client PFX identities are imported into the current user's temporary key context for Schannel compatibility and released after the probe. The local demo CA is not installed into Windows Root; the probe instead validates against each node's configured outbound truststore. Its revocation setting matches the Java default trust manager used by this application and the local CA has no revocation service.

## Scenario matrix

Record each row with the run date, node versions/configuration, node logs, expected result, and outcome:

| Scenario | Procedure | Acceptance condition |
| --- | --- | --- |
| Independent stores and identities | Confirm distinct database/schema names and peer certificate fingerprints for sender, carrier, and other node. | No shared writable database or private-key file; each certificate fingerprint matches its active peer registration. |
| mTLS authorization | Run the probe; then repeat one request with a certificate not registered on the target. | Registered peers can read P2P endpoints; an unregistered, revoked, or missing client certificate is rejected. |
| Pending transaction sync | Submit one valid event to sender and run the probe with its transaction ID before mining. | The transaction appears in each remote pending pool and remains independently verifiable. |
| Block propagation | Mine/confirm an event on one node, then rerun the probe. | All three nodes converge on the same canonical tip and trace result. |
| Shipment relay and retry | Create a proposal at sender, endorse it at carrier, then repeat delivery using the identical endorsement request. | Carrier and sender admit the same transaction ID; duplicate delivery is idempotent and no second event is created. |
| Disconnection and retry | Temporarily block one peer's network path, submit an event, observe logged retry failures, restore connectivity, and wait for scheduled synchronization. | No invalid success is reported while disconnected; the nodes converge after reconnection without manual database edits. |
| Fork choice | Isolate nodes after a common block, create independently valid branches using the supported block-production workflow, reconnect, and run the probe. | Every node validates both branches and converges on the branch selected by cumulative work; invalid blocks never become canonical. |
| Restart persistence | Stop and restart each application instance without changing its own database or identity, then run the probe. | Each node loads its canonical state and resumes peer synchronization; the same network eventually converges. |

**Latest local evidence (2026-10-06):** the three independent MySQL demo databases, active peer registrations, node-specific PKCS#12 identities, and common signed manifest were used by Nodes A/B/C. Tomcat application ports 8443–8445 and P2P ports 9443–9445 were listening; each application HTTPS endpoint returned HTTP 200. The mTLS probe passed all six peer directions. The real Farmer → Carrier proposal/endorsement → Retailer provenance flow completed, the public trace and SVG QR endpoints passed, and all nodes converged at canonical height 7. The interruption/recovery script stopped and restarted Node C; A and B stayed available and all three nodes reconverged at the same tip. This is local demo evidence, not production acceptance.
