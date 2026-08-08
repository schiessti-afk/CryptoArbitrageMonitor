package com.cryptoarbitrage.monitor.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * One {@link WebClient} bean per configured exchange, each read from {@link ExchangeProperties}
 * by adapter key. Explicit {@code @Bean} methods (rather than registering singletons dynamically
 * from the map) so bean creation order stays whatever Spring's dependency graph resolves —
 * adapters injecting via {@code @Qualifier} are never at risk of racing this class's own
 * initialization.
 */
@Configuration
public class WebClientConfig {

    /** KuCoin/Bitget all-ticker payloads exceed WebClient's default 256KB codec buffer. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

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

    @Bean
    public WebClient bitgetWebClient(ExchangeProperties props) {
        return createWebClient(props.getAdapters().get("bitget"));
    }

    @Bean
    public WebClient kucoinWebClient(ExchangeProperties props) {
        return createWebClient(props.getAdapters().get("kucoin"));
    }

    private WebClient createWebClient(ExchangeProperties.ExchangeConfig config) {
        if (config == null || config.getBaseUrl() == null) {
            throw new IllegalArgumentException("Exchange config or base-url is missing");
        }

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(config.getResponseTimeoutMs()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeoutMs());

        return WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }
}
