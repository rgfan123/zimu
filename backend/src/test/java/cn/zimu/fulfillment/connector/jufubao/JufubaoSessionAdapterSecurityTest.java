package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JufubaoSessionAdapterSecurityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void blankCanonicalCredentialFallsBackToTheLegacyAlias() {
        assertThat(JufubaoSessionAdapter.firstNonBlank("", "", "legacy-user"))
                .isEqualTo("legacy-user");
        assertThat(JufubaoSessionAdapter.firstNonBlank("configured", "canonical", "legacy"))
                .isEqualTo("configured");
    }

    @Test
    void productionConstructorRejectsPlaintextOrNonOfficialOrigins() {
        assertThatThrownBy(() -> new JufubaoSessionAdapter(
                        mapper,
                        "http://supplier-apis.jufubao.cn",
                        "https://g.jufubao.cn",
                        "user",
                        "",
                        "",
                        "password",
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("官方 HTTPS origin");

        assertThatThrownBy(() -> new JufubaoSessionAdapter(
                        mapper,
                        "https://attacker.example",
                        "https://g.jufubao.cn",
                        "user",
                        "",
                        "",
                        "password",
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("官方 HTTPS origin");
    }

    @Test
    void productionConstructorRejectsCredentialBearingOrPathfulOrigins() {
        assertThatThrownBy(() -> new JufubaoSessionAdapter(
                        mapper,
                        "https://user@supplier-apis.jufubao.cn",
                        "https://g.jufubao.cn",
                        "user",
                        "",
                        "",
                        "password",
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new JufubaoSessionAdapter(
                        mapper,
                        "https://supplier-apis.jufubao.cn/api",
                        "https://g.jufubao.cn",
                        "user",
                        "",
                        "",
                        "password",
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("官方 HTTPS origin");
    }
}
