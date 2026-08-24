package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;

/** 渠道专属写产物构造器；平台表格列等细节不得泄露进 core。 */
public interface SourceShipmentArtifactFactory {

    SourceChannel channel();

    SourceShipmentArtifact prepare(SourceSyncFacts facts);
}
