package cn.zimu.fulfillment.businessmodule;

import cn.zimu.fulfillment.followup.KehuzxMcpProperties;
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
 */
@Service
public class BusinessModuleAvailabilityService {

    private final KehuzxMcpProperties kehuzxRead;

    public BusinessModuleAvailabilityService(KehuzxMcpProperties kehuzxRead) {
        this.kehuzxRead = kehuzxRead;
    }

    /** 已开放模块标识，按 {@link BusinessModule} 声明顺序返回。 */
    public List<String> openModules() {
        List<String> open = new ArrayList<>();
        if (kehuzxRead.isReady()) {
            open.add(BusinessModule.CUSTOMER_CENTER.id());
        }
        return List.copyOf(open);
    }
}
