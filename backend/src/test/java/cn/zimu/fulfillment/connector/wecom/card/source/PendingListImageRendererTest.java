package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 待确认清单表格图：可解码的 PNG、行数随内容增长、长文本折行不炸。 */
class PendingListImageRendererTest {

    private final PendingListImageRenderer renderer = new PendingListImageRenderer();

    @Test
    void 渲染出可解码的PNG_高度随行数增长() throws IOException {
        String[] row = {"1", "spr0126177993", "张小姐", "13800000000",
                "北京市朝阳区某某街道某某小区 1 号楼 2 单元 303", "子牧精品羊肉礼包 ×1", "1"};
        byte[] one = renderer.render("大者整批待确认 · 1 单",
                BatchPreShipConfirmCardSource.PendingRow.HEADERS, List.<String[]>of(row));
        byte[] three = renderer.render("大者整批待确认 · 3 单",
                BatchPreShipConfirmCardSource.PendingRow.HEADERS, List.of(row, row, row));

        BufferedImage imageOne = ImageIO.read(new ByteArrayInputStream(one));
        BufferedImage imageThree = ImageIO.read(new ByteArrayInputStream(three));
        assertThat(imageOne).isNotNull();
        assertThat(imageThree).isNotNull();
        assertThat(imageOne.getWidth()).isGreaterThan(1000);
        assertThat(imageThree.getHeight()).isGreaterThan(imageOne.getHeight());
    }

    @Test
    void 超长明细折行封顶_不抛异常() throws IOException {
        String longGoods = "法式羊排 ×2、羊蝎子 ×2、羊排块（羊寸排）×2、带骨羊肉块 ×2、带骨羊后腿块 ×2、"
                + "羊腿小切 ×1、精品原切羔羊排 ×4、内蒙古羔羊卷 ×6、盐池滩羊整只礼盒 ×1";
        byte[] png = renderer.render("超长折行",
                BatchPreShipConfirmCardSource.PendingRow.HEADERS,
                List.<String[]>of(new String[] {"1", "spr01-x", "王", "139", longGoods, longGoods, "22"}));

        assertThat(ImageIO.read(new ByteArrayInputStream(png))).isNotNull();
    }
}
