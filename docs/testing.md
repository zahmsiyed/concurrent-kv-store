# Recovery and fault test matrix

Run `./mvnw verify` from the repository root. No separate server or external services are needed.

| Behavior / failure | Test evidence | Required outcome |
| --- | --- | --- |
| Binary, empty, and maximum-sized values | `WalCodecTest` | Round-trip exactly; reject invalid keys, oversized values, invalid fields, and bad checksums |
| Repeated PUT, DELETE, missing-key DELETE, recreate | `PersistentStoreTest` | Replay matches the live index; deleted keys remain absent |
| Defensive copies | `PersistentStoreTest` | Mutating input/output arrays does not change stored state |
| Every truncation point in the final record | `PersistentStoreTest` | Repair only the incomplete tail; subsequent append/restart succeeds |
| Bad header, impossible length, checksum mismatch | `PersistentStoreTest` | Fail startup; checksum failure reports offset and preserves bytes |
| Two directory owners; closed store | `PersistentStoreTest` | Reject second owner and operations after close |
| Short writes | `PersistentStoreTest` | Complete all bytes rather than assuming one channel write is enough |
| Force failure during mutation | `PersistentStoreTest` | Fail operation and poison the store |
| Unforced mutation with waiting reader | `ConcurrencyTest` | Reader waits for mutation to finish; later DELETE is visible |
| HTTP status/body contract and concurrent writers | `HttpApiTest` | Exact binary responses, limits, deletion, errors; independent keys survive restart |
| Successful/empty/repeated compaction | `CompactionTest` | Shrink to live records (header only when empty); preserve state and future appends |
| Partial append / temporary log write exception | `CompactionTest` | Poison live store; recover old canonical state without a phantom key |
| Temporary log force, atomic move, or reopen failure | `CompactionTest` | Poison live store; restart from canonical log; no non-atomic fallback |
| Injected failure at each compaction checkpoint | `CompactionTest` | Recover same live state before or after replacement |
| Stale temporary file without a canonical WAL | `CompactionTest` | Initialize empty canonical WAL; never promote temporary contents |
| Forced process death before append / after 7 bytes / after append / after force / after return | `CrashRecoveryIT` + `CrashWorker` | Preserve acknowledged seed writes/deletes; forced writes survive; incomplete append is discarded |
| Forced process death during temporary header/record, after temporary force, before/after replacement, after reopen | `CrashRecoveryIT` + `CrashWorker` | Canonical old/new log yields identical live state and accepts further durable writes |
| Actual HTTP PUT and DELETE acknowledgment, followed by forced server termination | `CrashRecoveryIT` | PUT survives and deleted key remains absent |
| Load reports, read-only/write-only, empty payload, config limits, nearest-rank percentiles | `LoadGeneratorTest` | Exact operation totals, zero errors, valid output, compaction completes |

Crash tests wait for an explicit checkpoint before terminating the child. Deadlines bound hung tests; they do not replace checkpoint synchronization. A complete but unanswered append may survive or disappear depending on its persistence state. Tests require survival after force and absence for incomplete records, without asserting a false rollback guarantee.

The corruption tests exercise application-visible file contents. They do not emulate disk controller caches, sudden machine power loss, or arbitrary filesystem behavior. The HTTP/concurrency tests demonstrate lock ordering and integration behavior; they are not an exhaustive linearizability proof.
