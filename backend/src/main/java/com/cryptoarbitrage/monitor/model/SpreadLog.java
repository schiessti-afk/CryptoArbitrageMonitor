package com.cryptoarbitrage.monitor.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "spread_log")
public class SpreadLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 50)
    private String buyExchange;

    @Column(nullable = false, length = 50)
    private String sellExchange;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal buyPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal sellPrice;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal rawSpreadPercent;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal netSpreadPercent;

    @Column(nullable = false)
    private Instant calculatedAt;

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getBuyExchange() {
        return buyExchange;
    }

    public void setBuyExchange(String buyExchange) {
        this.buyExchange = buyExchange;
    }

    public String getSellExchange() {
        return sellExchange;
    }

    public void setSellExchange(String sellExchange) {
        this.sellExchange = sellExchange;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }

    public BigDecimal getSellPrice() {
        return sellPrice;
    }

    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }

    public BigDecimal getRawSpreadPercent() {
        return rawSpreadPercent;
    }

    public void setRawSpreadPercent(BigDecimal rawSpreadPercent) {
        this.rawSpreadPercent = rawSpreadPercent;
    }

    public BigDecimal getNetSpreadPercent() {
        return netSpreadPercent;
    }

    public void setNetSpreadPercent(BigDecimal netSpreadPercent) {
        this.netSpreadPercent = netSpreadPercent;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(Instant calculatedAt) {
        this.calculatedAt = calculatedAt;
    }
}
