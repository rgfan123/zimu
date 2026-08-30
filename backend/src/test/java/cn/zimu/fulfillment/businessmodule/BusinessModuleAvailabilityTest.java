package cn.zimu.fulfillment.businessmodule;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.followup.KehuzxMcpProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * 票 03：业务模块开放清单是**功能可用性**事实，判据取自各模块自己的接通开关。
 *
 * <p>它与 {@code MCP_MODULES}（MCP 工具暴露面）是两件事，不共用配置、不互相推导。
 */
class BusinessModuleAvailabilityTest {

    @Test
    void customerCenterIsClosedWhileTheKehuzxReadGatewayIsNotConfigured() {
        BusinessModuleAvailabilityService service =
                new BusinessModuleAvailabilityService(new KehuzxMcpProperties());

        assertThat(service.openModules()).isEmpty();
    }

    @Test
    void customerCenterOpensOnlyWhenTheKehuzxReadGatewayIsReady() {
        BusinessModuleAvailabilityService service =
                new BusinessModuleAvailabilityService(readyKehuzxProperties());

        assertThat(service.openModules()).containsExactly("customer-center");
    }

    @Test
    void controllerExposesTheOpenModulesAsAReadOnlyList() {
        BusinessModuleController controller =
                new BusinessModuleController(new BusinessModuleAvailabilityService(readyKehuzxProperties()));

        assertThat(controller.openModules()).isEqualTo(new BusinessModuleAvailability(java.util.List.of("customer-center")));
    }

    @Test
    void enabledButUnusableEndpointKeepsTheModuleClosed() {
        KehuzxMcpProperties properties = readyKehuzxProperties();
        properties.setReadToken("  ");

        assertThat(new BusinessModuleAvailabilityService(properties).openModules()).isEmpty();
    }

    private static KehuzxMcpProperties readyKehuzxProperties() {
        KehuzxMcpProperties properties = new KehuzxMcpProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://kehuzx-mcp:9100/mcp"));
        properties.setReadToken("read-token");
        return properties;
    }
}
