package dev.kv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Test-only child JVM. Never exposed as a production command or environment flag. */
public final class CrashWorker {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]), marker = Path.of(args[2]); String point = args[1];
        try (var s = new PersistentStore(dir)) { CompactionTest.seed(s); }
        StorageIO io = new StorageIO() {
            private void waitForKill() throws IOException {
                Files.writeString(marker, "checkpoint");
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            @Override public int write(FileChannel c, ByteBuffer b) throws IOException {
                if (point.equals("partial-append")) {
                    b.limit(b.position() + 7); int written = c.write(b); waitForKill(); return written;
                }
                return StorageIO.super.write(c, b);
            }
            @Override public void checkpoint(String name) throws IOException { if (name.equals(point)) waitForKill(); }
        };
        try (var s = new PersistentStore(dir, io)) {
            if (point.startsWith("compact-")) s.compact();
            else { s.put("unanswered", new byte[]{9}); Files.writeString(marker, "acknowledged"); new java.util.concurrent.CountDownLatch(1).await(); }
        }
    }
}
