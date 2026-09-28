package io.jenkins.plugins.dorametrics.export;

import hudson.ProxyConfiguration;
import jenkins.util.SystemProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * HTTP for the exports: through the proxy configured in Jenkins, and with a limit on how long
 * a connection or a request may take, so an endpoint that stops answering fails the export
 * instead of blocking it for good.
 */
final class ExportHttp {

    static final String TIMEOUT_PROPERTY = ExportHttp.class.getName() + ".timeoutSeconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private ExportHttp() {
    }

    static HttpClient client() {
        return ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(timeout())
                .build();
    }

    static HttpRequest.Builder request(URI uri) {
        return ProxyConfiguration.newHttpRequestBuilder(uri).timeout(timeout());
    }

    private static Duration timeout() {
        int seconds = SystemProperties.getInteger(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
        return Duration.ofSeconds(Math.max(1, seconds));
    }
}
