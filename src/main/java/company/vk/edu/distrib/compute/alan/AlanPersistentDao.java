package company.vk.edu.distrib.compute.alan;

import company.vk.edu.distrib.compute.Dao;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AlanPersistentDao implements Dao<byte[]> {
    private static final HexFormat HEX = HexFormat.of();

    private final Path root;
    private final ConcurrentMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public AlanPersistentDao() throws IOException {
        this(Path.of(".data"));
    }

    public AlanPersistentDao(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    @Override
    public byte[] get(String key) throws IOException {
        validateKey(key);
        ReentrantReadWriteLock.ReadLock lock = lockFor(key).readLock();
        lock.lock();
        try {
            Path path = pathFor(key);
            if (!Files.exists(path)) {
                throw new NoSuchElementException("No value for key: " + key);
            }
            return readRecord(path, key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void upsert(String key, byte[] value) throws IOException {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        ReentrantReadWriteLock.WriteLock lock = lockFor(key).writeLock();
        lock.lock();
        try {
            Path path = pathFor(key);
            Files.createDirectories(path.getParent());

            Path tempFile = Files.createTempFile(path.getParent(), "tmp-", ".bin");
            try {
                writeRecord(tempFile, key, value);
                Files.move(
                        tempFile,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String key) throws IOException {
        validateKey(key);

        ReentrantReadWriteLock.WriteLock lock = lockFor(key).writeLock();
        lock.lock();
        try {
            Files.deleteIfExists(pathFor(key));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        // no-op
    }

    private ReentrantReadWriteLock lockFor(String key) {
        return locks.computeIfAbsent(key, ignored -> new ReentrantReadWriteLock());
    }

    private Path pathFor(String key) {
        String hash = sha256Hex(key);
        String first = hash.substring(0, 2);
        String second = hash.substring(2, 4);
        String rest = hash.substring(4);
        return root.resolve(first).resolve(second).resolve(rest + ".data");
    }

    private static String sha256Hex(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void writeRecord(Path path, String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] valueCopy = Arrays.copyOf(value, value.length);

        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(
                        Files.newOutputStream(
                                path,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING
                        )
                )
        )) {
            output.writeInt(keyBytes.length);
            output.write(keyBytes);
            output.writeInt(valueCopy.length);
            output.write(valueCopy);
        }
    }

    private static byte[] readRecord(Path path, String expectedKey) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path))
        )) {
            int keyLength = input.readInt();
            if (keyLength < 0) {
                throw new IOException("Negative key length");
            }

            byte[] keyBytes = input.readNBytes(keyLength);
            if (keyBytes.length != keyLength) {
                throw new IOException("Unexpected EOF while reading key");
            }

            String actualKey = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!expectedKey.equals(actualKey)) {
                throw new IOException("Stored key does not match requested key");
            }

            int valueLength = input.readInt();
            if (valueLength < 0) {
                throw new IOException("Negative value length");
            }

            byte[] value = input.readNBytes(valueLength);
            if (value.length != valueLength) {
                throw new IOException("Unexpected EOF while reading value");
            }

            return value;
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }
}
