#!/usr/bin/env bash
# ==============================================================
# docker-entrypoint.sh — AgriTrace Demo Node Bootstrap + Start
#
# Responsibilities:
#   1. Wait for MySQL to be ready
#   2. Generate node PKI (CA, server cert, P2P cert) if not present
#   3. Generate and sign the consortium manifest if not present
#   4. Configure Tomcat (server.xml, catalina.sh env)
#   5. Run bootstrap CLI (initialize) for this node
#   6. Start Tomcat in foreground
#
# Environment variables (required):
#   NODE_ID               - A, B, or C
#   NODE_ROLE             - FARMER, CARRIER, RETAILER
#   PEER_ID               - peer-node-a-farmer etc.
#   ORG_ID                - org-farmer-a etc.
#   ADMIN_USERNAME        - local admin account name (admin-a etc.)
#   ADMIN_PASSWORD        - initial local admin password (demo only!)
#   APP_PORT              - HTTPS application port (8443/8444/8445)
#   P2P_PORT              - mTLS P2P port (9443/9444/9445)
#   AGRITRACE_DB_URL      - jdbc:mysql://db-a:3306/agritrace?serverTimezone=UTC
#   AGRITRACE_DB_USERNAME - agritrace_app
#   AGRITRACE_DB_PASSWORD - from Docker secret / env
#   SHARED_PKI_DIR        - path to shared volume containing CA and manifests
#   NODE_DATA_DIR         - path to node-private volume (keystores etc.)
#   NODE_A_P2P_HOST       - Docker hostname for node A P2P
#   NODE_B_P2P_HOST       - Docker hostname for node B P2P
#   NODE_C_P2P_HOST       - Docker hostname for node C P2P
#   NETWORK_ID            - agritrace-docker-demo-v1 (must match all nodes)
# ==============================================================
set -euo pipefail
IFS=$'\n\t'

NODE_ID="${NODE_ID:?NODE_ID is required}"
NODE_ROLE="${NODE_ROLE:?NODE_ROLE is required}"
PEER_ID="${PEER_ID:?PEER_ID is required}"
ORG_ID="${ORG_ID:?ORG_ID is required}"
ADMIN_USERNAME="${ADMIN_USERNAME:?ADMIN_USERNAME is required}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"
APP_PORT="${APP_PORT:-8443}"
P2P_PORT="${P2P_PORT:-9443}"
AGRITRACE_DB_URL="${AGRITRACE_DB_URL:?AGRITRACE_DB_URL is required}"
AGRITRACE_DB_USERNAME="${AGRITRACE_DB_USERNAME:?AGRITRACE_DB_USERNAME is required}"
AGRITRACE_DB_PASSWORD="${AGRITRACE_DB_PASSWORD:?AGRITRACE_DB_PASSWORD is required}"
SHARED_PKI_DIR="${SHARED_PKI_DIR:-/data/shared}"
NODE_DATA_DIR="${NODE_DATA_DIR:-/data/node}"
NETWORK_ID="${NETWORK_ID:-agritrace-docker-demo-v1}"

NODE_ID_LOWER="${NODE_ID,,}"  # lowercase: a, b, c

SHARED_CA_DIR="${SHARED_PKI_DIR}/ca"
SHARED_MANIFEST_DIR="${SHARED_PKI_DIR}/manifest"
SHARED_CERTS_DIR="${SHARED_PKI_DIR}/certs"
NODE_IDENTITY_DIR="${NODE_DATA_DIR}/identity"
NODE_SECRETS_DIR="${NODE_DATA_DIR}/secrets"
CATALINA_BASE_DIR="${NODE_DATA_DIR}/catalina"
MANIFEST_FILE="${SHARED_MANIFEST_DIR}/consortium-manifest.json"
LOCK_DIR="${SHARED_PKI_DIR}/.locks"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] [Node ${NODE_ID}] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# ----------------------------------------------------------------
# Utility: acquire a simple file lock (for shared PKI generation)
# ----------------------------------------------------------------
acquire_lock() {
    local name="$1"
    local lockfile="${LOCK_DIR}/${name}.lock"
    mkdir -p "${LOCK_DIR}"
    # Try up to 120 seconds
    local i=0
    while ! mkdir "${lockfile}" 2>/dev/null; do
        if [ $((++i)) -ge 120 ]; then
            die "Could not acquire lock ${name} after 120s"
        fi
        sleep 1
    done
}

release_lock() {
    local name="$1"
    rmdir "${LOCK_DIR}/${name}.lock" 2>/dev/null || true
}

# ----------------------------------------------------------------
# Wait for MySQL
# ----------------------------------------------------------------
wait_for_mysql() {
    log "Waiting for MySQL at ${AGRITRACE_DB_URL} ..."
    # Extract host:port from jdbc URL (jdbc:mysql://host:port/db...)
    local jdbc_host
    jdbc_host=$(echo "${AGRITRACE_DB_URL}" | sed 's|jdbc:mysql://||' | cut -d'/' -f1 | cut -d':' -f1)
    local jdbc_port
    jdbc_port=$(echo "${AGRITRACE_DB_URL}" | sed 's|jdbc:mysql://||' | cut -d'/' -f1 | grep -oP ':\d+' | tr -d ':')
    jdbc_port="${jdbc_port:-3306}"

    local i=0
    while ! bash -c ">/dev/tcp/${jdbc_host}/${jdbc_port}" 2>/dev/null; do
        if [ $((++i)) -ge 120 ]; then
            die "MySQL at ${jdbc_host}:${jdbc_port} did not become available within 120s"
        fi
        sleep 1
    done
    # Give MySQL a bit more time after TCP is open
    sleep 2
    log "MySQL is reachable."
}

# ----------------------------------------------------------------
# Fingerprint helper (SHA-256 of DER cert)
# ----------------------------------------------------------------
cert_fingerprint() {
    local cert_file="$1"
    openssl x509 -in "${cert_file}" -outform DER 2>/dev/null \
        | openssl dgst -sha256 -hex \
        | awk '{print $2}'
}

# ----------------------------------------------------------------
# PHASE 1: Generate shared CA + all node certs (node A does this)
# ----------------------------------------------------------------
generate_shared_pki() {
    log "Generating shared demo CA and all node certificates ..."

    mkdir -p "${SHARED_CA_DIR}" "${SHARED_CERTS_DIR}"

    local CA_KEY="${SHARED_CA_DIR}/demo-ca.key"
    local CA_CERT="${SHARED_CA_DIR}/demo-ca.crt"
    local CA_P12="${SHARED_CA_DIR}/demo-ca.p12"
    local CA_PASS="agritrace-demo-ca-$(openssl rand -hex 8)"
    echo "${CA_PASS}" > "${SHARED_CA_DIR}/ca-password.txt"
    chmod 600 "${SHARED_CA_DIR}/ca-password.txt"

    # Generate CA private key + self-signed cert
    openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
        -keyout "${CA_KEY}" \
        -out "${CA_CERT}" \
        -subj "/CN=AgriTrace Docker Demo CA/O=AgriTrace Demo" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" \
        2>/dev/null

    chmod 600 "${CA_KEY}"

    # Export CA to PKCS12 (for Java truststores)
    openssl pkcs12 -export \
        -in "${CA_CERT}" \
        -nokeys \
        -out "${CA_P12}" \
        -passout "pass:${CA_PASS}" \
        2>/dev/null

    # Generate cert inventory file (will be filled in)
    local INVENTORY="${SHARED_CERTS_DIR}/cert-inventory.json"
    echo '{"nodes":[]}' > "${INVENTORY}"

    # For each node: server cert (TLS/HTTPS) + peer cert (mTLS P2P/clientAuth)
    local NODES=("a" "b" "c")
    local PEER_IDS=("peer-node-a-farmer" "peer-node-b-carrier" "peer-node-c-retailer")
    local P2P_HOSTS=("${NODE_A_P2P_HOST:-node-a}" "${NODE_B_P2P_HOST:-node-b}" "${NODE_C_P2P_HOST:-node-c}")

    for i in "${!NODES[@]}"; do
        local n="${NODES[$i]}"
        local peer_id="${PEER_IDS[$i]}"
        local p2p_host="${P2P_HOSTS[$i]}"
        local node_cert_dir="${SHARED_CERTS_DIR}/node-${n}"
        mkdir -p "${node_cert_dir}"

        # --- Server cert (serverAuth, SAN for all access points) ---
        openssl req -newkey rsa:2048 -nodes \
            -keyout "${node_cert_dir}/server.key" \
            -out "${node_cert_dir}/server.csr" \
            -subj "/CN=node-${n}-server/O=AgriTrace Demo" \
            2>/dev/null

        openssl x509 -req -days 730 \
            -in "${node_cert_dir}/server.csr" \
            -CA "${CA_CERT}" -CAkey "${CA_KEY}" -CAcreateserial \
            -out "${node_cert_dir}/server.crt" \
            -extfile <(printf "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,DNS:node-${n},IP:127.0.0.1") \
            2>/dev/null
        chmod 600 "${node_cert_dir}/server.key"

        # --- Peer cert (clientAuth, CN = peerId) ---
        openssl req -newkey rsa:2048 -nodes \
            -keyout "${node_cert_dir}/peer.key" \
            -out "${node_cert_dir}/peer.csr" \
            -subj "/CN=${peer_id}/O=AgriTrace Demo" \
            2>/dev/null

        openssl x509 -req -days 730 \
            -in "${node_cert_dir}/peer.csr" \
            -CA "${CA_CERT}" -CAkey "${CA_KEY}" -CAcreateserial \
            -out "${node_cert_dir}/peer.crt" \
            -extfile <(printf "extendedKeyUsage=clientAuth") \
            2>/dev/null
        chmod 600 "${node_cert_dir}/peer.key"

        # Compute and store fingerprint
        local fp
        fp=$(cert_fingerprint "${node_cert_dir}/peer.crt")
        echo "${fp}" > "${node_cert_dir}/peer.fingerprint"

        # --- Org signing key (EC P-256 for browser / transactions) ---
        local org_key_file="${node_cert_dir}/org-signing-key.pem"
        local org_pk8_file="${node_cert_dir}/org-signing-key.pkcs8.pem"
        local org_pub_file="${node_cert_dir}/org-signing-pub.pem"
        local org_spki_file="${node_cert_dir}/org-signing-spki.b64"

        if [ ! -f "${org_key_file}" ]; then
            openssl ecparam -name prime256v1 -genkey -noout -out "${org_key_file}" 2>/dev/null
            chmod 600 "${org_key_file}"
            openssl pkcs8 -topk8 -nocrypt -in "${org_key_file}" -out "${org_pk8_file}" 2>/dev/null
            chmod 600 "${org_pk8_file}"
            openssl ec -in "${org_key_file}" -pubout -out "${org_pub_file}" 2>/dev/null
            openssl ec -in "${org_key_file}" -pubout -outform DER 2>/dev/null \
                | base64 -w 0 > "${org_spki_file}"
        fi

        log "Node ${n^^}: server cert + peer cert + org key generated (peer fingerprint: ${fp:0:16}...)"
    done

    # Create CA truststore (shared by all nodes for mTLS + Java trust)
    local TRUST_PASS="agritrace-demo-trust-$(openssl rand -hex 8)"
    echo "${TRUST_PASS}" > "${SHARED_CA_DIR}/trust-password.txt"
    chmod 600 "${SHARED_CA_DIR}/trust-password.txt"

    keytool -importcert -noprompt \
        -alias "agritrace-demo-ca" \
        -file "${CA_CERT}" \
        -keystore "${SHARED_CA_DIR}/truststore.p12" \
        -storetype PKCS12 \
        -storepass "${TRUST_PASS}" \
        2>/dev/null

    log "Shared CA and certificates generated successfully."
}

# ----------------------------------------------------------------
# PHASE 2: Generate node PKCS12 keystores from shared certs
# ----------------------------------------------------------------
generate_node_pkcs12() {
    log "Generating node PKCS12 keystores ..."

    mkdir -p "${NODE_IDENTITY_DIR}" "${NODE_SECRETS_DIR}"

    local SHARED_NODE_DIR="${SHARED_CERTS_DIR}/node-${NODE_ID_LOWER}"
    local CA_CERT="${SHARED_CA_DIR}/demo-ca.crt"

    # Random PKCS12 passwords
    local SERVER_PASS
    SERVER_PASS="$(openssl rand -hex 16)"
    local PEER_PASS
    PEER_PASS="$(openssl rand -hex 16)"

    echo "${SERVER_PASS}" > "${NODE_SECRETS_DIR}/server-keystore-password.txt"
    echo "${PEER_PASS}"   > "${NODE_SECRETS_DIR}/peer-keystore-password.txt"
    chmod 600 "${NODE_SECRETS_DIR}"/*.txt

    # Server PKCS12 (for Tomcat HTTPS connector)
    openssl pkcs12 -export \
        -inkey "${SHARED_NODE_DIR}/server.key" \
        -in    "${SHARED_NODE_DIR}/server.crt" \
        -certfile "${CA_CERT}" \
        -out   "${NODE_IDENTITY_DIR}/server.p12" \
        -name  "node-${NODE_ID_LOWER}-server" \
        -passout "pass:${SERVER_PASS}" \
        2>/dev/null

    # Peer PKCS12 (for P2P connector + bootstrap verification)
    openssl pkcs12 -export \
        -inkey "${SHARED_NODE_DIR}/peer.key" \
        -in    "${SHARED_NODE_DIR}/peer.crt" \
        -certfile "${CA_CERT}" \
        -out   "${NODE_IDENTITY_DIR}/peer.p12" \
        -name  "node-${NODE_ID_LOWER}-peer" \
        -passout "pass:${PEER_PASS}" \
        2>/dev/null

    # Truststore (shared CA for mTLS: Tomcat clientAuth truststore)
    local TRUST_PASS
    TRUST_PASS="$(cat "${SHARED_CA_DIR}/trust-password.txt")"
    cp "${SHARED_CA_DIR}/truststore.p12" "${NODE_IDENTITY_DIR}/truststore.p12"
    echo "${TRUST_PASS}" > "${NODE_SECRETS_DIR}/truststore-password.txt"
    chmod 600 "${NODE_IDENTITY_DIR}"/*.p12
    chmod 600 "${NODE_SECRETS_DIR}/truststore-password.txt"

    log "Node PKCS12 keystores created."

    # Export env vars for later steps
    export AGRITRACE_P2P_PEER_ID="${PEER_ID}"
    export AGRITRACE_P2P_KEYSTORE_PATH="${NODE_IDENTITY_DIR}/peer.p12"
    export AGRITRACE_P2P_KEYSTORE_PASSWORD="${PEER_PASS}"
    export SERVER_KEYSTORE_PATH="${NODE_IDENTITY_DIR}/server.p12"
    export SERVER_KEYSTORE_PASSWORD="${SERVER_PASS}"
    export TRUSTSTORE_PATH="${NODE_IDENTITY_DIR}/truststore.p12"
    export TRUSTSTORE_PASSWORD="${TRUST_PASS}"
}

# ----------------------------------------------------------------
# PHASE 3: Generate organization EC keypairs (for signing)
# ----------------------------------------------------------------
generate_org_key() {
    local node_dir="${SHARED_CERTS_DIR}/node-${NODE_ID_LOWER}"
    local org_key_file="${node_dir}/org-signing-key.pem"
    local org_pub_file="${node_dir}/org-signing-pub.pem"
    local org_spki_file="${node_dir}/org-signing-spki.b64"

    if [ ! -f "${org_key_file}" ]; then
        openssl ecparam -name prime256v1 -genkey -noout -out "${org_key_file}" 2>/dev/null
        chmod 600 "${org_key_file}"
        openssl ec -in "${org_key_file}" -pubout -out "${org_pub_file}" 2>/dev/null
        # Export SPKI in DER format → base64 (this is the Java/Web Crypto SPKI format)
        openssl ec -in "${org_key_file}" -pubout -outform DER 2>/dev/null \
            | base64 -w 0 > "${org_spki_file}"
        log "Organization signing key generated."
    fi
}

# ----------------------------------------------------------------
# PHASE 4: Generate the admin (genesis) EC keypair (Node A only)
# ----------------------------------------------------------------
generate_admin_key() {
    local admin_key_dir="${SHARED_CERTS_DIR}/admin"
    mkdir -p "${admin_key_dir}"
    chmod 700 "${admin_key_dir}"

    local admin_key="${admin_key_dir}/admin-signing-key.pem"
    local admin_pk8="${admin_key_dir}/admin-signing-key.pkcs8.pem"
    local admin_pub="${admin_key_dir}/admin-signing-pub.pem"
    local admin_spki="${admin_key_dir}/admin-signing-spki.b64"

    if [ ! -f "${admin_key}" ]; then
        openssl ecparam -name prime256v1 -genkey -noout -out "${admin_key}" 2>/dev/null
        chmod 600 "${admin_key}"
        openssl pkcs8 -topk8 -nocrypt -in "${admin_key}" -out "${admin_pk8}" 2>/dev/null
        chmod 600 "${admin_pk8}"
        openssl ec -in "${admin_key}" -pubout -out "${admin_pub}" 2>/dev/null
        openssl ec -in "${admin_key}" -pubout -outform DER 2>/dev/null \
            | base64 -w 0 > "${admin_spki}"
        log "Genesis admin signing key generated."
    fi
}

# ----------------------------------------------------------------
# PHASE 5: Generate consortium manifest (Node A leads, signed)
# ----------------------------------------------------------------
generate_manifest() {
    log "Generating consortium manifest ..."

    mkdir -p "${SHARED_MANIFEST_DIR}"

    local ADMIN_KEY="${SHARED_CERTS_DIR}/admin/admin-signing-key.pkcs8.pem"
    local ADMIN_SPKI="${SHARED_CERTS_DIR}/admin/admin-signing-spki.b64"

    # Read org public keys
    local FARMER_SPKI CARRIER_SPKI RETAILER_SPKI
    FARMER_SPKI="$(cat "${SHARED_CERTS_DIR}/node-a/org-signing-spki.b64")"
    CARRIER_SPKI="$(cat "${SHARED_CERTS_DIR}/node-b/org-signing-spki.b64")"
    RETAILER_SPKI="$(cat "${SHARED_CERTS_DIR}/node-c/org-signing-spki.b64")"

    # Read peer cert fingerprints
    local FP_A FP_B FP_C
    FP_A="$(cat "${SHARED_CERTS_DIR}/node-a/peer.fingerprint")"
    FP_B="$(cat "${SHARED_CERTS_DIR}/node-b/peer.fingerprint")"
    FP_C="$(cat "${SHARED_CERTS_DIR}/node-c/peer.fingerprint")"

    local ADMIN_SPKI_VAL
    ADMIN_SPKI_VAL="$(cat "${ADMIN_SPKI}")"

    local NODE_A_ENDPOINT="${NODE_A_P2P_HOST:-node-a}"
    local NODE_B_ENDPOINT="${NODE_B_P2P_HOST:-node-b}"
    local NODE_C_ENDPOINT="${NODE_C_P2P_HOST:-node-c}"

    # Descriptor JSON
    cat > "${SHARED_MANIFEST_DIR}/descriptor.json" <<EOF
{
  "schemaVersion": 1,
  "environment": "development",
  "networkId": "${NETWORK_ID}",
  "genesisAdminPublicKey": "${ADMIN_SPKI_VAL}",
  "difficulty": 1,
  "genesisTimestamp": "2026-01-01T00:00:00.000Z",
  "governanceEventTime": "2026-01-01T00:01:00.000Z",
  "organizationBlockTimestamp": "2026-01-01T00:02:00.000Z",
  "peerBlockTimestamp": "2026-01-01T00:03:00.000Z",
  "organizations": [
    {
      "organizationId": "org-farmer-a",
      "organizationType": "FARMER",
      "name": "Docker Demo Farmer",
      "keyId": "key-farmer-a-v1",
      "publicKey": "${FARMER_SPKI}"
    },
    {
      "organizationId": "org-carrier-b",
      "organizationType": "CARRIER",
      "name": "Docker Demo Carrier",
      "keyId": "key-carrier-b-v1",
      "publicKey": "${CARRIER_SPKI}"
    },
    {
      "organizationId": "org-retailer-c",
      "organizationType": "RETAILER",
      "name": "Docker Demo Retailer",
      "keyId": "key-retailer-c-v1",
      "publicKey": "${RETAILER_SPKI}"
    }
  ],
  "peers": [
    {
      "peerId": "peer-node-a-farmer",
      "organizationId": "org-farmer-a",
      "endpoint": "https://${NODE_A_ENDPOINT}:9443/AgriTrace",
      "tlsCertificateFingerprint": "${FP_A}"
    },
    {
      "peerId": "peer-node-b-carrier",
      "organizationId": "org-carrier-b",
      "endpoint": "https://${NODE_B_ENDPOINT}:9444/AgriTrace",
      "tlsCertificateFingerprint": "${FP_B}"
    },
    {
      "peerId": "peer-node-c-retailer",
      "organizationId": "org-retailer-c",
      "endpoint": "https://${NODE_C_ENDPOINT}:9445/AgriTrace",
      "tlsCertificateFingerprint": "${FP_C}"
    }
  ]
}
EOF

    log "Descriptor written. Generating manifest via ConsortiumManifestGenerator ..."

    cd /opt/agritrace

    # Step 1: Generate signing requests
    mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.ConsortiumManifestGenerator" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="requests ${SHARED_MANIFEST_DIR}/descriptor.json ${SHARED_MANIFEST_DIR}/signing-requests.json" \
        2>/dev/null \
        || die "ConsortiumManifestGenerator requests failed"

    log "Signing requests generated. Signing with admin key ..."

    # Step 2: Sign all governance requests with admin key using DemoConsortiumSigner
    mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.DemoConsortiumSigner" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="sign-requests ${SHARED_MANIFEST_DIR}/signing-requests.json ${ADMIN_KEY} ${SHARED_MANIFEST_DIR}/governance-signatures.json" \
        || die "Governance signing failed"

    log "Governance signatures created. Assembling unsigned manifest ..."

    # Step 3: Assemble unsigned manifest
    mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.ConsortiumManifestGenerator" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="assemble ${SHARED_MANIFEST_DIR}/descriptor.json ${SHARED_MANIFEST_DIR}/governance-signatures.json ${SHARED_MANIFEST_DIR}/unsigned-manifest.json" \
        || die "ConsortiumManifestGenerator assemble failed"

    log "Unsigned manifest assembled. Signing manifest ..."

    # Step 4: Sign the manifest using DemoConsortiumSigner
    mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.DemoConsortiumSigner" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="sign-manifest ${SHARED_MANIFEST_DIR}/unsigned-manifest.json ${ADMIN_KEY} ${SHARED_MANIFEST_DIR}/manifest-signature.txt" \
        || die "Manifest signing failed"

    # Step 5: Finalize manifest
    mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.ConsortiumManifestGenerator" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="finalize ${SHARED_MANIFEST_DIR}/unsigned-manifest.json ${SHARED_MANIFEST_DIR}/manifest-signature.txt ${MANIFEST_FILE}" \
        2>/dev/null \
        || die "ConsortiumManifestGenerator finalize failed"

    log "Signed consortium manifest created: ${MANIFEST_FILE}"
    cd /
}

# ----------------------------------------------------------------
# PHASE 6: Configure Tomcat
# ----------------------------------------------------------------
configure_tomcat() {
    log "Configuring Tomcat ..."

    mkdir -p "${CATALINA_BASE_DIR}/conf" \
             "${CATALINA_BASE_DIR}/logs" \
             "${CATALINA_BASE_DIR}/webapps" \
             "${CATALINA_BASE_DIR}/work" \
             "${CATALINA_BASE_DIR}/temp"

    # Copy Tomcat shared configuration files
    cp -r "${CATALINA_HOME}/conf/." "${CATALINA_BASE_DIR}/conf/" 2>/dev/null || true

    local SERVER_PASS
    SERVER_PASS="$(cat "${NODE_SECRETS_DIR}/server-keystore-password.txt")"
    local PEER_PASS
    PEER_PASS="$(cat "${NODE_SECRETS_DIR}/peer-keystore-password.txt")"
    local TRUST_PASS
    TRUST_PASS="$(cat "${NODE_SECRETS_DIR}/truststore-password.txt")"

    # Determine shutdown port (APP_PORT + 10000 to avoid conflict)
    local SHUTDOWN_PORT
    SHUTDOWN_PORT=$((APP_PORT + 10000))

    # Substitute template → server.xml
    sed \
        -e "s|@SHUTDOWN_PORT@|${SHUTDOWN_PORT}|g" \
        -e "s|@APP_PORT@|${APP_PORT}|g" \
        -e "s|@P2P_PORT@|${P2P_PORT}|g" \
        -e "s|@APP_SERVER_KEYSTORE@|${SERVER_KEYSTORE_PATH}|g" \
        -e "s|@APP_SERVER_PASSWORD@|${SERVER_PASS}|g" \
        -e "s|@P2P_SERVER_KEYSTORE@|${SERVER_KEYSTORE_PATH}|g" \
        -e "s|@P2P_SERVER_PASSWORD@|${PEER_PASS}|g" \
        -e "s|@P2P_CLIENT_TRUSTSTORE@|${TRUSTSTORE_PATH}|g" \
        -e "s|@P2P_CLIENT_TRUSTSTORE_PASSWORD@|${TRUST_PASS}|g" \
        /opt/agritrace/server.xml.template \
        > "${CATALINA_BASE_DIR}/conf/server.xml"

    # Deploy WAR
    cp /opt/agritrace/AgriTrace.war "${CATALINA_BASE_DIR}/webapps/AgriTrace.war"

    log "Tomcat configured. WAR deployed."
}

# ----------------------------------------------------------------
# PHASE 7: Bootstrap the node
# ----------------------------------------------------------------
bootstrap_node() {
    log "Running bootstrap for node ${NODE_ID} (${ADMIN_USERNAME}) ..."

    cd /opt/agritrace

    export AGRITRACE_DB_URL
    export AGRITRACE_DB_USERNAME
    export AGRITRACE_DB_PASSWORD
    export AGRITRACE_P2P_PEER_ID
    export AGRITRACE_P2P_KEYSTORE_PATH
    export AGRITRACE_P2P_KEYSTORE_PASSWORD

    # Check current bootstrap status first
    local status_output
    status_output=$(mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.ConsortiumBootstrapCli" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="status ${MANIFEST_FILE} ${ADMIN_USERNAME}" \
        2>/dev/null) || true

    local state
    state=$(echo "${status_output}" | grep -oP '"state"\s*:\s*"\K[^"]+' | head -1 || echo "UNKNOWN")
    log "Bootstrap state: ${state}"

    if [ "${state}" == "INITIALIZED" ]; then
        log "Node ${NODE_ID} already bootstrapped. Skipping."
        cd /
        return
    fi

    if [ "${state}" == "UNKNOWN" ] || [ "${state}" == "INCONSISTENT" ] || [ "${state}" == "DIFFERENT_NETWORK" ]; then
        die "Bootstrap state '${state}' is not safe to initialize for node ${NODE_ID}"
    fi

    # Run initialize with password via stdin
    log "Initializing node ${NODE_ID} ..."
    printf "%s\n%s\n" "${ADMIN_PASSWORD}" "${ADMIN_PASSWORD}" | mvn -q -B exec:java \
        -Dexec.mainClass="bootstrap.ConsortiumBootstrapCli" \
        -Dexec.classpathScope="compile" \
        -Dexec.args="initialize ${MANIFEST_FILE} ${ADMIN_USERNAME}" \
        || die "Bootstrap initialize failed for node ${NODE_ID}"

    log "Node ${NODE_ID} bootstrap completed."
    cd /
}

# ----------------------------------------------------------------
# PHASE 8: Start Tomcat
# ----------------------------------------------------------------
start_tomcat() {
    log "Starting Tomcat (node ${NODE_ID}) ..."

    export CATALINA_HOME
    export CATALINA_BASE="${CATALINA_BASE_DIR}"

    # Set JVM system properties for AgriTrace
    export JAVA_OPTS="${JAVA_OPTS:-} \
        -DAGRITRACE_DB_URL=${AGRITRACE_DB_URL} \
        -DAGRITRACE_DB_USERNAME=${AGRITRACE_DB_USERNAME} \
        -DAGRITRACE_DB_PASSWORD=${AGRITRACE_DB_PASSWORD} \
        -DAGRITRACE_PUBLIC_BASE_URL=${AGRITRACE_PUBLIC_BASE_URL:-https://localhost:${APP_PORT}/AgriTrace} \
        -Dagritrace.p2p.peer.id=${PEER_ID} \
        -Dagritrace.p2p.keystore.path=${NODE_IDENTITY_DIR}/peer.p12 \
        -Dagritrace.p2p.keystore.password=$(cat "${NODE_SECRETS_DIR}/peer-keystore-password.txt") \
        -Djavax.net.ssl.trustStore=${NODE_IDENTITY_DIR}/truststore.p12 \
        -Djavax.net.ssl.trustStorePassword=$(cat "${NODE_SECRETS_DIR}/truststore-password.txt") \
        -Djavax.net.ssl.trustStoreType=PKCS12"

    exec "${CATALINA_HOME}/bin/catalina.sh" run
}

# ----------------------------------------------------------------
# MAIN
# ----------------------------------------------------------------
main() {
    log "=== AgriTrace Demo Node ${NODE_ID} Starting ==="

    wait_for_mysql

    # Node A is the "leader" — generates shared PKI and manifest
    if [ "${NODE_ID}" == "A" ]; then
        acquire_lock "pki-init"
        if [ ! -f "${SHARED_CA_DIR}/demo-ca.crt" ]; then
            generate_shared_pki
        fi
        release_lock "pki-init"

        acquire_lock "manifest-init"
        if [ ! -f "${MANIFEST_FILE}" ]; then
            generate_admin_key
            generate_manifest
        fi
        release_lock "manifest-init"
    else
        log "Waiting for shared CA and manifest from leader (Node A) ..."
        local i=0
        while [ ! -f "${SHARED_CA_DIR}/demo-ca.crt" ] || [ ! -f "${MANIFEST_FILE}" ]; do
            if [ $((++i)) -ge 180 ]; then
                die "Timeout waiting for shared CA or manifest from Node A"
            fi
            sleep 1
        done
    fi

    log "Shared PKI and manifest are ready."

    # All nodes generate their own PKCS12 keystores
    acquire_lock "node-pkcs12-${NODE_ID_LOWER}"
    if [ ! -f "${NODE_IDENTITY_DIR}/peer.p12" ]; then
        generate_node_pkcs12
    else
        # Re-load the passwords from files
        export AGRITRACE_P2P_PEER_ID="${PEER_ID}"
        export AGRITRACE_P2P_KEYSTORE_PATH="${NODE_IDENTITY_DIR}/peer.p12"
        export AGRITRACE_P2P_KEYSTORE_PASSWORD="$(cat "${NODE_SECRETS_DIR}/peer-keystore-password.txt")"
        export SERVER_KEYSTORE_PATH="${NODE_IDENTITY_DIR}/server.p12"
        export SERVER_KEYSTORE_PASSWORD="$(cat "${NODE_SECRETS_DIR}/server-keystore-password.txt")"
        export TRUSTSTORE_PATH="${NODE_IDENTITY_DIR}/truststore.p12"
        export TRUSTSTORE_PASSWORD="$(cat "${NODE_SECRETS_DIR}/truststore-password.txt")"
    fi
    release_lock "node-pkcs12-${NODE_ID_LOWER}"

    configure_tomcat
    bootstrap_node
    start_tomcat
}

main "$@"
