package service;

import java.util.List;
import java.util.Optional;
import model.ShipmentProposal;
import model.SignatureEnvelope;

public interface ShipmentProposalRepository {
    void insert(ShipmentProposal proposal);

    Optional<ShipmentProposal> find(String proposalId);

    List<ShipmentProposal> findUnexpiredForCarrier(String carrierOrganizationId);

    ShipmentProposal endorse(String proposalId, SignatureEnvelope carrierSignature);

    void markSubmitted(String proposalId, String transactionId);

    void markExpired(String proposalId);
}
