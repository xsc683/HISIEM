package com.xscsiem.hsiem_platform.soar.device;

import org.springframework.stereotype.Component;

/** Public HTTP baseline assembled from independent request/transport/response strategies. */
@Component
public class GenericHttpConnector extends AdapterBackedHttpConnector {

    public GenericHttpConnector(GenericHttpRequestAdapter requestAdapter,
                                GenericHttpResponseInterpreter responseInterpreter,
                                HttpConnectorTransport transport) {
        super(requestAdapter, responseInterpreter, transport);
    }
}
