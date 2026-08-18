package cn.zimu.fulfillment.demo;

import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.common.web.CommandContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/v1")
@Validated
public class DemoScenarioController {

    private final DemoScenarioService service;

    public DemoScenarioController(DemoScenarioService service) {
        this.service = service;
    }

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return service.scenarios();
    }

    @PostMapping("/scenarios")
    public ResponseEntity<?> run(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody DemoScenarioInput input) {
        String effectiveOperator = operator == null || operator.isBlank() ? "demo-ops" : operator;
        CommandContext context = WriteCommands.writeContext(effectiveOperator);
        return WriteCommands.respond(
                service.run(WriteCommands.requireIdempotencyKey(idempotencyKey), input, context));
    }

    @PostMapping("/extracted-orders")
    public ResponseEntity<?> runExtractedOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody DemoExtractedOrderInput input) {
        String effectiveOperator = operator == null || operator.isBlank() ? "demo-ops" : operator;
        return WriteCommands.respond(service.runExtracted(
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                input,
                WriteCommands.writeContext(effectiveOperator)));
    }

    @GetMapping("/runs/{run_id}")
    public Map<String, Object> detail(@PathVariable("run_id") String runId) {
        return service.detail(WriteCommands.parseIdentifier(runId));
    }
}
