package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MessageStructuredOutputBoundaryTest {

    @Test
    void serializationStopsAtFirstByteBeyondLimitWithoutMaterializingByteArray() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AtomicInteger attemptedBytes = new AtomicInteger();
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(0);
                    while (true) {
                        attemptedBytes.incrementAndGet();
                        output.write(0);
                    }
                })
                .when(objectMapper)
                .writeValue(any(OutputStream.class), any());
        MessageModelMetadataRegistry metadataRegistry = new MessageModelMetadataRegistry();
        MessageModelMetadataRegistry.PublicMetadataAlias alias =
                new MessageModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider("test-provider");
        alias.setModel("test-model");
        alias.setPromptVersion("test-prompt-v1");
        metadataRegistry.setPublicMetadataAliases(List.of(alias));
        MessageStructuredOutputBoundary boundary =
                new MessageStructuredOutputBoundary(objectMapper, metadataRegistry);
        InterpretationResult modelResult = new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("reason", "bounded-serialization-probe"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);

        InterpretationResult safeResult = boundary.failClosed(modelResult);

        assertThat(safeResult.intent()).isEqualTo(MessageIntent.NEED_REVIEW);
        assertThat(safeResult.error()).isEqualTo(InterpretationFailureCode.MODEL_OUTPUT_INVALID.name());
        assertThat(attemptedBytes).hasValue(MessageStructuredOutputBoundary.MAX_BYTES + 1);
        verify(objectMapper).writeValue(any(OutputStream.class), any());
        verify(objectMapper, never()).writeValueAsBytes(any());
    }
}
