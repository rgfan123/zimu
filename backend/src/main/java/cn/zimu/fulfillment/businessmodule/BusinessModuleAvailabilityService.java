package cn.zimu.fulfillment.businessmodule;

import cn.zimu.fulfillment.followup.KehuzxMcpProperties;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcGatewayProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 汇总各业务模块「今天接通了吗」的权威判据。
 *
 * <p>判据一律取自模块自己的接通开关，不新建一份可能与真实链路不同步的开关：
 * 客户中心取 {@link KehuzxMcpProperties#isReady()}——它正是
 * {@code KehuzxMcpReadClient} 抛 {@code KEHUZX_NOT_CONFIGURED} 的同一个判据，
 * 因此「菜单里有」与「点进去能用」不可能分叉。
 *
 * <p>原料库存取 {@link YuanliaokcGatewayProperties#isReady()}——票 08 的只读网关落地后，
 * 判据即网关自己的配置完备性（与客户中心同一形状），页面取数走
 * {@code /api/v1/raw-material-inventory/stock}，同一份配置不完备时那条路挂
 * RAW_MATERIAL_NOT_CONFIGURED，菜单与页面不可能分叉。
 */
@Service
public class BusinessModuleAvailabilityService {

    private final KehuzxMcpProperties kehuzxRead;
    private final YuanliaokcGatewayProperties yuanliaokcRead;

    public BusinessModuleAvailabilityService(
            KehuzxMcpProperties kehuzxRead, YuanliaokcGatewayProperties yuanliaokcRead) {
        this.kehuzxRead = kehuzxRead;
        this.yuanliaokcRead = yuanliaokcRead;
    }

    /** 已开放模块标识，按 {@link BusinessModule} 声明顺序返回。 */
    public List<String> openModules() {
        List<String> open = new ArrayList<>();
        if (kehuzxRead.isReady()) {
            open.add(BusinessModule.CUSTOMER_CENTER.id());
        }
        if (yuanliaokcRead.isReady()) {
            open.add(BusinessModule.RAW_MATERIAL_INVENTORY.id());
        }
        return List.copyOf(open);
    }
}
