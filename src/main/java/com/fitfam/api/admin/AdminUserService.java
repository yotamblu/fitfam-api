package com.fitfam.api.admin;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AdminDtos.AddCustomerRequest;
import com.fitfam.api.admin.AdminDtos.CustomerDto;
import com.fitfam.api.admin.AdminDtos.EnrollmentDto;
import com.fitfam.api.admin.AdminDtos.LevelDto;
import com.fitfam.api.admin.AdminDtos.PlanDto;
import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.domain.Enrollment;
import com.fitfam.api.domain.EnrollmentLevelHistory;
import com.fitfam.api.domain.EnrollmentLevelHistoryRepository;
import com.fitfam.api.domain.EnrollmentRepository;
import com.fitfam.api.domain.Plan;
import com.fitfam.api.domain.PlanLevel;
import com.fitfam.api.domain.PlanLevelRepository;
import com.fitfam.api.domain.PlanRepository;
import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;
import com.fitfam.api.web.ApiException;

@Service
public class AdminUserService {

	private static final int FIRST_LEVEL = 1;

	private final UserRepository users;
	private final PlanRepository plans;
	private final PlanLevelRepository levels;
	private final EnrollmentRepository enrollments;
	private final EnrollmentLevelHistoryRepository history;
	private final AuditLogService audit;

	public AdminUserService(UserRepository users, PlanRepository plans, PlanLevelRepository levels,
			EnrollmentRepository enrollments, EnrollmentLevelHistoryRepository history, AuditLogService audit) {
		this.users = users;
		this.plans = plans;
		this.levels = levels;
		this.enrollments = enrollments;
		this.history = history;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<PlanDto> listPlans() {
		Map<UUID, List<LevelDto>> levelsByPlan = levels.findAllByOrderByLevelNumber().stream()
				.collect(Collectors.groupingBy(l -> l.getPlan().getId(),
						Collectors.mapping(l -> new LevelDto(l.getLevelNumber(), l.getSlug(), l.getNameHe()),
								Collectors.toList())));
		return plans.findAllByOrderBySortOrder().stream()
				.map(p -> new PlanDto(p.getSlug(), p.getNameHe(), levelsByPlan.getOrDefault(p.getId(), List.of())))
				.toList();
	}

	/**
	 * Adds a customer (invited, not yet logged in) and enrolls them in each chosen plan at the first level. The user,
	 * enrollments, level history and audit entry are saved in one transaction.
	 */
	@Transactional
	public CustomerDto addCustomer(AuthenticatedUser admin, AddCustomerRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		Set<String> slugs = request.planSlugs().stream()
				.map(String::trim)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (slugs.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "no_plans");
		}
		if (users.existsByEmail(email)) {
			throw new ApiException(HttpStatus.CONFLICT, "email_exists");
		}
		List<Plan> chosen = plans.findBySlugIn(slugs).stream()
				.sorted(Comparator.comparingInt(Plan::getSortOrder))
				.toList();
		if (chosen.size() != slugs.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_plan");
		}

		User user = users.save(new User(email, User.ROLE_CUSTOMER, User.STATUS_INVITED));
		User changedBy = users.getReferenceById(admin.id());

		List<Enrollment> created = chosen.stream().map(plan -> {
			PlanLevel first = levels.findByPlanIdAndLevelNumber(plan.getId(), FIRST_LEVEL)
					.orElseThrow(() -> new IllegalStateException("Plan " + plan.getSlug() + " has no first level"));
			Enrollment enrollment = enrollments.save(new Enrollment(user, plan, first));
			history.save(new EnrollmentLevelHistory(enrollment, null, first, EnrollmentLevelHistory.REASON_ASSIGNED,
					changedBy));
			return enrollment;
		}).toList();

		Map<String, String> details = new LinkedHashMap<>();
		details.put("email", email);
		details.put("plans", String.join(",", slugs));
		audit.record(admin.id(), "customer.created", "user", String.valueOf(user.getId()), details);

		return toDto(user, created);
	}

	@Transactional(readOnly = true)
	public List<CustomerDto> listCustomers() {
		List<User> all = users.findAllByOrderByCreatedAtDesc();
		if (all.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<Enrollment>> byUser = enrollments
				.findAllForUsers(all.stream().map(User::getId).toList()).stream()
				.collect(Collectors.groupingBy(e -> e.getUser().getId()));
		return all.stream().map(u -> toDto(u, byUser.getOrDefault(u.getId(), List.of()))).toList();
	}

	private static CustomerDto toDto(User user, List<Enrollment> userEnrollments) {
		List<EnrollmentDto> items = userEnrollments.stream()
				.map(e -> new EnrollmentDto(e.getPlan().getSlug(), e.getPlan().getNameHe(),
						e.getCurrentLevel().getLevelNumber(), e.getCurrentLevel().getSlug(),
						e.getCurrentLevel().getNameHe(), e.getStatus()))
				.toList();
		return new CustomerDto(String.valueOf(user.getId()), user.getEmail(), user.getRole(), user.getStatus(),
				user.getDisplayName(), user.getFirstLoginAt(), items);
	}

}
