package cn.zimu.fulfillment.file;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 只读映射预览；确认后由管理端调用现有主数据写 API。 */
@RestController
class ProviderSkuMappingReferenceController {

    private final ProviderSkuMappingReferenceService service;

    ProviderSkuMappingReferenceController(ProviderSkuMappingReferenceService service) {
        this.service = service;
    }

    @PostMapping(
            path = "/api/v1/provider-sku-mapping-references/preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> preview(
            @RequestParam("reference_file") MultipartFile referenceFile,
            @RequestParam("source_file") MultipartFile sourceFile) throws IOException {
        return service.preview(referenceFile.getBytes(), sourceFile.getBytes());
    }
}
