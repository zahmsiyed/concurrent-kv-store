package dev.kv;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Closed-loop HTTP driver: fixed seeded operations per worker, no coordinated-omission correction. */
final class LoadGenerator {
    record Config(URI base, int concurrency, int operations, int warmup, int keys, int valueBytes,
                  int writes, long seed, int timeoutMs, boolean compact, String output, String hardware) {
        static Config parse(Map<String, String> o) {
            Main.rejectUnknown(o, "url", "concurrency", "operations", "warmup", "keys", "value-bytes", "writes", "seed", "timeout-ms", "compact", "output", "hardware");
            URI base = URI.create(o.getOrDefault("url", "http://127.0.0.1:8080"));
            if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getRawQuery() != null
                    || base.getRawFragment() != null || base.getUserInfo() != null || !(base.getPath().isEmpty() || base.getPath().equals("/")))
                throw new IllegalArgumentException("url must be an HTTP(S) origin");
            String compact = o.getOrDefault("compact", "false");
            if (!compact.equals("true") && !compact.equals("false")) throw new IllegalArgumentException("compact must be true or false");
            int operations = number(o, "operations", 10000, 1, 1000000);
            return new Config(URI.create(base.toString().replaceAll("/$", "")), number(o, "concurrency", 16, 1, Math.min(1024, operations)),
                    operations, number(o, "warmup", 1000, 0, 1000000), number(o, "keys", 1000, 1, 1000000),
                    number(o, "value-bytes", 256, 0, WalCodec.MAX_VALUE), number(o, "writes", 5, 0, 100),
                    Long.parseLong(o.getOrDefault("seed", "42")), number(o, "timeout-ms", 10000, 1, 300000),
                    Boolean.parseBoolean(compact), o.get("output"), o.getOrDefault("hardware", "unspecified; supply --hardware for reproducibility"));
        }
        private static int number(Map<String, String> o, String name, int fallback, int min, int max) {
            int n = Integer.parseInt(o.getOrDefault(name, Integer.toString(fallback)));
            if (n < min || n > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return n;
        }
    }
    record SampleSet(long[] reads, long[] writes, int readCount, int writeCount, long errors) {}
    record Result(String json, long errors) {}
    private final Config config;
    private final HttpClient client;
    private final byte[] value;

    LoadGenerator(Config config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs())).version(HttpClient.Version.HTTP_1_1).build();
        value = new byte[config.valueBytes()]; new Random(config.seed()).nextBytes(value);
    }

    Result run() throws Exception {
        try (client) {
            for (int i = 0; i < config.keys(); i++) {
                var response = client.send(request(true, i), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 204) throw new IOException("preload failed: HTTP " + response.statusCode());
            }
            execute(config.warmup(), false);
            Batch batch = execute(config.operations(), true);
            // Batch elapsed excludes thread setup; includes synchronous compaction and every attempted request.
            double seconds = batch.elapsedNanos() / 1e9;
            long[] reads = merge(batch.samples(), false), writes = merge(batch.samples(), true);
            long[] all = new long[reads.length + writes.length];
            System.arraycopy(reads, 0, all, 0, reads.length); System.arraycopy(writes, 0, all, reads.length, writes.length);
            long errors = batch.samples().stream().mapToLong(SampleSet::errors).sum();
            String json = String.format(Locale.ROOT, """
                    {
                      "schemaVersion":1,
                      "timestamp":%s,
                      "mode":"closed-loop",
                      "durability":"force-each-write; process-crash guarantee",
                      "workload":{"url":%s,"seed":%d,"concurrency":%d,"operations":%d,"warmup":%d,"keys":%d,"valueBytes":%d,"writePercent":%d,"timeoutMs":%d,"compact":%s},
                      "environment":{"jdk":%s,"os":%s,"arch":%s,"processors":%d,"hardware":%s},
                      "elapsedSeconds":%.6f,
                      "successfulOperations":%d,
                      "errors":%d,
                      "throughputOpsPerSecond":%.3f,
                      "latencyMicros":%s,
                      "reads":%s,
                      "writes":%s,
                      "compaction":{"attempted":%s,"errors":%d,"durationMicros":%.3f}
                    }
                    """, quote(java.time.Instant.now().toString()), quote(config.base().toString()), config.seed(), config.concurrency(),
                    config.operations(), config.warmup(), config.keys(), config.valueBytes(), config.writes(), config.timeoutMs(), config.compact(),
                    quote(System.getProperty("java.runtime.version")), quote(System.getProperty("os.name") + " " + System.getProperty("os.version")),
                    quote(System.getProperty("os.arch")), Runtime.getRuntime().availableProcessors(), quote(config.hardware()),
                    seconds, all.length, errors, all.length / seconds, stats(all), stats(reads), stats(writes), config.compact(), batch.compactErrors(), batch.compactNanos() / 1000.0);
            if (config.output() != null) {
                Path path = Path.of(config.output());
                if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
            }
            return new Result(json, errors + batch.compactErrors());
        }
    }
    private record Batch(List<SampleSet> samples, long elapsedNanos, long compactErrors, long compactNanos) {}
    private Batch execute(int operations, boolean measured) throws Exception {
        var ready = new CountDownLatch(config.concurrency()); var go = new CountDownLatch(1);
        var completed = new AtomicLong(); var compactErrors = new AtomicLong(); var compactNanos = new AtomicLong();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SampleSet>> futures = new ArrayList<>();
            for (int worker = 0; worker < config.concurrency(); worker++) {
                int count = operations / config.concurrency() + (worker < operations % config.concurrency() ? 1 : 0), id = worker;
                futures.add(pool.submit(() -> {
                    long[] reads = new long[count], writes = new long[count]; int r = 0, w = 0; long errors = 0;
                    var random = new SplittableRandom(config.seed() + 0x9e3779b97f4a7c15L * id + (measured ? 0 : 1));
                    ready.countDown(); go.await();
                    for (int i = 0; i < count; i++) {
                        boolean write = random.nextInt(100) < config.writes(); int key = random.nextInt(config.keys());
                        HttpRequest request = request(write, key); long start = System.nanoTime();
                        try {
                            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                            long elapsed = System.nanoTime() - start;
                            if (response.statusCode() != (write ? 204 : 200) || !write && !Arrays.equals(value, response.body())) errors++;
                            else if (write) writes[w++] = elapsed; else reads[r++] = elapsed;
                        } catch (IOException e) { errors++; }
                        if (measured && config.compact() && completed.incrementAndGet() == Math.max(1, operations / 2)) {
                            long compactStart = System.nanoTime();
                            try {
                                var response = client.send(HttpRequest.newBuilder(URI.create(config.base() + "/admin/compact"))
                                        .timeout(Duration.ofMillis(config.timeoutMs())).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                                if (response.statusCode() != 204) compactErrors.incrementAndGet();
                            } catch (IOException e) { compactErrors.incrementAndGet(); }
                            compactNanos.set(System.nanoTime() - compactStart);
                        }
                    }
                    return new SampleSet(reads, writes, r, w, errors);
                }));
            }
            ready.await(); long start = System.nanoTime(); go.countDown();
            List<SampleSet> samples = new ArrayList<>();
            for (var future : futures) samples.add(future.get());
            long elapsed = System.nanoTime() - start;
            if (!measured && samples.stream().anyMatch(s -> s.errors() != 0)) throw new IOException("warmup had errors");
            return new Batch(samples, elapsed, compactErrors.get(), compactNanos.get());
        }
    }
    private HttpRequest request(boolean write, int key) {
        var builder = HttpRequest.newBuilder(URI.create(config.base() + "/kv/load-" + key)).timeout(Duration.ofMillis(config.timeoutMs()));
        return write ? builder.PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build() : builder.GET().build();
    }
    private static long[] merge(List<SampleSet> samples, boolean writes) {
        int size = samples.stream().mapToInt(s -> writes ? s.writeCount() : s.readCount()).sum();
        long[] merged = new long[size]; int offset = 0;
        for (var s : samples) {
            int n = writes ? s.writeCount() : s.readCount(); System.arraycopy(writes ? s.writes() : s.reads(), 0, merged, offset, n); offset += n;
        }
        return merged;
    }
    static long percentile(long[] sorted, double fraction) { return sorted[(int) Math.ceil(fraction * sorted.length) - 1]; }
    private static String stats(long[] nanos) {
        if (nanos.length == 0) return "{\"count\":0,\"p50\":null,\"p95\":null,\"p99\":null}";
        Arrays.sort(nanos);
        return String.format(Locale.ROOT, "{\"count\":%d,\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f}",
                nanos.length, percentile(nanos, .50) / 1000.0, percentile(nanos, .95) / 1000.0, percentile(nanos, .99) / 1000.0);
    }
    static String quote(String s) {
        var b = new StringBuilder("\"");
        for (char c : s.toCharArray()) switch (c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
            default -> { if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
}
