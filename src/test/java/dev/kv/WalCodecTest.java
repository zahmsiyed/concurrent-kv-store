package dev.kv;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import static org.junit.jupiter.api.Assertions.*;

class WalCodecTest {
    @Test void roundTripsBinaryEmptyAndDelete() throws Exception {
        for (byte[] v : new byte[][]{new byte[0], new byte[]{0, -1, 10}, new byte[WalCodec.MAX_VALUE]}) {
            var r = WalCodec.decode(WalCodec.encode(WalCodec.PUT, "a._-1", v).array(), 8);
            assertEquals("a._-1", r.key()); assertArrayEquals(v, r.value());
        }
        assertEquals(WalCodec.DELETE, WalCodec.decode(WalCodec.encode(WalCodec.DELETE, "a", new byte[0]).array(), 8).operation());
    }
    @Test void rejectsInvalidInput() {
        for (String key : new String[]{"", "x/y", "é", "a".repeat(257)})
            assertThrows(IllegalArgumentException.class, () -> WalCodec.encode(WalCodec.PUT, key, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> WalCodec.encode(WalCodec.PUT, "a", new byte[WalCodec.MAX_VALUE + 1]));
        assertThrows(IllegalArgumentException.class, () -> WalCodec.encode(WalCodec.DELETE, "a", new byte[]{1}));
    }
    @Test void rejectsCorruptionAndInvalidFieldsEvenWithValidChecksum() {
        byte[] b = WalCodec.encode(WalCodec.PUT, "a", new byte[]{42}).array(); b[13] ^= 1;
        assertTrue(assertThrows(IOException.class, () -> WalCodec.decode(b, 123)).getMessage().contains("123"));
        for (int field : new int[]{0, 4, 5, 9, 13}) {
            byte[] bad = WalCodec.encode(WalCodec.PUT, "a", new byte[]{42}).array(); bad[field] = (byte) 0xff;
            CRC32C crc = new CRC32C(); crc.update(bad, 0, bad.length - 4);
            ByteBuffer.wrap(bad).putInt(bad.length - 4, (int) crc.getValue());
            assertThrows(IOException.class, () -> WalCodec.decode(bad, 8));
        }
    }
}
