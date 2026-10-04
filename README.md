# Mini AgriTrace

Mini AgriTrace is a Java web application for recording and checking agricultural batch history across a consortium of independently operated organizations. Farmers, carriers, warehouses, and retailers submit signed lifecycle events. Consortium nodes validate and synchronize the same ledger. Public visitors can read an allowlisted trace for a batch, including through a QR link.

The project is an application prototype/MVP. A successful build and unit tests do not establish production readiness or prove that separately deployed nodes interoperate.

## Product workflows

- **Batch traceability:** authorized organization users sign and submit harvest, handling, shipment, receipt, and sale events. Confirmed events form a verifiable history.
- **Shipment handoff:** a sender stores a shipment proposal locally; a carrier receives it and endorses it. The signed shipment event is submitted to the ledger after required endorsements.
- **Consortium governance:** local administrators submit signed organization, organization-key, and peer registration or status changes.
- **Node synchronization:** nodes authenticate one another with mutual TLS, exchange pending transactions and blocks, and independently validate data before accepting it.
- **Public trace:** anonymous visitors can retrieve selected public batch information and use a QR link to open the same trace page.

## Architecture

AgriTrace uses Java Servlets on a Jakarta EE API, JDBC/MySQL persistence, and a Maven WAR. Servlet filters handle browser sessions, CSRF, role access, and peer authentication. Services implement workflows, DAOs own SQL persistence, and the `blockchain` package handles canonical encoding, signatures, validation, proof of work, governance replay, fork choice, and projection rebuilds. The browser UI uses HTML, CSS, JavaScript, and Web Crypto; it has no frontend build framework.

```text
Browser ──HTTPS──> Servlet/controller ──> service ──> DAO ──> MySQL
                                              │
                                              └──> transaction/block validation

AgriTrace node <──HTTPS with mTLS / P2P──> AgriTrace node
```

Confirmed batch and governance transactions and their blocks are persisted as the ledger. Accounts, shipment drafts/signatures, the pending pool, transaction status, and rebuildable projections are local to a node. Public trace output is allowlisted. Organization and peer authorization comes from replayed canonical governance state, not from trusting a projection alone.

The P2P TLS server configuration is supplied by the servlet container/deployment. Each node needs its own database and PKCS#12 client identity. Peer authorization also checks the active canonical peer registration and certificate fingerprint.

## Technology

- Java source/target 17
- Maven 3.x
- Jakarta EE 10 APIs and annotation-mapped Servlets
- MySQL with MySQL Connector/J 8.4.0
- Tomcat-compatible Servlet container (the exact Tomcat version is not pinned here)
- Gson, JSON Canonicalization Scheme, ZXing Core, JUnit Jupiter
- HTML, CSS, JavaScript, and browser Web Crypto

## Repository layout

| Path | Contents |
| --- | --- |
| `src/main/java/controller` | HTTP Servlets and request/response handling |
| `src/main/java/service` | Application workflows and business services |
| `src/main/java/dal` | JDBC DAOs and persistence boundaries |
| `src/main/java/model` | Domain records and enums |
| `src/main/java/blockchain` | Transaction/block codecs, signatures, validators, PoW, governance, fork choice |
| `src/main/java/network` | Peer identity, mTLS clients/authentication, wire codecs, synchronization, relays |
| `src/main/java/security` | Sessions, authentication, CSRF, role/peer filters, password hashing |
| `src/main/java/bootstrap` | Offline signed-manifest validation, database status, safe bootstrap/resume CLI |
| `src/main/webapp` | Browser application and static resources |
| `database/schema.sql` | Schema for a new database |
| `database/migrations` | Explicit SQL migrations for existing installations |
| `docs/API.md` | HTTP API and browser contracts |
| `docs/ARCHITECTURE.md` | Architecture and request flows |
| `docs/MULTI_NODE_ACCEPTANCE.md` | Multi-node mTLS acceptance procedure |
| `docs/AI/PROJECT_STATUS.md` | Verified implementation status and roadmap |

## Requirements and local build

Install a Java 17 or newer JDK and Maven, then run from this directory:

```shell
mvn clean verify
```

This compiles the WAR and runs unit/Servlet/service tests. MySQL integration suites are opt-in; see [Database integration testing](#database-integration-testing). A clean build does not provision a database, account, certificate, or running Tomcat node.

The packaged WAR is `target/AgriTrace.war`. Deploy it to a compatible Servlet container only after its schema, network/genesis settings, HTTPS, mutual TLS, secrets, and node identity have been provisioned. No Docker Compose or hosting-platform configuration is included.

## Database and configuration

For an application node, provision an independent MySQL instance and create the `agritrace` schema using `database/schema.sql`. The schema creates the database as `agritrace`. Existing installations must be inspected and upgraded using the appropriate migration exactly once; do not blindly rerun migrations 001 or 002. The application does not automatically seed a network or create demo data.

Database connection settings are supplied outside the repository:

- `AGRITRACE_DB_URL`
- `AGRITRACE_DB_USERNAME`
- `AGRITRACE_DB_PASSWORD`

Node P2P identity settings are:

- `AGRITRACE_P2P_PEER_ID`
- `AGRITRACE_P2P_KEYSTORE_PATH`
- `AGRITRACE_P2P_KEYSTORE_PASSWORD`

The peer ID must be active in canonical governance state, and the leaf certificate in the PKCS#12 store must match its registered SHA-256 fingerprint. Configure the servlet container to request and validate client certificates for internal peer routes. Configure `AGRITRACE_PUBLIC_BASE_URL` (or JVM property `agritrace.public.base.url`) to the trusted public application URL used by QR links.

Never store passwords, private keys, PKCS#12 stores, or live credentials in source control or manifests. Use separate node identities and databases; do not share a writable database among consortium nodes.

## Consortium bootstrap

New nodes are initialized offline before the WAR starts. The bootstrap tool accepts a signed, versioned manifest containing public network/governance data; it does not expose an unauthenticated HTTP endpoint or accept a private signing key. Its read-only `status` command classifies an empty target, a complete matching ledger, an exact resumable prefix, or inconsistent/different/unexpected state. `initialize` resumes only a validated prefix and is idempotent after completion.

Commands (from the project root):

```shell
mvn exec:java "-Dexec.args=validate path/to/manifest.json"
mvn exec:java "-Dexec.args=status path/to/manifest.json"
mvn exec:java "-Dexec.args=status path/to/manifest.json local-admin-name"
mvn exec:java "-Dexec.args=signing-bytes path/to/unsigned-manifest.json path/to/signing-input.bin"
mvn exec:java "-Dexec.args=initialize path/to/signed-manifest.json local-admin-name"
```

The `status`/`initialize` commands use the configured node database and local P2P identity. New local ADMIN passwords are read through an interactive no-echo console and hashed before storage. `initialize` requires the database catalog `agritrace` to contain the checked-in schema and no existing application state, or to match an exact verified bootstrap prefix. It does not repair, clear, or overwrite divergent data. Read [the bootstrap operator guide](docs/AI/CONSORTIUM_BOOTSTRAP_USAGE.md) for manifest fields, signature handling, status output, and recovery details.

## Testing

- **Unit and web tests:** run as part of `mvn clean verify`.
- **MySQL persistence tests:** `AGRITRACE_DB_INTEGRATION=true` and `AGRITRACE_DB_BLOCK_INTEGRATION=true` enable existing suites. Use only a disposable isolated DB; the transaction suite uses temporary records and cleanup but does not itself prove DB isolation.
- **Bootstrap recovery integration test:** separately opt in with `AGRITRACE_BOOTSTRAP_IT_ENABLED=true` and the dedicated settings documented in the operator guide. It requires a separate MySQL instance, the `agritrace` catalog, and a dedicated marker row. It does not fall back to `AGRITRACE_DB_URL` and never drops/recreates a database.
- **Multi-node acceptance:** execute the live mTLS, convergence, fork, disconnect/retry, and restart scenarios in `docs/MULTI_NODE_ACCEPTANCE.md`. Unit tests do not replace this deployment verification.

## Current limitations

Bootstrap state verification proves the stored network configuration, canonical blocks/transactions, replayed governance state, projection rows, node-local ADMIN record, and configured local peer certificate against the verified manifest's effective ledger state. The `environment` label and manifest signature bytes are not stored in the existing database schema; changing metadata while keeping the same network and ledger does not create a distinct database state. A durable exact manifest digest would require a separately approved schema migration.

Live three-node mTLS, servlet-container trust setup, browser end-to-end workflows, and production operations (deployment, monitoring, backup/restore, upgrade rehearsals) still need environment-specific verification. See [the current project status](docs/AI/PROJECT_STATUS.md).
