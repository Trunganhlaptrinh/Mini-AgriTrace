package blockchain;

public final class BlockValidationException extends RuntimeException {
    private final String code;

    public BlockValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BlockValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
