package cn.zimu.fulfillment.connector.feixiang;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 飞象平台会话 seam：登录态、Cookie jar 与超时策略的<b>唯一</b>持有者。
 *
 * <p>存在的理由是「一个会话」这条不变式。旧线的回传网关自带一整套登录，且<b>每次</b>
 * 提交都新建 HttpClient 重新引导并登录一次——既浪费一次登录，又让读写两端的会话状态
 * 各自漂移，登录成功判据还比拉取侧宽松。这里反过来：回传网关不持有 HttpClient、不持有
 * cookie、不实现 login，一律通过本接口借用拉取侧那个单例会话。</p>
 *
 * <p>实现由 {@link FeixiangPullClient.Http} 提供（它已经有 {@code volatile authenticated}
 * 复用、{@code fxqf_sess} 存在性校验与「302 回登录页即判掉线」的处理）。</p>
 */
public interface FeixiangSession {

    /** 复用或建立登录会话；失败返回失败结果而非抛异常。 */
    FeixiangPullClient.LoginResult login();

    /** 平台基址（不含尾斜杠）。 */
    String baseUrl();

    /**
     * 在当前会话上发出一次请求。
     *
     * <p>实现负责：4xx/5xx 抛 {@link FeixiangPullClient.PullTransportException}、
     * 401/403 与「跟随重定向后落在登录页」时清除登录态。</p>
     *
     * @param what 诊断用途的中文名，只进日志与异常消息，绝不含请求体
     */
    HttpResponse<String> exchange(HttpRequest request, String what);
}
