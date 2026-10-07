package com.fitfam.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.fitfam.api.HealthController;
import com.fitfam.api.admin.AdminController;
import com.fitfam.api.admin.AdminDtos.CustomerDto;
import com.fitfam.api.admin.AdminDtos.WaitlistEntryDto;
import com.fitfam.api.admin.AdminDtos.WaitlistPageDto;
import com.fitfam.api.admin.AdminDtos.WaitlistSummaryDto;
import com.fitfam.api.admin.AdminUserService;
import com.fitfam.api.admin.WaitlistService;
import com.fitfam.api.auth.AuthController;
import com.fitfam.api.auth.AuthService;
import com.fitfam.api.auth.SessionCookies;
import com.fitfam.api.auth.SessionTokenService;
import com.fitfam.api.config.SecurityConfig;
import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;

import jakarta.servlet.http.Cookie;

@WebMvcTest({ HealthController.class, AuthController.class, AdminController.class })
@Import({ SecurityConfig.class, SessionTokenService.class, SessionCookies.class })
class WebSecurityTests {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private SessionTokenService tokens;

	@MockitoBean
	private UserRepository users;
	@MockitoBean
	private AuthService authService;
	@MockitoBean
	private AdminUserService adminService;
	@MockitoBean
	private WaitlistService waitlistService;

	private User userWithRole(String role) {
		User user = new User(role + "@example.com", role, User.STATUS_ACTIVE);
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		when(users.findById(user.getId())).thenReturn(Optional.of(user));
		return user;
	}

	private Cookie sessionFor(User user) {
		return new Cookie(SessionCookies.NAME, tokens.issue(user.getId(), tokens.lifetimeFor(user.getRole())));
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	void responsesCarryBrowserSecurityHeaders() throws Exception {
		mvc.perform(get("/health"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().exists("Strict-Transport-Security"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void meWithoutCookieIsUnauthorized() throws Exception {
		mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void meWithValidCookieReturnsTheUser() throws Exception {
		User user = userWithRole(User.ROLE_CUSTOMER);

		mvc.perform(get("/auth/me").cookie(sessionFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("customer@example.com"))
				.andExpect(jsonPath("$.role").value("customer"));
	}

	@Test
	void meWithTamperedCookieIsUnauthorized() throws Exception {
		mvc.perform(get("/auth/me").cookie(new Cookie(SessionCookies.NAME, "garbage")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void cookieOfAUserWhoWasRemovedIsUnauthorized() throws Exception {
		User user = new User("gone@example.com", User.ROLE_CUSTOMER, User.STATUS_ACTIVE);
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		when(users.findById(user.getId())).thenReturn(Optional.empty());

		mvc.perform(get("/auth/me").cookie(sessionFor(user))).andExpect(status().isUnauthorized());
	}

	@Test
	void adminEndpointsRejectAnonymousAndCustomersButAllowAdmins() throws Exception {
		when(adminService.listCustomers()).thenReturn(List.of());

		mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/users").cookie(sessionFor(userWithRole(User.ROLE_CUSTOMER))))
				.andExpect(status().isForbidden());
		mvc.perform(get("/admin/users").cookie(sessionFor(userWithRole(User.ROLE_ADMIN))))
				.andExpect(status().isOk());
	}

	@Test
	void waitlistIsForAdminsOnly() throws Exception {
		when(waitlistService.list(0, 50, null, null, null)).thenReturn(new WaitlistPageDto(1, 0, 50, List.of(
				new WaitlistEntryDto("w1", "wait@example.com", "running", Instant.parse("2026-01-02T03:04:05Z"), false)),
				new WaitlistSummaryDto(1, 0, java.util.Map.of("running", 1L))));

		mvc.perform(get("/admin/waitlist")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/waitlist").cookie(sessionFor(userWithRole(User.ROLE_CUSTOMER))))
				.andExpect(status().isForbidden());
		mvc.perform(get("/admin/waitlist").cookie(sessionFor(userWithRole(User.ROLE_ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].email").value("wait@example.com"))
				.andExpect(jsonPath("$.items[0].favoriteSport").value("running"))
				.andExpect(jsonPath("$.items[0].createdAt").value("2026-01-02T03:04:05Z"))
				.andExpect(jsonPath("$.items[0].alreadyUser").value(false))
				.andExpect(jsonPath("$.summary.total").value(1))
				.andExpect(jsonPath("$.summary.bySport.running").value(1));
	}

	@Test
	void waitlistSearchAndFiltersArePassedThrough() throws Exception {
		when(waitlistService.list(1, 20, "ann", "waiting", "running")).thenReturn(new WaitlistPageDto(0, 1, 20,
				List.of(), new WaitlistSummaryDto(0, 0, java.util.Map.of())));

		mvc.perform(get("/admin/waitlist").param("page", "1").param("size", "20").param("q", "ann")
				.param("status", "waiting").param("sport", "running")
				.cookie(sessionFor(userWithRole(User.ROLE_ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1));
	}

	@Test
	void googleLoginSetsAnHttpOnlySessionCookie() throws Exception {
		User user = userWithRole(User.ROLE_CUSTOMER);
		when(authService.login("good")).thenReturn(new AuthService.LoginResult(user, tokens.issue(user.getId(), tokens.lifetimeFor(user.getRole())),
				tokens.lifetimeFor(user.getRole())));

		mvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"good\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("customer@example.com"))
				.andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.startsWith(SessionCookies.NAME + "="),
						org.hamcrest.Matchers.containsString("HttpOnly"),
						org.hamcrest.Matchers.containsString("SameSite=Lax"),
						org.hamcrest.Matchers.containsString("Path=/"))));
	}

	@Test
	void googleLoginForAnEmailThatWasNotInvitedIsForbidden() throws Exception {
		when(authService.login(any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
				"not_invited"));

		mvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"x\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("not_invited"))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void googleLoginWithoutCredentialIsABadRequest() throws Exception {
		mvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"));
	}

	@Test
	void logoutClearsTheCookie() throws Exception {
		mvc.perform(post("/auth/logout"))
				.andExpect(status().isNoContent())
				.andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
	}

	@Test
	void addCustomerValidatesEmailAndPlans() throws Exception {
		Cookie admin = sessionFor(userWithRole(User.ROLE_ADMIN));

		mvc.perform(post("/admin/users").cookie(admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"planSlugs\":[\"plan-b\"]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/admin/users").cookie(admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"bob@example.com\",\"planSlugs\":[]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void addCustomerReturnsCreated() throws Exception {
		Cookie admin = sessionFor(userWithRole(User.ROLE_ADMIN));
		when(adminService.addCustomer(any(), any())).thenReturn(
				new CustomerDto("1", "bob@example.com", "customer", "invited", null, null, List.of()));

		mvc.perform(post("/admin/users").cookie(admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"bob@example.com\",\"planSlugs\":[\"plan-b\"]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("bob@example.com"));
	}

	@Test
	void corsAllowsTheConfiguredOriginsOnly() throws Exception {
		mvc.perform(options("/auth/google")
				.header("Origin", "http://localhost:3001")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));

		mvc.perform(options("/auth/google")
				.header("Origin", "http://evil.example")
				.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

}
