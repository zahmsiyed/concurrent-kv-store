package dev.kv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Version 1: big endian; total record length includes its own four bytes and CRC. */
final class WalCodec {
    static final int MAX_VALUE = 1024 * 1024;
    static final int MAX_KEY = 256;
    static final int MIN_RECORD = 18; // length + op + keyLen + valueLen + one key byte + crc
    static final int MAX_RECORD = 17 + MAX_KEY + MAX_VALUE;
    static final byte PUT = 1, DELETE = 2;
    static final byte[] HEADER = ByteBuffer.allocate(8).putInt(0x4b56574c).putInt(1).array();

    record Record(byte operation, String key, byte[] value) {}

    static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,256}"))
            throw new IllegalArgumentException("key must match [A-Za-z0-9._-]{1,256}");
    }

    static ByteBuffer encode(byte operation, String key, byte[] value) {
        validateKey(key);
        if (value == null || value.length > MAX_VALUE) throw new IllegalArgumentException("value exceeds 1 MiB or is null");
        if (operation != PUT && operation != DELETE || operation == DELETE && value.length != 0)
            throw new IllegalArgumentException("invalid operation");
        byte[] k = key.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(17 + k.length + value.length);
        b.putInt(b.capacity()).put(operation).putInt(k.length).putInt(value.length).put(k).put(value);
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 0, b.position());
        b.putInt((int) crc.getValue()).flip();
        return b;
    }

    static Record decode(byte[] bytes, long offset) throws IOException {
        if (bytes.length < MIN_RECORD || bytes.length > MAX_RECORD) throw corrupt(offset, "invalid record size");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int length = b.getInt();
        byte op = b.get();
        int keyLen = b.getInt(), valueLen = b.getInt();
        if (length != bytes.length || keyLen < 1 || keyLen > MAX_KEY || valueLen < 0 || valueLen > MAX_VALUE
                || 17L + keyLen + valueLen != length || op != PUT && op != DELETE || op == DELETE && valueLen != 0)
            throw corrupt(offset, "invalid fields");
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt())
            throw corrupt(offset, "checksum mismatch");
        // ASCII validation on raw bytes avoids silently replacing malformed input.
        byte[] k = Arrays.copyOfRange(bytes, 13, 13 + keyLen);
        for (byte c : k) if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '.' || c == '_' || c == '-')) throw corrupt(offset, "invalid key");
        return new Record(op, new String(k, StandardCharsets.US_ASCII), Arrays.copyOfRange(bytes, 13 + keyLen, bytes.length - 4));
    }

    static IOException corrupt(long offset, String message) {
        return new IOException("WAL corruption at byte " + offset + ": " + message);
    }
}
