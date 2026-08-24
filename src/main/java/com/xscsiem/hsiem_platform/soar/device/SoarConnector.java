package com.xscsiem.hsiem_platform.soar.device;

import java.util.Set;

/** A vendor-neutral action boundary. Implementations are Spring components. */
public interface SoarConnector {

    String runtimeKey();

    Set<String> capabilities();

    ConnectorResult execute(ConnectorInvocation invocation);
}
