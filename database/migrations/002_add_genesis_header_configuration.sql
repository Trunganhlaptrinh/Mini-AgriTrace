ALTER TABLE network_config
    ADD COLUMN genesis_timestamp DATETIME(3) NULL AFTER initial_pow_difficulty,
    ADD COLUMN genesis_nonce BIGINT NULL AFTER genesis_timestamp,
    ADD CONSTRAINT chk_network_genesis_nonce CHECK (
        genesis_nonce IS NULL OR genesis_nonce >= 0
    );
