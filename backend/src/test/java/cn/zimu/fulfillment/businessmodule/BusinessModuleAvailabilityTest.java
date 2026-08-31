package cn.zimu.fulfillment.businessmodule;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.followup.KehuzxMcpProperties;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcGatewayProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 票 03：业务模块开放清单是**功能可用性**事实，判据取自各模块自己的接通开关。
 *
 * <p>它与 {@code MCP_MODULES}（MCP 工具暴露面）是两件事，不共用配置、不互相推导。
 *
 * <p>票 08 已落地原料库存只读网关：raw-material-inventory 的判据自此取
 * {@link YuanliaokcGatewayProperties#isReady()}——与客户中心同一形状，
 * 菜单可见与页面取数共用同一份配置完备性判据。
 */
class BusinessModuleAvailabilityTest {

    @Test
    void everyModuleStaysClosedWhileNoGatewayIsConfigured() {
        BusinessModuleAvailabilityService service = new BusinessModuleAvailabilityService(
                new KehuzxMcpProperties(), new YuanliaokcGatewayProperties());

        assertThat(service.openModules()).isEmpty();
    }

    @Test
    void customerCenterOpensOnlyWhenTheKehuzxReadGatewayIsReady() {
        BusinessModuleAvailabilityService service = new BusinessModuleAvailabilityService(
                readyKehuzxProperties(), new YuanliaokcGatewayProperties());

        assertThat(service.openModules()).containsExactly("customer-center");
    }

    @Test
    void rawMaterialInventoryOpensOnlyWhenTheYuanliaokcReadGatewayIsReady() {
        assertThat(BusinessModule.RAW_MATERIAL_INVENTORY.id()).isEqualTo("raw-material-inventory");

        BusinessModuleAvailabilityService service = new BusinessModuleAvailabilityService(
                new KehuzxMcpProperties(), readyYuanliaokcProperties());

        assertThat(service.openModules()).containsExactly("raw-material-inventory");
    }

    @Test
    void bothModulesOpenIndependentlyAndKeepDeclarationOrder() {
        BusinessModuleAvailabilityService service = new BusinessModuleAvailabilityService(
                readyKehuzxProperties(), readyYuanliaokcProperties());

        assertThat(service.openModules()).containsExactly("customer-center", "raw-material-inventory");
    }

    @Test
    void controllerExposesTheOpenModulesAsAReadOnlyList() {
        BusinessModuleController controller = new BusinessModuleController(
                new BusinessModuleAvailabilityService(
                        readyKehuzxProperties(), new YuanliaokcGatewayProperties()));

        assertThat(controller.openModules())
                .isEqualTo(new BusinessModuleAvailability(List.of("customer-center")));
    }

    @Test
    void enabledButUnusableKehuzxEndpointKeepsCustomerCenterClosed() {
        KehuzxMcpProperties properties = readyKehuzxProperties();
        properties.setReadToken("  ");

        assertThat(new BusinessModuleAvailabilityService(properties, new YuanliaokcGatewayProperties())
                        .openModules())
                .isEmpty();
    }

    /** 判据是配置完备性而非单独 enabled 位：缺凭据/端点漂移都必须把模块关死（fail-closed）。 */
    @Test
    void enabledButUnusableYuanliaokcConfigurationKeepsRawMaterialInventoryClosed() {
        YuanliaokcGatewayProperties missingPassword = readyYuanliaokcProperties();
        missingPassword.setPassword("  ");
        assertThat(new BusinessModuleAvailabilityService(new KehuzxMcpProperties(), missingPassword)
                        .openModules())
                .isEmpty();

        YuanliaokcGatewayProperties wrongHost = readyYuanliaokcProperties();
        wrongHost.setEndpoint(URI.create("http://not-yuanliaokc:9200"));
        assertThat(new BusinessModuleAvailabilityService(new KehuzxMcpProperties(), wrongHost)
                        .openModules())
                .isEmpty();

        YuanliaokcGatewayProperties deepPath = readyYuanliaokcProperties();
        deepPath.setEndpoint(URI.create("http://yuanliaokc-api:9200/api/stock"));
        assertThat(new BusinessModuleAvailabilityService(new KehuzxMcpProperties(), deepPath)
                        .openModules())
                .isEmpty();
    }

    private static KehuzxMcpProperties readyKehuzxProperties() {
        KehuzxMcpProperties properties = new KehuzxMcpProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://kehuzx-mcp:9100/mcp"));
        properties.setReadToken("read-token");
        return properties;
    }

    private static YuanliaokcGatewayProperties readyYuanliaokcProperties() {
        YuanliaokcGatewayProperties properties = new YuanliaokcGatewayProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://yuanliaokc-api:9200"));
        properties.setUsername("zimu-gateway");
        properties.setPassword("read-only-secret");
        return properties;
    }
}
