package cn.zimu.fulfillment.procurement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record ProcurementReceiptInput(
        @NotNull Result result,
        Instant expectedShipTime,
        @Size(min = 1, max = 255) String sourceRef,
        @Size(max = 2000) String remark,
        @NotEmpty List<@Valid ProcurementReceiptItemInput> items) {
    public enum Result { SUCCESS, PARTIAL, FAILED }
}
