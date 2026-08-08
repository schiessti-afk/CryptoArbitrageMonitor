package com.cryptoarbitrage.monitor.repository;

import com.cryptoarbitrage.monitor.model.ExchangeFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeFeeRepository extends JpaRepository<ExchangeFee, Long> {
    Optional<ExchangeFee> findByExchange(String exchange);
}
