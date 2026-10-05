ALTER TABLE network_config
    ADD COLUMN bootstrap_manifest_digest CHAR(64) NULL AFTER genesis_admin_public_key,
    ADD COLUMN bootstrap_environment VARCHAR(20) NULL AFTER bootstrap_manifest_digest,
    ADD CONSTRAINT chk_bootstrap_manifest_identity CHECK (
        (bootstrap_manifest_digest IS NULL AND bootstrap_environment IS NULL)
        OR (bootstrap_manifest_digest REGEXP '^[0-9a-f]{64}$'
            AND bootstrap_environment IN ('development', 'staging', 'production'))
    );
