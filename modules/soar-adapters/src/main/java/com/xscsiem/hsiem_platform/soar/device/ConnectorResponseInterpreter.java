package com.xscsiem.hsiem_platform.soar.device;

/** Converts a transport response into the connector's stable business result. */
public interface ConnectorResponseInterpreter {

    ConnectorResult interpret(HttpConnectorResponse response);
}
