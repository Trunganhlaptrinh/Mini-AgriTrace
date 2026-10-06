package security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationThrottleTest {
    private static final byte[] TEST_KEY = new byte[32];

    @Test
    void throttlesRepeatedFailuresForNormalizedAccountWithoutPermanentLockout() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthenticationThrottle throttle = new AuthenticationThrottle(clock, TEST_KEY);
        for (int attempt = 0; attempt < 7; attempt++) {
            throttle.recordFailure("192.0.2." + attempt, "  Farmer.One ");
        }
        assertEquals(0, throttle.retryAfterSeconds("192.0.2.99", "farmer.one"));

        throttle.recordFailure("192.0.2.99", "farmer.one");
        assertEquals(60, throttle.retryAfterSeconds("192.0.2.100", "FARMER.ONE"));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, throttle.retryAfterSeconds("192.0.2.100", "farmer.one"));
    }

    @Test
    void throttlesAddressAcrossManyAccountNames() {
        AuthenticationThrottle throttle = new AuthenticationThrottle(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), TEST_KEY);
        for (int attempt = 0; attempt < 30; attempt++) {
            throttle.recordFailure("192.0.2.5", "user-" + attempt);
        }
        assertEquals(60, throttle.retryAfterSeconds("192.0.2.5", "another-user"));

        assertEquals(60, throttle.retryAfterSeconds("192.0.2.5", "another-user"));
    }

    @Test
    void successfulAuthenticationClearsThatAccountFailures() {
        AuthenticationThrottle throttle = new AuthenticationThrottle(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), TEST_KEY);
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("192.0.2." + attempt, "farmer.one");
        }
        assertEquals(60, throttle.retryAfterSeconds("192.0.2.20", "farmer.one"));

        throttle.recordSuccess("192.0.2.20", "farmer.one");
        assertEquals(0, throttle.retryAfterSeconds("192.0.2.20", "farmer.one"));
    }

    @Test
    void expiresStaleEntriesAndBoundsStorage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthenticationThrottle throttle = new AuthenticationThrottle(clock, TEST_KEY);
        throttle.recordFailure("192.0.2.1", "stale-user");
        clock.advance(Duration.ofMinutes(16));
        assertEquals(0, throttle.retryAfterSeconds("192.0.2.2", "new-user"));
        assertEquals(0, throttle.entryCount());

        for (int index = 0; index < 20_100; index++) {
            throttle.recordFailure("198.51.100.1", "user-" + index);
        }
        assertTrue(throttle.entryCount() <= 20_000);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
