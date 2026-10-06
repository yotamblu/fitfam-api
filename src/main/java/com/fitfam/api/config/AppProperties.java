package com.fitfam.api.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Google google, Jwt jwt, Session session, Cookie cookie, Cors cors) {

	public record Google(String clientId) {
	}

	public record Jwt(String secret) {
	}

	public record Session(int days) {
	}

	public record Cookie(boolean secure) {
	}

	public record Cors(List<String> allowedOrigins) {
	}

}
