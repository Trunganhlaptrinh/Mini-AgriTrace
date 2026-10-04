package service;

public final class ShipmentProposalNotFoundException extends RuntimeException {
    public ShipmentProposalNotFoundException(String proposalId) {
        super("Shipment proposal was not found: " + proposalId);
    }
}
