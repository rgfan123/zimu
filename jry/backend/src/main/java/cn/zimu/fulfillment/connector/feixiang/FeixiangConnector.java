package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.ExcelPlatformConnector;
import org.springframework.stereotype.Component;

@Component
public class FeixiangConnector extends ExcelPlatformConnector {

    @Override
    public SourceChannel channel() {
        return SourceChannel.FEIXIANG;
    }
}
