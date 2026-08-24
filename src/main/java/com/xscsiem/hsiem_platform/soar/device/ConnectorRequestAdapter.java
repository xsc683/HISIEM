package com.xscsiem.hsiem_platform.soar.device;

import java.util.Set;

/** Translates a vendor-neutral invocation into one HTTP request. */
public interface ConnectorRequestAdapter {

    String runtimeKey();

    Set<String> capabilities();

    HttpConnectorRequest adapt(ConnectorInvocation invocation);
}
