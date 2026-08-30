package cn.zimu.fulfillment.connector.wecom.card.source;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * 待确认清单的表格图渲染（小批切图片：手机上点开即看，不用下载 Excel）。
 *
 * <p>依赖容器内有 CJK 字体（Dockerfile 装 font-noto-cjk + fontconfig），
 * 否则中文全成方块——这是镜像层约束，代码里用逻辑字体 SansSerif 由 fontconfig 解析。
 * 长文本列（地址/明细）按像素宽度折行，最多三行，超出省略——清单以 Excel 兜底，
 * 图片的职责是「一眼核对」，不是存档。
 */
@Service
public class PendingListImageRenderer {

    /** 2x 画布：企微里图片会被压缩，1x 的 13px 字发出去糊成一团。 */
    private static final int SCALE = 2;

    private static final int FONT_SIZE = 13;
    private static final int LINE_HEIGHT = 20;
    private static final int CELL_PADDING = 8;
    private static final int MAX_LINES = 3;

    /** 逻辑列宽（px）：序号/单号/收件人/电话/地址/明细/件数。 */
    private static final int[] DEFAULT_COLUMN_WIDTHS = {44, 150, 84, 112, 330, 320, 54};

    public byte[] render(String title, String[] headers, List<String[]> rows) {
        return render(title, headers, rows, DEFAULT_COLUMN_WIDTHS);
    }

    /** 按当前表格形状指定逻辑列宽；三参旧入口仍固定使用已上线的 7 列尺寸。 */
    public byte[] render(String title, String[] headers, List<String[]> rows, int[] columnWidths) {
        if (columnWidths == null || columnWidths.length == 0) {
            throw new IllegalArgumentException("列宽不能为空");
        }
        for (int width : columnWidths) {
            if (width <= CELL_PADDING * 2) {
                throw new IllegalArgumentException("列宽必须大于单元格左右留白");
            }
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE * SCALE);
        Font bold = font.deriveFont(Font.BOLD);
        Font titleFont = font.deriveFont(Font.BOLD, (FONT_SIZE + 3f) * SCALE);

        int tableWidth = 0;
        for (int width : columnWidths) {
            tableWidth += width * SCALE;
        }
        int margin = 16 * SCALE;
        int titleHeight = 34 * SCALE;
        int headerHeight = (LINE_HEIGHT + 10) * SCALE;

        // 先量后画：行高取决于折行数，必须先用同款字体测出每行的实际行数
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(font);
        FontMetrics metrics = probeGraphics.getFontMetrics();
        List<List<List<String>>> wrapped = new ArrayList<>();
        int[] rowHeights = new int[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            List<List<String>> cells = new ArrayList<>();
            int maxLines = 1;
            String[] row = rows.get(r);
            for (int c = 0; c < columnWidths.length; c++) {
                String value = c < row.length && row[c] != null ? row[c] : "";
                List<String> lines = wrap(value, metrics, (columnWidths[c] - CELL_PADDING * 2) * SCALE);
                maxLines = Math.max(maxLines, lines.size());
                cells.add(lines);
            }
            wrapped.add(cells);
            rowHeights[r] = (maxLines * LINE_HEIGHT + 12) * SCALE;
        }
        probeGraphics.dispose();

        int bodyHeight = 0;
        for (int height : rowHeights) {
            bodyHeight += height;
        }
        int imageWidth = tableWidth + margin * 2;
        int imageHeight = margin + titleHeight + headerHeight + bodyHeight + margin;

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imageWidth, imageHeight);

            g.setColor(new Color(0x22, 0x22, 0x22));
            g.setFont(titleFont);
            g.drawString(title, margin, margin + 20 * SCALE);

            int top = margin + titleHeight;
            int left = margin;

            // 表头
            g.setColor(new Color(0xF2, 0xF3, 0xF5));
            g.fillRect(left, top, tableWidth, headerHeight);
            g.setColor(new Color(0x22, 0x22, 0x22));
            g.setFont(bold);
            int x = left;
            for (int c = 0; c < columnWidths.length; c++) {
                g.drawString(
                        c < headers.length && headers[c] != null ? headers[c] : "",
                        x + CELL_PADDING * SCALE,
                        top + (LINE_HEIGHT + 1) * SCALE);
                x += columnWidths[c] * SCALE;
            }

            // 数据行
            g.setFont(font);
            int y = top + headerHeight;
            for (int r = 0; r < wrapped.size(); r++) {
                if (r % 2 == 1) {
                    g.setColor(new Color(0xFA, 0xFA, 0xFB));
                    g.fillRect(left, y, tableWidth, rowHeights[r]);
                }
                g.setColor(new Color(0x33, 0x33, 0x33));
                x = left;
                for (int c = 0; c < columnWidths.length; c++) {
                    List<String> lines = wrapped.get(r).get(c);
                    int lineY = y + (LINE_HEIGHT - 3) * SCALE;
                    for (String line : lines) {
                        g.drawString(line, x + CELL_PADDING * SCALE, lineY);
                        lineY += LINE_HEIGHT * SCALE;
                    }
                    x += columnWidths[c] * SCALE;
                }
                y += rowHeights[r];
            }

            // 网格线
            g.setColor(new Color(0xDD, 0xDD, 0xDD));
            g.setStroke(new BasicStroke(SCALE));
            int gridBottom = top + headerHeight + bodyHeight;
            g.drawRect(left, top, tableWidth, headerHeight + bodyHeight);
            x = left;
            for (int c = 0; c < columnWidths.length - 1; c++) {
                x += columnWidths[c] * SCALE;
                g.drawLine(x, top, x, gridBottom);
            }
            y = top + headerHeight;
            g.drawLine(left, y, left + tableWidth, y);
            for (int r = 0; r < rowHeights.length - 1; r++) {
                y += rowHeights[r];
                g.drawLine(left, y, left + tableWidth, y);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("待确认清单图片编码失败", ex);
        }
    }

    /** 逐字符量宽折行（CJK 没有空格分词），超过 {@value MAX_LINES} 行省略。 */
    private static List<String> wrap(String value, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            if (metrics.stringWidth(current + ch) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder();
                if (lines.size() == MAX_LINES) {
                    String last = lines.get(MAX_LINES - 1);
                    lines.set(MAX_LINES - 1, last.isEmpty() ? "…" : last.substring(0, last.length() - 1) + "…");
                    return lines;
                }
            }
            current.append(ch);
            i += Character.charCount(codePoint);
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
