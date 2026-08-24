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
        return readAllBytes(openRead(fileRef));
    }

    /**
     * 受控 Path 解析 seam（Issue #84 上传消费）：返回根目录内、普通且可读的文件路径，
     * 供上传器流式读取（禁止先 {@link #read} 成 20MiB byte[] 再写临时文件）。
     * 调用方负责在持有该 Path 期间文件不被移动/删除（内容寻址存储为 append-only）。
     */
    public Path openRead(String fileRef) {
        Path raw = Path.of(fileRef);
        Path file = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("文件引用超出受控目录");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException("无法读取已留存文件");
        }
        return file;
    }

    private static byte[] readAllBytes(Path file) {
        try {
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
