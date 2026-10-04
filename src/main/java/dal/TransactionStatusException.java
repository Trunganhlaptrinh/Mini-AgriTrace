package dal;

public final class TransactionStatusException extends RuntimeException {
    private final String code;

    public TransactionStatusException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TransactionStatusException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
