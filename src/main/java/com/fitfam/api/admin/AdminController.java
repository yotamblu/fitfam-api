package com.fitfam.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.admin.AdminDtos.AddCustomerRequest;
import com.fitfam.api.admin.AdminDtos.CustomerDto;
import com.fitfam.api.admin.AdminDtos.PlanDto;
import com.fitfam.api.admin.AdminDtos.WaitlistPageDto;
import com.fitfam.api.auth.AuthenticatedUser;

import jakarta.validation.Valid;

/** Admin-only endpoints (enforced for {@code /admin/**} in SecurityConfig). */
@RestController
public class AdminController {

	private final AdminUserService service;
	private final WaitlistService waitlist;

	public AdminController(AdminUserService service, WaitlistService waitlist) {
		this.service = service;
		this.waitlist = waitlist;
	}

	@GetMapping("/admin/plans")
	public List<PlanDto> plans() {
		return service.listPlans();
	}

	@GetMapping("/admin/users")
	public List<CustomerDto> users() {
		return service.listCustomers();
	}

	@GetMapping("/admin/waitlist")
	public WaitlistPageDto waitlist(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String sport) {
		return waitlist.list(page, size, q, status, sport);
	}

	@PostMapping("/admin/users")
	@ResponseStatus(HttpStatus.CREATED)
	public CustomerDto addUser(@AuthenticationPrincipal AuthenticatedUser admin,
			@Valid @RequestBody AddCustomerRequest request) {
		return service.addCustomer(admin, request);
	}

}
