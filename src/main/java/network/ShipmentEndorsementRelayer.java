package network;

import model.BatchEvent;
import model.ShipmentProposal;

@FunctionalInterface
public interface ShipmentEndorsementRelayer {
    void relay(ShipmentProposal proposal, BatchEvent signedEvent);
}
