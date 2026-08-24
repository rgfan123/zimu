package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 08 — JSON Schema 可解析判定（networknt）：门禁引擎 output_schema 项依赖
 * {@link JsonSchemaValidator#schemaParses}。纯单元测试。
 */
class JsonSchemaValidatorTest {

    @Test
    void validSchemaParses() {
        assertThat(JsonSchemaValidator.schemaParses("{\"type\":\"object\",\"properties\":{}}")).isTrue();
        assertThat(JsonSchemaValidator.schemaParses("{\"type\":\"object\",\"required\":[\"answer\"]}")).isTrue();
    }

    @Test
    void invalidOrBlankSchemaDoesNotParse() {
        assertThat(JsonSchemaValidator.schemaParses("{oops")).isFalse();
        assertThat(JsonSchemaValidator.schemaParses("not-a-schema")).isFalse();
        assertThat(JsonSchemaValidator.schemaParses(null)).isFalse();
        assertThat(JsonSchemaValidator.schemaParses("   ")).isFalse();
    }
}
