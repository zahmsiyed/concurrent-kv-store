package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {
    @TempDir Path dir;
    static void seed(PersistentStore s) throws IOException {
        s.put("keep", new byte[]{1}); s.put("keep", new byte[]{2});
        s.put("gone", new byte[]{3}); s.delete("gone"); s.delete("absent");
    }
    static void assertState(PersistentStore s) throws IOException {
        assertArrayEquals(new byte[]{2}, s.get("keep").orElseThrow()); assertTrue(s.get("gone").isEmpty()); assertTrue(s.get("absent").isEmpty());
    }
    @Test void compactionShrinksLogAndSupportsFurtherMutations() throws Exception {
        try (var s = new PersistentStore(dir)) {
            seed(s); var result = s.compact(); assertTrue(result.afterBytes() < result.beforeBytes()); assertState(s);
            long size = result.afterBytes(); assertEquals(size, s.compact().afterBytes()); s.put("new", new byte[]{4});
        }
        try (var s = new PersistentStore(dir)) { assertState(s); assertArrayEquals(new byte[]{4}, s.get("new").orElseThrow()); }
    }
    @Test void emptyStoreCompactsToHeader() throws Exception {
        try (var s = new PersistentStore(dir)) { s.put("a", new byte[0]); s.delete("a"); assertEquals(8, s.compact().afterBytes()); }
        try (var s = new PersistentStore(dir)) { assertTrue(s.get("a").isEmpty()); }
    }
    @Test void injectedFailuresAtEveryCompactionBoundaryRecoverCanonicalState() throws Exception {
        for (String point : new String[]{"compact-header", "compact-record", "compact-forced", "compact-before-replace", "compact-after-replace", "compact-reopened"}) {
            Path d = dir.resolve(point);
            StorageIO io = new StorageIO() {
                @Override public void checkpoint(String name) throws IOException { if (name.equals(point)) throw new IOException("injected " + point); }
            };
            try (var s = new PersistentStore(d, io)) { seed(s); assertThrows(IOException.class, s::compact); assertFalse(s.healthy()); assertThrows(IOException.class, () -> s.put("x", new byte[0])); }
            try (var s = new PersistentStore(d)) { assertState(s); assertFalse(Files.exists(d.resolve("store.wal.tmp"))); s.put("next", new byte[0]); }
        }
    }
    @Test void rejectsNonAtomicMoveAndReopenFailureThenRecovers() throws Exception {
        for (boolean failMove : new boolean[]{true, false}) {
            AtomicBoolean replaced = new AtomicBoolean();
            StorageIO io = new StorageIO() {
                @Override public void replace(Path tmp, Path log) throws IOException {
                    if (failMove) throw new AtomicMoveNotSupportedException(tmp.toString(), log.toString(), "injected");
                    StorageIO.super.replace(tmp, log); replaced.set(true);
                }
                @Override public FileChannel open(Path path) throws IOException {
                    if (replaced.get()) throw new IOException("injected reopen failure");
                    return StorageIO.super.open(path);
                }
            };
            Path d = dir.resolve("move" + failMove);
            try (var s = new PersistentStore(d, io)) { seed(s); assertThrows(IOException.class, s::compact); assertFalse(s.healthy()); }
            try (var s = new PersistentStore(d)) { assertState(s); }
        }
    }
    @Test void partialAppendAndCompactionWriteFailuresRecover() throws Exception {
        for (boolean compact : new boolean[]{false, true}) {
            Path d = dir.resolve("partial" + compact);
            try (var s = new PersistentStore(d)) { seed(s); }
            StorageIO io = new StorageIO() {
                @Override public int write(FileChannel c, ByteBuffer b) throws IOException {
                    int limit = b.limit(); b.limit(b.position() + 2); c.write(b); b.limit(limit);
                    throw new IOException("injected after partial write");
                }
            };
            try (var s = new PersistentStore(d, io)) {
                if (compact) assertThrows(IOException.class, s::compact);
                else assertThrows(IOException.class, () -> s.put("new", new byte[]{1}));
                assertFalse(s.healthy());
            }
            try (var s = new PersistentStore(d)) { assertState(s); assertTrue(s.get("new").isEmpty()); }
        }
    }
    @Test void compactionForceFailureLeavesOldLogAuthoritative() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        StorageIO io = new StorageIO() {
            @Override public void force(FileChannel c) throws IOException {
                if (armed.get()) throw new IOException("injected temporary log force failure");
                StorageIO.super.force(c);
            }
        };
        try (var s = new PersistentStore(dir, io)) {
            seed(s); armed.set(true); assertThrows(IOException.class, s::compact); assertFalse(s.healthy());
        }
        try (var s = new PersistentStore(dir)) { assertState(s); }
    }
    @Test void staleTemporaryFileIsNeverPromoted() throws Exception {
        Files.write(dir.resolve("store.wal.tmp"), new byte[]{1, 2, 3});
        try (var s = new PersistentStore(dir)) { assertTrue(s.get("anything").isEmpty()); assertFalse(Files.exists(dir.resolve("store.wal.tmp"))); }
    }
}
