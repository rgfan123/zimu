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
class ContentAddressedFileStore {

    private final Path root;

    ContentAddressedFileStore(
            @Value("${app.file-store.root:${java.io.tmpdir}/zimu-fulfillment-files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    StoredFile put(String namespace, byte[] bytes, String suffix) {
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

    byte[] read(String fileRef) {
        try {
            Path file = Path.of(fileRef).toAbsolutePath().normalize();
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

    record StoredFile(String fileRef, String sha256) {}
}
