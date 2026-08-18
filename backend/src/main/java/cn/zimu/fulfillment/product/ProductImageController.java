package cn.zimu.fulfillment.product;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 商品主图上传与读取；引用写入 products.main_image_ref 后经 GET 展示。 */
@RestController
public class ProductImageController {

    private final ProductImageService service;

    public ProductImageController(ProductImageService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/product-images", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        Map<String, Object> stored = service.store(file.getBytes(), file.getContentType());
        return ResponseEntity.status(201).body(stored);
    }

    @GetMapping("/api/v1/product-images")
    public ResponseEntity<byte[]> read(@RequestParam("ref") String ref) {
        byte[] bytes = service.read(ref);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ProductImageService.contentType(ref)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable())
                .body(bytes);
    }
}
