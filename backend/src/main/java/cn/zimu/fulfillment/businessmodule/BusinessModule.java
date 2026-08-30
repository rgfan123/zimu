package cn.zimu.fulfillment.businessmodule;

/**
 * 业务模块：外部业务能力在本系统里的接通单位（票 03 / spec unified-business-frontend D3）。
 *
 * <p><b>与 MCP 模块（{@code MCP_MODULES}）不是同一件事，两者不得互相推导。</b>
 * MCP 模块划的是「哪些 MCP 工具暴露给外部 Agent」的访问面；业务模块答的是
 * 「这块业务能力今天接通了吗」——判据来自该能力自己的接通开关。
 *
 * <p>标识是对外契约的一部分（{@code GET /api/v1/business-modules} 与前端外壳的导航过滤都用它），
 * 改名等同于破坏契约。
 */
public enum BusinessModule {

    /** 客户中心（kehuzx）：客户档案与客户跟进的权威来源，经远端只读网关接入。 */
    CUSTOMER_CENTER("customer-center"),

    /**
     * 原料库存（yuanliaokc）：原料、批次与结存事实的权威来源，同样走远端只读网关（票 06）。
     *
     * <p>今天恒为未开放：上游只有 stdio MCP 面、没有 HTTP 接口，本系统够不着，因此本仓
     * 还不存在原料库存的只读网关，也就没有任何接通开关可取（spec D7，前置是票 07/08）。
     * 枚举先声明它，是为了让前端入口从第一天起就由本清单裁定——而不是先无条件显示，
     * 等接通了再回来补一层门。
     */
    RAW_MATERIAL_INVENTORY("raw-material-inventory");

    private final String id;

    BusinessModule(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
