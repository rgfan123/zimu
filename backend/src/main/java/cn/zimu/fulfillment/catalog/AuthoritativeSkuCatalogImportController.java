package cn.zimu.fulfillment.catalog;

import cn.zimu.fulfillment.common.web.WriteCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员触发冻结京东商品目录导入的公开 HTTP seam；不调用任何京东写接口。 */
@RestController
@RequestMapping("/api/v1/admin/catalog-imports")
public class AuthoritativeSkuCatalogImportController {

    private final AuthoritativeSkuCatalogImportService service;

    public AuthoritativeSkuCatalogImportController(AuthoritativeSkuCatalogImportService service) {
        this.service = service;
    }

    @PostMapping("/jd-authoritative")
    public ResponseEntity<?> importJdAuthoritativeCatalog(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.importCatalog(
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
