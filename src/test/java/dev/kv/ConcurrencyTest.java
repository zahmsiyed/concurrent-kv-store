package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {
    @TempDir Path dir;
    @Test void readerCannotObserveAnUnforcedMutation() throws Exception {
        var appended = new CountDownLatch(1); var release = new CountDownLatch(1);
        StorageIO io = new StorageIO() {
            @Override public void checkpoint(String name) throws java.io.IOException {
                if (name.equals("after-append")) {
                    appended.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                }
            }
        };
        try (var s = new PersistentStore(dir, io); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> { s.put("same", new byte[]{1}); return null; });
            try {
                assertTrue(appended.await(5, TimeUnit.SECONDS));
                var started = new CountDownLatch(1);
                var reader = pool.submit(() -> { started.countDown(); return s.get("same"); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                release.countDown(); writer.get(5, TimeUnit.SECONDS);
                assertArrayEquals(new byte[]{1}, reader.get(5, TimeUnit.SECONDS).orElseThrow());
                var deletion = pool.submit(() -> { s.delete("same"); return null; }); deletion.get(5, TimeUnit.SECONDS);
                assertTrue(pool.submit(() -> s.get("same")).get(5, TimeUnit.SECONDS).isEmpty());
            } finally { release.countDown(); }
        }
    }
}
