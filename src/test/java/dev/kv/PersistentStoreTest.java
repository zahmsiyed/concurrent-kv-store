package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PersistentStoreTest {
    @TempDir Path dir;
    @Test void replayPreservesOverwritesDeletionBinaryAndDefensiveCopies() throws Exception {
        byte[] value = {0, -1, 2};
        try (var s = new PersistentStore(dir)) {
            s.put("a", value); value[0] = 9;
            byte[] copy = s.get("a").orElseThrow(); copy[0] = 8;
            assertEquals(0, s.get("a").orElseThrow()[0]);
            s.put("gone", new byte[]{1}); s.delete("gone"); s.delete("missing");
            s.put("empty", new byte[0]); s.put("again", new byte[]{1}); s.delete("again"); s.put("again", new byte[]{2});
        }
        try (var s = new PersistentStore(dir)) {
            assertArrayEquals(new byte[]{0, -1, 2}, s.get("a").orElseThrow());
            assertTrue(s.get("gone").isEmpty()); assertTrue(s.get("missing").isEmpty());
            assertArrayEquals(new byte[0], s.get("empty").orElseThrow());
            assertArrayEquals(new byte[]{2}, s.get("again").orElseThrow());
        }
    }
    @Test void repairsEveryIncompleteFinalRecordAndCanAppendAgain() throws Exception {
        byte[] first = WalCodec.encode(WalCodec.PUT, "first", new byte[]{1}).array();
        byte[] last = WalCodec.encode(WalCodec.PUT, "last", new byte[]{2, 3, 4}).array();
        for (int cut = 0; cut < last.length; cut++) {
            Path d = Files.createDirectory(dir.resolve("cut" + cut));
            byte[] log = ByteBuffer.allocate(8 + first.length + cut).put(WalCodec.HEADER).put(first).put(last, 0, cut).array();
            Files.write(d.resolve("store.wal"), log);
            try (var s = new PersistentStore(d)) {
                assertArrayEquals(new byte[]{1}, s.get("first").orElseThrow()); assertTrue(s.get("last").isEmpty());
                assertEquals(8 + first.length, Files.size(d.resolve("store.wal"))); s.put("new", new byte[]{5});
            }
            try (var s = new PersistentStore(d)) { assertArrayEquals(new byte[]{5}, s.get("new").orElseThrow()); }
        }
    }
    @Test void rejectsCorruptionWithoutChangingFileAndReleasesLock() throws Exception {
        byte[] record = WalCodec.encode(WalCodec.PUT, "a", new byte[]{4}).array();
        record[record.length - 1] ^= 1;
        byte[] log = ByteBuffer.allocate(8 + record.length).put(WalCodec.HEADER).put(record).array();
        Files.write(dir.resolve("store.wal"), log);
        assertTrue(assertThrows(IOException.class, () -> new PersistentStore(dir)).getMessage().contains("byte 8"));
        assertArrayEquals(log, Files.readAllBytes(dir.resolve("store.wal")));
        assertThrows(IOException.class, () -> new PersistentStore(dir));
    }
    @Test void rejectsInvalidHeadersAndLengths() throws Exception {
        for (byte[] b : new byte[][]{new byte[0], Arrays.copyOf(WalCodec.HEADER, 7), new byte[8],
                ByteBuffer.allocate(12).put(WalCodec.HEADER).putInt(Integer.MAX_VALUE).array()}) {
            Files.write(dir.resolve("store.wal"), b);
            assertThrows(IOException.class, () -> new PersistentStore(dir));
        }
    }
    @Test void directoryHasOnlyOneOwnerAndClosedStoreRejectsOperations() throws Exception {
        var s = new PersistentStore(dir);
        assertThrows(IOException.class, () -> new PersistentStore(dir)); s.close();
        assertThrows(IOException.class, () -> s.get("a"));
        try (var reopened = new PersistentStore(dir)) { assertTrue(reopened.healthy()); }
    }
    @Test void shortWritesAreCompletedAndForcePrecedesVisibility() throws Exception {
        StorageIO io = new StorageIO() {
            @Override public int write(FileChannel c, ByteBuffer b) throws IOException {
                int limit = b.limit(); b.limit(Math.min(limit, b.position() + 2));
                try { return c.write(b); } finally { b.limit(limit); }
            }
        };
        try (var s = new PersistentStore(dir, io)) { s.put("a", new byte[]{1, 2, 3}); }
        try (var s = new PersistentStore(dir)) { assertArrayEquals(new byte[]{1, 2, 3}, s.get("a").orElseThrow()); }
    }
    @Test void storageFailurePoisonsStore() throws Exception {
        try (var ignored = new PersistentStore(dir)) {}
        StorageIO io = new StorageIO() {
            @Override public void force(FileChannel c) throws IOException { throw new IOException("injected force failure"); }
        };
        try (var s = new PersistentStore(dir, io)) {
            assertThrows(IOException.class, () -> s.put("a", new byte[]{1})); assertFalse(s.healthy());
            assertThrows(IOException.class, () -> s.get("a")); assertThrows(IOException.class, () -> s.delete("a"));
        }
    }
}
