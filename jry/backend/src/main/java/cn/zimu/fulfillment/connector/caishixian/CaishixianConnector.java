package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.ExcelPlatformConnector;
import org.springframework.stereotype.Component;

@Component
public class CaishixianConnector extends ExcelPlatformConnector {

    @Override
    public SourceChannel channel() {
        return SourceChannel.CAISHIXIAN;
    }
}
