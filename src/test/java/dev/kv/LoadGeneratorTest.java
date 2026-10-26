package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoadGeneratorTest {
    @TempDir Path dir;
    @Test void producesCompleteReportsAndCompactsUnderLoad() throws Exception {
        try (var s = new PersistentStore(dir.resolve("data")); var api = new HttpApi(s, "127.0.0.1", 0)) {
            api.start(); Path output = dir.resolve("result.json");
            var config = LoadGenerator.Config.parse(Map.of("url", "http://127.0.0.1:" + api.port(), "concurrency", "4", "operations", "40",
                    "warmup", "8", "keys", "4", "writes", "50", "compact", "true", "output", output.toString()));
            var result = new LoadGenerator(config).run();
            assertEquals(0, result.errors()); assertEquals(result.json(), Files.readString(output));
            assertTrue(result.json().contains("\"successfulOperations\":40"));
            assertTrue(result.json().contains("\"compaction\":{\"attempted\":true,\"errors\":0"));
            assertTrue(result.json().contains("\"p99\":"));
        }
    }
    @Test void handlesReadOnlyWriteOnlyEmptyValuesAndNoWarmup() throws Exception {
        try (var s = new PersistentStore(dir); var api = new HttpApi(s, "127.0.0.1", 0)) {
            api.start();
            for (int writes : new int[]{0, 100}) {
                var c = LoadGenerator.Config.parse(Map.of("url", "http://127.0.0.1:" + api.port(), "concurrency", "1", "operations", "4",
                        "warmup", "0", "keys", "1", "value-bytes", "0", "writes", Integer.toString(writes)));
                var result = new LoadGenerator(c).run(); assertEquals(0, result.errors());
                assertTrue(result.json().contains("\"successfulOperations\":4"));
                assertTrue(result.json().contains("\"count\":0,\"p50\":null"));
            }
        }
    }
    @Test void validatesConfigurationAndPercentiles() {
        for (var invalid : java.util.List.of(Map.of("writes", "101"), Map.of("concurrency", "0"), Map.of("keys", "0"),
                Map.of("url", "file:///tmp/data"), Map.of("url", "http://localhost/path"), Map.of("compact", "yes"), Map.of("unknown", "x")))
            assertThrows(IllegalArgumentException.class, () -> LoadGenerator.Config.parse(invalid));
        long[] sorted = java.util.stream.LongStream.rangeClosed(1, 100).toArray();
        assertEquals(50, LoadGenerator.percentile(sorted, .50)); assertEquals(95, LoadGenerator.percentile(sorted, .95)); assertEquals(99, LoadGenerator.percentile(sorted, .99));
        assertEquals("\"a\\\"\\\\\\u000a\"", LoadGenerator.quote("a\"\\\n"));
    }
}
