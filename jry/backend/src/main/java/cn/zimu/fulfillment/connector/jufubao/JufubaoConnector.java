package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.ExcelPlatformConnector;
import org.springframework.stereotype.Component;

@Component
public class JufubaoConnector extends ExcelPlatformConnector {

    @Override
    public SourceChannel channel() {
        return SourceChannel.JUFUBAO;
    }
}
