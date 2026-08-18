package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.MasterDataRecord;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.customer.CustomerJdCodeImport;
import cn.zimu.fulfillment.customer.CustomerPatch;
import cn.zimu.fulfillment.customer.CustomerWrite;
import cn.zimu.fulfillment.product.NamedCodePatch;
import cn.zimu.fulfillment.product.NamedCodeWrite;
import cn.zimu.fulfillment.product.ProductPatch;
import cn.zimu.fulfillment.product.ProductWrite;
import cn.zimu.fulfillment.sku.FulfillmentProviderDto;
import cn.zimu.fulfillment.sku.FulfillmentProviderPatch;
import cn.zimu.fulfillment.sku.ProviderSkuJdFactorImport;
import cn.zimu.fulfillment.sku.ProviderSkuMappingPatch;
import cn.zimu.fulfillment.sku.ProviderSkuMappingWrite;
import cn.zimu.fulfillment.sku.SkuPatch;
import cn.zimu.fulfillment.sku.SkuWrite;
import cn.zimu.fulfillment.sku.SourceSkuMappingPatch;
import cn.zimu.fulfillment.sku.SourceSkuMappingWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
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

/** 客户、商品、SKU 与履约方映射的 JSON API。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    @GetMapping("/customers")
    public PageResponse<MasterDataRecord> customers(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String query) {
        return service.customers(page, size, query);
    }

    @GetMapping("/customers/{id}") public MasterDataRecord customer(@PathVariable String id) { return service.customer(id(id)); }
    @PostMapping("/customers") public ResponseEntity<?> createCustomer(@Valid @RequestBody CustomerWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createCustomer(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/customers/{id}") public ResponseEntity<?> patchCustomer(@PathVariable String id,
            @Valid @RequestBody CustomerPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchCustomer(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @PostMapping("/customers/jd-customer-code-imports") public ResponseEntity<?> importJdCustomerCodes(
            @RequestBody CustomerJdCodeImport body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.importJdCustomerCodes(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/categories") public PageResponse<MasterDataRecord> categories(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) { return service.categories(page, size); }
    @GetMapping("/categories/{id}") public MasterDataRecord category(@PathVariable String id) { return service.category(id(id)); }
    @PostMapping("/categories") public ResponseEntity<?> createCategory(@Valid @RequestBody NamedCodeWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createCategory(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/categories/{id}") public ResponseEntity<?> patchCategory(@PathVariable String id,
            @Valid @RequestBody NamedCodePatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchCategory(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/products") public PageResponse<MasterDataRecord> products(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) { return service.products(page, size); }
    @GetMapping("/products/tags") public List<String> productTags() { return service.productTags(); }
    @GetMapping("/products/{id}") public MasterDataRecord product(@PathVariable String id) { return service.product(id(id)); }
    @PostMapping("/products") public ResponseEntity<?> createProduct(@Valid @RequestBody ProductWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createProduct(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/products/{id}") public ResponseEntity<?> patchProduct(@PathVariable String id,
            @Valid @RequestBody ProductPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchProduct(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/skus") public PageResponse<MasterDataRecord> skus(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "provider_id", required = false) String providerId) { return service.skus(page, size, providerId); }
    @GetMapping("/skus/{id}") public MasterDataRecord sku(@PathVariable String id) { return service.sku(id(id)); }
    @PostMapping("/skus") public ResponseEntity<?> createSku(@Valid @RequestBody SkuWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createSku(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/skus/{id}") public ResponseEntity<?> patchSku(@PathVariable String id,
            @Valid @RequestBody SkuPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchSku(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/source-sku-mappings") public PageResponse<MasterDataRecord> sourceMappings(
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "source_channel", required = false) SourceChannel channel) { return service.sourceMappings(page, size, channel); }
    @GetMapping("/source-sku-mappings/{id}") public MasterDataRecord sourceMapping(@PathVariable String id) { return service.sourceMapping(id(id)); }
    @PostMapping("/source-sku-mappings") public ResponseEntity<?> createSourceMapping(@Valid @RequestBody SourceSkuMappingWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createSourceMapping(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/source-sku-mappings/{id}") public ResponseEntity<?> patchSourceMapping(@PathVariable String id,
            @Valid @RequestBody SourceSkuMappingPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchSourceMapping(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/provider-sku-mappings") public PageResponse<MasterDataRecord> providerMappings(
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) { return service.providerMappings(page, size); }
    @GetMapping("/provider-sku-mappings/{id}") public MasterDataRecord providerMapping(@PathVariable String id) { return service.providerMapping(id(id)); }
    @PostMapping("/provider-sku-mappings") public ResponseEntity<?> createProviderMapping(@Valid @RequestBody ProviderSkuMappingWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createProviderMapping(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
    @PatchMapping("/provider-sku-mappings/{id}") public ResponseEntity<?> patchProviderMapping(@PathVariable String id,
            @Valid @RequestBody ProviderSkuMappingPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchProviderMapping(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @PostMapping("/provider-sku-mappings/jd-pieces-per-unit-imports") public ResponseEntity<?> importJdPiecesPerUnit(
            @RequestBody ProviderSkuJdFactorImport body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.importJdPiecesPerUnit(body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @GetMapping("/provider-sku-mappings/jd-pieces-candidates") public List<Map<String, Object>> jdPiecesCandidates() {
        return service.jdPiecesCandidates();
    }

    @GetMapping("/fulfillment-providers") public List<FulfillmentProviderDto> providers() { return service.providers(); }
    @GetMapping("/fulfillment-providers/{id}") public FulfillmentProviderDto provider(@PathVariable String id) { return service.provider(id(id)); }
    @PatchMapping("/fulfillment-providers/{id}") public ResponseEntity<?> patchProvider(@PathVariable String id,
            @Valid @RequestBody FulfillmentProviderPatch body, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchProvider(id(id), body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    private static long id(String value) { return WriteCommands.parseIdentifier(value); }
}
