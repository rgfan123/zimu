package cn.zimu.fulfillment.file;

/** 到期未收齐运单回传的群提醒文案（markdown；#84）。不含 media_id/config/secret。 */
public final class WecomTrackingReminderMessage {

    private WecomTrackingReminderMessage() {}

    /**
     * @param batchNo 导出批次号
     * @param providerName 履约方名称
     * @param waitedMinutes 距 initial 成功 ack 的已等待分钟数
     * @param missingShipmentCount 未回传运单的发货批次数
     */
    public static String build(
            String batchNo, String providerName, long waitedMinutes, int missingShipmentCount) {
        return "**【运单回传提醒】**\n"
                + "> 导出批次 **" + batchNo + "**（" + providerName + "）已等待约 **"
                + waitedMinutes + " 分钟**，仍有 **" + missingShipmentCount
                + "** 个发货批次未回传运单。\n"
                + "请履约方在群内回传填写后的导出文件（含快递公司与运单号）；"
                + "如已回传请忽略本提醒，如遇问题请联系履约运营。";
    }
}
