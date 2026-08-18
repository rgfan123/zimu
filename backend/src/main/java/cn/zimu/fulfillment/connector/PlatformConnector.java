package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;

public interface PlatformConnector {

    SourceChannel channel();

    ConnectorCapabilities capabilities();

    ConnectionTestResult testConnection(ConnectorRuntime runtime);
}
