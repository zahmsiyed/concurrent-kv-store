package dev.kv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PersistentStore implements KeyValueStore {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, byte[]> index = new HashMap<>();
    private final Path directory;
    private final StorageIO io;
    private FileChannel lockChannel;
    private FileLock directoryLock;
    private FileChannel wal;
    private boolean closed;
    private IOException failure;

    public PersistentStore(Path directory) throws IOException { this(directory, StorageIO.SYSTEM); }

    PersistentStore(Path directory, StorageIO io) throws IOException {
        this.directory = directory.toAbsolutePath(); this.io = io;
        Files.createDirectories(this.directory);
        try {
            lockChannel = FileChannel.open(this.directory.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { directoryLock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("data directory already in use", e); }
            if (directoryLock == null) throw new IOException("data directory already in use");
            Path path = this.directory.resolve("store.wal");
            if (Files.notExists(path)) {
                try (FileChannel initial = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writeFully(initial, ByteBuffer.wrap(WalCodec.HEADER)); io.force(initial);
                }
            }
            wal = io.open(path);
            replay();
            Files.deleteIfExists(this.directory.resolve("store.wal.tmp"));
        } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private void replay() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(WalCodec.HEADER.length);
        readFully(wal, header);
        if (header.hasRemaining() || !Arrays.equals(header.array(), WalCodec.HEADER)) throw WalCodec.corrupt(0, "invalid magic/version header");
        long valid = wal.position(), size = wal.size();
        while (valid < size) {
            ByteBuffer length = ByteBuffer.allocate(4); readFully(wal, length);
            if (length.hasRemaining()) { repairTail(valid); return; }
            int n = length.flip().getInt();
            if (n < WalCodec.MIN_RECORD || n > WalCodec.MAX_RECORD) throw WalCodec.corrupt(valid, "invalid record length");
            ByteBuffer record = ByteBuffer.allocate(n); record.putInt(n); readFully(wal, record);
            if (record.hasRemaining()) { repairTail(valid); return; }
            WalCodec.Record r = WalCodec.decode(record.array(), valid);
            apply(r.operation(), r.key(), r.value()); valid = wal.position();
        }
        wal.position(valid);
    }

    private void repairTail(long valid) throws IOException {
        wal.truncate(valid); io.force(wal); wal.position(valid);
    }

    private static void readFully(FileChannel c, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) { if (c.read(b) < 0) return; }
    }

    private void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (io.write(channel, bytes) <= 0) throw new IOException("storage write made no progress");
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("store is closed");
        if (failure != null) throw new IOException("store requires restart after storage failure", failure);
    }

    public boolean healthy() {
        lock.readLock().lock();
        try { return !closed && failure == null; } finally { lock.readLock().unlock(); }
    }

    @Override public Optional<byte[]> get(String key) throws IOException {
        WalCodec.validateKey(key); lock.readLock().lock();
        try { checkOpen(); byte[] value = index.get(key); return value == null ? Optional.empty() : Optional.of(value.clone()); }
        finally { lock.readLock().unlock(); }
    }

    @Override public void put(String key, byte[] value) throws IOException {
        WalCodec.validateKey(key);
        if (value == null || value.length > WalCodec.MAX_VALUE) throw new IllegalArgumentException("value exceeds 1 MiB or is null");
        mutate(WalCodec.PUT, key, value.clone());
    }

    @Override public void delete(String key) throws IOException { mutate(WalCodec.DELETE, key, new byte[0]); }

    private void mutate(byte op, String key, byte[] value) throws IOException {
        ByteBuffer record = WalCodec.encode(op, key, value);
        lock.writeLock().lock();
        try {
            checkOpen();
            try {
                io.checkpoint("before-append"); writeFully(wal, record); io.checkpoint("after-append");
                io.force(wal); io.checkpoint("after-force");
                apply(op, key, value);
            } catch (IOException e) { failure = e; throw e; }
        } finally { lock.writeLock().unlock(); }
    }

    private void apply(byte op, String key, byte[] value) {
        if (op == WalCodec.PUT) index.put(key, value); else index.remove(key);
    }

    public record CompactionResult(long beforeBytes, long afterBytes, long durationNanos) {}

    /** Stop-the-world compaction: the old canonical log remains authoritative until atomic replacement. */
    public CompactionResult compact() throws IOException {
        lock.writeLock().lock();
        try {
            checkOpen();
            long started = System.nanoTime();
            try {
                long before = wal.size();
                Path temporary = directory.resolve("store.wal.tmp");
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    writeFully(output, ByteBuffer.wrap(WalCodec.HEADER));
                    io.checkpoint("compact-header");
                    for (var entry : new java.util.TreeMap<>(index).entrySet()) {
                        writeFully(output, WalCodec.encode(WalCodec.PUT, entry.getKey(), entry.getValue()));
                        io.checkpoint("compact-record");
                    }
                    io.force(output); io.checkpoint("compact-forced");
                }
                wal.close(); io.checkpoint("compact-before-replace");
                io.replace(temporary, directory.resolve("store.wal"));
                io.checkpoint("compact-after-replace");
                wal = io.open(directory.resolve("store.wal")); wal.position(wal.size());
                io.checkpoint("compact-reopened");
                var result = new CompactionResult(before, wal.size(), System.nanoTime() - started);
                System.err.printf("compaction beforeBytes=%d afterBytes=%d durationNanos=%d%n",
                        result.beforeBytes(), result.afterBytes(), result.durationNanos());
                return result;
            } catch (IOException e) { failure = e; throw e; }
        } finally { lock.writeLock().unlock(); }
    }

    @Override public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) return; closed = true;
            IOException error = null;
            for (AutoCloseable resource : new AutoCloseable[]{wal, directoryLock, lockChannel}) {
                if (resource != null) try { resource.close(); }
                catch (Exception e) { if (error == null) error = new IOException("closing storage", e); else error.addSuppressed(e); }
            }
            if (error != null) throw error;
        } finally { lock.writeLock().unlock(); }
    }
}
