package blockchain;

public final class TransactionValidationException extends RuntimeException {
    private final String code;

    public TransactionValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TransactionValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
