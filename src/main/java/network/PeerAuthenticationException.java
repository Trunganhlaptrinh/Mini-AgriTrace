package network;

public final class PeerAuthenticationException extends RuntimeException {
    public PeerAuthenticationException(String message) {
        super(message);
    }

    public PeerAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
