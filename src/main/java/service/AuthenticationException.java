package service;

public final class AuthenticationException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public AuthenticationException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
