package io.github.jwharm.javagi.examples.playsound.utils.javahttp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class LoggingHttpClient extends HttpClient {
    private final HttpClient delegate;
    private static final Logger log = LoggerFactory.getLogger(LoggingHttpClient.class);

    public LoggingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return this.delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return this.delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return this.delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return this.delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return this.delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return this.delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return this.delegate.authenticator();
    }

    @Override
    public Version version() {
        return this.delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return this.delegate.executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        logRequest(request);
        try {
            var res = this.delegate.send(request, responseBodyHandler);
            logResponse(res);
            return res;
        } catch (Exception e) {
            logError(request, e);
            throw e;
        }
    }

    private void logError(HttpRequest request, Exception e) {
        log.error("[%s %s] --> ERROR: %s".formatted(request.uri().toString(), request.method(), e.getMessage()));
    }

    private <T> HttpResponse<T> logResponse(HttpResponse<T> res) {
        log.info("[%s %s] <-- %d ".formatted(res.request().uri().toString(), res.request().method(), res.statusCode()));
        return res;
    }

    private void logRequest(HttpRequest request) {
        log.info("[%s %s] --> ".formatted(request.uri().toString(), request.method()));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        logRequest(request);
        return this.delegate.sendAsync(request, responseBodyHandler)
                .thenApply(this::logResponse);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        logRequest(request);
        return this.delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler)
                .thenApply(this::logResponse);
    }
}
