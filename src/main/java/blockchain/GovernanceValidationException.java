package blockchain;

public final class GovernanceValidationException extends RuntimeException {
    private final String code;

    public GovernanceValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public GovernanceValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
