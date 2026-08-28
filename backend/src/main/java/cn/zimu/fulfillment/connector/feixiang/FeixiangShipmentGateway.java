package cn.zimu.fulfillment.connector.feixiang;

import java.util.List;
import java.util.Set;

/**
 * 飞象单子单发货的外部 HTTP seam（渠道私有，与彩食鲜/聚福宝各自的网关同构）。
 *
 * <p>三段式，与聚福宝一致：<b>写前只读</b>（{@link #orderDetail}）→ <b>不可逆写</b>
 * （{@link #submit}）→ <b>写后回查</b>（{@link #awaitTrackingApplied}）。飞象的成功响应是
 * {@code {"status":1,"msg":"","data":[]}}，<b>不含任何平台单据号</b>，因此
 * 「平台受理」无法自证成功——回查不是加分项而是<b>唯一</b>的成功凭据。</p>
 */
public interface FeixiangShipmentGateway {

    /** 写后回查的最大轮询次数；平台无异步语义，只为吸收一次写后短暂不一致。 */
    int DEFAULT_VERIFY_ATTEMPTS = 3;

    /** 当前写门闩状态；{@code FeixiangConnector.capabilities()} 据此置位 onlinePush。 */
    FeixiangShipmentWriteMode writeMode();

    /** 写前准备（登录等可安全重试的动作）；失败抛 {@link FeixiangPullClient.PullTransportException}。 */
    void prepareWrite();

    /** 只读取子单详情；不得产生任何远端写效果。 */
    FeixiangOrderDetail orderDetail(String orderSonId);

    /**
     * 提交发货（<b>不可逆</b>）。实现必须在打开 socket 之前最后一次校验写门闩：
     * 门闩未放行时返回 {@link Outcome#NOT_SENT}，且<b>绝不</b>发出请求。
     */
    SubmitResult submit(String orderSonId, FeixiangShipmentRequest request);

    /**
     * 写后回查：重新只读拉取详情，确认目标商品行的 {@code sn} <b>恰好等于</b>我方运单号。
     *
     * <p>判据刻意比 {@link FeixiangOrderDetail.ProductLine#alreadyShipped()} 严一档——
     * 「这一行有运单号」不够，那可能是别人写的；必须是<b>我们这一次</b>写进去的那个号。</p>
     */
    default VerifyResult awaitTrackingApplied(
            String orderSonId, Set<String> orderProductIds, String trackingNumber) {
        VerifyResult latest = VerifyResult.unknown("写后回查尚未执行");
        for (int attempt = 0; attempt < DEFAULT_VERIFY_ATTEMPTS; attempt++) {
            try {
                latest = verifyOnce(orderDetail(orderSonId), orderProductIds, trackingNumber);
            } catch (RuntimeException exception) {
                latest = VerifyResult.unknown("写后回查读取失败");
            }
            if (latest.state() == VerifyState.CONFIRMED) {
                return latest;
            }
        }
        return latest;
    }

    /** 纯判定：详情快照里，全部目标行的 sn 是否都等于我方运单号。 */
    static VerifyResult verifyOnce(
            FeixiangOrderDetail detail, Set<String> orderProductIds, String trackingNumber) {
        if (detail == null || detail.products().isEmpty()) {
            return VerifyResult.unknown("平台详情不可用，无法确认运单号已写入");
        }
        String expected = trackingNumber == null ? "" : trackingNumber.trim();
        if (expected.isEmpty()) {
            return VerifyResult.unknown("回查缺少运单号");
        }
        List<FeixiangOrderDetail.ProductLine> targets = detail.products().stream()
                .filter(line -> orderProductIds.contains(line.orderProductId()))
                .toList();
        if (targets.size() != orderProductIds.size()) {
            return VerifyResult.unknown("平台详情缺少部分目标商品行，无法确认");
        }
        for (FeixiangOrderDetail.ProductLine line : targets) {
            String actual = line.sn() == null ? "" : line.sn().trim();
            if (actual.isEmpty()) {
                return VerifyResult.notConfirmed("平台商品行仍无运单号");
            }
            if (!expected.equals(actual)) {
                // 平台上是另一个运单号：可能是他人并发写入，绝不能当成我们成功。
                return VerifyResult.notConfirmed("平台商品行运单号与本次提交不一致");
            }
        }
        return VerifyResult.confirmed();
    }

    /** 写响应判定；ACCEPTED 也<b>不等于</b>成功，仍须回查。 */
    record SubmitResult(Outcome outcome, String businessCode, String message) {

        public static SubmitResult accepted() {
            return new SubmitResult(Outcome.ACCEPTED, "OK", "平台已受理");
        }

        public static SubmitResult rejected(String businessCode, String message) {
            return new SubmitResult(Outcome.REJECTED, businessCode, message);
        }

        public static SubmitResult unknown(String message) {
            return new SubmitResult(Outcome.UNKNOWN, "RECONCILIATION_REQUIRED", message);
        }

        public static SubmitResult notSent(String businessCode, String message) {
            return new SubmitResult(Outcome.NOT_SENT, businessCode, message);
        }
    }

    /**
     * 写结果三值 + 一个「确定没发出」。
     *
     * <p><b>fail-closed</b>：只有 {@code status==1} 才是 ACCEPTED，{@code status==0} 才是
     * REJECTED，其余一切（未知数值、缺 status、非 JSON、非 2xx、连接异常）一律 UNKNOWN。
     * 失败文案库整个未采样，把未知判成功等于把未知平台行为记成已发货。</p>
     */
    enum Outcome {
        /** 平台明确受理（status=1）。仍须回查才算成功。 */
        ACCEPTED,
        /** 平台明确拒绝（status=0）。未产生发货效果，允许改正后重试。 */
        REJECTED,
        /** 结果不可确定。必须走人工对账，禁止重试。 */
        UNKNOWN,
        /** 写门闩未放行，请求<b>确定没有发出</b>。属安全失败，不进对账。 */
        NOT_SENT
    }

    /** 写后回查结论。 */
    enum VerifyState {
        /** 平台侧目标行的运单号与本次提交一致。 */
        CONFIRMED,
        /** 平台侧读到了，但运单号缺失或不是我们写的。 */
        NOT_CONFIRMED,
        /** 读不到或读失败，无法判断。 */
        UNKNOWN
    }

    record VerifyResult(VerifyState state, String message) {

        public static VerifyResult confirmed() {
            return new VerifyResult(VerifyState.CONFIRMED, "平台已确认运单号");
        }

        public static VerifyResult notConfirmed(String message) {
            return new VerifyResult(VerifyState.NOT_CONFIRMED, message);
        }

        public static VerifyResult unknown(String message) {
            return new VerifyResult(VerifyState.UNKNOWN, message);
        }
    }
}
