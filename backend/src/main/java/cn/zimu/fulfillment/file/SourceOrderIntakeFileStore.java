package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 不可变来源订单附件存储；扩展名、MIME 与容器魔数必须相互一致。 */
@Component
class SourceOrderIntakeFileStore {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private final Path root;

    SourceOrderIntakeFileStore(
            @Value("${app.file-store.root:${java.io.tmpdir}/zimu-fulfillment-files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize().resolve("source-order-intake");
    }

    StoredFile store(byte[] bytes, String originalFilename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.unprocessable("EMPTY_FILE", "上传文件为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw BusinessException.unprocessable("SOURCE_FILE_TOO_LARGE", "来源订单文件不能超过 20MB");
        }
        String normalizedType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.split(";", 2)[0].toLowerCase(Locale.ROOT).strip();
        String safeName = safeFilename(originalFilename);
        Format format = detect(bytes, safeName);
        if (!format.accepts(normalizedType)) {
            throw BusinessException.unprocessable(
                    "SOURCE_FILE_CONTENT_TYPE_MISMATCH", "文件 MIME、扩展名与实际 Excel/CSV 格式不一致");
        }
        String sha256 = sha256(bytes);
        Path target = root.resolve(sha256 + "." + format.extension).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("来源订单文件存储路径越界");
        }
        try {
            Files.createDirectories(root);
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(root, "intake-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return new StoredFile(safeName, normalizedType, format.name(), sha256, target.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("来源订单文件保存失败", exception);
        }
    }

    byte[] load(String fileRef) {
        Path path = Path.of(fileRef).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalStateException("来源订单文件读取路径越界");
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_BYTES) {
                throw new IllegalStateException("来源订单文件大小异常");
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("来源订单文件读取失败", exception);
        }
    }

    private Format detect(byte[] bytes, String filename) {
        String extension = extension(filename);
        if (startsWith(bytes, new byte[] {'P', 'K'})) {
            if ("xlsx".equals(extension)) {
                return Format.XLSX;
            }
            throw formatMismatch();
        }
        if (startsWith(bytes, OLE2)) {
            if ("xls".equals(extension)) {
                return Format.XLS;
            }
            throw formatMismatch();
        }
        if ("csv".equals(extension) && !containsNul(bytes)) {
            return Format.CSV;
        }
        throw formatMismatch();
    }

    private static BusinessException formatMismatch() {
        return BusinessException.unprocessable(
                "SOURCE_FILE_FORMAT_UNSUPPORTED", "文件扩展名与实际 Excel/CSV 格式不一致");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNul(byte[] bytes) {
        int limit = Math.min(bytes.length, 8 * 1024);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "source-orders" : Path.of(filename).getFileName().toString();
        value = value.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (value.isBlank()) {
            return "source-orders";
        }
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        XLS("xls", "application/vnd.ms-excel"),
        CSV("csv", "text/csv", "application/csv", "text/plain");

        private final String extension;
        private final java.util.Set<String> contentTypes;

        Format(String extension, String... contentTypes) {
            this.extension = extension;
            this.contentTypes = java.util.Set.of(contentTypes);
        }

        boolean accepts(String contentType) {
            return "application/octet-stream".equals(contentType) || contentTypes.contains(contentType);
        }
    }

    record StoredFile(
            String originalFilename,
            String contentType,
            String format,
            String sha256,
            String fileRef) {}
}
