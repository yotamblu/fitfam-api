package com.fitfam.api.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fitfam.api.auth.SessionAuthFilter;
import com.fitfam.api.auth.SessionTokenService;
import com.fitfam.api.domain.UserRepository;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SessionTokenService tokens, UserRepository users)
			throws Exception {
		http
				// Stateless cookie auth. CSRF protection comes from SameSite=Lax cookies, the CORS allowlist, and
				// state-changing endpoints accepting only JSON bodies.
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				// This API only returns JSON: nothing may be framed, scripted or sniffed, and nothing leaks via Referer.
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
						.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
						.httpStrictTransportSecurity(hsts -> hsts
								.requestMatcher(AnyRequestMatcher.INSTANCE)
								.maxAgeInSeconds(31_536_000)
								.includeSubDomains(true)))
				.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/health", "/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/google", "/auth/logout").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.addFilterBefore(new SessionAuthFilter(tokens, users), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties props) {
		requireSafeCorsForProduction(props.cookie().secure(), props.cors().allowedOrigins());
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(props.cors().allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Content-Type"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	/**
	 * Secure cookies mean a real (https) deployment. In that case the CORS allowlist must not contain localhost or
	 * plain http origins (the development default), because origins listed there may call the API with the cookie.
	 */
	static void requireSafeCorsForProduction(boolean secureCookies, java.util.List<String> allowedOrigins) {
		if (!secureCookies) {
			return;
		}
		for (String origin : allowedOrigins) {
			if (!origin.startsWith("https://")) {
				throw new IllegalStateException(
						"Refusing to start: COOKIE_SECURE is on but CORS_ALLOWED_ORIGINS contains a non-https origin ("
								+ origin + "). Set CORS_ALLOWED_ORIGINS to the real https sites.");
			}
		}
	}

}
