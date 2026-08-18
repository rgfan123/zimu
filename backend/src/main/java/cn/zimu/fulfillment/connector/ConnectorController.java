package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connectors")
@Validated
public class ConnectorController {

    private final ConnectorService service;

    public ConnectorController(ConnectorService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectorConfigView> list() {
        return service.list();
    }

    @GetMapping("/{source_channel}")
    public ConnectorConfigView get(@PathVariable("source_channel") SourceChannel channel) {
        return service.get(channel);
    }

    @PatchMapping("/{source_channel}")
    public ResponseEntity<?> patch(
            @PathVariable("source_channel") SourceChannel channel,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody ConnectorPatch patch) {
        return WriteCommands.respond(service.patch(
                channel,
                patch,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    @PostMapping("/{source_channel}/test-connection")
    public ResponseEntity<?> testConnection(
            @PathVariable("source_channel") SourceChannel channel,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return WriteCommands.respond(service.testConnection(
                channel,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
