package com.siem;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;

/**
 * Emits the alert downstream only after Elasticsearch confirms the update.
 * This ordering is intentional: SOAR must never consume alert.created before
 * the target alert can be read and updated by the control-plane services.
 */
public class AlertElasticsearchIndexer extends RichAsyncFunction<String, String> {

    private final String esUrl;
    private final String username;
    private final String password;
    private transient HttpClient client;

    public AlertElasticsearchIndexer(String esUrl, String username, String password) {
        this.esUrl = esUrl.replaceAll("/+$", "");
        this.username = username;
        this.password = password;
    }

    @Override
    public void open(OpenContext openContext) {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void asyncInvoke(String alert, ResultFuture<String> resultFuture) {
        String id = DetectionJob.alertId(alert);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(esUrl + "/siem-alerts/_update/" + id))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DetectionJob.alertUpdateBody(alert)));
        if (username != null && !username.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
        client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        resultFuture.completeExceptionally(error);
                    } else if (response.statusCode() / 100 != 2) {
                        resultFuture.completeExceptionally(new IllegalStateException(
                                "Elasticsearch alert update failed HTTP " + response.statusCode()
                                        + ": " + response.body()));
                    } else {
                        resultFuture.complete(Collections.singleton(alert));
                    }
                });
    }

    @Override
    public void timeout(String input, ResultFuture<String> resultFuture) {
        resultFuture.completeExceptionally(new IllegalStateException("Elasticsearch alert update timed out"));
    }
}
