package com.momentum.config;

import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.properties.DataAPIType;
import net.jacobpeterson.alpaca.model.properties.EndpointAPIType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AlpacaConfig {

    @Value("${alpaca.system.api-key}")
    private String systemApiKey;

    @Value("${alpaca.system.api-secret}")
    private String systemApiSecret;

    @Bean
    public AlpacaAPI systemAlpacaAPI() {
        return new AlpacaAPI(
                systemApiKey,
                systemApiSecret,
                EndpointAPIType.PAPER,
                DataAPIType.IEX
        );
    }

    public AlpacaAPI createUserAlpacaAPI(String apiKey, String apiSecret) {
        return new AlpacaAPI(
                apiKey,
                apiSecret,
                EndpointAPIType.PAPER,
                DataAPIType.IEX
        );
    }
}
