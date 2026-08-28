package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 只通过容器魔数和精确表头指纹识别来源，不使用文件名或数据内容猜测。 */
@Service
class SourceFileParser {

    private static final Logger log = LoggerFactory.getLogger(SourceFileParser.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<SourceChannel, Set<String>> FINGERPRINTS = fingerprints();
    private static final Set<String> FEIXIANG_V2_FINGERPRINT = Set.of(
            "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "商品数量",
            "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流单号");
    /** 万齐订单管理导出的有序 52 列版本；回填依赖原列顺序，增删或换序均视为新模板。 */
    private static final List<String> WANQI_52_HEADERS = List.of(
            "收货人姓名", "收货人手机号", "详细地址", "商品名称", "规格信息", "商品类型", "品牌",
            "一级分类", "二级分类", "三级分类", "一级逻辑分类", "二级逻辑分类", "三级逻辑分类",
            "售价", "购买数量", "成本价", "结算价", "优惠类型", "优惠金额", "供应商", "商品来源",
            "子订单状态", "售后状态", "退款类型", "供应商发货时间", "确认收货时间", "申请退款时间",
            "售后完成时间", "用户备注", "商家/客服备注", "订单处理形式", "订单ID", "聚合ID", "子订单ID",
            "供应商单号", "商品id", "供应商商品id", "门店id", "供应商sku id", "服务时效", "期望时间",
            "物流信息", "crm 单号", "订单总金额", "skuid", "sku名称", "不含运毛利额", "不含运毛利率",
            "含运毛利额", "含运毛利率", "订单类型", "实物售后");
    private static final Set<String> WANQI_PENDING_STATUSES = Set.of("超时未发货", "待发货");
    /**
     * 大者 v2：11 列「订单往返表」——渠道发订单给我们，我们发完货把后两列填回去还它。
     *
     * <p>与 v1（15 列，{@code 渠道订单号/主商品编码/…/快递单号/快递公司}）是两份不同的导出，
     * 需要共存。两者必填集互斥（v1 有 主商品编码/快递单号，v2 有 主订单号/物流单号），
     * 不会同时命中。
     *
     * <p><b>刻意不把「价格」「合计」列进必填</b>：金额列是各家导出里最容易增删的，
     * 拿它当身份特征，改版一次就全认不出来了。身份靠订单号、收件人、物流回填列。
     *
     * <p><b>这份表没有商品编码</b>，只有商品名称——所以映射只能按名称做，见 {@code dazheV2}。
     */
    private static final Set<String> DAZHE_V2_FINGERPRINT = Set.of(
            "编号", "主订单号", "商品名称", "数量",
            "收件人", "收件人电话", "收件人地址", "物流公司", "物流单号");

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);

    /**
     * 只读表头做模板识别：认得出就返回来源渠道，认不出返回 empty。
     *
     * <p><b>为什么需要它</b>：企微单聊收到的文件此前一律被当成 24 列运单回传表
     * （{@code WecomTrackingFileProcessor} 的硬编码路径），渠道发来的订单表因此一律报
     * 「不符合精确 24 列模板」。要分岔就得先能问一句「这是不是来源订单表」，
     * 而指纹全是本类的 private static。
     *
     * <p><b>认不出返回 empty 而不是抛异常</b>：认不出是正常情况——用户发的可能本来就是
     * 运单回传表。用异常表达正常分支，会逼调用方 try/catch，而 catch 块最容易把真错误一起吞掉。
     *
     * <p>无副作用，可重复调用；不解析行，因此比 {@link #parse} 便宜得多。
     */
    public Optional<SourceChannel> detectChannel(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return isWorkbook(bytes) ? detectWorkbookChannel(bytes) : detectCsvChannel(bytes);
        } catch (Exception exception) {
            // 连文件都打不开，那就不是任何已知来源模板；判定失败等同于认不出
            return Optional.empty();
        }
    }

    private Optional<SourceChannel> detectWorkbookChannel(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Set<SourceChannel> hits = new java.util.LinkedHashSet<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<String> headers = headers(sheet.getRow(0));
                for (SourceChannel channel : SourceChannel.values()) {
                    if (channel == SourceChannel.WECOM || !eligibleSheet(channel, sheetIndex, sheet.getSheetName())) {
                        continue;
                    }
                    if (matches(channel, headers)) {
                        hits.add(channel);
                    }
                }
            }
            // 命中多个渠道时不猜：交给 parse() 去抛 TEMPLATE_FINGERPRINT_AMBIGUOUS，
            // 那里的报错信息才说得清是哪几个撞了
            return hits.size() == 1 ? Optional.of(hits.iterator().next()) : Optional.empty();
        }
    }

    private Optional<SourceChannel> detectCsvChannel(byte[] bytes) throws IOException {
        DecodedCsv decoded = decodeCsv(bytes);
        String text = decoded.text();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(new StringReader(text))) {
            List<String> headers = parser.getHeaderNames().stream().map(this::normalizeHeader).toList();
            boolean v1 = matches(headers, FINGERPRINTS.get(SourceChannel.FEIXIANG));
            boolean v2 = matches(headers, FEIXIANG_V2_FINGERPRINT);
            // 与 parseCsv 同构：恰好命中一套才算认出
            return v1 ^ v2 ? Optional.of(SourceChannel.FEIXIANG) : Optional.empty();
        }
    }

    ParsedSourceFile parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.unprocessable("EMPTY_FILE", "上传文件为空");
        }
        try {
            return isWorkbook(bytes) ? parseWorkbook(bytes) : parseCsv(bytes);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable(
                    "FILE_READ_FAILED", "文件无法识别，请确认文件未损坏且格式为 Excel 或 CSV 后重试");
        }
    }

    private static boolean isWorkbook(byte[] bytes) {
        return startsWith(bytes, new byte[] {'P', 'K'}) || startsWith(bytes, OLE2_MAGIC);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private ParsedSourceFile parseWorkbook(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<SheetCandidate> matches = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<String> headers = headers(sheet.getRow(0));
                for (SourceChannel channel : SourceChannel.values()) {
                    if (channel == SourceChannel.WECOM || !eligibleSheet(channel, sheetIndex, sheet.getSheetName())) {
                        continue;
                    }
                    if (matches(channel, headers)) {
                        matches.add(new SheetCandidate(channel, sheet, sheetIndex, headers));
                    }
                }
            }
            SheetCandidate candidate = unique(matches);
            List<ParsedSourceRow> rows = new ArrayList<>();
            for (int index = 1; index <= candidate.sheet().getLastRowNum(); index++) {
                Map<String, String> cells = cells(candidate.headers(), candidate.sheet().getRow(index));
                if (cells.values().stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (candidate.channel() == SourceChannel.JUFUBAO && isJufubaoSummary(cells)) {
                    break;
                }
                if ((candidate.channel() == SourceChannel.DAZHE || candidate.channel() == SourceChannel.WANGQI)
                        && isWangqiPurchaseTotal(cells)) {
                    continue;
                }
                if (candidate.channel() == SourceChannel.DAZHE && isDazheV2(cells) && isDazheV2Summary(cells)) {
                    continue;
                }
                rows.add(map(candidate.channel(), candidate.sheet().getSheetName(), candidate.sheetIndex(), index + 1, cells));
            }
            List<ParsedSourceRow> validatedRows = candidate.channel() == SourceChannel.WANQI
                    ? validateWanqi52Identities(rows)
                    : rows;
            return parsed(
                    candidate.channel(), candidate.headers(), false,
                    templateVersion(candidate.channel(), candidate.headers()), validatedRows);
        }
    }

    private ParsedSourceFile parseCsv(byte[] bytes) throws IOException {
        DecodedCsv decoded = decodeCsv(bytes);
        String text = decoded.text();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(new StringReader(text))) {
            List<String> headers = parser.getHeaderNames().stream().map(this::normalizeHeader).toList();
            boolean v1 = matches(headers, FINGERPRINTS.get(SourceChannel.FEIXIANG));
            boolean v2 = matches(headers, FEIXIANG_V2_FINGERPRINT);
            if (v1 == v2) {
                throw fingerprintError(v1 ? 2 : 0);
            }
            assertNoDuplicateKeyHeaders(headers, v2 ? FEIXIANG_V2_FINGERPRINT : FINGERPRINTS.get(SourceChannel.FEIXIANG));
            List<ParsedSourceRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> cells = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    cells.put(headers.get(index), index < record.size() ? record.get(index) : "");
                }
                if (cells.values().stream().allMatch(String::isBlank)) {
                    continue;
                }
                rows.add(map(SourceChannel.FEIXIANG, "CSV", 0, Math.toIntExact(record.getRecordNumber() + 1), cells));
            }
            String version = v2
                    ? "v2-" + decoded.encodingName() + "-" + decoded.newlineName()
                    : "v1";
            return parsed(SourceChannel.FEIXIANG, headers, true, version, rows);
        }
    }

    private ParsedSourceFile parsed(
            SourceChannel channel, List<String> headers, boolean csv, String version, List<ParsedSourceRow> rows) {
        return new ParsedSourceFile(
                channel,
                channel.name() + "_SOURCE_ORDER",
                version,
                channel.name() + "-" + version + "-" + digest(String.join("\u001f", headers)).substring(0, 16),
                csv,
                List.copyOf(rows));
    }

    private ParsedSourceRow map(
            SourceChannel channel, String sheetName, int sheetIndex, int rowIndex, Map<String, String> cells) {
        return switch (channel) {
            case CAISHIXIAN -> caishixian(sheetName, sheetIndex, rowIndex, cells);
            case DAZHE -> isDazheV2(cells)
                    ? dazheV2(sheetName, sheetIndex, rowIndex, cells)
                    : wangqi(sheetName, sheetIndex, rowIndex, cells);
            case JUFUBAO -> jufubao(sheetName, sheetIndex, rowIndex, cells);
            case FEIXIANG -> feixiang(sheetName, sheetIndex, rowIndex, cells);
            case ZHONGHUI -> zhonghui(sheetName, sheetIndex, rowIndex, cells);
            case WANGQI -> wangqi(sheetName, sheetIndex, rowIndex, cells);
            case WANQI -> wanqi52(sheetName, sheetIndex, rowIndex, cells);
            case WECOM -> throw new IllegalArgumentException("WECOM is not a file source adapter");
        };
    }

    /**
     * 解析投影：按渠道模板从原始单元格提取白名单核对字段（收货人/电话/地址/商品/规格/数量/来源 SKU）。
     *
     * <p>供「确认明细」展示解析结果、核对基本信息是否解析正确；只返回这些键，不透出其他单元格。
     *
     * <p>复用 {@link #map} 整条解析管线（而非复制提取逻辑），保证确认明细展示的解析值
     * 与导入落单（canonical）时实际使用的值同源一致，模板列名变更时两处同步漂移。
     */
    public Map<String, String> projection(SourceChannel channel, Map<String, String> cells) {
        ParsedSourceRow parsed = map(channel, "", 0, 0, cells);
        Map<String, String> projection = new LinkedHashMap<>();
        putIfPresent(projection, "receiver_name", parsed.receiverName());
        putIfPresent(projection, "receiver_phone", parsed.receiverPhone());
        putIfPresent(projection, "receiver_address", parsed.receiverAddress());
        putIfPresent(projection, "product_name", parsed.productName());
        putIfPresent(projection, "quantity", parsed.quantity());
        putIfPresent(projection, "specification", parsed.specification());
        putIfPresent(projection, "source_sku_ref", parsed.sourceSkuRef());
        if (channel == SourceChannel.WANQI) {
            putIfPresent(projection, "source_line_ref", parsed.sourceLineRef());
        }
        return projection;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private ParsedSourceRow caishixian(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        String address = join(cells, "省", "市", "区", "详细地址");
        return row(
                sheet, sheetIndex, row, cells,
                first(cells, "主订单编号", "子订单编号"), value(cells, "子订单编号"),
                value(cells, "站点编码"), value(cells, "站点编码"),
                value(cells, "收货人"), value(cells, "联系电话"), address,
                value(cells, "省"), value(cells, "市"), value(cells, "区"),
                value(cells, "商品编号"), value(cells, "商品名称"), value(cells, "规格"), value(cells, "单位"),
                value(cells, "下单数量"), null, "OTHER", value(cells, "订单备注"), false);
    }

    private ParsedSourceRow jufubao(String sheet, int sheetIndex, int row, Map<String, String> original) {
        Map<String, String> cells = new LinkedHashMap<>();
        original.forEach((key, value) -> cells.put(key, value.replaceFirst("[\\t\\s]+$", "")));
        return row(
                sheet, sheetIndex, row, cells,
                first(cells, "主单号", "拆单号"), value(cells, "拆单号"),
                value(cells, "渠道订单号"), value(cells, "供货商"),
                value(cells, "收货人姓名"), value(cells, "收货人电话"), value(cells, "收货地址"),
                "", "", "",
                first(cells, "商品ID", "商品编码", "商品条码"), value(cells, "商品名称"), value(cells, "规格"), value(cells, "单位"),
                value(cells, "数量"), parseTime(value(cells, "下单时间")), settlement(value(cells, "结算方式")),
                value(cells, "订单备注"), false);
    }

    /**
     * 飞象来源行。已发货的行同样拦下——{@code 物流状态/物流公司/物流单号} 就在飞象指纹里，
     * 此前一直没人读（与中汇同型缺陷，2026-08-27 一并堵上）。
     */
    private ParsedSourceRow feixiang(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        ParsedSourceRow parsed = feixiangRow(sheet, sheetIndex, row, cells);
        if (!parsed.valid()) {
            return parsed;
        }
        if ("已发货".equals(value(cells, "物流状态"))) {
            return withError(parsed, "SOURCE_ORDER_ALREADY_FULFILLED", "飞象来源行已标记发货，不重复建单");
        }
        if (!value(cells, "物流单号").isBlank() || !value(cells, "物流公司").isBlank()) {
            return withError(parsed, "SOURCE_ORDER_ALREADY_FULFILLED", "飞象来源行已有物流事实，不重复建单");
        }
        return parsed;
    }

    private ParsedSourceRow feixiangRow(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        return row(
                sheet, sheetIndex, row, cells,
                value(cells, "订单号"), value(cells, "订单商品ID"),
                value(cells, "会员名称"), value(cells, "会员名称"),
                value(cells, "收货人姓名"), value(cells, "收货人手机号"), value(cells, "收货人地址"),
                "", "", "",
                first(cells, "商品ID", "订单商品ID"), value(cells, "商品名称"),
                first(cells, "规格", "商品规格"), value(cells, "单位"),
                first(cells, "可发货数量", "商品数量"), parseTime(value(cells, "下单时间")), "OTHER",
                value(cells, "备注"), true);
    }

    /**
     * 中汇来源行。
     *
     * <p><b>已发货的行必须拦下。</b>2026-08-27 生产实证：用户转发的表里混着两行
     * 「发货状态=已发货 / 物流单号=SF1220303588771、JDVA46735986612」的历史单，
     * 解析器把它们当成待发新单建了出来，还推了「确认发货」卡——点下去就会给
     * 已用顺丰/京东发出的货再建一张真实京东出库单，重复发货。
     *
     * <p>万齐适配器早有同型拦截（SOURCE_ORDER_ALREADY_FULFILLED），中汇漏了。
     * 判据取来源自己给的事实：发货状态/订单状态说已发货，或物流公司/物流单号已有值。
     * 不猜、不跳过——落成带错误码的行，人能看见为什么没进来。
     *
     * <p><b>更正（2026-08-28 生产实测）</b>：这里原先写「让它出现在复核队列里」，是错的。
     * 复核事项挂在订单上（{@code review_cases.order_id}），而被本方法拦下的行<b>压根没建单</b>，
     * 结构上就生不出复核事项——生产库里这类行对应的 {@code review_cases} 一条都没有。
     * 它们的可见性由另外两处提供：批次行清单里带 error_code/error_detail 原样可查；
     * 确认闸门投影 {@link SourceBatchConfirmReadiness} 把它们计入
     * {@code benign_skipped_rows} 并在工作台待确认区显示。既然没建单，确认发货结构上
     * 碰不到它们，所以它们也不阻断整批确认（口径见 {@code SourceBatchConfirmReadiness}）。
     */
    private ParsedSourceRow zhonghui(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        ParsedSourceRow parsed = zhonghuiRow(sheet, sheetIndex, row, cells);
        if (!parsed.valid()) {
            return parsed;
        }
        if ("已发货".equals(value(cells, "发货状态")) || "已发货".equals(value(cells, "订单状态"))) {
            return withError(parsed, "SOURCE_ORDER_ALREADY_FULFILLED", "中汇来源行已标记发货，不重复建单");
        }
        if (!value(cells, "物流单号").isBlank() || !value(cells, "物流公司").isBlank()) {
            return withError(parsed, "SOURCE_ORDER_ALREADY_FULFILLED", "中汇来源行已有物流事实，不重复建单");
        }
        return parsed;
    }

    private ParsedSourceRow zhonghuiRow(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        return row(
                sheet, sheetIndex, row, cells,
                value(cells, "订单号"), value(cells, "商品编号"),
                "", "",
                value(cells, "收件人"), value(cells, "收件电话"), value(cells, "收件地址"),
                "", "", "",
                value(cells, "商品编号"), value(cells, "商品名称"),
                value(cells, "包装规格"), value(cells, "单位"),
                value(cells, "件数"), parseTime(value(cells, "下单时间")), "OTHER",
                value(cells, "用户留言"), true);
    }

    /**
     * 大者 v2（11 列）行映射。
     *
     * <p><b>商品标识用商品名称</b>：这份导出里根本没有商品编码列（三个 sheet 全查过），
     * 名称是唯一稳定的标识。代价是运营要为每个礼包在 {@code source_channel_bundles}
     * 按名称配一条映射——{@code source_bundle_name} 本来就在那张表里，配得上。
     * 硬要伪造一个编码只会让映射错得更隐蔽。
     *
     * <p><b>物流公司 / 物流单号 两列是空的，这是正常的</b>：它们是留给我们发完货填回去的，
     * 不参与解析，也不该因为空而报错。
     *
     * <p>{@code 编号} 是表内行号（1、2、3…），只在本文件内唯一，跨文件会重复，
     * 因此当行标识而不是订单标识——订单标识用 {@code 主订单号}。
     */
    private ParsedSourceRow dazheV2(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        String productName = value(cells, "商品名称");
        return row(
                sheet, sheetIndex, row, cells,
                value(cells, "主订单号"), value(cells, "编号"),
                "", "",
                value(cells, "收件人"), value(cells, "收件人电话"), value(cells, "收件人地址"),
                "", "", "",
                productName, productName,
                "", "件",
                value(cells, "数量"), null, "OTHER",
                "", true);
    }

    private ParsedSourceRow wangqi(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        String paidAt = value(cells, "渠道支付时间");
        return row(
                sheet, sheetIndex, row, cells,
                value(cells, "渠道订单号"), value(cells, "主商品编码"),
                "", "",
                value(cells, "收货人"), value(cells, "收货人手机"), value(cells, "收货人详细地址"),
                "", "", "",
                value(cells, "主商品编码"), value(cells, "商品名称"),
                "", "件",
                value(cells, "商品数量"), parseTime(first(cells, "渠道支付时间", "渠道下单时间")),
                paidAt.isBlank() ? "OTHER" : "IMMEDIATE", "", true);
    }

    private ParsedSourceRow wanqi52(String sheet, int sheetIndex, int row, Map<String, String> cells) {
        ParsedSourceRow parsed = row(
                sheet, sheetIndex, row, cells,
                value(cells, "订单ID"), value(cells, "子订单ID"),
                "", "",
                value(cells, "收货人姓名"), value(cells, "收货人手机号"), value(cells, "详细地址"),
                "", "", "",
                value(cells, "skuid"), value(cells, "商品名称"), value(cells, "规格信息"), "件",
                value(cells, "购买数量"), null, "UNSPECIFIED", wanqiRemark(cells), true);
        if (!parsed.valid()) {
            return parsed;
        }
        if (value(cells, "子订单ID").isBlank()) {
            return withError(parsed, "SOURCE_LINE_REF_REQUIRED", "万齐来源行缺少子订单 ID");
        }
        if (!"实体商品".equals(value(cells, "商品类型")) || !"销售订单".equals(value(cells, "订单类型"))) {
            return withError(parsed, "SOURCE_ORDER_TYPE_BLOCKED", "万齐来源行不是可发货的实体销售订单");
        }
        if (!WANQI_PENDING_STATUSES.contains(value(cells, "子订单状态"))) {
            return withError(parsed, "SOURCE_ORDER_STATUS_BLOCKED", "万齐子订单状态不是明确待发货状态");
        }
        if (!value(cells, "供应商发货时间").isBlank()
                || !value(cells, "确认收货时间").isBlank()
                || !value(cells, "物流信息").isBlank()) {
            return withError(parsed, "SOURCE_ORDER_ALREADY_FULFILLED", "万齐来源行已有发货、收货或物流事实");
        }
        if (!value(cells, "退款类型").isBlank()
                || !value(cells, "申请退款时间").isBlank()) {
            return withError(parsed, "SOURCE_ORDER_REFUND_BLOCKED", "万齐来源行存在退款事实");
        }
        if (!value(cells, "售后状态").isBlank()
                || !value(cells, "售后完成时间").isBlank()) {
            return withError(parsed, "SOURCE_ORDER_AFTER_SALES_BLOCKED", "万齐来源行存在售后事实");
        }
        return parsed;
    }

    private List<ParsedSourceRow> validateWanqi52Identities(List<ParsedSourceRow> rows) {
        Map<String, Long> counts = rows.stream()
                .filter(row -> row.sourceOrderRef() != null && row.sourceLineRef() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> row.sourceOrderRef() + "\u001f" + row.sourceLineRef(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        return rows.stream().map(row -> {
            String key = row.sourceOrderRef() + "\u001f" + row.sourceLineRef();
            return counts.getOrDefault(key, 0L) > 1
                    ? withError(row, "SOURCE_LINE_REF_DUPLICATE", "同一万齐订单内子订单 ID 重复")
                    : row;
        }).toList();
    }

    private String wanqiRemark(Map<String, String> cells) {
        return java.util.stream.Stream.of(
                        labeled("用户备注", value(cells, "用户备注")),
                        labeled("商家/客服备注", value(cells, "商家/客服备注")),
                        labeled("crm 单号", value(cells, "crm 单号")))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private String labeled(String label, String value) {
        return value.isBlank() ? "" : label + "：" + value;
    }

    private ParsedSourceRow withError(ParsedSourceRow row, String errorCode, String errorMessage) {
        return new ParsedSourceRow(
                row.sheetName(), row.sheetIndex(), row.rowIndex(), row.rawCells(), row.sourceOrderRef(), row.sourceLineRef(),
                row.sourceCustomerRef(), row.customerName(), row.receiverName(), row.receiverPhone(), row.receiverAddress(),
                row.receiverProvince(), row.receiverCity(), row.receiverDistrict(), row.sourceSkuRef(), row.productName(),
                row.specification(), row.unit(), row.quantity(), row.orderedAt(), row.settlementMethod(), row.remark(),
                errorCode, errorMessage);
    }

    private ParsedSourceRow row(
            String sheet, int sheetIndex, int row, Map<String, String> cells,
            String orderRef, String lineRef, String customerRef, String customerName,
            String receiverName, String receiverPhone, String receiverAddress,
            String province, String city, String district,
            String sourceSkuRef, String productName, String specification, String unit,
            String quantity, Instant orderedAt, String settlementMethod, String remark, boolean orderRefRequired) {
        String error = null;
        if ((orderRefRequired && blank(orderRef)) || blank(receiverName) || blank(receiverPhone)
                || blank(receiverAddress) || blank(sourceSkuRef) || blank(productName) || blank(quantity)) {
            error = "来源行缺少订单号、收货人、商品或数量必填值";
        } else {
            try {
                BigDecimal parsed = new BigDecimal(quantity);
                if (parsed.signum() <= 0) {
                    error = "数量必须大于 0";
                } else if (parsed.stripTrailingZeros().scale() > 3) {
                    return build(sheet, sheetIndex, row, cells, orderRef, lineRef, customerRef, customerName,
                            receiverName, receiverPhone, receiverAddress, province, city, district, sourceSkuRef,
                            productName, specification, unit, quantity, orderedAt, settlementMethod, remark,
                            "QUANTITY_SCALE", "数量最多三位小数");
                }
            } catch (NumberFormatException exception) {
                error = "数量格式非法";
            }
        }
        return build(sheet, sheetIndex, row, cells, orderRef, lineRef, customerRef, customerName,
                receiverName, receiverPhone, receiverAddress, province, city, district, sourceSkuRef,
                productName, specification, unit, quantity, orderedAt, settlementMethod, remark,
                error == null ? null : "IMPORT_VALIDATION", error);
    }

    private ParsedSourceRow build(
            String sheet, int sheetIndex, int row, Map<String, String> cells,
            String orderRef, String lineRef, String customerRef, String customerName,
            String receiverName, String receiverPhone, String receiverAddress,
            String province, String city, String district,
            String sourceSkuRef, String productName, String specification, String unit,
            String quantity, Instant orderedAt, String settlementMethod, String remark,
            String errorCode, String errorMessage) {
        return new ParsedSourceRow(
                sheet, sheetIndex, row, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cells)),
                blankToNull(orderRef), blankToNull(lineRef),
                fallback(customerRef, "UNRESOLVED"), fallback(customerName, "待匹配客户"),
                receiverName, receiverPhone, receiverAddress, province, city, district,
                sourceSkuRef, productName, fallback(specification, "来源未提供"), fallback(unit, "来源数量单位"),
                quantity, orderedAt, settlementMethod, remark, errorCode, errorMessage);
    }

    private List<String> headers(Row row) {
        if (row == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            result.add(normalizeHeader(formatter.formatCellValue(row.getCell(index))));
        }
        return result;
    }

    private Map<String, String> cells(List<String> headers, Row row) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            result.put(headers.get(index), row == null ? "" : formatter.formatCellValue(row.getCell(index)));
        }
        return result;
    }

    private SheetCandidate unique(List<SheetCandidate> matches) {
        if (matches.size() != 1) {
            throw fingerprintError(matches.size());
        }
        SheetCandidate candidate = matches.getFirst();
        assertNoDuplicateKeyHeaders(candidate.headers(), keyHeaders(candidate.channel(), candidate.headers()));
        return candidate;
    }

    private boolean matches(SourceChannel channel, List<String> headers) {
        if (channel == SourceChannel.WANQI) {
            return WANQI_52_HEADERS.equals(headers);
        }
        if (channel == SourceChannel.DAZHE) {
            // 两份导出都属于大者；命中任一即认，具体用哪套解析由 isDazheV2 判定
            return matches(headers, FINGERPRINTS.get(channel)) || isDazheV2(headers);
        }
        return matches(headers, FINGERPRINTS.get(channel));
    }

    /** 表头是否是大者 v2（11 列订单往返表）。判定只看必填集，不看列序也不看列数。 */
    private boolean isDazheV2(List<String> headers) {
        return matches(headers, DAZHE_V2_FINGERPRINT);
    }

    /** 行级判定：{@code cells} 的键就是表头，用同一套必填集，避免两处判定漂移。 */
    private boolean isDazheV2(Map<String, String> cells) {
        return cells.keySet().containsAll(DAZHE_V2_FINGERPRINT);
    }

    private Set<String> keyHeaders(SourceChannel channel, List<String> headers) {
        if (channel == SourceChannel.WANQI) {
            return new HashSet<>(WANQI_52_HEADERS);
        }
        // 拿错版本的必填集去查重复表头，会把「v2 文件里没有的列」报成重复，错得莫名其妙
        if (channel == SourceChannel.DAZHE && isDazheV2(headers)) {
            return DAZHE_V2_FINGERPRINT;
        }
        return FINGERPRINTS.get(channel);
    }

    private String templateVersion(SourceChannel channel, List<String> headers) {
        if (channel == SourceChannel.WANQI) {
            return "v1-52-columns";
        }
        return channel == SourceChannel.DAZHE && isDazheV2(headers) ? "v2-11-columns" : "v1";
    }

    private boolean matches(List<String> headers, Set<String> required) {
        if (required == null || headers.isEmpty()) {
            return false;
        }
        return new HashSet<>(headers).containsAll(required);
    }

    private void assertNoDuplicateKeyHeaders(List<String> headers, Set<String> keys) {
        for (String key : keys) {
            if (headers.stream().filter(key::equals).count() != 1) {
                throw BusinessException.unprocessable("DUPLICATE_KEY_HEADER", "关键表头重复: " + key);
            }
        }
    }

    private boolean eligibleSheet(SourceChannel channel, int index, String name) {
        return switch (channel) {
            case CAISHIXIAN -> index == 0;
            case DAZHE -> index == 0;
            case JUFUBAO -> "sheet1".equals(name);
            case FEIXIANG -> index == 0;
            case ZHONGHUI -> index == 0;
            case WANGQI -> index == 0;
            case WANQI -> index == 0;
            case WECOM -> false;
        };
    }

    private boolean isJufubaoSummary(Map<String, String> cells) {
        return cells.values().stream().map(String::strip)
                .anyMatch(value -> "供应商汇总".equals(value) || "汇总".equals(value));
    }

    /**
     * 大者 v2 的合计行：表尾只有「合计」列带 SUM 公式、身份列全空。
     *
     * <p>生产实证（批次 28 第 8 行）：{@code {"合计": "SUM(I2:I7)", 其余全空}} 被解析成一行、
     * 落 NEED_REVIEW，而批次确认要求全行 ACCEPTED——一行合计把六张真订单全部拖住。
     * 判定条件用「身份列全空」而不是「值里含 SUM」：公式文本随导出工具变（有的给公式、
     * 有的给算好的数字），身份列空才是合计行不变的本质。
     */
    private boolean isDazheV2Summary(Map<String, String> cells) {
        return value(cells, "主订单号").isBlank()
                && value(cells, "收件人").isBlank()
                && value(cells, "商品名称").isBlank();
    }

    private boolean isWangqiPurchaseTotal(Map<String, String> cells) {
        return !value(cells, "采购单价(元)").isBlank()
                && cells.entrySet().stream()
                        .filter(cell -> !"采购单价(元)".equals(cell.getKey()))
                        .allMatch(cell -> cell.getValue().isBlank());
    }

    private BusinessException fingerprintError(int matches) {
        return BusinessException.unprocessable(
                matches == 0 ? "TEMPLATE_FINGERPRINT_NOT_FOUND" : "TEMPLATE_FINGERPRINT_AMBIGUOUS",
                matches == 0 ? "文件表头未命中已知渠道指纹" : "文件表头命中多个渠道指纹");
    }

    private String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
    }

    private DecodedCsv decodeCsv(byte[] bytes) throws CharacterCodingException {
        String text;
        String encoding;
        try {
            text = strictUtf8(bytes);
            encoding = "utf8";
        } catch (CharacterCodingException exception) {
            text = GB18030.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
            encoding = "gb18030";
        }
        String newline = text.contains("\r\n") ? "crlf" : "lf";
        return new DecodedCsv(text, encoding, newline);
    }

    String normalizeHeader(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        return normalized.replace("\uFEFF", "").strip();
    }

    /**
     * 来源下单/结算时间的唯一解析入口：字符串按 yyyy-MM-dd HH:mm:ss 解析、Asia/Shanghai
     * 解释后转 Instant。解析失败（格式不符/空白）落 null，不阻断建单——调用方（settlement
     * 与 source_ordered_at 均取自本方法）各自决定 null 时的兜底策略；这里只诚实记录一次
     * debug 日志，供事后排查具体是哪个渠道的哪个原始字符串没能解析。
     */
    private Instant parseTime(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip(), SOURCE_TIME).atZone(SHANGHAI).toInstant();
        } catch (DateTimeParseException exception) {
            log.debug("来源时间解析失败，落 null 不阻断建单：raw=\"{}\"", value, exception);
            return null;
        }
    }

    private String settlement(String value) {
        if (value != null && value.contains("月")) {
            return "MONTHLY";
        }
        if (value != null && (value.contains("现") || value.contains("立即"))) {
            return "IMMEDIATE";
        }
        return "OTHER";
    }

    private String value(Map<String, String> cells, String key) {
        return cells.getOrDefault(key, "").strip();
    }

    private String first(Map<String, String> cells, String... keys) {
        return Arrays.stream(keys).map(key -> value(cells, key)).filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private String join(Map<String, String> cells, String... keys) {
        return Arrays.stream(keys).map(key -> value(cells, key)).filter(value -> !value.isBlank())
                .reduce("", (left, right) -> left + right);
    }

    private String fallback(String value, String fallback) {
        return blank(value) ? fallback : value.strip();
    }

    private String blankToNull(String value) {
        return blank(value) ? null : value.strip();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<SourceChannel, Set<String>> fingerprints() {
        Map<SourceChannel, Set<String>> map = new EnumMap<>(SourceChannel.class);
        map.put(SourceChannel.CAISHIXIAN, Set.of("主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "下单数量"));
        map.put(SourceChannel.JUFUBAO, Set.of("主单号", "拆单号", "供货商", "渠道订单号", "结算方式", "需结算总额"));
        map.put(SourceChannel.FEIXIANG, Set.of("订单号", "订单商品ID", "可发货数量", "物流状态", "物流公司", "物流单号"));
        map.put(SourceChannel.ZHONGHUI, Set.of(
                "订单号", "商品编号", "商品名称", "件数", "收件人", "收件电话", "收件地址", "包装规格", "单位"));
        // 大者 v1 指纹收缩为 12 列核心集（2026-08-27 生产实证）：同一渠道存在
        // 不带「预计到货时间/渠道下单时间/渠道支付时间」的 12 列导出（全角括号由 NFKC 归一）。
        // containsAll 语义下 15 列文件天然命中核心集，两版通吃；日期列缺失时行内取空即可。
        map.put(SourceChannel.DAZHE, Set.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价(元)", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "快递单号", "快递公司"));
        return map;
    }

    private record SheetCandidate(SourceChannel channel, Sheet sheet, int sheetIndex, List<String> headers) {}
    private record DecodedCsv(String text, String encodingName, String newlineName) {}
}
