package com.xscsiem.hsiem_platform.soar.device;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Shared bounded HTTP transport. Production egress proxy/mTLS is intentionally not claimed here. */
@Component
public class HttpConnectorTransport {

    public HttpConnectorResponse send(HttpConnectorRequest request) {
        validateDestination(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body());
        builder.method(request.method(), body);
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(request.timeout()).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpConnectorResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP Connector 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP Connector 被中断", e);
        }
    }

    private void validateDestination(HttpConnectorRequest request) {
        String scheme = request.uri().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("HTTP Connector 仅允许 http/https");
        }
        String host = request.uri().getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("HTTP URL 缺少主机名");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("HTTP Connector 禁止访问内网地址");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("HTTP 主机无法解析: " + host, e);
        }
    }
}
