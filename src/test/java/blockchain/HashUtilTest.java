package blockchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HashUtilTest {
    @Test
    void computesLowercaseSha256HexForUtf8Text() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtil.sha256Hex("abc"));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> HashUtil.sha256((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.sha256Hex((String) null));
    }
}
