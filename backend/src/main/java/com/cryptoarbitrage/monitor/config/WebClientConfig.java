package com.cryptoarbitrage.monitor.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient binanceWebClient(ExchangeProperties props) {
        return createWebClient(props.getAdapters().get("binance"));
    }

    @Bean
    public WebClient krakenWebClient(ExchangeProperties props) {
        return createWebClient(props.getAdapters().get("kraken"));
    }

    @Bean
    public WebClient coinbaseWebClient(ExchangeProperties props) {
        return createWebClient(props.getAdapters().get("coinbase"));
    }

    private WebClient createWebClient(ExchangeProperties.ExchangeConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Exchange config is null");
        }

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(config.getResponseTimeoutMs()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeoutMs());

        return WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
