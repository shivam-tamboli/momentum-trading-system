package com.momentum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // Pattern-based (not a fixed list) so every Vercel preview deployment of this project — each
    // gets its own unique subdomain per PR/branch — is allowed without needing this list updated
    // per-preview. allowedOriginPatterns (not allowedOrigins) is required to combine a wildcard
    // with allowCredentials(true): Spring computes and echoes back the actual matched origin per
    // request rather than a literal "*", which the CORS spec disallows alongside credentials.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(
                        "http://localhost:3000",
                        "https://momentum-trading-system-*.vercel.app"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
