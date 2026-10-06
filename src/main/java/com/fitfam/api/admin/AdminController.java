package com.fitfam.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.admin.AdminDtos.AddCustomerRequest;
import com.fitfam.api.admin.AdminDtos.CustomerDto;
import com.fitfam.api.admin.AdminDtos.PlanDto;
import com.fitfam.api.auth.AuthenticatedUser;

import jakarta.validation.Valid;

/** Admin-only endpoints (enforced for {@code /admin/**} in SecurityConfig). */
@RestController
public class AdminController {

	private final AdminUserService service;

	public AdminController(AdminUserService service) {
		this.service = service;
	}

	@GetMapping("/admin/plans")
	public List<PlanDto> plans() {
		return service.listPlans();
	}

	@GetMapping("/admin/users")
	public List<CustomerDto> users() {
		return service.listCustomers();
	}

	@PostMapping("/admin/users")
	@ResponseStatus(HttpStatus.CREATED)
	public CustomerDto addUser(@AuthenticationPrincipal AuthenticatedUser admin,
			@Valid @RequestBody AddCustomerRequest request) {
		return service.addCustomer(admin, request);
	}

}
