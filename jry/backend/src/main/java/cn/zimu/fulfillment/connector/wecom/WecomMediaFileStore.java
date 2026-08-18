package cn.zimu.fulfillment.connector.wecom;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 媒体原件的受控内容寻址存储：文件名 = 明文 SHA-256，内容不变则路径不变、不可变复用；原件不可被
 * 覆盖。写入采用「临时文件 + 原子移动」，并发写同一内容时后到者复用已存在文件。
 */
@Service
class WecomMediaFileStore {

    private final Path root;

    WecomMediaFileStore(@Value("${app.media.dir:./data/media}") String rootDirectory) {
        this.root = Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    /** 持久化明文媒体字节，返回受控存储引用（绝对路径，可按 sha256 复用）。 */
    String put(byte[] bytes) {
        String sha256 = sha256(bytes);
        try {
            Files.createDirectories(root);
            Path destination = root.resolve(sha256).normalize();
            if (!destination.startsWith(root)) {
                throw new IllegalArgumentException("非法媒体存储引用");
            }
            Path temporary = Files.createTempFile(root, "write-", ".part");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(temporary);
            }
            return destination.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("媒体文件持久化失败", exception);
        }
    }

    /** 按受控引用读取明文原件；引用超出受控目录时拒绝。 */
    byte[] read(String fileRef) {
        try {
            Path file = Path.of(fileRef).toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                throw new IllegalArgumentException("媒体引用超出受控目录");
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取已留存媒体", exception);
        }
    }

    String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
