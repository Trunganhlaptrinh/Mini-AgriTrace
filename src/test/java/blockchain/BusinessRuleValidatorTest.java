package blockchain;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.BatchSnapshot;
import model.BatchState;
import model.EventType;
import model.Organization;
import model.OrganizationType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessRuleValidatorTest {
    private static final String BATCH_CODE = "MANGO-2026-0001";
    private static final Instant EVENT_TIME = Instant.parse("2026-10-01T02:00:00Z");

    private final BusinessRuleValidator validator = new BusinessRuleValidator();
    private Map<String, Organization> organizations;

    @BeforeEach
    void setUp() {
        organizations = Map.of(
                "farm-1", new Organization("farm-1", OrganizationType.FARMER, true),
                "farm-2", new Organization("farm-2", OrganizationType.FARMER, true),
                "carrier-1", new Organization("carrier-1", OrganizationType.CARRIER, true),
                "warehouse-1", new Organization("warehouse-1", OrganizationType.WAREHOUSE, true),
                "warehouse-2", new Organization("warehouse-2", OrganizationType.WAREHOUSE, true),
                "retailer-1", new Organization("retailer-1", OrganizationType.RETAILER, true),
                "inactive-farm", new Organization("inactive-farm", OrganizationType.FARMER, false));
    }

    @Test
    void appliesHarvestPackShipReceiveAndSaleInOrder() {
        BatchSnapshot harvested = validator.apply(
                null, harvest("tx-harvest", "farm-1"), organizations, Map.of());
        assertEquals(BatchState.HARVESTED, harvested.state());
        assertEquals("farm-1", harvested.currentHolderOrganizationId());

        BatchSnapshot packaged = validator.apply(
                harvested, packaged("tx-packaged", "farm-1"), organizations, Map.of());
        assertEquals(BatchState.PACKAGED, packaged.state());

        BatchSnapshot inTransit = validator.apply(
                packaged, shipped("tx-shipped-1", "farm-1", "carrier-1", "warehouse-1"),
                organizations, Map.of());
        assertEquals(BatchState.IN_TRANSIT, inTransit.state());
        assertEquals("farm-1", inTransit.currentHolderOrganizationId());
        assertEquals("warehouse-1", inTransit.expectedRecipientOrganizationId());

        BatchSnapshot received = validator.apply(
                inTransit, received("tx-received-1", "warehouse-1", "tx-shipped-1"),
                organizations, Map.of());
        assertEquals(BatchState.RECEIVED, received.state());
        assertEquals("warehouse-1", received.currentHolderOrganizationId());

        BatchSnapshot secondTransit = validator.apply(
                received, shipped("tx-shipped-2", "warehouse-1", "carrier-1", "retailer-1"),
                organizations, Map.of());
        BatchSnapshot retailerReceived = validator.apply(
                secondTransit, received("tx-received-2", "retailer-1", "tx-shipped-2"),
                organizations, Map.of());

        BatchSnapshot sold = validator.apply(
                retailerReceived, sold("tx-sold", "retailer-1"), organizations, Map.of());
        assertEquals(BatchState.SOLD, sold.state());
        assertEquals("retailer-1", sold.currentHolderOrganizationId());
    }

    @Test
    void allowsAReceivedHolderToShipTheBatchAgain() {
        BatchSnapshot received = receivedBatchAtWarehouse();

        BatchSnapshot inTransit = validator.apply(
                received, shipped("tx-shipped-2", "warehouse-1", "carrier-1", "retailer-1"),
                organizations, Map.of());

        assertEquals(BatchState.IN_TRANSIT, inTransit.state());
        assertEquals("warehouse-1", inTransit.currentHolderOrganizationId());
        assertEquals("tx-shipped-2", inTransit.lastShipmentTransactionId());
    }

    @Test
    void rejectsPackagingBeforeHarvest() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(null, packaged("tx-packaged", "farm-1"), organizations, Map.of()));

        assertEquals("BATCH_NOT_FOUND", exception.getCode());
    }

    @Test
    void rejectsAnEventFromAnInactiveOrganization() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(null, harvest("tx-harvest", "inactive-farm"), organizations, Map.of()));

        assertEquals("INACTIVE_ORGANIZATION", exception.getCode());
    }

    @Test
    void rejectsAnInvalidHarvestDate() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(null, harvestWithDataValue("harvestDate", "2026-02-30"),
                        organizations, Map.of()));

        assertEquals("INVALID_HARVEST_DATE", exception.getCode());
    }

    @Test
    void rejectsAQuantityOutsideTheSupportedDecimalFormat() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(null, harvestWithDataValue("quantity", "12.00"),
                        organizations, Map.of()));

        assertEquals("INVALID_HARVEST_QUANTITY", exception.getCode());
    }

    @Test
    void rejectsPackagingByTheWrongRole() {
        BatchSnapshot harvested = validator.apply(
                null, harvest("tx-harvest", "farm-1"), organizations, Map.of());
        BatchEvent invalidPackaging = event(
                "tx-packaged",
                EventType.PACKAGED,
                Map.of(),
                List.of(signature("carrier-1", "FARMER_PACKAGED")));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(harvested, invalidPackaging, organizations, Map.of()));

        assertEquals("INVALID_ACTOR_ROLE", exception.getCode());
    }

    @Test
    void rejectsShipmentWithoutBothAuthorizedSignatures() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent invalidShipment = event(
                "tx-shipped",
                EventType.SHIPPED,
                shipmentData("farm-1", "carrier-1", "warehouse-1"),
                List.of(signature("farm-1", "SHIPMENT_SENDER")));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, invalidShipment, organizations, Map.of()));

        assertEquals("INVALID_SHIPMENT_SIGNATURES", exception.getCode());
    }

    @Test
    void rejectsShipmentInitiatedByAnOrganizationThatDoesNotHoldTheBatch() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent invalidShipment = shipped(
                "tx-shipped", "farm-2", "carrier-1", "warehouse-1");

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, invalidShipment, organizations, Map.of()));

        assertEquals("NOT_CURRENT_HOLDER", exception.getCode());
    }

    @Test
    void rejectsAnOrganizationWithTheWrongCarrierRole() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent invalidShipment = shipped(
                "tx-shipped", "farm-1", "warehouse-2", "retailer-1");

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, invalidShipment, organizations, Map.of()));

        assertEquals("INVALID_CARRIER_ROLE", exception.getCode());
    }

    @Test
    void rejectsReceiptByAnOrganizationOtherThanTheDesignatedRecipient() {
        BatchSnapshot inTransit = shippedBatch();
        BatchEvent invalidReceipt = received("tx-received", "warehouse-2", "tx-shipped-1");

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(inTransit, invalidReceipt, organizations, Map.of()));

        assertEquals("UNEXPECTED_RECIPIENT", exception.getCode());
    }

    @Test
    void rejectsReceiptThatReferencesAnOlderOrDifferentShipment() {
        BatchSnapshot inTransit = shippedBatch();
        BatchEvent invalidReceipt = received("tx-received", "warehouse-1", "tx-other-shipment");

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(inTransit, invalidReceipt, organizations, Map.of()));

        assertEquals("SHIPMENT_MISMATCH", exception.getCode());
    }

    @Test
    void rejectsSaleBeforeTheRetailerHasReceivedTheBatch() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent invalidSale = sold("tx-sold", "retailer-1");

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, invalidSale, organizations, Map.of()));

        assertEquals("INVALID_STATE_TRANSITION", exception.getCode());
    }

    @Test
    void acceptsCorrectionFromAnOriginalEventSignerWithoutRewritingState() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent originalEvent = packaged("tx-packaged", "farm-1");
        BatchEvent correction = event(
                "tx-correction",
                EventType.CORRECTION,
                Map.of(
                        "correctionOfTxId", "tx-packaged",
                        "reason", "Corrected variety spelling",
                        "correctedPublicData", Map.of("variety", "Cat Hoa Loc")),
                List.of(signature("farm-1", "CORRECTION")));

        BatchSnapshot corrected = validator.apply(
                packaged, correction, organizations, Map.of(originalEvent.transactionId(), originalEvent));

        assertEquals(packaged.state(), corrected.state());
        assertEquals(packaged.currentHolderOrganizationId(), corrected.currentHolderOrganizationId());
        assertEquals("tx-correction", corrected.lastEventTransactionId());
    }

    @Test
    void rejectsCorrectionOfAnEventFromAnotherBatch() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent otherBatchEvent = new BatchEvent(
                "tx-other-batch",
                "event-other-batch",
                "BANANA-2026-0001",
                EventType.PACKAGED,
                EVENT_TIME,
                Map.of(),
                List.of(signature("farm-1", "FARMER_PACKAGED")));
        BatchEvent correction = event(
                "tx-correction",
                EventType.CORRECTION,
                Map.of(
                        "correctionOfTxId", "tx-other-batch",
                        "reason", "Correction",
                        "correctedPublicData", Map.of("variety", "Cat Hoa Loc")),
                List.of(signature("farm-1", "CORRECTION")));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, correction, organizations,
                        Map.of(otherBatchEvent.transactionId(), otherBatchEvent)));

        assertEquals("CORRECTION_BATCH_MISMATCH", exception.getCode());
    }

    @Test
    void rejectsCorrectionByAnOrganizationThatDidNotSignTheOriginalEvent() {
        BatchSnapshot packaged = packagedBatch();
        BatchEvent originalEvent = packaged("tx-packaged", "farm-1");
        BatchEvent correction = event(
                "tx-correction",
                EventType.CORRECTION,
                Map.of(
                        "correctionOfTxId", "tx-packaged",
                        "reason", "Correction",
                        "correctedPublicData", Map.of("variety", "Cat Hoa Loc")),
                List.of(signature("farm-2", "CORRECTION")));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> validator.apply(packaged, correction, organizations,
                        Map.of(originalEvent.transactionId(), originalEvent)));

        assertEquals("CORRECTION_SIGNER_MISMATCH", exception.getCode());
    }

    private BatchSnapshot packagedBatch() {
        BatchSnapshot harvested = validator.apply(
                null, harvest("tx-harvest", "farm-1"), organizations, Map.of());
        return validator.apply(
                harvested, packaged("tx-packaged", "farm-1"), organizations, Map.of());
    }

    private BatchSnapshot shippedBatch() {
        return validator.apply(
                packagedBatch(), shipped("tx-shipped-1", "farm-1", "carrier-1", "warehouse-1"),
                organizations, Map.of());
    }

    private BatchSnapshot receivedBatchAtWarehouse() {
        return validator.apply(
                shippedBatch(), received("tx-received-1", "warehouse-1", "tx-shipped-1"),
                organizations, Map.of());
    }

    private BatchEvent harvest(String transactionId, String farmerId) {
        return event(
                transactionId,
                EventType.HARVESTED,
                Map.of(
                        "productType", "Mango",
                        "variety", "Cat Hoa Loc",
                        "harvestDate", "2026-10-01",
                        "quantity", "1200.000",
                        "quantityUnit", "kg",
                        "farmName", "Mekong Mango Farm",
                        "province", "Tien Giang"),
                List.of(signature(farmerId, "FARMER_HARVEST")));
    }

    private BatchEvent harvestWithDataValue(String field, Object value) {
        BatchEvent original = harvest("tx-harvest", "farm-1");
        Map<String, Object> data = new HashMap<>(original.data());
        data.put(field, value);
        return event(original.transactionId(), original.eventType(), data, original.signatures());
    }

    private BatchEvent packaged(String transactionId, String farmerId) {
        return event(
                transactionId,
                EventType.PACKAGED,
                Map.of(),
                List.of(signature(farmerId, "FARMER_PACKAGED")));
    }

    private BatchEvent shipped(
            String transactionId,
            String senderId,
            String carrierId,
            String recipientId
    ) {
        return event(
                transactionId,
                EventType.SHIPPED,
                shipmentData(senderId, carrierId, recipientId),
                List.of(
                        signature(senderId, "SHIPMENT_SENDER"),
                        signature(carrierId, "SHIPMENT_CARRIER")));
    }

    private Map<String, Object> shipmentData(String senderId, String carrierId, String recipientId) {
        return Map.of(
                "senderOrganizationId", senderId,
                "carrierOrganizationId", carrierId,
                "recipientOrganizationId", recipientId);
    }

    private BatchEvent received(String transactionId, String recipientId, String shipmentTransactionId) {
        String purpose = recipientId.startsWith("retailer")
                ? "RETAILER_RECEIPT" : "WAREHOUSE_RECEIPT";
        return event(
                transactionId,
                EventType.RECEIVED,
                Map.of("shipmentTxId", shipmentTransactionId),
                List.of(signature(recipientId, purpose)));
    }

    private BatchEvent sold(String transactionId, String retailerId) {
        return event(
                transactionId,
                EventType.SOLD,
                Map.of(),
                List.of(signature(retailerId, "RETAILER_SALE")));
    }

    private BatchEvent event(
            String transactionId,
            EventType eventType,
            Map<String, Object> data,
            List<SignatureEnvelope> signatures
    ) {
        return new BatchEvent(
                transactionId,
                "event-" + transactionId,
                BATCH_CODE,
                eventType,
                EVENT_TIME,
                data,
                signatures);
    }

    private SignatureEnvelope signature(String organizationId, String purpose) {
        return new SignatureEnvelope(
                organizationId,
                organizationId + "-key-1",
                purpose,
                Base64.getEncoder().encodeToString(new byte[64]));
    }
}
