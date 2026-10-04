package blockchain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.BatchSnapshot;
import model.BatchState;
import model.EventType;
import model.Organization;
import model.OrganizationType;
import model.SignatureEnvelope;

public final class BusinessRuleValidator {
    public BatchSnapshot apply(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations,
            Map<String, BatchEvent> eventsByTransaction
    ) {
        if (event == null) {
            throw invalid("INVALID_EVENT", "Event must not be null");
        }
        Map<String, Organization> knownOrganizations = organizations == null
                ? Map.of() : organizations;
        Map<String, BatchEvent> historicalEvents = eventsByTransaction == null
                ? Map.of() : eventsByTransaction;

        if (current != null && !current.batchCode().equals(event.batchCode())) {
            throw invalid("BATCH_MISMATCH", "Event batch code does not match the current batch");
        }

        return switch (event.eventType()) {
            case HARVESTED -> harvest(current, event, knownOrganizations);
            case PACKAGED -> packageBatch(current, event, knownOrganizations);
            case SHIPPED -> ship(current, event, knownOrganizations);
            case RECEIVED -> receive(current, event, knownOrganizations);
            case SOLD -> sell(current, event, knownOrganizations);
            case CORRECTION -> correct(current, event, knownOrganizations, historicalEvents);
        };
    }

    private BatchSnapshot harvest(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations
    ) {
        if (current != null) {
            throw invalid("BATCH_ALREADY_EXISTS", "A batch can only be harvested once");
        }
        Organization farmer = requireSingleSigner(
                event, organizations, OrganizationType.FARMER, "FARMER_HARVEST");
        requireData(event, "productType");
        requireData(event, "variety");
        validateHarvestDate(requireData(event, "harvestDate"));
        validateQuantity(requireData(event, "quantity"));
        requireData(event, "quantityUnit");
        requireData(event, "farmName");
        requireData(event, "province");

        return new BatchSnapshot(
                event.batchCode(),
                BatchState.HARVESTED,
                farmer.organizationId(),
                farmer.organizationId(),
                null,
                null,
                null,
                event.transactionId());
    }

    private BatchSnapshot packageBatch(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations
    ) {
        requireState(current, BatchState.HARVESTED);
        Organization farmer = requireSingleSigner(
                event, organizations, OrganizationType.FARMER, "FARMER_PACKAGED");
        requireCurrentHolder(current, farmer);

        return copyState(current, BatchState.PACKAGED, current.currentHolderOrganizationId(),
                null, null, null, event.transactionId());
    }

    private BatchSnapshot ship(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations
    ) {
        requireOneOfStates(current, BatchState.PACKAGED, BatchState.RECEIVED);
        List<SignatureEnvelope> signatures = event.signatures();
        if (signatures.size() != 2) {
            throw invalid("INVALID_SHIPMENT_SIGNATURES", "SHIPPED requires sender and carrier signatures");
        }

        String senderId = requireData(event, "senderOrganizationId");
        String carrierId = requireData(event, "carrierOrganizationId");
        String recipientId = requireData(event, "recipientOrganizationId");
        if (senderId.equals(carrierId) || senderId.equals(recipientId) || carrierId.equals(recipientId)) {
            throw invalid("INVALID_SHIPMENT_PARTIES", "Sender, carrier, and recipient must be distinct");
        }
        if (!senderId.equals(current.currentHolderOrganizationId())) {
            throw invalid("NOT_CURRENT_HOLDER", "Only the current holder may initiate a shipment");
        }

        SignatureEnvelope senderSignature = requireSignature(signatures, senderId, "SHIPMENT_SENDER");
        SignatureEnvelope carrierSignature = requireSignature(signatures, carrierId, "SHIPMENT_CARRIER");
        Organization sender = requireActiveOrganization(senderSignature, organizations);
        Organization carrier = requireActiveOrganization(carrierSignature, organizations);
        Organization recipient = requireActiveOrganizationById(recipientId, organizations);
        if (sender.type() != OrganizationType.FARMER
                && sender.type() != OrganizationType.WAREHOUSE
                && sender.type() != OrganizationType.RETAILER) {
            throw invalid("INVALID_SENDER_ROLE", "The sender organization cannot hold a batch");
        }
        if (carrier.type() != OrganizationType.CARRIER) {
            throw invalid("INVALID_CARRIER_ROLE", "The carrier signature must belong to a carrier");
        }
        if (recipient.type() != OrganizationType.WAREHOUSE
                && recipient.type() != OrganizationType.RETAILER) {
            throw invalid("INVALID_RECIPIENT_ROLE", "The recipient must be a warehouse or retailer");
        }

        return copyState(current, BatchState.IN_TRANSIT, current.currentHolderOrganizationId(),
                event.transactionId(), carrierId, recipientId, event.transactionId());
    }

    private BatchSnapshot receive(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations
    ) {
        requireState(current, BatchState.IN_TRANSIT);
        Organization recipient = requireSingleSigner(
                event, organizations, null, null);
        if (recipient.type() != OrganizationType.WAREHOUSE && recipient.type() != OrganizationType.RETAILER) {
            throw invalid("INVALID_RECIPIENT_ROLE", "Only a warehouse or retailer may receive a batch");
        }
        if (!recipient.organizationId().equals(current.expectedRecipientOrganizationId())) {
            throw invalid("UNEXPECTED_RECIPIENT", "Only the designated recipient may receive this shipment");
        }
        if (!current.lastShipmentTransactionId().equals(event.data().get("shipmentTxId"))) {
            throw invalid("SHIPMENT_MISMATCH", "RECEIVED must reference the latest shipment transaction");
        }
        String expectedPurpose = recipient.type() == OrganizationType.WAREHOUSE
                ? "WAREHOUSE_RECEIPT" : "RETAILER_RECEIPT";
        requireSignature(event.signatures(), recipient.organizationId(), expectedPurpose);

        return copyState(current, BatchState.RECEIVED, recipient.organizationId(),
                current.lastShipmentTransactionId(), null, null, event.transactionId());
    }

    private BatchSnapshot sell(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations
    ) {
        requireState(current, BatchState.RECEIVED);
        Organization retailer = requireSingleSigner(
                event, organizations, OrganizationType.RETAILER, "RETAILER_SALE");
        requireCurrentHolder(current, retailer);

        return copyState(current, BatchState.SOLD, current.currentHolderOrganizationId(),
                current.lastShipmentTransactionId(), null, null, event.transactionId());
    }

    private BatchSnapshot correct(
            BatchSnapshot current,
            BatchEvent event,
            Map<String, Organization> organizations,
            Map<String, BatchEvent> eventsByTransaction
    ) {
        if (current == null) {
            throw invalid("BATCH_NOT_FOUND", "A correction requires an existing batch");
        }
        String originalTransactionId = requireData(event, "correctionOfTxId");
        String reason = requireData(event, "reason");
        Object correctedPublicData = event.data().get("correctedPublicData");
        if (!(correctedPublicData instanceof Map<?, ?> correctedFields) || correctedFields.isEmpty()) {
            throw invalid("MISSING_EVENT_DATA", "correctedPublicData must be a non-empty object");
        }
        BatchEvent originalEvent = eventsByTransaction.get(originalTransactionId);
        if (originalEvent == null) {
            throw invalid("CORRECTION_TARGET_NOT_FOUND", "Correction target is not in the event history");
        }
        if (!current.batchCode().equals(originalEvent.batchCode())) {
            throw invalid("CORRECTION_BATCH_MISMATCH", "Correction target belongs to a different batch");
        }
        Organization signer = requireSingleSigner(event, organizations, null, "CORRECTION");
        boolean originalSigner = originalEvent.signatures().stream()
                .anyMatch(signature -> signature.organizationId().equals(signer.organizationId()));
        if (!originalSigner) {
            throw invalid("CORRECTION_SIGNER_MISMATCH", "Correction must be signed by an original event signer");
        }
        return copyState(current, current.state(), current.currentHolderOrganizationId(),
                current.lastShipmentTransactionId(), current.expectedCarrierOrganizationId(),
                current.expectedRecipientOrganizationId(), event.transactionId());
    }

    private Organization requireSingleSigner(
            BatchEvent event,
            Map<String, Organization> organizations,
            OrganizationType requiredType,
            String requiredPurpose
    ) {
        if (event.signatures().size() != 1) {
            throw invalid("INVALID_SIGNATURE_COUNT", "This event requires exactly one organization signature");
        }
        SignatureEnvelope signature = event.signatures().get(0);
        if (requiredPurpose != null && !requiredPurpose.equals(signature.purpose())) {
            throw invalid("INVALID_SIGNATURE_PURPOSE", "Signature purpose does not match the event");
        }
        Organization organization = requireActiveOrganization(signature, organizations);
        if (requiredType != null && organization.type() != requiredType) {
            throw invalid("INVALID_ACTOR_ROLE", "Organization role is not allowed for this event");
        }
        return organization;
    }

    private Organization requireActiveOrganization(
            SignatureEnvelope signature,
            Map<String, Organization> organizations
    ) {
        Organization organization = organizations.get(signature.organizationId());
        if (organization == null) {
            throw invalid("UNKNOWN_ORGANIZATION", "Signer organization is not registered");
        }
        if (!organization.active()) {
            throw invalid("INACTIVE_ORGANIZATION", "Signer organization is not active");
        }
        return organization;
    }

    private Organization requireActiveOrganizationById(
            String organizationId,
            Map<String, Organization> organizations
    ) {
        Organization organization = organizations.get(organizationId);
        if (organization == null) {
            throw invalid("UNKNOWN_ORGANIZATION", "Organization is not registered");
        }
        if (!organization.active()) {
            throw invalid("INACTIVE_ORGANIZATION", "Organization is not active");
        }
        return organization;
    }

    private SignatureEnvelope requireSignature(
            List<SignatureEnvelope> signatures,
            String organizationId,
            String purpose
    ) {
        return signatures.stream()
                .filter(signature -> signature.organizationId().equals(organizationId))
                .filter(signature -> signature.purpose().equals(purpose))
                .findFirst()
                .orElseThrow(() -> invalid(
                        "INVALID_SHIPMENT_SIGNATURES", "Required organization signature is missing"));
    }

    private void requireCurrentHolder(BatchSnapshot current, Organization signer) {
        if (!current.currentHolderOrganizationId().equals(signer.organizationId())) {
            throw invalid("NOT_CURRENT_HOLDER", "Only the current holder may perform this event");
        }
    }

    private void requireState(BatchSnapshot current, BatchState expected) {
        if (current == null) {
            throw invalid("BATCH_NOT_FOUND", "Batch does not exist");
        }
        if (current.state() != expected) {
            throw invalid("INVALID_STATE_TRANSITION", "Event is not valid for the current batch state");
        }
    }

    private void requireOneOfStates(BatchSnapshot current, BatchState first, BatchState second) {
        if (current == null) {
            throw invalid("BATCH_NOT_FOUND", "Batch does not exist");
        }
        if (current.state() != first && current.state() != second) {
            throw invalid("INVALID_STATE_TRANSITION", "Event is not valid for the current batch state");
        }
    }

    private String requireData(BatchEvent event, String field) {
        Object value = event.data().get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("MISSING_EVENT_DATA", "Required event field is missing: " + field);
        }
        return text;
    }

    private void validateHarvestDate(String harvestDate) {
        try {
            LocalDate.parse(harvestDate);
        } catch (DateTimeParseException exception) {
            throw invalid("INVALID_HARVEST_DATE", "harvestDate must be a valid yyyy-MM-dd date");
        }
    }

    private void validateQuantity(String quantityValue) {
        try {
            BigDecimal quantity = new BigDecimal(quantityValue);
            if (quantity.signum() <= 0 || quantity.scale() != 3 || quantity.precision() > 18) {
                throw invalid("INVALID_HARVEST_QUANTITY",
                        "quantity must be positive and fit DECIMAL(18,3)");
            }
        } catch (NumberFormatException exception) {
            throw invalid("INVALID_HARVEST_QUANTITY", "quantity must be a decimal with three fractional digits");
        }
    }

    private BatchSnapshot copyState(
            BatchSnapshot current,
            BatchState state,
            String holderId,
            String shipmentId,
            String carrierId,
            String recipientId,
            String lastEventTransactionId
    ) {
        return new BatchSnapshot(
                current.batchCode(),
                state,
                current.farmerOrganizationId(),
                holderId,
                shipmentId,
                carrierId,
                recipientId,
                lastEventTransactionId);
    }

    private BusinessRuleException invalid(String code, String message) {
        return new BusinessRuleException(code, message);
    }
}
