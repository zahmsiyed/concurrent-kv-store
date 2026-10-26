package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CrashRecoveryIT {
    @TempDir Path dir;
    @Test void forciblyKilledJvmRecoversAtMutationAndCompactionBoundaries() throws Exception {
        for (String point : new String[]{"before-append", "partial-append", "after-append", "after-force", "acknowledged",
                "compact-header", "compact-record", "compact-forced", "compact-before-replace", "compact-after-replace", "compact-reopened"}) {
            Path data = dir.resolve(point), marker = dir.resolve(point + ".ready"), output = dir.resolve(point + ".out");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CrashWorker.class.getName(), data.toString(), point, marker.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                    while (!Files.exists(marker)) {
                        assertTrue(child.isAlive(), () -> { try { return Files.readString(output); } catch (Exception e) { return e.toString(); } });
                        // Poll the explicit checkpoint file; elapsed time never substitutes for readiness.
                        Thread.sleep(10);
                    }
                });
            } finally { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
            try (var s = new PersistentStore(data)) {
                CompactionTest.assertState(s);
                if (point.equals("after-force") || point.equals("acknowledged")) assertArrayEquals(new byte[]{9}, s.get("unanswered").orElseThrow(), point);
                if (point.equals("before-append") || point.equals("partial-append")) assertTrue(s.get("unanswered").isEmpty(), point);
                s.put("afterRestart", new byte[]{7});
            }
            try (var s = new PersistentStore(data)) { assertArrayEquals(new byte[]{7}, s.get("afterRestart").orElseThrow()); }
        }
    }
    @Test void actualServerAcknowledgmentSurvivesForcedTermination() throws Exception {
        Path data = dir.resolve("http"), output = dir.resolve("server.out");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "target/kv-store.jar", "server", "--port", "0", "--data", data.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                while (!Files.readString(output).contains("READY port=")) { assertTrue(child.isAlive()); Thread.sleep(10); }
            });
            String text = Files.readString(output); int port = Integer.parseInt(text.substring(text.indexOf("READY port=") + 11).strip().split("\\s")[0]);
            var client = java.net.http.HttpClient.newHttpClient();
            var uri = java.net.URI.create("http://127.0.0.1:" + port + "/kv/durable");
            assertEquals(204, client.send(java.net.http.HttpRequest.newBuilder(uri).PUT(java.net.http.HttpRequest.BodyPublishers.ofString("yes")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            var gone = java.net.URI.create("http://127.0.0.1:" + port + "/kv/gone");
            assertEquals(204, client.send(java.net.http.HttpRequest.newBuilder(gone).PUT(java.net.http.HttpRequest.BodyPublishers.ofString("delete-me")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(204, client.send(java.net.http.HttpRequest.newBuilder(gone).DELETE().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
        try (var s = new PersistentStore(data)) { assertArrayEquals("yes".getBytes(java.nio.charset.StandardCharsets.UTF_8), s.get("durable").orElseThrow()); assertTrue(s.get("gone").isEmpty()); }
    }
}
