package dev.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {
    @TempDir Path dir;
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpResponse<byte[]> request(int port, String method, String path, byte[] value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(value)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void httpContract() throws Exception {
        try (var s = new PersistentStore(dir); var api = new HttpApi(s, "127.0.0.1", 0)) {
            api.start(); int p = api.port();
            assertEquals(200, request(p, "GET", "/health", new byte[0]).statusCode());
            assertEquals(404, request(p, "GET", "/kv/a", new byte[0]).statusCode());
            byte[] binary = {0, -1, 3};
            assertEquals(204, request(p, "PUT", "/kv/a", binary).statusCode());
            var response = request(p, "GET", "/kv/a", new byte[0]);
            assertEquals(200, response.statusCode()); assertArrayEquals(binary, response.body());
            assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(204, request(p, "PUT", "/kv/empty", new byte[0]).statusCode());
            var empty = request(p, "GET", "/kv/empty", new byte[0]); assertEquals(200, empty.statusCode()); assertEquals(0, empty.body().length);
            assertEquals(204, request(p, "DELETE", "/kv/a", new byte[0]).statusCode());
            assertEquals(204, request(p, "DELETE", "/kv/a", new byte[0]).statusCode());
            assertEquals(404, request(p, "GET", "/kv/a", new byte[0]).statusCode());
            for (String bad : List.of("", "x/y", "a%20b", "a".repeat(257)))
                assertEquals(400, request(p, "GET", "/kv/" + bad, new byte[0]).statusCode());
            assertEquals(413, request(p, "PUT", "/kv/a", new byte[WalCodec.MAX_VALUE + 1]).statusCode());
            assertEquals(405, request(p, "POST", "/kv/a", new byte[0]).statusCode());
            assertEquals(405, request(p, "PUT", "/health", new byte[0]).statusCode());
            assertEquals(404, request(p, "GET", "/unknown", new byte[0]).statusCode());
        }
    }
    @Test void concurrentHttpWritesHaveRecoverableIndependentKeys() throws Exception {
        try (var s = new PersistentStore(dir); var api = new HttpApi(s, "127.0.0.1", 0); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            api.start(); var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int w = worker;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 12; i++) {
                        byte[] value = {(byte) i}; String path = "/kv/key" + w;
                        assertEquals(204, request(api.port(), "PUT", path, value).statusCode());
                        assertArrayEquals(value, request(api.port(), "GET", path, new byte[0]).body());
                    }
                    return null;
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        }
        try (var s = new PersistentStore(dir)) {
            for (int w = 0; w < 8; w++) assertArrayEquals(new byte[]{11}, s.get("key" + w).orElseThrow());
        }
    }
    @Test void storageFailureReturnsUnavailable() throws Exception {
        try (var s = new PersistentStore(dir); var api = new HttpApi(s, "127.0.0.1", 0)) {
            api.start(); s.close();
            assertEquals(503, request(api.port(), "GET", "/health", new byte[0]).statusCode());
            assertEquals(503, request(api.port(), "PUT", "/kv/a", new byte[0]).statusCode());
        }
    }
}
