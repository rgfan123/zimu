package cn.zimu.fulfillment.file;

/**
 * 来源回填文件的命名：以来源原始文件名为基名，只允许追加后缀，不得替换基名。
 *
 * <p>为什么：回填文件是还给来源平台的，平台侧很可能按文件名识别或归档。原始文件名一直存在
 * {@code app.import_batches.original_file_name}，此前下载时没人读它，凭空拼成
 * 「彩食鲜-来源回填-42.xlsx」，把平台原名整个丢掉了——飞象那种由用户从平台手工导出再上传的
 * 文件（例如「订单导出2026-08-28_09_57_54.csv」）尤其不能改名。
 *
 * <p>注意例外：给第三方履约方的发货指令文件（{@code fulfillment_exports}）有自己的命名机制，
 * 不适用本规则。本类只管来源回填。
 */
final class SourceReturnFileNaming {

    /** 文件名整体上限，给基名留出后缀空间；超长时截断基名而不是丢掉后缀。 */
    private static final int MAX_STEM_LENGTH = 120;

    private SourceReturnFileNaming() {}

    /**
     * 拼出回填文件名。
     *
     * @param originalFileName 来源批次的原始文件名，可为 null/空白
     * @param channelDisplayName 渠道显示名，仅在原始文件名不可用时用于回退名
     * @param versionNo 回填版本号，用于区分同一批次的多个版本，避免重名
     * @param extension 实际产物的扩展名（含点，例如 {@code .csv}）——以产物为准，
     *     原名扩展名与产物不符时以产物为准，基名主干仍保留
     */
    static String fileName(String originalFileName, String channelDisplayName, int versionNo, String extension) {
        String stem = stemOf(originalFileName);
        if (stem.isEmpty()) {
            // 原名缺失或全是不可用字符：退回合成名，不抛异常挡住下载。
            stem = channelDisplayName == null || channelDisplayName.isBlank() ? "来源回填" : channelDisplayName;
        }
        return stem + "-来源回填-v" + versionNo + extension;
    }

    /**
     * 取原始文件名的主干：去掉目录部分与扩展名，清掉会破坏 multipart/HTTP 头的字符。
     *
     * <p>清理是必须的——这个名字会进 Content-Disposition，也会成为推送到平台时 multipart 的
     * filename，其中有的构造方式是裸字符串拼接。引号、换行、路径分隔符一律不能过去。
     */
    private static String stemOf(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "";
        }
        String name = originalFileName.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (char character : name.toCharArray()) {
            // 控制字符、引号、分号与路径分隔符会破坏承载它的头部或多部分边界。
            boolean unsafe = character < 0x20
                    || character == 0x7f
                    || character == '"'
                    || character == '\''
                    || character == ';'
                    || character == ','
                    || character == '/'
                    || character == '\\';
            cleaned.append(unsafe ? '_' : character);
        }
        String stem = cleaned.toString().trim();
        return stem.length() > MAX_STEM_LENGTH ? stem.substring(0, MAX_STEM_LENGTH).trim() : stem;
    }
}
