package cn.zimu.fulfillment.file;

/**
 * 来源回填产物的格式：扩展名与 MIME 类型的单一真源。
 *
 * <p>为什么要单独立一个：同一份字节此前有三条出口各写一遍判定——生成时决定
 * {@code .csv/.xlsx}、HTTP 下载再判一次、企微投递则干脆写死 {@code .xlsx}。
 * 2026-08-28 生产实证：飞象 export 的 {@code template_version='v2-gb18030-lf'}（内容是
 * GB18030 编码的 CSV），投递到企微的文件却叫 {@code FEIXIANG-回填-批次51.xlsx}，
 * 用户用 Excel 打不开，传回平台大概率被拒。两处各写一遍的判定迟早分叉，所以收敛到这里。
 *
 * <p>判定只依赖渠道与模板版本，不依赖调用方——任何新出口只要问这里就不会走偏。
 */
record SourceReturnArtifactFormat(String extension, String contentType) {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 飞象真实来源文件是文本 CSV；v2 模板锁定 GB18030 编码，其余按 UTF-8。 */
    private static final String FEIXIANG = "FEIXIANG";

    private static final String FEIXIANG_V2_PREFIX = "v2-gb18030-lf";

    /**
     * @param channel 生效来源渠道
     * @param templateVersion 批次模板版本，可为 null（按非 v2 处理）
     */
    static SourceReturnArtifactFormat of(String channel, String templateVersion) {
        if (!FEIXIANG.equals(channel)) {
            return new SourceReturnArtifactFormat(".xlsx", XLSX_CONTENT_TYPE);
        }
        boolean gb18030 = templateVersion != null && templateVersion.startsWith(FEIXIANG_V2_PREFIX);
        return new SourceReturnArtifactFormat(".csv", gb18030 ? "text/csv;charset=GB18030" : "text/csv;charset=UTF-8");
    }
}
