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

    /**
     * 票 06：原料库存的入口受本清单裁定，而本仓还没有它的只读网关——所以它必须**永远**
     * 不出现在开放清单里，哪怕客户中心那条链路完全就绪。
     *
     * <p>这条断言的价值在票 08：那时有人接上原料库存网关，必须显式改这里，
     * 而不是让「原料库存好像开了」悄悄发生。
     */
    @Test
    void rawMaterialInventoryStaysClosedWhileThisRepositoryHasNoReadGateway() {
        assertThat(BusinessModule.RAW_MATERIAL_INVENTORY.id()).isEqualTo("raw-material-inventory");

        assertThat(new BusinessModuleAvailabilityService(new KehuzxMcpProperties()).openModules())
                .doesNotContain("raw-material-inventory");
        assertThat(new BusinessModuleAvailabilityService(readyKehuzxProperties()).openModules())
                .doesNotContain("raw-material-inventory");
    }

    private static KehuzxMcpProperties readyKehuzxProperties() {
        KehuzxMcpProperties properties = new KehuzxMcpProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://kehuzx-mcp:9100/mcp"));
        properties.setReadToken("read-token");
        return properties;
    }
}
