package dev.kv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Injectable storage boundaries; production has no fault behavior. */
interface StorageIO {
    StorageIO SYSTEM = new StorageIO() {};
    default int write(FileChannel channel, ByteBuffer bytes) throws IOException { return channel.write(bytes); }
    default void force(FileChannel channel) throws IOException { channel.force(true); }
    default FileChannel open(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
    default void checkpoint(String name) throws IOException {}
}
