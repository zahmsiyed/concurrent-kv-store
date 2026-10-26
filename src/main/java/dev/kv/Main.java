package dev.kv;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) { usage(); return; }
        Map<String, String> options = options(args);
        if (!args[0].equals("server")) throw new IllegalArgumentException("unknown command: " + args[0]);
        rejectUnknown(options, "host", "port", "data");
        PersistentStore store = new PersistentStore(Path.of(options.getOrDefault("data", "./data")));
        HttpApi api;
        try { api = new HttpApi(store, options.getOrDefault("host", "127.0.0.1"), Integer.parseInt(options.getOrDefault("port", "8080"))); }
        catch (Exception e) { store.close(); throw e; }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close(); try { store.close(); } catch (Exception e) { e.printStackTrace(System.err); }
        }, "kv-shutdown"));
        api.start(); System.out.println("READY port=" + api.port()); System.out.flush();
        new CountDownLatch(1).await();
    }
    static Map<String, String> options(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("expected --option value");
            if (result.put(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException("duplicate option " + args[i]);
        }
        return result;
    }
    static void rejectUnknown(Map<String, String> options, String... known) {
        for (String key : options.keySet()) if (!java.util.Arrays.asList(known).contains(key)) throw new IllegalArgumentException("unknown option --" + key);
    }
    private static void usage() {
        System.out.println("server [--host 127.0.0.1] [--port 8080] [--data ./data]");
    }
}
