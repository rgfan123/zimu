package cn.zimu.fulfillment.businessmodule;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读端点：前端外壳启动时读取当前已开放的业务模块，据此过滤导航树（票 03）。
 *
 * <p>只读、无副作用、无参数——它回答的是部署事实，不接受调用方指定视角。
 */
@RestController
@RequestMapping("/api/v1/business-modules")
public class BusinessModuleController {

    private final BusinessModuleAvailabilityService service;

    public BusinessModuleController(BusinessModuleAvailabilityService service) {
        this.service = service;
    }

    @GetMapping
    public BusinessModuleAvailability openModules() {
        return new BusinessModuleAvailability(service.openModules());
    }
}
