# AgriTrace API v1

## 1. Conventions

- Base path: `/api/v1`.
- Request and response bodies use UTF-8 JSON.
- Application responses use `{ "success": boolean, "message": string, "data": object|null }`.
- A transaction accepted by a node is not yet confirmed by the chain. Clients poll its status.
- Event payloads are canonicalized before hashing and signing. The canonicalization format is part of the protocol and must be identical in JavaScript and Java.
- Organization signatures use ECDSA P-256 with SHA-256. The private key remains with the organization; only the public key is registered on-chain.
- State-changing browser requests use the authenticated session and a CSRF token. P2P endpoints are not browser APIs and accept only authorized node peers.
- Routes under `/admin/*` and `/node/*` require an authenticated `ADMIN` session; other roles receive `403 FORBIDDEN`.
- State-changing browser requests send the CSRF token in `X-CSRF-Token`. Session cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` when served over HTTPS.
- Passwords are verified using a salted password-hashing function; the raw password is never stored or logged.
- Times in signed payloads are ISO-8601 UTC timestamps. Nodes also record their local receive time. Block time is set by the miner and must increase relative to its parent; consensus does not compare it with a node's local clock.
- IDs and enum values are case-sensitive.

## Browser application

The WAR serves a browser workspace at `/` for session login, batch-event signing/submission, shipment proposal and carrier inbox workflows, transaction status, and public trace lookup. Organization event signatures use the registered ECDSA P-256 key imported by the user as a PKCS#8 file. The key is imported into Web Crypto as non-extractable and retained only in the current page's memory; the browser sends signatures, never private-key bytes. Serve the application over HTTPS (or localhost during development). The frontend obtains the node's network ID from the public `GET /network` route before constructing signature payloads.

`GET /public/trace-qr/{batchCode}` returns a locally generated SVG QR image linking to the browser workspace's public trace view. Scanning the code opens the same batch's anonymous public record; the trace data remains governed by the public trace field allowlist. Configure `AGRITRACE_PUBLIC_BASE_URL` (or JVM property `agritrace.public.base.url`) to the trusted public application URL, including its context path, such as `https://trace.example/AgriTrace`. The QR endpoint deliberately does not construct links from the incoming `Host` header. Production URLs must use HTTPS; HTTP is accepted only for localhost development.

## 2. Authentication

### `POST /auth/login`

Request:

```json
{
  "username": "farmer.one",
  "password": "..."
}
```

Success: `200 OK`; creates an HTTP session and returns the account context. Passwords and session identifiers are never written to the blockchain.

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "userId": 12,
    "username": "farmer.one",
    "role": "FARMER",
    "organizationId": "org-farm-001",
    "csrfToken": "..."
  }
}
```

Failures: `400` invalid request, `401` invalid credentials, `403` inactive account.
The server reads only a bounded JSON body, rotates any existing session, creates a fresh session and CSRF token, and returns the token once for subsequent state-changing requests. The session cookie is HttpOnly, Secure, and SameSite=Lax. Local account activation and canonical organization availability must both be true for authentication to succeed.

### `GET /auth/me`

Requires an authenticated session. Returns the current user, role, organization ID, and CSRF token; never returns a password hash, private key, or session ID.

### `POST /auth/logout`

Requires an authenticated session and CSRF token. Invalidates the session and returns `200 OK`.

### `POST /auth/password`

Requires an authenticated session, CSRF token, and JSON fields `currentPassword` and `newPassword`. New passwords must contain 12 to 1024 characters. The current password is verified before an optimistic conditional update that also checks the account remains active and canonically available. Success returns a replacement CSRF token for the current session; password changes do not rotate an organization's signing key. Other already-issued sessions are not revoked by this endpoint.

## 3. Organizations and governance

Organization/key/peer governance endpoints require an active `ADMIN` session and an admin governance signature. The signature is verified against the genesis administrator public key. Accepted governance transactions are propagated and become effective only when included in the canonical chain. Local user-administration endpoints require an active `ADMIN` session and CSRF token, but do not create chain transactions.

Governance transactions use these signed canonical fields: `networkId`, `eventId`, `eventType`, `eventTime`, and flat string-valued `data`. The administrator signs those fields plus `purpose: "GENESIS_ADMIN_GOVERNANCE"` with ECDSA P-256/SHA-256; its Base64 signature is the sole governance signature. The payload hash is SHA-256 of the canonical unsigned fields, and the transaction ID is SHA-256 of the canonical fields plus `payloadHash` and a one-item `signatures` array containing `purpose` and `signature`. A governance transaction in a block changes registry state only for later transactions in deterministic transaction-ID order and later blocks on that branch.

The governance transaction types are `REGISTER_ORGANIZATION` (organization metadata and its first public key), `REGISTER_ORGANIZATION_KEY`, `REVOKE_ORGANIZATION_KEY`, `SET_ORGANIZATION_STATUS`, `REGISTER_PEER`, and `REVOKE_PEER`. Organization/key/peer identifiers are append-only; revocations change status/height and never erase historical registry entries. A revoked organization cannot be reactivated. Peer endpoints must use HTTPS and peer certificate fingerprints are lowercase SHA-256 hex.

### `POST /admin/users`

Creates a local application account. This operation is stored only in the current node's database and does not create a blockchain transaction.

```json
{
  "username": "farmer.one",
  "temporaryPassword": "...",
  "role": "FARMER",
  "organizationId": "org-farm-001"
}
```

The role must match the registered organization type. Admin accounts have no organization ID. Passwords are stored using a password-hashing function and are never returned by the API.
The temporary password must contain 12 to 1024 characters. For non-admin users, creation requires the organization to be active in the canonical projection and its type to match the requested role. The account is created locally and is never written to the ledger.

### `PATCH /admin/users/{userId}/status`

Request body: `{ "isActive": true }`. Activates or deactivates a local account. This change affects only this node; it does not revoke the organization or its public key on the chain. The user role and canonical organization state are still checked at login.

### `POST /admin/organizations`

Registers an organization and its first public key.

```json
{
  "organization": {
    "organizationId": "org-farm-001",
    "type": "FARMER",
    "name": "Mekong Mango Farm",
    "province": "Tien Giang"
  },
  "key": {
    "keyId": "org-farm-001-key-1",
    "algorithm": "ECDSA_P256_SHA256",
    "publicKey": "base64-encoded-SPKI"
  },
  "eventId": "event-uuid",
  "eventTime": "2026-10-04T10:00:00.000Z",
  "adminSignature": "base64-signature"
}
```

`eventTime` is part of the signed governance payload and must be UTC with exactly three fractional digits. The administrator signs the canonical flat governance `data` fields (not the nested HTTP JSON) using `purpose: "GENESIS_ADMIN_GOVERNANCE"`. The API rejects unsupported fields and verifies this signature against the genesis administrator public key configured for the node before submitting it to the transaction pool.

Returns `202 Accepted`, a governance transaction ID, and `PENDING`. Organization and key become active only after a valid block containing this transaction becomes canonical. Invalid signatures return `400`; governance that conflicts with the current canonical registry returns `422`; an event/transaction collision returns `409`.

### `POST /admin/organizations/{organizationId}/keys`

Registers a new public key for an existing organization. The old key is not deleted; it remains available to verify historical signatures. Request fields are `keyId`, `algorithm`, `publicKey`, `eventId`, `eventTime`, and `adminSignature`. The governance `data` signed by the genesis administrator contains `organizationId` (from the path), `keyId`, `algorithm`, and `publicKey`.

### `DELETE /admin/organizations/{organizationId}/keys/{keyId}`

Revokes the named key for future transactions without removing it from historical verification. The JSON body contains `eventId`, `eventTime`, and `adminSignature`; the signed governance `data` contains only `keyId`.

### `POST /admin/organizations/{organizationId}/status`

Request fields are `status`, `eventId`, `eventTime`, and `adminSignature`. `status` is `SUSPENDED` or `REVOKED`; revocation is terminal. The signed governance `data` contains `organizationId` (from the path) and `status`. Historical signatures remain verifiable; the status controls whether new transactions may be accepted.

### `POST /admin/peers`

Registers a peer ID, organization ID, LAN endpoint, and SHA-256 fingerprint of its TLS certificate. The peer becomes eligible for P2P only after the governance transaction is confirmed and its mutual-TLS certificate matches the registered fingerprint.

```json
{
  "eventId": "event-uuid",
  "eventTime": "2026-10-04T10:00:00.000Z",
  "peerId": "node-warehouse-001",
  "organizationId": "org-warehouse-001",
  "endpoint": "https://10.0.0.20:8443",
  "tlsCertificateFingerprint": "64-lowercase-hex-characters",
  "adminSignature": "base64-signature"
}
```

### `DELETE /admin/peers/{peerId}`

Revokes a peer through a signed governance transaction. The JSON body contains `eventId`, `eventTime`, and `adminSignature`; the signed governance `data` contains `peerId` (from the path). This does not erase blocks received from that peer.

All state-changing governance requests require an authenticated `ADMIN` session and `X-CSRF-Token`. Successful submissions return `202 Accepted` with `status: "PENDING"`; the transaction ID is stable for an identical signed request.

## 4. Batches and supply-chain events

Each event body contains:

- `eventId`: client-generated unique ID used for idempotency. Governance transactions use the same field; shipment proposals have their own unique `eventId`.
- `batchCode`: stable, unique batch code.
- `eventType`: one of the event types below.
- `eventTime`: UTC time asserted by the signer, encoded with exactly three fractional digits (for example `2026-10-01T02:00:00.000Z`) to match browser millisecond precision.
- `data`: event-specific fields.
- `signatures`: one or more signatures over the canonical event envelope.
- `quantity` values use decimal strings with exactly three fractional digits (for example `"1200.000"`); do not encode decimal quantities as JSON floating-point numbers.

Every signature identifies `organizationId`, `keyId`, `purpose`, and Base64 signature bytes. The signed envelope includes the network ID, event ID, batch code, event type, event time, and event data. Signatures are not allowed to alter the payload they attest to.
For the MVP, event payloads replicated to consortium nodes contain only the agreed traceability fields; do not put passwords, private keys, or confidential business metadata in a transaction. The public QR endpoint still applies an explicit field allowlist.

The deterministic transaction encoding is:

1. The unsigned payload is the JCS object `{networkId, eventId, batchCode, eventType, eventTime, data}`. `payloadHash` is the lowercase SHA-256 hex digest of its UTF-8 canonical JSON.
2. Each organization signs the JCS object containing those same fields plus `purpose`, `signerOrganizationId`, and `keyId`. This binds the signature to its network, role in the operation, signer, and registered key without changing the shared payload hash.
3. `txId` is the lowercase SHA-256 hex digest of the JCS transaction object containing the unsigned payload fields, `payloadHash`, and the complete signature list. Sort signatures by `organizationId`, then `purpose`, then `keyId`, then Base64 `signature` before computing the ID.
4. A key is valid for a transaction at height `h` when `validFromHeight <= h` and either it has no revocation height or `h < revokedAtHeight`. Nodes verify historical transactions against the registry state applicable at their inclusion height.

### `POST /batches`

Creates a batch by submitting its signed `HARVESTED` event.

```json
{
  "eventId": "event-uuid",
  "batchCode": "MANGO-2026-0001",
  "eventType": "HARVESTED",
  "eventTime": "2026-10-01T02:00:00.000Z",
  "data": {
    "productType": "Mango",
    "variety": "Cat Hoa Loc",
    "harvestDate": "2026-10-01",
    "quantity": "1200.000",
    "quantityUnit": "kg",
    "farmName": "Mekong Mango Farm",
    "province": "Tien Giang"
  },
  "signatures": [
    {
      "organizationId": "org-farm-001",
      "keyId": "org-farm-001-key-1",
      "purpose": "FARMER_HARVEST",
      "signature": "base64-signature"
    }
  ]
}
```

The signer must be an active `FARMER`. The batch code must not already exist in the canonical chain. Batch fields are sourced from this event and are not edited in place.

Batch event POST requests require an authenticated organization-bound session and `X-CSRF-Token`. The authenticated user's organization must be among the event signers; every signature, key, role, and state transition is independently verified against the current canonical state before the transaction is admitted. Request JSON must contain exactly the documented envelope/signature fields, and `eventTime` must be UTC with exactly three fractional digits. A successful submission returns `202 Accepted` with `status: "PENDING"`; invalid business/state/signature requests return `422`, and transaction/event-ID collisions return `409`.

### `POST /batches/{batchCode}/events`

Submits `PACKAGED`, `RECEIVED`, `SOLD`, or `CORRECTION`. The path code must equal the signed `batchCode`.

`PACKAGED` is signed by the batch's farmer after `HARVESTED` and before any shipment.

`RECEIVED` is signed by the intended recipient of the latest confirmed shipment. It must reference that shipment transaction.

`SOLD` is signed by the active retailer currently holding the batch after a confirmed `RECEIVED`.

`CORRECTION` is signed by an organization that signed the referenced event. It includes `correctionOfTxId`, a reason, and corrected public data. It appends a correction; it does not replace the original event or signature.

### `POST /batches/{batchCode}/shipments`

Creates an immutable shipment proposal. The sender is the current holder and signs the exact payload. The selected carrier must be active and different from the sender. The proposal is stored on the current node; it is not yet a `SHIPPED` event and does not change the batch state.

Requires an authenticated sender organization session and `X-CSRF-Token`.

```json
{
  "proposalId": "proposal-uuid",
  "eventId": "event-uuid",
  "batchCode": "MANGO-2026-0001",
  "eventTime": "2026-10-02T02:00:00.000Z",
  "senderOrganizationId": "org-farm-001",
  "carrierOrganizationId": "org-carrier-001",
  "recipientOrganizationId": "org-warehouse-001",
  "fromProvince": "Tien Giang",
  "toProvince": "Ho Chi Minh City",
  "expiresAt": "2026-10-02T04:00:00Z",
  "signature": {
    "keyId": "org-farm-001-key-1",
    "purpose": "SHIPMENT_SENDER",
    "value": "base64-signature"
  }
}
```

`eventTime` must use UTC with exactly three fractional digits. `expiresAt` must be a UTC ISO-8601 timestamp and is copied into the signed `SHIPPED` transaction data, so the sender and carrier signatures bind the expiration as well as the parties and route. The `signature` signs the canonical AgriTrace `SHIPPED` payload using purpose `SHIPMENT_SENDER`; `proposalId` is a local workflow identifier and is not part of the on-chain transaction payload.

Returns `202 Accepted` with the immutable proposal, canonical payload hash, and status `AWAITING_CARRIER`. Proposals are persisted locally and relayed over mTLS to an active peer registered for the selected carrier organization. Carrier inbox and endorsement are available on the receiving carrier node.

### `POST /shipments/{proposalId}/endorsements`

Requires an authenticated carrier organization session and `X-CSRF-Token`. The carrier submits `{ "keyId": "...", "purpose": "SHIPMENT_CARRIER", "value": "base64-signature" }` over the identical canonical `SHIPPED` payload. The node verifies both signatures, the current holder, intended carrier/recipient, proposal expiry, and batch state. It then creates a single `SHIPPED` transaction containing the immutable payload and both signatures and returns `202 Accepted` with a transaction ID and status `PENDING`. Normal transaction admission and block validation still apply; a proposal is not proof that the shipment has been confirmed.

One proposal may have at most one sender and one carrier signature. An endorsement for a different payload, organization, or expired proposal is rejected.

### `GET /shipments/inbox`

Requires an authenticated `CARRIER` user. Returns unexpired proposals addressed to that user's organization, including the sender, recipient, batch code, payload hash, and signatures available so far. It never exposes another carrier's proposals.

### `GET /shipments/{proposalId}`

Requires an authenticated user belonging to the sender, selected carrier, or recipient organization. Returns proposal status and the transaction ID if submitted. Use `GET /transactions/{txId}` to retrieve its current transaction status and block metadata.

## 5. Read and verification endpoints

### `GET /batches/{batchCode}`

Requires an authenticated partner account. Returns batch projection, current holder/status, event history, and chain confirmation metadata. The farmer, current holder, organizations that signed a canonical event, and the designated carrier/recipient while a shipment is in transit may read the batch. The projection is rebuilt from the canonical chain and is not an authority for accepting writes.

### `GET /public/batches/{batchCode}/trace`

Public; no login required. Returns only the agreed public batch fields, public event timeline, organization display names/roles, confirmation metadata, and verification status. It never returns account data, private keys, internal metadata, or non-public event fields.

Example:

```json
{
  "success": true,
  "message": "Traceability record",
  "data": {
    "batch": {
      "batchCode": "MANGO-2026-0001",
      "productType": "Mango",
      "variety": "Cat Hoa Loc",
      "harvestDate": "2026-10-01",
      "quantity": "1200.000",
      "quantityUnit": "kg",
      "farmName": "Mekong Mango Farm",
      "province": "Tien Giang",
      "status": "IN_TRANSIT",
      "currentHolder": "Mekong Warehouse"
    },
    "events": [],
    "verification": {
      "valid": true,
      "chainHeight": 42,
      "checkedAt": "2026-10-02T03:00:00Z"
    }
  }
}
```

### `GET /transactions/{txId}`

Requires an authenticated session. Returns `PENDING`, `CONFIRMED`, or `REJECTED`, transaction type, and block confirmation metadata when available. `CONFIRMED` includes canonical block height, hash, and timestamp; `REJECTED` includes a rejection code. A request rejected during initial validation returns an HTTP error immediately and does not get a transaction ID. Transactions orphaned by a canonical reorganization return to `PENDING` and re-enter the local pool for revalidation; `REJECTED` is reserved for a later explicit rejection with a reason.

The response data contains `transactionId`, `eventId`, `transactionType`, `payloadHash`, `status`, and `updatedAt`. Confirmed responses include a `block` object with `height`, `hash`, and `timestamp`; rejected responses include `rejectionCode`. Unknown or malformed transaction IDs return `404 TRANSACTION_NOT_FOUND`.

Resubmitting an identical complete transaction is idempotent and returns its existing transaction ID/status. Reusing its `eventId` with a different transaction envelope is a conflict (`409`).

### `GET /node/chain/validation`

Admin/diagnostic access only. Recomputes block hashes, PoW, previous-hash links, transaction signatures, governance state, and business transitions for the canonical chain. A failed check returns the first invalid height and reason; it must not silently report success.

## 6. P2P endpoints

P2P endpoints are under `/internal/p2p` and require an authenticated, currently authorized peer connection. They are never exposed to public browser clients.

- `POST /internal/p2p/transactions`: submit one complete signed ledger transaction; local signature, governance and business validation still apply.
- `GET /internal/p2p/transactions/pending?after={transactionId}`: read a page of locally pending, complete signed transactions. `nextAfter` is empty when there are no more pages.
- `POST /internal/p2p/blocks`: submit a complete candidate block and its ordered transaction bodies for full local validation.
- `GET /internal/p2p/chain/locator`: get sparse canonical-chain checkpoints, including genesis and the current tip.
- `GET /internal/p2p/blocks/{blockHash}`: fetch a complete block on the local canonical chain.
- `GET /internal/p2p/blocks/next?afterHash={blockHash}`: fetch the next canonical block after a shared ancestor; returns `404` at the current tip.
- `POST /internal/p2p/shipment-proposals`: relay an immutable, sender-signed proposal to the selected carrier node.
- `POST /internal/p2p/shipment-proposals/{proposalId}/endorsements`: relay the carrier-endorsed, two-signature `SHIPPED` event back to the sender node.

Peers validate every payload locally. A sender's claim that a block or transaction is valid is never sufficient for acceptance.
Shipment proposal relay is synchronous: a sender proposal is stored locally and then delivered to an active peer registered for the selected carrier organization. The carrier stores it in its local inbox. After endorsement, the carrier submits the transaction to its own pool and relays the complete signed event to an active sender peer; the sender independently validates and submits it to its local pool. Retrying the endorsement relays the already-submitted event again, and receipt of the same transaction is idempotent. The workflow proposal and inbox are node-local records; only the final signed event is ledger data.

Each running node also polls active registered peers every 30 seconds (after a 10-second startup delay). It compares sparse locators, pulls and locally validates contiguous canonical blocks from a shared ancestor, then pages through the peer's pending transaction pool and independently submits each transaction. An invalid pending transaction is logged and skipped; it is not accepted on the strength of a peer's assertion. This is pull-based MVP synchronization, not a consensus protocol: nodes independently validate blocks and select the preferred chain by the configured cumulative-work rule. Peer endpoints are protected by the mTLS authentication filter described below.

### Shipment P2P deployment

- Configure `AGRITRACE_P2P_PEER_ID`, `AGRITRACE_P2P_KEYSTORE_PATH` (an absolute path), and `AGRITRACE_P2P_KEYSTORE_PASSWORD`, or their JVM properties `agritrace.p2p.peer.id`, `agritrace.p2p.keystore.path`, and `agritrace.p2p.keystore.password`. Do not commit the PKCS#12 file or password.
- The PKCS#12 file must contain exactly one private-key entry and its X.509 certificate. Its SHA-256 fingerprint must match the active canonical peer registration, and the local peer's organization must be active.
- Tomcat manages the server certificate. Configure the inbound P2P HTTPS listener/proxy to request and require client certificates so the servlet container supplies the X.509 certificate chain to the application. Keep browser-facing HTTPS on a listener that does not require node client certificates.
- Outbound peer connections present the configured PKCS#12 client certificate and use the JVM's default trust configuration to validate remote server certificates. Install the appropriate server CA in the JVM truststore when it is not already trusted.
- Register each peer with its externally reachable HTTPS endpoint and certificate fingerprint through governance before starting the node. Runtime startup fails if local peer configuration, canonical registration, or certificate fingerprint does not match.

## 7. Common errors

```json
{
  "success": false,
  "message": "The event is not valid for the batch's current state",
  "data": {
    "code": "INVALID_STATE_TRANSITION"
  }
}
```

Use HTTP `400` for malformed input, `401` for missing/invalid login, `403` for role or organization authorization failures, `404` for unknown resources, `409` for duplicate IDs or conflicting state, and `422` for valid JSON that fails signature/business validation. Use `503` when a node cannot perform a required peer/chain operation. Error responses must not include private keys, password hashes, session IDs, or stack traces.
# Offline operator command

Consortium bootstrap is performed locally with `mvn exec:java`; it is not exposed through the HTTP API.
The command validates an offline signed manifest and initializes a fresh `agritrace` database before Tomcat
starts. See [CONSORTIUM_BOOTSTRAP_USAGE.md](AI/CONSORTIUM_BOOTSTRAP_USAGE.md). No temporary or unauthenticated
bootstrap endpoint is part of the application API.
