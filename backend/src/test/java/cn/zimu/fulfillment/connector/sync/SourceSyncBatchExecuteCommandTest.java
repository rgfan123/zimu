package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceSyncBatchExecuteCommandTest {

    @Test
    void everyItemUsesTheSameMinimumIdempotencyKeyLengthAsSingleExecute() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            SourceSyncBatchExecuteCommand tooShort = new SourceSyncBatchExecuteCommand(List.of(
                    new SourceSyncBatchExecuteCommand.Item(7L, "a".repeat(64), "1234567")));
            SourceSyncBatchExecuteCommand valid = new SourceSyncBatchExecuteCommand(List.of(
                    new SourceSyncBatchExecuteCommand.Item(7L, "a".repeat(64), "12345678")));

            assertThat(validator.validate(tooShort))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("items[0].idempotencyKey");
            assertThat(validator.validate(valid)).isEmpty();
        }
    }
}
