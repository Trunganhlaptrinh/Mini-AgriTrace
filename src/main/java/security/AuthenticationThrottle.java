package security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Short-lived, per-JVM throttle for password authentication attempts. */
public final class AuthenticationThrottle {
    private static final AuthenticationThrottle SHARED = new AuthenticationThrottle();
    private static final int MAX_ENTRIES = 20_000;
    private static final int ACCOUNT_FAILURE_LIMIT = 8;
    private static final int ADDRESS_FAILURE_LIMIT = 30;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);
    private static final Duration BLOCK_DURATION = Duration.ofSeconds(60);
    private static final Duration ENTRY_TTL = Duration.ofMinutes(15);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Clock clock;
    private final byte[] accountKey;
    private final LinkedHashMap<String, FailureState> failures = new LinkedHashMap<>(128, 0.75f, true);
    private Instant nextCleanup = Instant.MIN;

    public AuthenticationThrottle() {
        this(Clock.systemUTC(), randomKey());
    }

    public AuthenticationThrottle(Clock clock) {
        this(clock, randomKey());
    }

    AuthenticationThrottle(Clock clock, byte[] accountKey) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (accountKey == null || accountKey.length < 32) {
            throw new IllegalArgumentException("accountKey must contain at least 32 bytes");
        }
        this.accountKey = accountKey.clone();
    }

    public static AuthenticationThrottle shared() {
        return SHARED;
    }

    /** Returns zero when allowed, otherwise the number of seconds to wait. */
    public synchronized long retryAfterSeconds(String remoteAddress, String accountName) {
        Instant now = clock.instant();
        cleanupIfDue(now);
        long addressWait = secondsRemaining(failures.get(addressKey(remoteAddress)), now);
        long accountWait = secondsRemaining(failures.get(accountKey(accountName)), now);
        return Math.max(addressWait, accountWait);
    }

    public synchronized void recordFailure(String remoteAddress, String accountName) {
        Instant now = clock.instant();
        cleanupIfDue(now);
        increment(addressKey(remoteAddress), ADDRESS_FAILURE_LIMIT, now);
        increment(accountKey(accountName), ACCOUNT_FAILURE_LIMIT, now);
        trimToBound();
    }

    public synchronized void recordSuccess(String remoteAddress, String accountName) {
        Instant now = clock.instant();
        cleanupIfDue(now);
        failures.remove(accountKey(accountName));
    }

    synchronized int entryCount() {
        return failures.size();
    }

    private void increment(String key, int limit, Instant now) {
        FailureState state = failures.get(key);
        if (state == null || !now.isBefore(state.windowStarted.plus(FAILURE_WINDOW))) {
            state = new FailureState(now);
            failures.put(key, state);
        }
        state.expiresAt = now.plus(ENTRY_TTL);
        state.attempts++;
        if (state.attempts >= limit) {
            state.blockedUntil = now.plus(BLOCK_DURATION);
            state.attempts = 0;
            state.windowStarted = now;
            state.expiresAt = state.blockedUntil.plus(ENTRY_TTL);
        }
    }

    private long secondsRemaining(FailureState state, Instant now) {
        if (state == null || !now.isBefore(state.blockedUntil)) {
            return 0;
        }
        return Math.max(1, Duration.between(now, state.blockedUntil).toSeconds());
    }

    private void cleanupIfDue(Instant now) {
        if (now.isBefore(nextCleanup)) {
            return;
        }
        Iterator<Map.Entry<String, FailureState>> iterator = failures.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().expiresAt)) {
                iterator.remove();
            }
        }
        nextCleanup = now.plus(CLEANUP_INTERVAL);
    }

    private void trimToBound() {
        Iterator<String> iterator = failures.keySet().iterator();
        while (failures.size() > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private String addressKey(String remoteAddress) {
        String address = remoteAddress == null || remoteAddress.isBlank()
                ? "unknown" : remoteAddress.strip();
        return "address:" + address;
    }

    private String accountKey(String accountName) {
        String normalized = accountName == null ? "" : accountName.strip().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(accountKey, HMAC_ALGORITHM));
            return "account:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required authentication throttle algorithm is unavailable", exception);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static final class FailureState {
        private Instant windowStarted;
        private Instant blockedUntil = Instant.MIN;
        private Instant expiresAt;
        private int attempts;

        private FailureState(Instant now) {
            windowStarted = now;
            expiresAt = now.plus(ENTRY_TTL);
        }
    }
}
