package model;

import java.time.Instant;
import java.util.Objects;

public record ShipmentProposal(
        String proposalId,
        BatchEvent event,
        Instant expiresAt,
        Status status,
        String submittedTransactionId
) {
    public ShipmentProposal {
        requireText(proposalId, "proposalId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if (event.eventType() != EventType.SHIPPED) {
            throw new IllegalArgumentException("shipment proposal must contain a SHIPPED event");
        }
        if (status == Status.AWAITING_CARRIER && event.signatures().size() != 1) {
            throw new IllegalArgumentException("awaiting proposal must contain only the sender signature");
        }
        if (status == Status.READY || status == Status.SUBMITTED) {
            if (event.signatures().size() != 2) {
                throw new IllegalArgumentException("ready proposal must contain sender and carrier signatures");
            }
        }
        if ((status == Status.SUBMITTED) != (submittedTransactionId != null)) {
            throw new IllegalArgumentException("submitted status and transaction ID must be consistent");
        }
        if (submittedTransactionId != null) {
            requireText(submittedTransactionId, "submittedTransactionId");
        }
    }

    public enum Status {
        AWAITING_CARRIER,
        READY,
        EXPIRED,
        SUBMITTED
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
