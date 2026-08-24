package com.xscsiem.hsiem_platform.soar.device;

import java.util.Set;

/** Vendor connectors can reuse this adapter/transport/interpreter pipeline. */
public abstract class AdapterBackedHttpConnector implements SoarConnector {

    private final ConnectorRequestAdapter requestAdapter;
    private final ConnectorResponseInterpreter responseInterpreter;
    private final HttpConnectorTransport transport;

    protected AdapterBackedHttpConnector(ConnectorRequestAdapter requestAdapter,
                                         ConnectorResponseInterpreter responseInterpreter,
                                         HttpConnectorTransport transport) {
        this.requestAdapter = requestAdapter;
        this.responseInterpreter = responseInterpreter;
        this.transport = transport;
    }

    @Override
    public final String runtimeKey() {
        return requestAdapter.runtimeKey();
    }

    @Override
    public final Set<String> capabilities() {
        return requestAdapter.capabilities();
    }

    @Override
    public final ConnectorResult execute(ConnectorInvocation invocation) {
        return responseInterpreter.interpret(transport.send(requestAdapter.adapt(invocation)));
    }
}
