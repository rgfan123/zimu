package cn.zimu.fulfillment.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Bounded JSON contract applied to untrusted model output before persistence or routing. */
@Component
public final class MessageStructuredOutputBoundary {

    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_DEPTH = 8;
    static final int MAX_FIELDS = 256;
    static final int MAX_ARRAY_ITEMS = 100;

    private final ObjectMapper objectMapper;
    private final MessageModelMetadataRegistry metadataRegistry;

    public MessageStructuredOutputBoundary(
            ObjectMapper objectMapper, MessageModelMetadataRegistry metadataRegistry) {
        this.objectMapper = objectMapper;
        this.metadataRegistry = metadataRegistry;
    }

    /** Invalid successful results become a stable, non-retryable NEED_REVIEW result. */
    public InterpretationResult failClosed(InterpretationResult result) {
        if (!metadataRegistry.allows(result)) {
            return invalidResult();
        }
        if (result != null && result.error() != null && !result.error().isBlank()) {
            return result;
        }
        if (result == null || !isValid(result.structuredOutput())) {
            return invalidResult();
        }
        return result;
    }

    private boolean isValid(Map<String, Object> output) {
        if (output == null) {
            return false;
        }
        FieldCounter fields = new FieldCounter();
        if (!hasValidShape(output, 1, fields, new IdentityHashMap<>())) {
            return false;
        }
        try (OutputStream bounded = new CappedOutputStream(MAX_BYTES)) {
            objectMapper.writeValue(bounded, output);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean hasValidShape(
            Object value,
            int depth,
            FieldCounter fields,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Number number) {
            if (number instanceof Double doubleValue) {
                return Double.isFinite(doubleValue);
            }
            if (number instanceof Float floatValue) {
                return Float.isFinite(floatValue);
            }
            return true;
        }
        if (depth > MAX_DEPTH || activeContainers.put(value, Boolean.TRUE) != null) {
            return false;
        }
        try {
            if (value instanceof Map<?, ?> map) {
                fields.add(map.size());
                if (fields.exceeded()) {
                    return false;
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String)
                            || !hasValidShape(entry.getValue(), depth + 1, fields, activeContainers)) {
                        return false;
                    }
                }
                return true;
            }
            if (value instanceof List<?> list) {
                if (list.size() > MAX_ARRAY_ITEMS) {
                    return false;
                }
                for (Object item : list) {
                    if (!hasValidShape(item, depth + 1, fields, activeContainers)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        } finally {
            activeContainers.remove(value);
        }
    }

    private static InterpretationResult invalidResult() {
        String failure = InterpretationFailureCode.MODEL_OUTPUT_INVALID.name();
        return new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", failure),
                "none",
                "none",
                "none",
                failure);
    }

    private static final class FieldCounter {

        private int value;

        private void add(int amount) {
            value += amount;
        }

        private boolean exceeded() {
            return value > MAX_FIELDS;
        }
    }

    /** Counts and discards JSON bytes, aborting serialization on the first byte over the cap. */
    private static final class CappedOutputStream extends OutputStream {

        private final int maxBytes;
        private int written;

        private CappedOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            written += length;
        }

        private void requireCapacity(int length) throws OutputLimitExceededException {
            if (length > maxBytes - written) {
                throw new OutputLimitExceededException();
            }
        }
    }

    private static final class OutputLimitExceededException extends IOException {}
}
