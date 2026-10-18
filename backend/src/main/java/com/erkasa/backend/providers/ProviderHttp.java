package com.erkasa.backend.providers;



import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds HTTP clients for provider APIs, always with timeouts.
 *
 * Without a read timeout, a provider that hangs would block a worker thread forever.
 * The real providers (3.2 - 3.4) get their RestClient from here.
 */
@Component
public class ProviderHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * @param baseUrl     e.g. https://api.anthropic.com
     * @param readTimeout how long to wait for the answer: AI generation can take a while
     */
    public RestClient client(String baseUrl, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}