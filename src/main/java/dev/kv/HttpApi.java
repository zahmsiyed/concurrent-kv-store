package dev.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpApi implements AutoCloseable {
    private final PersistentStore store;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HttpApi(PersistentStore store, String host, int port) throws IOException {
        this.store = store;
        server = HttpServer.create(new InetSocketAddress(host, port), 128);
        server.setExecutor(executor); server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
            if (path.equals("/health")) {
                if (!method.equals("GET")) { methodNotAllowed(x, "GET"); return; }
                send(x, store.healthy() ? 200 : 503, store.healthy() ? "ready" : "storage unavailable"); return;
            }
            if (path.equals("/admin/compact")) {
                if (!method.equals("POST")) { methodNotAllowed(x, "POST"); return; }
                store.compact(); send(x, 204, ""); return;
            }
            if (!path.startsWith("/kv/")) { send(x, 404, "not found"); return; }
            String key = path.substring(4); WalCodec.validateKey(key);
            switch (method) {
                case "GET" -> {
                    var value = store.get(key);
                    if (value.isEmpty()) send(x, 404, "not found");
                    else {
                        byte[] bytes = value.orElseThrow();
                        x.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        x.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
                        x.getResponseBody().write(bytes);
                    }
                }
                case "PUT" -> {
                    byte[] value = x.getRequestBody().readNBytes(WalCodec.MAX_VALUE + 1);
                    if (value.length > WalCodec.MAX_VALUE) send(x, 413, "value exceeds 1 MiB");
                    else { store.put(key, value); send(x, 204, ""); }
                }
                case "DELETE" -> { store.delete(key); send(x, 204, ""); }
                default -> methodNotAllowed(x, "GET, PUT, DELETE");
            }
        } catch (IllegalArgumentException e) { send(x, 400, e.getMessage()); }
        catch (IOException e) {
            // A client disconnect does not poison storage; storage itself owns that decision.
            if (!store.healthy()) send(x, 503, "storage unavailable; restart required");
            else throw e;
        } finally { x.close(); }
    }
    private static void methodNotAllowed(HttpExchange x, String methods) throws IOException {
        x.getResponseHeaders().set("Allow", methods); send(x, 405, "method not allowed");
    }
    private static void send(HttpExchange x, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
        if (status != 204 && bytes.length > 0) x.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(1); executor.close(); }
}
