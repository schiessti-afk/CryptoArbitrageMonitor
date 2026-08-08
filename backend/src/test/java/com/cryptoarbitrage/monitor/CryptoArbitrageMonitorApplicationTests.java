package com.cryptoarbitrage.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 0 smoke test. Full {@code @SpringBootTest} context tests arrive with
 * Flyway + Testcontainers in Sprint 1.
 */
class CryptoArbitrageMonitorApplicationTests {

	@Test
	void applicationClassIsLoadable() {
		assertNotNull(CryptoArbitrageMonitorApplication.class);
	}

}
