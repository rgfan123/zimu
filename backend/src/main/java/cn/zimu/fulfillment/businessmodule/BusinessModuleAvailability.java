package cn.zimu.fulfillment.businessmodule;

import java.util.List;

/**
 * 只读响应：当前**已开放**的业务模块标识清单。
 *
 * <p>只列开放的，不列未开放的：未开放模块的存在与其未接通的原因都属于部署事实，
 * 前端外壳只需要「能不能用」，多给会把配置细节泄到浏览器。
 */
public record BusinessModuleAvailability(List<String> modules) {

    public BusinessModuleAvailability {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
