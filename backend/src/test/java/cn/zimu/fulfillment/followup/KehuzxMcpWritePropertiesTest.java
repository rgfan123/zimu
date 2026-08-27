package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class KehuzxMcpWritePropertiesTest {

    @Test
    void writeBoundaryRequiresItsOwnEnabledTokenSigningKeyAndExactOrigin() {
        KehuzxMcpWriteProperties properties = readyProperties();
        assertThat(properties.isReady()).isTrue();

        properties.setApprovalSigningKey(null);
        assertThat(properties.isReady()).isFalse();
        properties = readyProperties();
        properties.setWriteToken(null);
        assertThat(properties.isReady()).isFalse();
        properties = readyProperties();
        properties.setEnabled(false);
        assertThat(properties.isReady()).isFalse();
    }

    @Test
    void pathPortQueryUserInfoAndRedirectTargetsCannotRelaxTheDeploymentAllowlist() {
        for (String endpoint : new String[] {
                "http://kehuzx-mcp:9101/mcp",
                "http://kehuzx-mcp:9100/other",
                "http://kehuzx-mcp:9100/mcp?next=http://attacker",
                "http://user@kehuzx-mcp:9100/mcp",
                "http://attacker:9100/mcp"
        }) {
            KehuzxMcpWriteProperties properties = readyProperties();
            properties.setEndpoint(URI.create(endpoint));
            assertThat(properties.isReady()).as(endpoint).isFalse();
        }
    }

    @Test
    void weakOrReusedProductionSecretsKeepTheWriterDisabled() {
        KehuzxMcpWriteProperties properties = readyProperties();
        properties.setWriteToken("too-short");
        assertThat(properties.isReady()).isFalse();

        properties = readyProperties();
        properties.setApprovalSigningKey(properties.getWriteToken());
        assertThat(properties.isReady()).isFalse();
    }

    private static KehuzxMcpWriteProperties readyProperties() {
        KehuzxMcpWriteProperties properties = new KehuzxMcpWriteProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://kehuzx-mcp:9100/mcp"));
        properties.setAllowedHost("kehuzx-mcp");
        properties.setAllowedPort(9100);
        properties.setWriteToken("independent-write-token-32-characters");
        properties.setApprovalSigningKey("independent-signing-key-with-at-least-32-bytes");
        return properties;
    }
}
