package org.subsound.utils;

import okhttp3.Interceptor;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.HttpHeader;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import java.util.List;

public class LogUtils {
    public static void setRootLogLevel(String level) {
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger logger = loggerContext.exists(org.slf4j.Logger.ROOT_LOGGER_NAME); // give it your logger name
        final Level newLevel = Level.toLevel(level, null); // give it your log level
        logger.setLevel(newLevel);
    }

    public static Interceptor loggingInterceptor(org.slf4j.Logger log) {
        return chain -> {
            var request = chain.request();
            log.info("[{} {}] -->", request.method(), request.url());
            long startNanos = System.nanoTime();
            try {
                var response = chain.proceed(request);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("[{} {}] <-- {} in {}ms", request.method(), request.url(), response.code(), elapsedMs);
                return response;
            } catch (Exception e) {
                log.warn("[{} {}] <-- ERROR: {}", request.method(), request.url(), e.getMessage());
                throw e;
            }
        };
    }

    public static Interceptor userAgentInterceptor(String agentString) {
        return chain -> {
            var request = chain.request().newBuilder()
                    .header("User-Agent", agentString)
                    .build();
            return chain.proceed(request);
        };
    }

    /**
     * Attaches user-configured custom headers (e.g. Cloudflare Access) to every request.
     * Uses addHeader so multiple values for the same header name are preserved.
     */
    public static Interceptor customHeadersInterceptor(List<HttpHeader> headers) {
        return chain -> {
            if (headers == null || headers.isEmpty()) {
                return chain.proceed(chain.request());
            }
            var builder = chain.request().newBuilder();
            for (var header : headers) {
                if (header.name() != null && !header.name().isBlank()) {
                    builder.addHeader(header.name(), header.value() != null ? header.value() : "");
                }
            }
            return chain.proceed(builder.build());
        };
    }

}
