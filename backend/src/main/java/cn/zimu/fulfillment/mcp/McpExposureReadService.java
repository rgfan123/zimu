package cn.zimu.fulfillment.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 把 {@link McpToolRegistry} 投影成管理员核对视图（票 05）。
 *
 * <p>权威来源只有注册表一处：
 * <ul>
 *   <li>「已开放」不读 {@code app.mcp.modules}，而是看 {@link McpToolRegistry#all()} 里
 *       **真的注册了工具**的模块——配置解析、启动期自检与注册过滤已经在注册表里发生过一次，
 *       这里再解析一遍就等于第二个真源，界面可能显示「开着」而调用方拿不到工具。</li>
 *   <li>「已知但未开放」= {@link McpToolRegistry#knownModules()} 减去上面那批。已知模块由工具
 *       自己声明的 {@link McpTool#module()} 推出，新增模块无需改这里。</li>
 * </ul>
 *
 * <p>顺序是确定的（注册表的 {@code byName} 是 {@code Map.copyOf}，迭代序不保证）：模块按已知
 * 模块的声明顺序，模块内工具按名称升序——同一份配置每次打开视图看到的排布一致，逐条核对才有意义。
 */
@Service
public class McpExposureReadService {

    private final McpToolRegistry registry;

    public McpExposureReadService(McpToolRegistry registry) {
        this.registry = registry;
    }

    public McpExposure exposure() {
        Map<String, List<McpExposureTool>> registeredByModule = new LinkedHashMap<>();
        for (McpTool tool : registry.all()) {
            registeredByModule
                    .computeIfAbsent(tool.module(), module -> new ArrayList<>())
                    .add(new McpExposureTool(tool.name(), tool.description(), tool.readOnly()));
        }

        List<McpExposureModule> open = new ArrayList<>();
        List<String> unopened = new ArrayList<>();
        for (String module : registry.knownModules()) {
            List<McpExposureTool> tools = registeredByModule.get(module);
            if (tools == null || tools.isEmpty()) {
                unopened.add(module);
                continue;
            }
            tools.sort(Comparator.comparing(McpExposureTool::name));
            open.add(new McpExposureModule(module, tools));
        }
        return new McpExposure(open, unopened);
    }
}
