# Concurrent Key-Value Store

A Java 21 HTTP server with an in-memory index, a CRC32C append-only write-ahead log, restart recovery, and atomic log compaction. Maven and JUnit 5 are the only build/test dependencies; the server and load generator have no third-party runtime dependencies.

## Build and run

Requires a JDK 21 installation. The Maven Wrapper downloads pinned Maven 3.9.9 on its first run.

```sh
./mvnw verify
java -jar target/kv-store.jar server --host 127.0.0.1 --port 8080 --data ./data
```

The server replays the WAL before printing `READY port=8080`. Port `0` selects an available port. Stop with Ctrl-C. Only one JVM may own a data directory. The server binds to loopback by default; its API includes an unauthenticated compaction endpoint, so keep it on a trusted local interface.

```sh
curl -i -X PUT --data-binary 'hello' http://127.0.0.1:8080/kv/greeting
curl -i http://127.0.0.1:8080/kv/greeting
curl -i -X DELETE http://127.0.0.1:8080/kv/greeting
curl -i -X POST http://127.0.0.1:8080/admin/compact
```

For offline compaction, stop the server first:

```sh
java -jar target/kv-store.jar compact --data ./data
```

## HTTP contract

Keys match `[A-Za-z0-9._-]{1,256}` and appear literally in the URL path; encoded aliases such as `%61` are rejected. Values are arbitrary bytes, including empty values, capped at 1 MiB. PUT replaces the entire value. There are no multi-key operations.

| Request | Success | Other responses |
| --- | --- | --- |
| `PUT /kv/{key}` | `204`, after log force and index update | `400` invalid key; `413` oversized value |
| `GET /kv/{key}` | `200`, raw `application/octet-stream` bytes | `404` missing; `400` invalid key |
| `DELETE /kv/{key}` | `204`, including an absent key | `400` invalid key |
| `POST /admin/compact` | `204`, after compaction completes | `503` storage failure |
| `GET /health` | `200` when ready | `503` storage failure |

Known routes reject unsupported methods with `405` and an `Allow` header. Unknown routes return `404`. After a fatal storage failure, valid storage operations return `503` until restart. There is no automatic retry or log reset.

## Consistency and durability

A fair `ReentrantReadWriteLock` protects a `HashMap<String, byte[]>`. GET takes the read lock and returns a defensive copy. PUT and DELETE take the write lock through the entire append, `FileChannel.force(true)`, and index update. This serializes mutations and provides linearizable single-key operations. Readers cannot observe a partially applied or unforced mutation.

A successful mutation survives **JVM/process termination on local disk**. A failed or unanswered mutation may also survive: the log may already contain a complete record when the process dies or an HTTP response is lost. DELETE always writes a tombstone, including for an absent key. Append/force/move/reopen failures poison the running store so it cannot continue with uncertain disk state.

Machine power loss, filesystem corruption repair, network filesystems, replication, transactions, authentication, TTL, bounded total memory, and online compaction are outside this version's guarantees. `force(true)` is used, but this project does not implement directory fsync or claim power-loss-safe file replacement. CRC32C detects accidental corruption; it is not authentication.

## WAL and restart recovery

All integers are big endian. `store.wal` begins with four-byte magic `KVWL` and a four-byte version (`1`). Each record is:

| Field | Bytes |
| --- | ---: |
| Total record length (including length and CRC) | 4 |
| Operation: PUT = 1, DELETE = 2 | 1 |
| Key length | 4 |
| Value length | 4 |
| ASCII key | key length |
| Value | value length |
| CRC32C over all preceding bytes of this record | 4 |

DELETE has zero value length. Empty PUT is distinguished by the operation byte. The decoder checks size limits before allocating a record buffer and validates fields and CRC before updating the index.

Startup replays records in append order. An incomplete final length or record is truncated back to the last valid boundary and the repair is forced before new requests are accepted. Invalid headers, impossible record lengths, invalid complete records, unsupported versions, and checksum mismatches cause startup to fail with the byte offset; the server does not skip records or silently discard checksum failures. A damaged length that still describes a plausible incomplete final record cannot be distinguished from an interrupted append; this is a limitation of the format's tail-repair policy.

An absent WAL initializes an empty store. An existing empty or partial file header is an error, including after a crash during initial creation. Startup failure leaves the canonical log intact except for explicit incomplete-tail repair. Back up a damaged directory before inspecting it; there is no destructive repair command.

## Compaction

Compaction takes the write lock and pauses storage requests. It writes a versioned `store.wal.tmp` containing one PUT per live key in sorted order, forces and closes that file, closes the old WAL, atomically replaces `store.wal`, and reopens it for append. It logs before/after byte counts and elapsed nanoseconds to stderr.

The canonical filename is the commit point. Before atomic replacement, the old WAL remains authoritative; afterward, the new WAL contains the same live state. On restart, leftover temporary files are discarded only after the canonical WAL has replayed successfully. Temporary files are never promoted. Atomic same-directory replacement is required; unsupported filesystems fail compaction without a non-atomic fallback. A separate `store.lock` file remains locked throughout replacement.

## Verification

```sh
./mvnw test       # unit, HTTP, concurrency, and deterministic I/O fault tests
./mvnw verify     # also builds the JAR and runs child-JVM crash integration tests
```

Unit reports are in `target/surefire-reports/`; crash integration reports are in `target/failsafe-reports/`. The GitHub Actions workflow runs verification on Linux and macOS with Java 21. Local verification for the included results was performed on macOS; hosted CI results require pushing to GitHub.

The test-only `StorageIO` boundary supports short writes, partial writes followed by failure, force failure, atomic-move failure, reopen failure, and explicit execution checkpoints. Fault injection is not exposed through production command-line flags or environment variables. Child JVMs publish a checkpoint marker and block until the parent forcibly terminates them; timing does not decide when to crash. See [the test matrix](docs/testing.md).

## Load generator

Start the server, then run:

```sh
java -jar target/kv-store.jar load \
  --url http://127.0.0.1:8080 --concurrency 16 \
  --operations 10000 --warmup 1000 --keys 1000 \
  --value-bytes 256 --writes 50 --seed 42 \
  --hardware 'describe CPU, RAM, disk/filesystem, and client/server placement' \
  --output benchmarks/runs/mixed-c16.json
```

`--writes` is an integer percentage; the remaining operations are GET. Keys are preloaded using durable PUTs. Every worker receives a deterministic operation count and seeded random stream. The same seed and configuration reproduce request choices, although thread scheduling, response times, and write interleaving vary. All benchmark values use the same seeded payload, letting the driver validate successful GET bodies. Use a dedicated data directory: preloading overwrites keys named `load-0`, `load-1`, etc.

The driver reuses one Java HTTP client, executes warm-up, then releases measured workers through a barrier. Preload, warm-up, thread setup, and output serialization are excluded from measured time. The measured interval includes request construction, all attempts, and optional synchronous compaction. Per-request latency measures the HTTP send/receive call. GET latency includes receipt of the complete body. Percentiles use nearest-rank over successful requests and are reported in microseconds overall and by operation. Throughput is successful requests divided by measured wall time. Empty operation groups have null percentiles.

Errors include HTTP failures, unexpected values, and I/O/timeouts, and are excluded from latency percentiles. Measured failures are exported and cause a nonzero CLI exit. Preload or warm-up failures abort before measurement. Request/connect timeout defaults to 10 seconds. `--compact true` sends one compaction request after half the measured key operations finish, blocking that worker while other workers continue; compaction duration/errors are separate from key-operation samples. Its pause remains visible in elapsed time and overlapping request latencies.

This is a **closed-loop** driver, not a constant-arrival-rate benchmark. It does not correct coordinated omission; its latency numbers describe requests it actually sends. It uses a finite operation count, not a fixed-duration run.

Run the full fresh-server matrix with Python 3 (standard library only):

```sh
python3 scripts/benchmark.py \
  --hardware 'describe this machine, storage, and client/server placement'
```

The matrix covers read-only, 95/5, 50/50, write-only, and 50/50 with compaction, each at concurrency 1, 4, 16, and 64. Each configuration gets a new JVM and data directory. Raw JSON preserves configuration and client environment metadata. Repeat longer runs before drawing performance conclusions. [Included smoke measurements](benchmarks/README.md) exercise all configurations without claiming stable capacity or tail-latency estimates.

## Commit phases

1. Storage API and versioned CRC32C WAL codec.
2. Forced mutations, directory ownership, replay, and corruption handling.
3. HTTP API, virtual-thread request handling, and concurrency verification.
4. Atomic compaction, deterministic faults, and subprocess crash tests.
5. Reproducible load generation, benchmark evidence, documentation, and CI.
