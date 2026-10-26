# Recorded smoke measurements

Measured October 25, 2026, on an Apple M4 with 16 GiB RAM, macOS, and local storage. Client and server share the same host. Storage model is unspecified. Full OS/JDK and workload details are in each raw JSON file under `smoke-2026-10-25/`.

These are short functional load runs: 64 preloaded keys, 256-byte values, 64 warm-up operations, 256 measured operations, seed 42, and force on every mutation. Each configuration uses a fresh server JVM and data directory. All 20 configurations completed with zero request or compaction errors. These samples do not establish steady-state capacity or reliable p99 estimates, especially at high concurrency. There is no throughput pass/fail threshold.

Reproduce the same workload choices:

```sh
./mvnw verify
python3 scripts/benchmark.py --operations 256 --warmup 64 --keys 64 --value-bytes 256 \
  --hardware 'Apple M4; 16 GiB RAM; macOS local filesystem; server and client on same host; storage model unspecified' \
  --output benchmarks/runs/smoke
```

Run the matrix with its larger defaults, repeat it on an otherwise idle machine, and report variability before using these results in a performance claim. Seeded requests are reproducible; timings and interleaving are not. Percentiles below cover successful key operations and use milliseconds for readability; raw JSON uses microseconds. Compaction request duration is recorded separately in JSON.

| Workload | Concurrency | Successful ops/s | p50 ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| read-only | 1 | 2583.0 | 0.36 | 0.54 | 0.72 |
| read-only | 4 | 4772.1 | 0.75 | 1.28 | 2.70 |
| read-only | 16 | 8016.4 | 1.88 | 2.91 | 3.59 |
| read-only | 64 | 5600.1 | 9.23 | 18.37 | 20.92 |
| read95-write5 | 1 | 1968.4 | 0.32 | 1.75 | 4.41 |
| read95-write5 | 4 | 2398.3 | 0.70 | 4.83 | 11.88 |
| read95-write5 | 16 | 7204.4 | 1.98 | 3.08 | 3.91 |
| read95-write5 | 64 | 5089.4 | 9.27 | 13.83 | 16.91 |
| read50-write50 | 1 | 424.9 | 1.10 | 4.86 | 6.13 |
| read50-write50 | 4 | 569.4 | 6.94 | 13.06 | 16.99 |
| read50-write50 | 16 | 543.6 | 28.21 | 39.02 | 42.78 |
| read50-write50 | 64 | 622.8 | 99.10 | 111.16 | 115.27 |
| write-only | 1 | 248.0 | 3.99 | 4.91 | 5.91 |
| write-only | 4 | 262.6 | 15.86 | 16.13 | 17.99 |
| write-only | 16 | 258.2 | 62.51 | 69.97 | 70.06 |
| write-only | 64 | 260.2 | 238.88 | 255.92 | 256.06 |
| compaction-50-50 | 1 | 452.8 | 0.93 | 4.51 | 4.62 |
| compaction-50-50 | 4 | 545.3 | 7.50 | 14.87 | 16.08 |
| compaction-50-50 | 16 | 537.4 | 28.95 | 38.82 | 39.94 |
| compaction-50-50 | 64 | 621.1 | 98.92 | 113.04 | 116.90 |
