package cn.zimu.fulfillment.file;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ContentAddressedFileStore {

    private final Path root;

    public ContentAddressedFileStore(
            @Value("${app.file-store.root:${java.io.tmpdir}/zimu-fulfillment-files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public StoredFile put(String namespace, byte[] bytes, String suffix) {
        String sha256 = sha256(bytes);
        try {
            Path directory = root.resolve(namespace).normalize();
            if (!directory.startsWith(root)) {
                throw new IllegalArgumentException("invalid file namespace");
            }
            Files.createDirectories(directory);
            Path destination = directory.resolve(sha256 + suffix).normalize();
            Path temporary = Files.createTempFile(directory, "write-", ".part");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(temporary);
            }
            return new StoredFile(destination.toString(), sha256);
        } catch (IOException exception) {
            throw new IllegalStateException("无法持久化文件", exception);
        }
    }

    /**
     * 按受控引用读取文件；绝对引用须位于根目录内，相对引用按根目录解析。
     * 两种形态均拒绝越界（含 .. 穿越）。
     */
    public byte[] read(String fileRef) {
        try {
            Path raw = Path.of(fileRef);
            Path file = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            if (!file.startsWith(root)) {
                throw new IllegalArgumentException("文件引用超出受控目录");
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取已留存文件", exception);
        }
    }

    String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record StoredFile(String fileRef, String sha256) {}
}
