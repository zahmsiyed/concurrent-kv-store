package dev.kv;

import java.io.IOException;
import java.util.Optional;

/** Single-key operations are linearizable; successful mutations are forced before return. */
public interface KeyValueStore extends AutoCloseable {
    Optional<byte[]> get(String key) throws IOException;
    void put(String key, byte[] value) throws IOException;
    void delete(String key) throws IOException;
    @Override void close() throws IOException;
}
