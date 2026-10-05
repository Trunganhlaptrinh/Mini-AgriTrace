CREATE DATABASE IF NOT EXISTS agritrace
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE agritrace;

CREATE TABLE network_config (
    id TINYINT UNSIGNED PRIMARY KEY,
    network_id VARCHAR(100) NOT NULL UNIQUE,
    genesis_hash CHAR(64) NOT NULL,
    initial_pow_difficulty SMALLINT UNSIGNED NOT NULL,
    genesis_timestamp DATETIME(3) NOT NULL,
    genesis_nonce BIGINT NOT NULL,
    genesis_admin_public_key TEXT NOT NULL,
    bootstrap_manifest_digest CHAR(64) NULL,
    bootstrap_environment VARCHAR(20) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_network_config_singleton CHECK (id = 1),
    CONSTRAINT chk_network_pow_difficulty CHECK (initial_pow_difficulty BETWEEN 1 AND 16),
    CONSTRAINT chk_network_genesis_nonce CHECK (genesis_nonce >= 0),
    CONSTRAINT chk_bootstrap_manifest_identity CHECK (
        (bootstrap_manifest_digest IS NULL AND bootstrap_environment IS NULL)
        OR (bootstrap_manifest_digest REGEXP '^[0-9a-f]{64}$'
            AND bootstrap_environment IN ('development', 'staging', 'production'))
    )
);

CREATE TABLE blockchain_transactions (
    tx_id CHAR(64) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    tx_type VARCHAR(40) NOT NULL,
    payload JSON NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    signatures JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_blockchain_transactions_type (tx_type),
    INDEX idx_blockchain_transactions_submitted (submitted_at)
);

CREATE TABLE blockchain_blocks (
    block_hash CHAR(64) PRIMARY KEY,
    height BIGINT UNSIGNED NOT NULL,
    previous_hash CHAR(64) NULL,
    block_timestamp DATETIME(6) NOT NULL,
    nonce BIGINT UNSIGNED NOT NULL,
    difficulty SMALLINT UNSIGNED NOT NULL,
    transactions_hash CHAR(64) NOT NULL,
    cumulative_work DECIMAL(65, 0) NOT NULL,
    is_canonical BOOLEAN NOT NULL DEFAULT FALSE,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_block_previous
        FOREIGN KEY (previous_hash) REFERENCES blockchain_blocks(block_hash),
    CONSTRAINT chk_block_difficulty CHECK (difficulty BETWEEN 1 AND 16),
    CONSTRAINT chk_block_cumulative_work CHECK (cumulative_work >= 0),
    INDEX idx_blocks_height (height),
    INDEX idx_blocks_previous_hash (previous_hash),
    INDEX idx_blocks_canonical_height (is_canonical, height)
);

CREATE TABLE block_transactions (
    block_hash CHAR(64) NOT NULL,
    tx_id CHAR(64) NOT NULL,
    transaction_index INT UNSIGNED NOT NULL,
    PRIMARY KEY (block_hash, tx_id),
    CONSTRAINT uq_block_transaction_index UNIQUE (block_hash, transaction_index),
    CONSTRAINT fk_block_transactions_block
        FOREIGN KEY (block_hash) REFERENCES blockchain_blocks(block_hash),
    CONSTRAINT fk_block_transactions_transaction
        FOREIGN KEY (tx_id) REFERENCES blockchain_transactions(tx_id),
    INDEX idx_block_transactions_tx (tx_id)
);

CREATE TABLE transaction_pool (
    tx_id CHAR(64) PRIMARY KEY,
    source_node_id VARCHAR(100) NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_transaction_pool_transaction
        FOREIGN KEY (tx_id) REFERENCES blockchain_transactions(tx_id)
        ON DELETE CASCADE,
    INDEX idx_transaction_pool_received (received_at)
);

CREATE TABLE node_transaction_status (
    tx_id CHAR(64) PRIMARY KEY,
    status ENUM('PENDING', 'CONFIRMED', 'REJECTED') NOT NULL,
    rejection_code VARCHAR(80) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_node_transaction_status_transaction
        FOREIGN KEY (tx_id) REFERENCES blockchain_transactions(tx_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_node_transaction_rejection CHECK (
        (status = 'REJECTED' AND rejection_code IS NOT NULL)
        OR (status <> 'REJECTED' AND rejection_code IS NULL)
    )
);

CREATE TABLE organizations (
    organization_id VARCHAR(100) PRIMARY KEY,
    organization_type ENUM('FARMER', 'CARRIER', 'WAREHOUSE', 'RETAILER') NOT NULL,
    name VARCHAR(200) NOT NULL,
    province VARCHAR(100) NULL,
    status ENUM('ACTIVE', 'SUSPENDED', 'REVOKED') NOT NULL,
    registered_by_tx_id CHAR(64) NULL,
    updated_by_tx_id CHAR(64) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_organizations_registered_tx
        FOREIGN KEY (registered_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    CONSTRAINT fk_organizations_updated_tx
        FOREIGN KEY (updated_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    INDEX idx_organizations_type_status (organization_type, status)
);

CREATE TABLE organization_keys (
    key_id VARCHAR(100) PRIMARY KEY,
    organization_id VARCHAR(100) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,
    public_key TEXT NOT NULL,
    valid_from_height BIGINT UNSIGNED NOT NULL,
    revoked_at_height BIGINT UNSIGNED NULL,
    registered_by_tx_id CHAR(64) NULL,
    revoked_by_tx_id CHAR(64) NULL,
    CONSTRAINT fk_organization_keys_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(organization_id),
    CONSTRAINT fk_organization_keys_registered_tx
        FOREIGN KEY (registered_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    CONSTRAINT fk_organization_keys_revoked_tx
        FOREIGN KEY (revoked_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    INDEX idx_organization_keys_org_height (organization_id, valid_from_height),
    INDEX idx_organization_keys_revoked_height (revoked_at_height)
);

CREATE TABLE authorized_peers (
    peer_id VARCHAR(100) PRIMARY KEY,
    organization_id VARCHAR(100) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    tls_certificate_fingerprint CHAR(64) NOT NULL,
    status ENUM('ACTIVE', 'REVOKED') NOT NULL,
    registered_by_tx_id CHAR(64) NULL,
    updated_by_tx_id CHAR(64) NULL,
    CONSTRAINT fk_authorized_peers_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(organization_id),
    CONSTRAINT fk_authorized_peers_registered_tx
        FOREIGN KEY (registered_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    CONSTRAINT fk_authorized_peers_updated_tx
        FOREIGN KEY (updated_by_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    INDEX idx_authorized_peers_status (status)
);

CREATE TABLE users (
    user_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('ADMIN', 'FARMER', 'CARRIER', 'WAREHOUSE', 'RETAILER') NOT NULL,
    organization_id VARCHAR(100) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    organization_canonical BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_user_organization_role CHECK (
        (role = 'ADMIN' AND organization_id IS NULL)
        OR (role <> 'ADMIN' AND organization_id IS NOT NULL)
    ),
    INDEX idx_users_org_role (organization_id, role)
);

CREATE TABLE shipment_proposals (
    proposal_id CHAR(36) PRIMARY KEY,
    event_id CHAR(36) NOT NULL UNIQUE,
    batch_code VARCHAR(100) NOT NULL,
    sender_organization_id VARCHAR(100) NOT NULL,
    carrier_organization_id VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status ENUM('AWAITING_CARRIER', 'READY', 'EXPIRED', 'SUBMITTED') NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    submitted_tx_id CHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shipment_proposals_submitted_tx
        FOREIGN KEY (submitted_tx_id)
        REFERENCES blockchain_transactions(tx_id),
    INDEX idx_shipment_proposals_batch (batch_code, created_at),
    INDEX idx_shipment_proposals_carrier_status
        (carrier_organization_id, status, expires_at)
);

CREATE TABLE shipment_proposal_signatures (
    proposal_id CHAR(36) NOT NULL,
    signer_organization_id VARCHAR(100) NOT NULL,
    signer_role ENUM('SENDER', 'CARRIER') NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    signature TEXT NOT NULL,
    signed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (proposal_id, signer_organization_id),
    CONSTRAINT uq_shipment_proposal_signer_role UNIQUE (proposal_id, signer_role),
    CONSTRAINT fk_shipment_signatures_proposal
        FOREIGN KEY (proposal_id) REFERENCES shipment_proposals(proposal_id)
);

CREATE TABLE batches (
    batch_code VARCHAR(100) PRIMARY KEY,
    product_type VARCHAR(150) NOT NULL,
    variety VARCHAR(150) NOT NULL,
    harvest_date DATE NOT NULL,
    quantity DECIMAL(18, 3) NOT NULL,
    quantity_unit VARCHAR(30) NOT NULL,
    farm_name VARCHAR(200) NOT NULL,
    province VARCHAR(100) NOT NULL,
    farmer_organization_id VARCHAR(100) NOT NULL,
    current_holder_organization_id VARCHAR(100) NULL,
    current_status ENUM('HARVESTED', 'PACKAGED', 'IN_TRANSIT', 'RECEIVED', 'SOLD')
        NOT NULL,
    harvest_tx_id CHAR(64) NOT NULL,
    last_event_tx_id CHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_batch_quantity_positive CHECK (quantity > 0),
    CONSTRAINT fk_batches_farmer
        FOREIGN KEY (farmer_organization_id)
        REFERENCES organizations(organization_id),
    CONSTRAINT fk_batches_current_holder
        FOREIGN KEY (current_holder_organization_id)
        REFERENCES organizations(organization_id),
    CONSTRAINT fk_batches_harvest_tx
        FOREIGN KEY (harvest_tx_id) REFERENCES blockchain_transactions(tx_id),
    CONSTRAINT fk_batches_last_event_tx
        FOREIGN KEY (last_event_tx_id) REFERENCES blockchain_transactions(tx_id),
    INDEX idx_batches_holder_status
        (current_holder_organization_id, current_status)
);

CREATE TABLE batch_events (
    tx_id CHAR(64) PRIMARY KEY,
    batch_code VARCHAR(100) NOT NULL,
    event_type ENUM(
        'HARVESTED', 'PACKAGED', 'SHIPPED', 'RECEIVED', 'SOLD', 'CORRECTION'
    ) NOT NULL,
    actor_organization_id VARCHAR(100) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    correction_of_tx_id CHAR(64) NULL,
    public_payload JSON NOT NULL,
    CONSTRAINT fk_batch_events_transaction
        FOREIGN KEY (tx_id) REFERENCES blockchain_transactions(tx_id),
    CONSTRAINT fk_batch_events_batch
        FOREIGN KEY (batch_code) REFERENCES batches(batch_code),
    CONSTRAINT fk_batch_events_actor
        FOREIGN KEY (actor_organization_id)
        REFERENCES organizations(organization_id),
    CONSTRAINT fk_batch_events_correction
        FOREIGN KEY (correction_of_tx_id) REFERENCES batch_events(tx_id),
    INDEX idx_batch_events_batch_time (batch_code, event_time, tx_id),
    INDEX idx_batch_events_actor (actor_organization_id, event_type)
);
