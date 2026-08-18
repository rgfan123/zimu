package cn.zimu.fulfillment.order;

import java.util.List;

/** 人工复核完成后，订单用例调用文件 Adapter 的最窄导出端口。 */
public interface ReadySourceBatchExporter {

    List<Long> generateReadyExports(long sourceBatchId, String operator);
}
