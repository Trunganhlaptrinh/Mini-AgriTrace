package model;

public record BatchSnapshot(
        String batchCode,
        BatchState state,
        String farmerOrganizationId,
        String currentHolderOrganizationId,
        String lastShipmentTransactionId,
        String expectedCarrierOrganizationId,
        String expectedRecipientOrganizationId,
        String lastEventTransactionId
) {
    public BatchSnapshot {
        if (batchCode == null || batchCode.isBlank()) {
            throw new IllegalArgumentException("batchCode must not be blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        requireText(farmerOrganizationId, "farmerOrganizationId");
        requireText(currentHolderOrganizationId, "currentHolderOrganizationId");
        requireText(lastEventTransactionId, "lastEventTransactionId");
        if (state == BatchState.IN_TRANSIT) {
            requireText(lastShipmentTransactionId, "lastShipmentTransactionId");
            requireText(expectedCarrierOrganizationId, "expectedCarrierOrganizationId");
            requireText(expectedRecipientOrganizationId, "expectedRecipientOrganizationId");
        } else if (expectedCarrierOrganizationId != null || expectedRecipientOrganizationId != null) {
            throw new IllegalArgumentException("Only an in-transit batch may have an expected recipient");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
