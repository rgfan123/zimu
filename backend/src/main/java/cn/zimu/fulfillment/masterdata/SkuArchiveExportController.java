package cn.zimu.fulfillment.masterdata;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商品档案 xlsx 下载端点。 */
@RestController
public class SkuArchiveExportController {

    static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SkuArchiveExportService service;

    public SkuArchiveExportController(SkuArchiveExportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/skus/export")
    ResponseEntity<byte[]> exportSkuArchive() {
        SkuArchiveExportService.ExportFile file = service.export();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(file.bytes());
    }
}
