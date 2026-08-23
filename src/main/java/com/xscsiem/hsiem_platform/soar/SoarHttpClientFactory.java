package com.xscsiem.hsiem_platform.soar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;

/** 统一构建出站客户端：全局代理、禁止重定向、可选 mTLS 和私有 CA。 */
@Component
public class SoarHttpClientFactory {

    private final SoarSecretResolver secrets;
    private final String proxyHost;
    private final int proxyPort;

    public SoarHttpClientFactory(SoarSecretResolver secrets,
                                 @Value("${app.soar.outbound.proxy-host:}") String proxyHost,
                                 @Value("${app.soar.outbound.proxy-port:0}") int proxyPort) {
        this.secrets = secrets;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    public HttpClient create(SoarConnector connector) {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5));
            if (proxyHost != null && !proxyHost.isBlank()) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            }
            if (connector.tls() != null) builder.sslContext(sslContext(connector.tls()));
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("构建连接器 TLS 客户端失败: " + connector.id(), e);
        }
    }

    private SSLContext sslContext(SoarConnector.Tls tls) throws Exception {
        KeyManagerFactory keys = null;
        if (Boolean.TRUE.equals(tls.mtls())) {
            char[] password = secrets.resolve(tls.keyStorePasswordRef()).toCharArray();
            KeyStore store = load(tls.keyStoreRef(), password);
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
        }
        TrustManagerFactory trusts = null;
        if (tls.trustStoreRef() != null && !tls.trustStoreRef().isBlank()) {
            char[] password = tls.trustStorePasswordRef() == null ? new char[0]
                    : secrets.resolve(tls.trustStorePasswordRef()).toCharArray();
            KeyStore store = load(tls.trustStoreRef(), password);
            trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(store);
        }
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys == null ? null : keys.getKeyManagers(),
                trusts == null ? null : trusts.getTrustManagers(), null);
        return context;
    }

    private KeyStore load(String reference, char[] password) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(secrets.resolve(reference));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(bytes), password);
        return store;
    }
}
