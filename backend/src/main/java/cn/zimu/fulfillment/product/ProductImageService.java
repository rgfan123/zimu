package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.file.ContentAddressedFileStore;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 商品主图：内容寻址存储、类型/大小限制、按引用读取。
 * 引用为相对存储根的 URL 安全形态（{@code product-images/<sha256>.png}），可直接放入查询参数。
 */
@Service
public class ProductImageService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String NAMESPACE = "product-images";

    private final ContentAddressedFileStore fileStore;

    public ProductImageService(ContentAddressedFileStore fileStore) {
        this.fileStore = fileStore;
    }

    /** 校验并持久化主图，返回受控引用与可访问 URL。 */
    public Map<String, Object> store(byte[] bytes, String contentType) {
        String suffix = switch (contentType == null ? "" : contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw BusinessException.badRequest("INVALID_PRODUCT_IMAGE_TYPE", "仅支持 PNG / JPEG / WebP 图片");
        };
        if (bytes.length == 0) {
            throw BusinessException.badRequest("INVALID_PRODUCT_IMAGE", "图片内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw BusinessException.badRequest("PRODUCT_IMAGE_TOO_LARGE", "主图不能超过 10MB");
        }
        ContentAddressedFileStore.StoredFile stored = fileStore.put(NAMESPACE, bytes, suffix);
        String ref = NAMESPACE + "/" + stored.sha256() + suffix;
        return Map.of(
                "file_ref", ref,
                "url", "/api/v1/product-images?ref=" + ref);
    }

    /** 按受控引用读取主图字节；引用非法返回 400，文件缺失返回 404。 */
    public byte[] read(String fileRef) {
        try {
            return fileStore.read(fileRef);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("INVALID_PRODUCT_IMAGE_REF", "主图引用非法");
        } catch (IllegalStateException exception) {
            throw BusinessException.notFound("主图不存在");
        }
    }

    public static String contentType(String fileRef) {
        String lower = fileRef == null ? "" : fileRef.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
