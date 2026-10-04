USE agritrace;

ALTER TABLE users
    DROP FOREIGN KEY fk_users_organization,
    ADD COLUMN organization_canonical BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE shipment_proposals
    DROP FOREIGN KEY fk_shipment_proposals_sender,
    DROP FOREIGN KEY fk_shipment_proposals_carrier;

ALTER TABLE shipment_proposal_signatures
    DROP FOREIGN KEY fk_shipment_signatures_organization,
    DROP FOREIGN KEY fk_shipment_signatures_key;

ALTER TABLE organizations
    MODIFY COLUMN registered_by_tx_id CHAR(64) NULL,
    MODIFY COLUMN updated_by_tx_id CHAR(64) NULL;

ALTER TABLE organization_keys
    MODIFY COLUMN registered_by_tx_id CHAR(64) NULL;

ALTER TABLE authorized_peers
    MODIFY COLUMN registered_by_tx_id CHAR(64) NULL,
    MODIFY COLUMN updated_by_tx_id CHAR(64) NULL;

ALTER TABLE blockchain_transactions
    MODIFY COLUMN event_id VARCHAR(255) NOT NULL;
