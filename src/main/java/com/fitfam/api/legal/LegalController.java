package com.fitfam.api.legal;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.auth.SessionCookies;

import jakarta.servlet.http.HttpServletResponse;

/** What a signed-in customer does about the legal side: accept terms, declare health, see their data, delete the account. */
@RestController
public class LegalController {

	private final ConsentService consents;
	private final SessionCookies cookies;

	public LegalController(ConsentService consents, SessionCookies cookies) {
		this.consents = consents;
		this.cookies = cookies;
	}

	@GetMapping("/me/consents")
	public ConsentService.Status status(@AuthenticationPrincipal AuthenticatedUser user) {
		return consents.status(user);
	}

	@PostMapping("/me/consents/terms")
	public ConsentService.Status acceptTerms(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestBody ConsentService.TermsRequest request) {
		return consents.acceptTerms(user, request);
	}

	@PostMapping("/me/consents/health")
	public ConsentService.Status declareHealth(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestBody ConsentService.HealthRequest request) {
		return consents.declareHealth(user, request);
	}

	/** A copy of everything stored about the caller. */
	@GetMapping("/me/export")
	public Map<String, Object> export(@AuthenticationPrincipal AuthenticatedUser user) {
		return consents.export(user.id());
	}

	/** Deletes the caller's account and data, and signs them out. */
	@DeleteMapping("/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAccount(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
		consents.deleteAccount(user);
		response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
	}

}
