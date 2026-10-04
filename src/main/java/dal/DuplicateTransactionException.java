package dal;

public final class DuplicateTransactionException extends RuntimeException {
    private final String code;

    public DuplicateTransactionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
