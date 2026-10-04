package network;

import model.ShipmentProposal;

@FunctionalInterface
public interface ShipmentProposalRelayer {
    void relay(ShipmentProposal proposal);
}
