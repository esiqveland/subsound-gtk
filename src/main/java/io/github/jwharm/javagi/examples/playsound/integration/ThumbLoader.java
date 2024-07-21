package io.github.jwharm.javagi.examples.playsound.integration;

import io.github.jwharm.javagi.examples.playsound.utils.javahttp.LoggingHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

public class ThumbLoader {
    private final HttpClient client = new LoggingHttpClient(HttpClient.newBuilder().build());

    public void loadThumb(URI link, Consumer<byte[]> target) {
        var req = HttpRequest.newBuilder().GET().uri(link).build();
        var bodyHandler = HttpResponse.BodyHandlers.ofByteArrayConsumer(bytes -> {
            bytes.ifPresent(target);
        });
        //CompletableFuture<HttpResponse<Void>> httpResponseCompletableFuture = this.client.sendAsync(req, bodyHandler);
        try {
            HttpResponse<Void> res = this.client.send(req, bodyHandler);
            if (res.statusCode() != 200) {
                throw new RuntimeException("error loading: status=" + res.statusCode() + " link=" + link.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("error loading: " + link.toString(), e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
