package blockchain;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public final class SignatureUtil {
    private static final BigInteger P256_ORDER = new BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
    private static final int P256_COORDINATE_LENGTH = 32;

    private SignatureUtil() {
    }

    public static boolean verifyP256Sha256(
            byte[] data,
            byte[] signatureP1363,
            byte[] publicKeySpki
    ) throws GeneralSecurityException {
        requireNonNull(data, "data");
        requireNonNull(signatureP1363, "signature");
        requireNonNull(publicKeySpki, "public key");

        if (signatureP1363.length != P256_COORDINATE_LENGTH * 2) {
            throw new IllegalArgumentException("P-256 signature must contain exactly 64 bytes");
        }

        PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(publicKeySpki));
        if (!(publicKey instanceof ECKey ecKey) || !isP256(ecKey.getParams())) {
            throw new IllegalArgumentException("Public key must use the NIST P-256 curve");
        }

        byte[] derSignature = p1363ToDer(signatureP1363);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(derSignature);
    }

    public static void validateP256PublicKey(byte[] publicKeySpki) throws GeneralSecurityException {
        requireNonNull(publicKeySpki, "public key");
        PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(publicKeySpki));
        if (!(publicKey instanceof ECKey ecKey) || !isP256(ecKey.getParams())) {
            throw new IllegalArgumentException("Public key must use the NIST P-256 curve");
        }
    }

    static byte[] p1363ToDer(byte[] signatureP1363) {
        if (signatureP1363 == null || signatureP1363.length != P256_COORDINATE_LENGTH * 2) {
            throw new IllegalArgumentException("P-256 signature must contain exactly 64 bytes");
        }

        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signatureP1363, 0, P256_COORDINATE_LENGTH));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(
                signatureP1363, P256_COORDINATE_LENGTH, signatureP1363.length));
        if (r.signum() == 0 || r.compareTo(P256_ORDER) >= 0
                || s.signum() == 0 || s.compareTo(P256_ORDER) >= 0) {
            throw new IllegalArgumentException("ECDSA signature components are outside the P-256 range");
        }

        byte[] encodedR = encodeDerInteger(r);
        byte[] encodedS = encodeDerInteger(s);
        int sequenceLength = encodedR.length + encodedS.length;
        byte[] der = new byte[sequenceLength + 2];
        der[0] = 0x30;
        der[1] = (byte) sequenceLength;
        System.arraycopy(encodedR, 0, der, 2, encodedR.length);
        System.arraycopy(encodedS, 0, der, 2 + encodedR.length, encodedS.length);
        return der;
    }

    private static byte[] encodeDerInteger(BigInteger value) {
        byte[] integerBytes = value.toByteArray();
        byte[] encoded = new byte[integerBytes.length + 2];
        encoded[0] = 0x02;
        encoded[1] = (byte) integerBytes.length;
        System.arraycopy(integerBytes, 0, encoded, 2, integerBytes.length);
        return encoded;
    }

    private static boolean isP256(ECParameterSpec parameters) {
        if (parameters == null || parameters.getCurve().getField().getFieldSize() != 256) {
            return false;
        }
        try {
            AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance("EC");
            algorithmParameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = algorithmParameters.getParameterSpec(ECParameterSpec.class);
            return parameters.getOrder().equals(expected.getOrder())
                    && parameters.getCofactor() == expected.getCofactor()
                    && parameters.getGenerator().equals(expected.getGenerator())
                    && parameters.getCurve().equals(expected.getCurve());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 curve parameters are not available", exception);
        }
    }

    private static void requireNonNull(byte[] value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
