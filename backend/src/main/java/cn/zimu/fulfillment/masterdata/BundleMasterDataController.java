package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.MasterDataRecord;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.product.BundlePatch;
import cn.zimu.fulfillment.product.BundleWrite;
import cn.zimu.fulfillment.product.SourceBundleMappingPatch;
import cn.zimu.fulfillment.product.SourceBundleMappingWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 静态礼包及来源礼包映射的公共 JSON API。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class BundleMasterDataController {

    private final BundleMasterDataService service;

    public BundleMasterDataController(BundleMasterDataService service) {
        this.service = service;
    }

    @GetMapping("/product-bundles")
    public PageResponse<MasterDataRecord> productBundles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String query) {
        return service.productBundles(page, size, query);
    }

    @GetMapping("/product-bundles/{id}")
    public MasterDataRecord productBundle(@PathVariable String id) {
        return service.productBundle(WriteCommands.parseIdentifier(id));
    }

    @PostMapping("/product-bundles")
    public ResponseEntity<?> createProductBundle(
            @Valid @RequestBody BundleWrite body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createProductBundle(
                body,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }

    @PatchMapping("/product-bundles/{id}")
    public ResponseEntity<?> patchProductBundle(
            @PathVariable String id,
            @Valid @RequestBody BundlePatch body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchProductBundle(
                WriteCommands.parseIdentifier(id),
                body,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }

    @GetMapping("/source-bundle-mappings")
    public PageResponse<MasterDataRecord> sourceBundleMappings(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel) {
        return service.sourceBundleMappings(page, size, sourceChannel);
    }

    @GetMapping("/source-bundle-mappings/{id}")
    public MasterDataRecord sourceBundleMapping(@PathVariable String id) {
        return service.sourceBundleMapping(WriteCommands.parseIdentifier(id));
    }

    @PostMapping("/source-bundle-mappings")
    public ResponseEntity<?> createSourceBundleMapping(
            @Valid @RequestBody SourceBundleMappingWrite body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createSourceBundleMapping(
                body,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }

    @PatchMapping("/source-bundle-mappings/{id}")
    public ResponseEntity<?> patchSourceBundleMapping(
            @PathVariable String id,
            @Valid @RequestBody SourceBundleMappingPatch body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchSourceBundleMapping(
                WriteCommands.parseIdentifier(id),
                body,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }
}
