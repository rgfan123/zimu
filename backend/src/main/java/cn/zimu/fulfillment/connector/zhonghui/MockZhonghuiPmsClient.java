package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.BrandView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CaptchaView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CertificationView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateResult;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsVerifyView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LogisticsView;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地中汇 PMS 客户端。与 REAL 客户端共用同一应用边界，返回稳定假数据，不触网。
 * 用于本地联调与批量上传服务的自动化测试：登录恒成功、创建商品校验必填字段后返回成功。
 */
@Service
@ConditionalOnProperty(name = "app.zhonghui-pms.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockZhonghuiPmsClient implements ZhonghuiPmsService {

    /** 1x1 透明 PNG（Base64，无 data URI 前缀），供前端验证码展示。 */
    static final String MOCK_CAPTCHA_IMG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Override
    public boolean authenticated() {
        return true;
    }

    @Override
    public CaptchaView captcha() {
        return new CaptchaView("mock-captcha-20260819", MOCK_CAPTCHA_IMG);
    }

    @Override
    public LoginView login(LoginCommand command) {
        if (command.authCode() == null || command.authCode().isBlank()) {
            return new LoginView(false, "PMS_LOGIN_FAILED", "请输入图片验证码");
        }
        return new LoginView(true, "OK", "");
    }

    @Override
    public List<BrandView> usableBrands() {
        return List.of(new BrandView("164343", "子牧"));
    }

    @Override
    public List<CertificationView> certifications() {
        return List.of(new CertificationView("56118", "默认资质", "2026-01-16", "2027-01-15"));
    }

    @Override
    public List<LogisticsView> logistics() {
        return List.of(new LogisticsView("1", "顺丰速运"), new LogisticsView("20", "京东快递"));
    }

    @Override
    public GoodsVerifyView queryGoods(String goodsItem, String goodsName) {
        if (goodsItem == null || goodsItem.isBlank()) {
            return null;
        }
        return new GoodsVerifyView("560001", "待平台审核", "待上架");
    }

    @Override
    public String uploadImage(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        return "https://img.zhonghuihaotai.com/mock-" + Integer.toHexString(bytes.length) + ".jpeg";
    }

    @Override
    public GoodsCreateResult createGoods(GoodsCreateCommand command) {
        if (command.goodsName() == null || command.goodsName().isBlank()) {
            return new GoodsCreateResult(false, "MOCK_MISSING_FIELD", "商品名称为空");
        }
        if (command.goodsPrice() == null) {
            return new GoodsCreateResult(false, "MOCK_MISSING_FIELD", "商品售价为空");
        }
        if (command.supplyPrice() == null) {
            return new GoodsCreateResult(false, "MOCK_MISSING_FIELD", "供货价为空");
        }
        if (command.brandId() == null) {
            return new GoodsCreateResult(false, "MOCK_MISSING_FIELD", "品牌 ID 未配置");
        }
        return new GoodsCreateResult(true, "OK", "");
    }
}
