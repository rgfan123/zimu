package cn.zimu.fulfillment.connector.feixiang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 飞象供应商平台「待发货订单」在线拉取客户端。
 *
 * <p><b>2026-08-28 改造：从「下载导出 Excel」改为「调用平台私有 JSON/HTML 接口」。</b></p>
 *
 * <p>为什么改（生产确证的根因）：旧实现走 {@code GET /order/deliveryExport?start_time=&end_time=}
 * 下载 xlsx，但我们传的 {@code start_time}/{@code end_time} <b>平台根本不认</b>——平台列表页
 * 用的是 {@code start_create_time}/{@code end_create_time}。参数名对不上，平台丢弃后回落成
 * 「只返回拉取当天下单的订单」。生产库里历史上拉到的全部 8 行，拉取日与下单日 100% 相同、
 * 跨 5 天两渠道零反例，即为实证。后果是<b>任何没在下单当天被拉到的单永久丢失</b>
 * （已确认丢失实例：飞象订单 D2026826346818550490，2026-08-26 16:58 下单，从未进入系统）。
 * 因此 {@code pullDeliverExport} 已整体删除——留着它就是留着一个「窗口静默失效」的坑。</p>
 *
 * <p>新链路（会话态私有 Web API，依赖 {@code fxqf_sess} Cookie，AJAX 用 form-urlencoded）：
 * <ol>
 *   <li>登录：沿用既有 {@code POST /welcome/index/} 表单 + 302 判定（2026-08-18 抓包实据，未改）；</li>
 *   <li>枚举：{@code GET /esOrder/index/{page}}（返回 HTML，每页 20）按
 *       {@code start_create_time}/{@code end_create_time} 真窗口翻页取全；</li>
 *   <li>详情：{@code POST /order/ajaxGetSendBeforePro}（{@code order_son_id=<数字>}）取 JSON，
 *       这是唯一给出收货信息、下单时间与商品行的接口。</li>
 * </ol>
 *
 * <p><b>范围边界</b>：本类只读。{@code POST /order/ajaxSendOrderProduct}（提交运单号发货）、
 * {@code ajax_change_express}、{@code ajax_get_product_by_sn} 一律<b>未实现</b>——HAR 分析自己
 * 声明没有抓到成功写入与回查，未经验证且会真实改变平台发货状态，留给后续回传票。</p>
 *
 * <p>凭据只走环境变量 {@code FEIXIANG_USERNAME} / {@code FEIXIANG_PASSWORD}，绝不落盘、不打日志。</p>
 */
public interface FeixiangPullClient {

    /** 登录结果；businessCode 复用业务码（CREDENTIALS_REQUIRED / PLATFORM_AUTH_FAILED / OK）。 */
    record LoginResult(boolean ok, String businessCode, String message) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message);
        }
    }

    /**
     * 待发货订单枚举结果。
     *
     * @param orderSonIds        去重后的 order_son_id（详情接口专用数字 ID，<b>不是</b>订单号）
     * @param platformReportedCount 平台 {@code ajaxOrderNum} 自报的区间订单数；未知为 {@code -1}
     * @param truncated          是否因页数上限提前停止（true 时必然有被丢弃的订单，须显式记录）
     */
    record PendingOrderList(
            List<String> orderSonIds,
            int platformReportedCount,
            boolean truncated,
            /**
             * 解析出 0 单时的列表页结构指纹（见 {@link FeixiangListPageFingerprint}）。
             *
             * <p>解析成功时为 null——只有失败才需要它，正常路径不该把页面结构搬进内存。
             */
            String listPageFingerprint) {
        public PendingOrderList {
            orderSonIds = orderSonIds == null ? List.of() : List.copyOf(orderSonIds);
        }

        /** 旧构造保留给不关心指纹的调用方（含测试夹具）。 */
        PendingOrderList(List<String> orderSonIds, int platformReportedCount, boolean truncated) {
            this(orderSonIds, platformReportedCount, truncated, null);
        }

        /** 被丢弃的订单数；平台计数未知时返回 -1（未知就说未知，不猜 0）。 */
        public int droppedCount() {
            if (platformReportedCount < 0) {
                return -1;
            }
            return Math.max(0, platformReportedCount - orderSonIds.size());
        }
    }

    /** 登录（内部读取环境变量凭据；未配置/失败返回失败结果而非抛异常）。 */
    LoginResult login();

    /**
     * 枚举窗口内的待发货订单（翻页取全，不静默截断）。
     *
     * @param startCreateTime 下单开始日期 {@code yyyy-MM-dd}
     * @param endCreateTime   下单结束日期 {@code yyyy-MM-dd}。<b>是否含当日未经验证</b>——
     *                        平台边界语义没抓包确认，而且这一条<b>无法</b>被本类的计数交叉核对
     *                        发现：枚举与计数用的是同一对日期串，平台若把边界理解成别的样子，
     *                        两边会一致地错，{@code collected == reported} 反而给出虚假信心。
     *                        首次真实运行必须人工核对边界当天的订单有没有拿到
     * @throws PullTransportException 传输/业务失败
     */
    PendingOrderList listPendingOrders(String startCreateTime, String endCreateTime);

    /**
     * 取单笔订单详情。
     *
     * @param orderSonId 详情接口专用的<b>数字</b> ID；传订单号（D…）或子订单号（S…）会直接被拒
     * @throws PullTransportException 传输/业务失败
     */
    FeixiangOrderDetail fetchOrderDetail(String orderSonId);

    /** 飞象拉取传输/业务失败；消息不携带任何密钥或请求体。 */
    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** 生产实现：JDK {@link HttpClient} + {@link CookieManager} 自动管理 {@code fxqf_sess} 会话。 */
    @Component("feixiangPullClient")
    class Http implements FeixiangPullClient, FeixiangSession {

        private static final Logger log = LoggerFactory.getLogger(Http.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static final String BASE_URL = "https://ziyousupplier.wowcarp.com";
        private static final String LOGIN_PATH = "/welcome/index/";
        private static final String LOGIN_SUCCESS_PATH = "/product_library/publish_list";
        private static final String LIST_PATH = "/esOrder/index";
        private static final String DETAIL_PATH = "/order/ajaxGetSendBeforePro";
        private static final String COUNT_PATH = "/order/ajaxOrderNum";
        private static final String SESSION_COOKIE = "fxqf_sess";
        private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

        /** 列表页每页条数（HAR 线索：每页 20）。用于判定「不满一页即末页」。 */
        static final int PAGE_SIZE = 20;

        /**
         * 单个状态最多翻的页数上限（20 × 200 = 4000 单）。上限是防御死循环的兜底，
         * 不是业务限制；一旦真的触顶，调用方<b>必须</b>显式记录被丢弃的数量（见
         * {@link PendingOrderList#truncated()}），绝不静默截断——这正是彩食鲜
         * {@code pageSize=10} 写死导致超 10 单静默丢弃的同型缺陷。
         */
        static final int MAX_PAGES = 200;

        /**
         * 「待发货」对应的两个 order_state 取值（HAR 线索：{@code 2,7=待发货}）。
         *
         * <p>平台是否接受 {@code order_state=2,7} 这种逗号列表<b>未经验证</b>，所以这里
         * 逐个状态各拉一轮再取并集：无论平台是「只认单值」「认列表」还是「压根忽略该参数」，
         * 并集都不会漏单，最多是重复拉到同一批 ID 后被去重。宁可多请求一轮，不可漏单。</p>
         */
        private static final List<String> PENDING_ORDER_STATES = List.of("2", "7");

        private final HttpClient client;
        private final String baseUrl;
        private final Function<String, String> environment;
        private volatile boolean authenticated;

        public Http() {
            this(HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(), BASE_URL, System::getenv);
        }

        /** 测试入口：注入 HttpClient（避免测试真连）。 */
        Http(HttpClient client) {
            this(client, BASE_URL, System::getenv);
        }

        /** 测试入口：注入本地 server 与凭据源，精确验证重定向/cookie 契约。 */
        Http(HttpClient client, String baseUrl, Function<String, String> environment) {
            this.client = client;
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.environment = environment;
        }

        @Override
        public synchronized LoginResult login() {
            String username = env("FEIXIANG_USERNAME");
            String password = env("FEIXIANG_PASSWORD");
            if (isBlank(username) || isBlank(password)) {
                return LoginResult.failed("CREDENTIALS_REQUIRED", "飞象凭据未配置（FEIXIANG_USERNAME/FEIXIANG_PASSWORD）");
            }
            if (authenticated && hasSessionCookie()) {
                return new LoginResult(true, "OK", "已复用登录会话");
            }
            try {
                // 1) 引导会话：种下 fxqf_sess cookie
                HttpRequest seed = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + LOGIN_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                HttpResponse<Void> seedResponse = client.send(seed, HttpResponse.BodyHandlers.discarding());
                if (!is2xx(seedResponse.statusCode()) || !hasSessionCookie()) {
                    return LoginResult.failed(
                            "PLATFORM_AUTH_FAILED", "飞象登录失败（会话引导未返回 2xx 或缺少 fxqf_sess）");
                }

                // 2) 表单登录，跟随 302
                String form = "username=" + encode(username) + "&password=" + encode(password);
                HttpRequest loginRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + LOGIN_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> response = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
                String finalPath = response.uri().getPath();
                if (!is2xx(response.statusCode())
                        || !LOGIN_SUCCESS_PATH.equals(finalPath)
                        || !hasSessionCookie()) {
                    authenticated = false;
                    return LoginResult.failed(
                            "PLATFORM_AUTH_FAILED",
                            "飞象登录失败（最终响应必须为 2xx、路径必须匹配抓包成功页且会话 cookie 必须存在）");
                }
                authenticated = true;
                return new LoginResult(true, "OK", "登录成功");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "飞象登录被中断");
            } catch (Exception exception) {
                log.warn("飞象登录失败", exception);
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "飞象登录失败: " + safeMessage(exception));
            }
        }

        @Override
        public PendingOrderList listPendingOrders(String startCreateTime, String endCreateTime) {
            String start = requireDate(startCreateTime, "start_create_time");
            String end = requireDate(endCreateTime, "end_create_time");
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            boolean truncated = false;
            for (String state : PENDING_ORDER_STATES) {
                truncated |= collectState(state, start, end, ids);
            }
            int reported = countPendingOrders(start, end);
            // 一单都没解析出来时，多打一次第一页取结构指纹。这一次额外请求只在故障路径上发生：
            // 没有它，last_error 只剩一句「结构不匹配」，每次排查都得重新抓包（2026-08-29 实证）。
            String fingerprint = ids.isEmpty()
                    ? FeixiangListPageFingerprint.of(safeFirstPage(start, end))
                    : null;
            return new PendingOrderList(List.copyOf(ids), reported, truncated, fingerprint);
        }

        /** 取指纹是诊断行为，本身不得再把拉取打挂——任何异常都降级成一句说明。 */
        private String safeFirstPage(String start, String end) {
            try {
                return getListPage(1, PENDING_ORDER_STATES.getFirst(), start, end);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        /**
         * 翻完某个 order_state 的全部页，把 ID 并进 {@code sink}。
         *
         * <p>翻页停止判据只看<b>本状态自己</b>的采集进展（{@code seen}），刻意不看跨状态的
         * {@code sink}：否则「后一个状态的第 1 页恰好是前一个状态结果的子集」就会被误判成
         * 「平台忽略了页码」而提前停止，把该状态后续页面上的订单静默丢掉。每个状态独立翻页，
         * 多花几次请求，换掉一个会丢单的耦合。</p>
         *
         * @return 是否因页数上限提前停止（true = 有订单被丢弃）
         */
        private boolean collectState(String state, String start, String end, LinkedHashSet<String> sink) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            try {
                for (int page = 1; page <= MAX_PAGES; page++) {
                    String html = getListPage(page, state, start, end);
                    List<String> pageIds = FeixiangOrderListParser.extractOrderSonIds(html);
                    if (pageIds.isEmpty()) {
                        // 末页（或本状态无单）。注意：解析不到 ID 也走这里——是否属于「选择器失效」
                        // 由上层用平台自报计数交叉核对后显式报错，本层不做判断也不静默吞掉。
                        return false;
                    }
                    boolean grew = seen.addAll(pageIds);
                    if (pageIds.size() < PAGE_SIZE) {
                        return false; // 不满一页 = 末页
                    }
                    if (!grew) {
                        // 本状态翻页没带来任何新 ID：平台很可能忽略了页码路径，再翻下去是死循环。
                        log.warn("飞象列表翻页未产生新订单（state={}, page={}），提前停止", state, page);
                        return false;
                    }
                }
            } finally {
                // 无论正常结束还是触顶，已采集到的 ID 都要并入结果，绝不因为提前返回而丢掉。
                sink.addAll(seen);
            }
            log.warn("飞象列表翻页触顶 {} 页（state={}），本状态可能仍有未取回的订单", MAX_PAGES, state);
            return true;
        }

        /** 列表页：第 1 页用 {@code /esOrder/index}，第 N 页用 {@code /esOrder/index/{N}}（HAR 线索）。 */
        private String getListPage(int page, String state, String start, String end) {
            String path = page <= 1 ? LIST_PATH : LIST_PATH + "/" + page;
            // 关键修复：窗口参数是 start_create_time/end_create_time，不是旧实现的 start_time/end_time。
            String query = "?start_create_time=" + encode(start)
                    + "&end_create_time=" + encode(end)
                    + "&order_state=" + encode(state)
                    + "&sel_type=1"
                    + "&keyword=";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path + query))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            HttpResponse<String> response = send(request, "列表页");
            return response.body() == null ? "" : response.body();
        }

        @Override
        public FeixiangOrderDetail fetchOrderDetail(String orderSonId) {
            String id = requireNumericId(orderSonId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + DETAIL_PATH))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/javascript, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .POST(HttpRequest.BodyPublishers.ofString("order_son_id=" + encode(id)))
                    .build();
            HttpResponse<String> response = send(request, "订单详情");
            JsonNode root = parseJson(response.body(), "订单详情");
            if (!statusOk(root)) {
                throw new PullTransportException("飞象订单详情返回失败状态: " + messageOf(root));
            }
            return FeixiangOrderDetail.from(root.path("data"));
        }

        /**
         * 平台自报的区间订单数（{@code ajaxOrderNum}，2026-08-18 抓包实据）。
         *
         * <p>只作<b>交叉核对</b>用：拿它和我们实际枚举到的条数比对，就能发现「HTML 选择器
         * 失效」或「翻页截断」这类静默丢单。任何失败都返回 {@code -1}（未知），绝不让一个
         * 辅助统计接口把整条拉取链路打挂。</p>
         */
        private int countPendingOrders(String start, String end) {
            try {
                String form = "start_create_time=" + encode(start)
                        + "&end_create_time=" + encode(end)
                        + "&order_state="
                        + "&sel_type=1"
                        + "&keyword="
                        + "&start_finish_time="
                        + "&end_finish_time=";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + COUNT_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", USER_AGENT)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    return -1;
                }
                JsonNode root = MAPPER.readTree(response.body());
                if (!statusOk(root)) {
                    return -1;
                }
                JsonNode num = root.path("data").path("num");
                if (num.isNumber()) {
                    return num.asInt();
                }
                return num.isTextual() ? Integer.parseInt(num.asText().trim()) : -1;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Exception exception) {
                log.debug("飞象订单计数交叉核对不可用（不影响拉取）");
                return -1;
            }
        }

        // ------------------------------------------------------- FeixiangSession（回传网关借用会话）

        /**
         * 平台基址。回传网关不自建 baseUrl，跟随拉取侧同一配置，避免读写打到两个环境。
         */
        @Override
        public String baseUrl() {
            return baseUrl;
        }

        /**
         * 在<b>同一个</b> cookie jar 上发出请求，供同包回传网关复用登录态。
         *
         * <p>本方法只是 {@link #send} 的受控出口：会话失效判定、401/403 清登录态、
         * 「302 落回登录页」的识别全部沿用拉取侧既有实现，回传侧不再复制一份。
         * 它<b>不</b>决定请求方法与路径——写请求由 {@code FeixiangHttpShipmentGateway}
         * 在自己的写门闩后面构造，这里不为任何调用方隐含授权。</p>
         */
        @Override
        public HttpResponse<String> exchange(HttpRequest request, String what) {
            return send(request, what);
        }

        // ---------------------------------------------------------------- HTTP 工具

        private HttpResponse<String> send(HttpRequest request, String what) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        authenticated = false;
                    }
                    throw new PullTransportException("飞象" + what + "返回 HTTP " + response.statusCode());
                }
                // 会话失效时平台 302 回登录页；跟随重定向后落在登录路径即判定掉线。
                if (isLoginPath(response.uri().getPath())) {
                    authenticated = false;
                    throw new PullTransportException("飞象" + what + "被重定向回登录页（会话已失效）");
                }
                return response;
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("飞象" + what + "被中断");
            } catch (Exception exception) {
                throw new PullTransportException("飞象" + what + "请求失败: " + safeMessage(exception), exception);
            }
        }

        /**
         * 是否落在登录页。
         *
         * <p>平台对未登录请求 302 回登录页；抓包见过 {@code /welcome/index/} 与
         * {@code /manage} 两种落点，且尾斜杠不稳定，所以按前缀判定而不是精确等值——
         * 判漏一次就会把「登录页 HTML」当成「本区间没有订单」，正是本票要消灭的静默丢单。</p>
         */
        private static boolean isLoginPath(String path) {
            if (path == null) {
                return false;
            }
            String normalized = path.endsWith("/") && path.length() > 1
                    ? path.substring(0, path.length() - 1)
                    : path;
            return "/welcome/index".equals(normalized)
                    || "/welcome".equals(normalized)
                    || "/manage".equals(normalized);
        }

        /** 解析 JSON；拿到 HTML（登录页）时判定会话失效——不把 HTML 当成「没有数据」。 */
        private JsonNode parseJson(String body, String what) {
            if (body == null || body.isBlank()) {
                throw new PullTransportException("飞象" + what + "响应为空");
            }
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("<")) {
                authenticated = false;
                throw new PullTransportException("飞象" + what + "返回 HTML 而非 JSON（可能未登录或会话失效）");
            }
            try {
                return MAPPER.readTree(body);
            } catch (Exception exception) {
                // 不回显响应体：可能含收货人 PII。
                throw new PullTransportException("飞象" + what + "响应不是合法 JSON");
            }
        }

        /** 平台约定 {@code status=1} 为成功（2026-08-18 抓包 ajaxOrderNum 实据）。 */
        private static boolean statusOk(JsonNode root) {
            JsonNode status = root.path("status");
            if (status.isNumber()) {
                return status.asInt() == 1;
            }
            return status.isTextual() && "1".equals(status.asText().trim());
        }

        /** 只取平台 msg，且截断长度；绝不回显 data（含收货人姓名/电话/地址）。 */
        private static String messageOf(JsonNode root) {
            String msg = root.path("msg").asText("");
            if (msg.isBlank()) {
                return "平台未提供原因";
            }
            return msg.length() > 200 ? msg.substring(0, 200) : msg;
        }

        /**
         * 详情接口只接受数字 order_son_id。
         *
         * <p>这是标识符混用的<b>硬门闩</b>：HAR 分析里已经出现过一次把 order_son_id 当
         * order_id 提交、平台回「供应商不正确」的失败。订单号（{@code D…}）与子订单号
         * （{@code S…}）都不是数字，传进来会在发出请求<b>之前</b>就被拒。</p>
         */
        private static String requireNumericId(String orderSonId) {
            String id = orderSonId == null ? "" : orderSonId.trim();
            if (id.isEmpty() || !id.chars().allMatch(Character::isDigit)) {
                throw new PullTransportException("order_son_id 必须是数字 ID（不可传订单号或子订单号）");
            }
            return id;
        }

        /**
         * 窗口日期必须是真实存在的 yyyy-MM-dd。
         *
         * <p>格式或日历不合法时宁可失败，也不发一个平台会静默忽略的请求——「参数不对就被平台
         * 丢弃、回落成默认窗口」正是本票要修的故障。用 {@link java.time.LocalDate#parse} 而不是
         * 只做正则形状校验，{@code 2026-13-99} 这种也会被挡下。</p>
         */
        private static String requireDate(String value, String field) {
            String date = value == null ? "" : value.trim();
            try {
                return java.time.LocalDate.parse(date).toString();
            } catch (RuntimeException exception) {
                throw new PullTransportException(field + " 必须是合法的 yyyy-MM-dd 日期");
            }
        }

        private String env(String name) {
            String value = environment.apply(name);
            return value == null ? "" : value.trim();
        }

        private boolean hasSessionCookie() {
            return client.cookieHandler()
                    .filter(CookieManager.class::isInstance)
                    .map(CookieManager.class::cast)
                    .map(manager -> manager.getCookieStore().getCookies().stream()
                            .anyMatch(cookie -> SESSION_COOKIE.equals(cookie.getName())
                                    && cookie.getValue() != null
                                    && !cookie.getValue().isBlank()))
                    .orElse(false);
        }

        private static boolean is2xx(int statusCode) {
            return statusCode >= 200 && statusCode < 300;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String safeMessage(Throwable exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }

        private static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
