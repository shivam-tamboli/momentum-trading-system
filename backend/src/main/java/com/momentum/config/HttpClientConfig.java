package com.momentum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The one shared {@link RestTemplate} for outbound calls this backend makes to other HTTP
 * services (right now: Supabase's {@code /auth/v1/user}, from both {@code JwtAuthFilter} and
 * {@code UserController}). Explicit connect/read timeouts, deliberately — a bare {@code new
 * RestTemplate()} has none by default, which is exactly the class of bug already found and fixed
 * once in this codebase for the SMTP mail client (a hang with no timeout, reproduced live against
 * production as 90+ seconds with no response while other endpoints on the same instance answered
 * in ~1s). Every authenticated request on this backend depends on a Supabase call succeeding
 * quickly; without a bound here, a slow or partially-down Supabase Auth API hangs request threads
 * indefinitely and can exhaust the whole server's worker pool, not just fail auth.
 */
@Configuration
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
