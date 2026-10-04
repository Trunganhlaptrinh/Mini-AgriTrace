package service;

import blockchain.GovernanceValidationException;
import dal.TransactionDAO;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import config.NetworkConfiguration;
import model.GovernanceTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GovernanceServiceTest {
    private static final String ADMIN_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7nX/JA2YfGSysJeqenLw9b6h4pRQ3rKfymHiJ0UTffY0PMjTnaQjoHdPZaOSVspa66K6W/IoajGhgb0/JTK18Q==";
    private static final String ORGANIZATION_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEM9TzrY9EMvcgIYeFEtB+dn+C5cBj1Y+Tv207DRZlqT2GcHwWDk0D16VbO4V4sgCWpo2MfDuopazC07eF2cH6aQ==";
    private static final String VALID_SIGNATURE =
            "DU2M7TtvlXdkvTZZR8wqFmYBfkNA0NNvwcYO+3AWi6emkELCH2ZxwDjzGeK+5R+aD1Oe0ezJQca9BS/9SjIOwQ==";

    @Test
    void verifiesAndSubmitsTheCanonicalOrganizationRegistration() {
        AtomicReference<GovernanceTransaction> submitted = new AtomicReference<>();
        GovernanceService service = new GovernanceService(configuration(), transaction -> {
            submitted.set(transaction);
            return TransactionDAO.SubmissionResult.INSERTED;
        });

        GovernanceService.RegistrationResult result = service.registerOrganization(
                "11111111-2222-4333-8444-555555555555",
                Instant.parse("2026-10-04T10:00:00.000Z"),
                "org-farm-001",
                "FARMER",
                "Mekong Mango Farm",
                "Tien Giang",
                "org-farm-001-key-1",
                "ECDSA_P256_SHA256",
                ORGANIZATION_PUBLIC_KEY,
                VALID_SIGNATURE);

        assertEquals("9540f5491f4713aa114503259e6ad56d516ce49084c8bc40da7a5329bc07d09f",
                result.transactionId());
        assertEquals(TransactionDAO.SubmissionResult.INSERTED, result.submissionResult());
        assertEquals(result.transactionId(), submitted.get().transactionId());
        assertEquals("Tien Giang", submitted.get().data().get("province"));
    }

    @Test
    void rejectsSignatureThatDoesNotCoverTheRegistrationPayload() {
        AtomicReference<GovernanceTransaction> submitted = new AtomicReference<>();
        GovernanceService service = new GovernanceService(configuration(), transaction -> {
            submitted.set(transaction);
            return TransactionDAO.SubmissionResult.INSERTED;
        });

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> service.registerOrganization(
                        "11111111-2222-4333-8444-555555555555",
                        Instant.parse("2026-10-04T10:00:00.000Z"),
                        "org-farm-001",
                        "FARMER",
                        "Tampered farm name",
                        "Tien Giang",
                        "org-farm-001-key-1",
                        "ECDSA_P256_SHA256",
                        ORGANIZATION_PUBLIC_KEY,
                        VALID_SIGNATURE));

        assertEquals("INVALID_ADMIN_SIGNATURE", exception.getCode());
        assertNull(submitted.get());
    }

    private NetworkConfiguration configuration() {
        return new NetworkConfiguration(
                "agritrace-test",
                "0".repeat(64),
                1,
                Instant.parse("2026-10-04T10:00:00.000Z"),
                0,
                ADMIN_PUBLIC_KEY);
    }
}
