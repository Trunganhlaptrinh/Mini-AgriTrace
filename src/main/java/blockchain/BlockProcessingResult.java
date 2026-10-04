package blockchain;

public record BlockProcessingResult(
        BlockValidationResult validation,
        BlockRepository.StoreResult persistenceResult,
        ChainSnapshot canonicalState
) {
    public BlockProcessingResult {
        if (validation == null || persistenceResult == null || canonicalState == null) {
            throw new IllegalArgumentException("Validation, persistence result, and canonical state are required");
        }
    }
}
