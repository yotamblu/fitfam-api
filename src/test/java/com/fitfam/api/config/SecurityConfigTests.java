package com.fitfam.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SecurityConfigTests {

	@Test
	void secureCookiesWithLocalhostOriginsRefuseToStart() {
		assertThatThrownBy(() -> SecurityConfig.requireSafeCorsForProduction(true,
				List.of("https://app.example.com", "http://localhost:3000")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Refusing to start");
	}

	@Test
	void secureCookiesWithPlainHttpOriginRefuseToStart() {
		assertThatThrownBy(() -> SecurityConfig.requireSafeCorsForProduction(true, List.of("http://app.example.com")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secureCookiesWithOnlyHttpsOriginsAreFine() {
		assertThatCode(() -> SecurityConfig.requireSafeCorsForProduction(true,
				List.of("https://app.example.com", "https://admin.example.com"))).doesNotThrowAnyException();
	}

	@Test
	void localDevelopmentWithInsecureCookiesMayUseLocalhost() {
		assertThatCode(() -> SecurityConfig.requireSafeCorsForProduction(false,
				List.of("http://localhost:3000", "http://localhost:3001"))).doesNotThrowAnyException();
	}

}
