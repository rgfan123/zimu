# 01 — WS 长连接客户端选型与协议基线

Type: research
Status: resolved
Blocked by: None — can start immediately

Label: wayfinder:research

## Question

在 Java 21 / Spring Boot 单体中实现企微智能机器人长连接客户端，选哪个技术底座、协议实现基线是什么？

- 候选：JDK 内置 `java.net.http.WebSocket`（零新依赖）/ Spring WebSocket `StandardWebSocketClient` / 第三方库（Java-WebSocket、Netty）。
- 需要确认的协议基线：`aibot_subscribe` 订阅帧与成功判定（errcode=0 后避免反复订阅）；`req_id` 生成与回显关联规则；心跳是业务 JSON 帧 `ping`（非 WS 控制帧），30s 间隔；消息/事件帧的解析与分发框架；连接建立与订阅失败时的可诊断输出（不打印 Secret）。
- 输出：选型结论 + 理由（依赖面、测试友好性、断线重连支持），以及一份最小协议实现基线（帧格式、req_id 规则、订阅时序）。

## Answer

选型结论：**JDK 内置 `java.net.http.WebSocket`**。理由：企微协议是纯 JSON 文本帧、心跳是业务 `ping` 帧（非 WS 控制帧）、任何方案都必须手写断线重连——JDK 方案零新依赖、免疫对 tomcat-embed-websocket 传递依赖的隐性绑定（换 undertow/jetty 即断），测试可用手写 RFC6455 echo 服务器（不依赖容器）。pom 事实：Spring Boot parent 3.5.16、`java.version=21`、WebSocket 零直接声明、全树唯一命中 `tomcat-embed-websocket`。

协议基线：`aibot_subscribe` 一次成功（errcode=0）后不再重复订阅、不打印 secret；回调 `req_id` 原样回传、主动帧自生成；30s 业务 ping + 60–75s 入站看门狗；指数退避重连（单 bot 单连接、重连前关旧实例）；`onText` 按 cmd 分发。详见 `## Findings` 段。

### 1. pom 依赖事实（已用 `mvn -o dependency:tree` 实测验证）

- Parent：`spring-boot-starter-parent:3.5.16`；直接依赖：starter-web / validation / data-jpa / data-redis / actuator、flyway-core + flyway-database-postgresql、postgresql、poi-ooxml 5.4.1（手工 pin）、commons-csv 1.14.1（手工 pin）、京东 ISC 官方 system-scope jar ×2。
- **WebSocket 相关库：pom 零直接声明。** 按 `*websocket* / jakarta.websocket / netty / java-websocket` 过滤全树，唯一命中是 `org.apache.tomcat.embed:tomcat-embed-websocket:10.1.55`（由 starter-web → starter-tomcat 传递引入）。无 spring-websocket、无 spring-messaging、无 netty、无 Java-WebSocket、无独立 jakarta.websocket-api artifact。
- 已验证：`tomcat-embed-websocket` jar 内嵌 `jakarta.websocket` API 类并注册 `META-INF/services/jakarta.websocket.ContainerProvider`（JSR-356 client 容器实现可用——这是候选 (b) 的隐性依赖，换 undertow/jetty starter 即失效）。
- 测试依赖：spring-boot-starter-test（JUnit5 + AssertJ + Mockito + Awaitility + JSONassert）、spring-boot-testcontainers、testcontainers junit-jupiter + postgresql。surefire 未显式声明，继承 Boot parent 的托管版本；无 failsafe、无 jacoco。

### 2. JDK 版本事实

- `properties.java.version = 21`，Boot parent 据此设 `maven.compiler.release=21`（编译目标 Java 21 字节码）。`java.net.http.WebSocket`（自 Java 11 起内置，Java 21 完整可用，wss 走内置 TLS），无需任何新增依赖。

### 3. 现有接线 / 测试基建观察（wecom 包 + 相关测试）

- **配置注入**：`WecomProperties` = `@Component + @ConfigurationProperties(prefix = "app.wecom")`，connections 为 `Map<connectionId, Connection>`；`requireEnabled()` 对缺配抛 `503 WECOM_CONNECTION_NOT_READY`。`application.yml` 中 `app.wecom.connections.business-relay` 全部经环境变量注入（`WECOM_BOT_ID` / `WECOM_CALLBACK_TOKEN` / `WECOM_ENCODING_AES_KEY` 等），`enabled` 默认 false。
- **readiness 风格**：`WecomReadinessService.inspect()` 返回不可变 record `WecomConnectionReadiness`（有序 checks Map + missingRequirements 列表，非密投影、从不输出凭据值）；`WecomReadinessController` 挂 `/api/v1/wecom/connections/{id}/readiness`，要求 `X-Operator` 头；`WecomConnector.testConnection` 恒 `success=false`——配置完整只给 `WECOM_REAL_MESSAGE_ACCEPTANCE_REQUIRED`，从不把配置当验收。
- **现有传输层**：`WecomCallbackController`（HTTP 回调：SHA-1 签名校验 + AES-CBC 解密 → 字段校验 → `MessageSubmissionService.submit(ChannelMessageCommand)` → 加密流式回执）；`WecomCallbackCrypto` 为包私有 final 类，构造器可注入 SecureRandom（可测性模式）。长连接模式回调为**明文 JSON**（map 已确认 from.userid 明文），接收侧不再需要信封解密。
- **测试基建**：connector 测试目录**无 wecom 专属单测**（全 test 树 grep 确认）；wecom 唯一覆盖是 `ConnectorApiTest` 的黑盒 HTTP（`@SpringBootTest(RANDOM_PORT)` + Testcontainers postgres:16-alpine + TestRestTemplate，断言 test-connection 返回 `WECOM_CONNECTION_NOT_READY` 及审计落库）。单测构造风格参考 `JdWarehouseClientSafetyTest`：直接 `new` 真实类 + `Mockito.mock(AuditLogService.class)`，断言稳定错误码与信息不泄露；JD 侧另有 `@TestConfiguration` + 受控 mock bean（ControlledJdClient）模式。
- **WS 使用先例**：全仓 `**/*.java` grep `WebSocket` **零命中**；主代码无 `java.net.http.HttpClient` / WebClient / RestTemplate（出站全走官方 SDK jar，入站是 Spring MVC）——没有现成客户端代码可参照。

### 4. 候选对比表

| 维度 | (a) JDK `java.net.http.WebSocket` | (b) Spring `StandardWebSocketClient` | (c) Java-WebSocket（第三方） |
|---|---|---|---|
| 依赖面 | **0 新依赖**（JDK 21 内置） | +spring-websocket（不在现有树，需显式加；starter 会再带 spring-messaging，可只加 spring-websocket 规避），Boot 管版本 | +org.java-websocket 第三方（需手工 pin 版本，沿用 poi/commons-csv 的 pin 风格） |
| 与项目风格一致性 | 标准库 API + 自管理生命周期；Spring 侧保留 @Component + 定时任务/关闭钩子即可衔接现有风格 | **最贴合** Spring 惯用法（WebSocketHandler/TextWebSocketHandler、Spring 生命周期） | 事件回调风格，与 Spring 管理风格有偏差 |
| 心跳实现成本 | 自建业务 ping 定时器 + 入站看门狗（~几十行） | 同样自建（Spring 无业务帧心跳） | 自带 WS 控制帧 ping 与重连线程，但企微要求**业务 JSON ping**——协议层仍须自建，内置能力基本帮不上 |
| 断线重连成本 | onClose/onError 手动重连 + 退避（三案都需手写，无差异） | afterConnectionClosed 手动重连 | 内置 connectionLost 重连线程（唯一增量，价值有限） |
| 测试友好性 | 纯 JDK 手写 RFC6455 echo 服务器（ServerSocket 握手 + 帧编解码 ~100 行）或嵌入式 Tomcat 回环；不依赖 testcontainers | 同模块嵌入式 Tomcat + JSR-356 @ServerEndpoint 回环（最 Spring 原生），WebSocketClient 接口可 Mockito mock | 同样需手写/回环服务器；无标准 testcontainers 模块 |
| 隐性风险 | 无（不依赖任何传递依赖） | 依赖 classpath 上有 JSR-356 client 容器——当前恰好由 tomcat-embed-websocket 提供，换容器 starter 即断 | 供应链面最大；单连接 JSON 帧场景无协议层收益 |

（Netty 候选已排除：单条常驻 JSON 连接无需引入 netty 依赖树。）

### 5. 选型结论：选 (a) JDK `java.net.http.WebSocket`

理由：
1. **协议是纯 JSON 文本帧**，JDK 客户端原生处理（自动拼 continuation 帧、wss 走内置 TLS），不需要 JSR-356/Spring 适配层；
2. **心跳与重连任何方案都必须手写**——企微心跳是业务 JSON 帧 `ping`（非 WS 控制帧），Spring 与 JDK 客户端都不自动重连；因此 (a) 无额外缺失，Java-WebSocket 的内置重连/控制帧心跳对协议层帮助为零；
3. **测试不依赖容器**：手写 RFC6455 echo 服务器即可确定性验证订阅/心跳/重连时序，testcontainers 与第三方版本 pin 都不需要；
4. **依赖面最小且免疫传递漂移**：tomcat-embed-websocket 在 classpath 这一事实对 (b) 是隐性依赖（换 undertow/jetty 即断），对 (a) 无关；(c) 引入第三方供应链面换不来协议层收益；
5. 与仓库保守依赖风格（仅官方 SDK + Boot 管理 starter + 少量手工 pin）一致——标准库不算引入新依赖。

备选：若团队明确倾向 Spring 抽象，(b) 可接受（加一个 Boot 托管 starter，测试用嵌入式 Tomcat 回环），但需在代码注释/决策记录中钉住 JSR-356 容器依赖。

### 6. 协议实现基线要点（最小实现）

- **端点**：`wss://openws.work.weixin.qq.com`，TLS 长连接，无需自定义 header。
- **订阅帧**：连接建立后立即发 `aibot_subscribe`（JSON 文本帧，携带 `bot_id` + `secret`；secret 不打印、不入日志、不进入 readiness 投影）；以 `errcode=0` 为成功判定，成功后连接存活期内**只发一次、不重复订阅**；订阅失败 → 关闭连接进入重连退避（防狂刷）。
- **req_id 规则**：回调帧（`aibot_msg_callback` / `aibot_event_callback`）自带 `req_id`，被动回复 `aibot_respond_msg` **原样回传**该 `req_id` 完成关联；客户端主动帧（订阅/心跳）自生成唯一 `req_id`（UUID 或 时间戳+自增序号）用于日志关联；对回调按 `req_id`/`msgid` 做幂等保护（与现有 submission 链路衔接，细节归 04 票）。
- **心跳**：自建定时器发业务 JSON 帧 `ping`，30s 间隔（非 WS 控制帧）；同时运行入站看门狗——超过 ~60–75s 无任何入站帧视为僵死，主动关闭重连。
- **断线重连最小实现**：onClose/onError → 指数退避（1s 起步、翻倍、30s 封顶 + 抖动）→ 重连 → 重新订阅；`disconnected_event`（被踢）同样走重连退避（细节归 03 票）；单 bot 单连接（新连接踢旧连接），全局仅一个活跃连接实例，重连前必须先关闭旧实例。
- **帧分发**：onText → JSON `type` 字段路由：`aibot_msg_callback` → 复用 `MessageSubmissionService.submit(ChannelMessageCommand)` 链路（明文 JSON，无需 WecomCallbackCrypto）；`aibot_event_callback` → 事件留档（落库与否归 04 票）；`aibot_subscribe` 响应 / `pong` → 连接状态机推进。
- **诊断**：连接/订阅状态作为 readiness 输入，沿用 `WecomConnectionReadiness` 非密投影风格（不打印 Secret）；失败原因可诊断输出（升级失败、TLS 错误、订阅 errcode/errmsg）。
