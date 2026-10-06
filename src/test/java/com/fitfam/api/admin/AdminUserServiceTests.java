package com.fitfam.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.fitfam.api.admin.AdminDtos.AddCustomerRequest;
import com.fitfam.api.admin.AdminDtos.CustomerDto;
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

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTests {

	@Mock
	private UserRepository users;
	@Mock
	private PlanRepository plans;
	@Mock
	private PlanLevelRepository levels;
	@Mock
	private EnrollmentRepository enrollments;
	@Mock
	private EnrollmentLevelHistoryRepository history;
	@Mock
	private AuditLogService audit;

	private AdminUserService service;
	private AuthenticatedUser admin;
	private Plan planA;
	private Plan planB;
	private PlanLevel planAFirst;
	private PlanLevel planBFirst;

	@BeforeEach
	void setUp() {
		service = new AdminUserService(users, plans, levels, enrollments, history, audit);
		admin = new AuthenticatedUser(UUID.randomUUID(), "admin@example.com", User.ROLE_ADMIN, null, null);
		planA = plan("plan-a", "Plan A", 1);
		planB = plan("plan-b", "Plan B", 3);
		planAFirst = level(planA, 1, "regular");
		planBFirst = level(planB, 1, "regular");
	}

	private static Plan plan(String slug, String nameHe, int sortOrder) {
		Plan plan = BeanUtils.instantiateClass(Plan.class);
		ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(plan, "slug", slug);
		ReflectionTestUtils.setField(plan, "nameHe", nameHe);
		ReflectionTestUtils.setField(plan, "sortOrder", sortOrder);
		return plan;
	}

	private static PlanLevel level(Plan plan, int number, String slug) {
		PlanLevel level = BeanUtils.instantiateClass(PlanLevel.class);
		ReflectionTestUtils.setField(level, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(level, "plan", plan);
		ReflectionTestUtils.setField(level, "levelNumber", number);
		ReflectionTestUtils.setField(level, "slug", slug);
		return level;
	}

	private void stubSaves() {
		when(users.save(any(User.class))).thenAnswer(call -> {
			User user = call.getArgument(0);
			ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
			return user;
		});
		when(enrollments.save(any(Enrollment.class))).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void addsInvitedCustomerEnrolledAtLevelOneInEveryChosenPlan() {
		stubSaves();
		when(users.existsByEmail("bob@example.com")).thenReturn(false);
		when(plans.findBySlugIn(any())).thenReturn(List.of(planB, planA));
		when(levels.findByPlanIdAndLevelNumber(planA.getId(), 1)).thenReturn(Optional.of(planAFirst));
		when(levels.findByPlanIdAndLevelNumber(planB.getId(), 1)).thenReturn(Optional.of(planBFirst));

		CustomerDto result = service.addCustomer(admin,
				new AddCustomerRequest("  Bob@Example.COM ", List.of("plan-b", "plan-a")));

		ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
		verify(users).save(savedUser.capture());
		assertThat(savedUser.getValue().getEmail()).isEqualTo("bob@example.com");
		assertThat(savedUser.getValue().getRole()).isEqualTo(User.ROLE_CUSTOMER);
		assertThat(savedUser.getValue().getStatus()).isEqualTo(User.STATUS_INVITED);

		verify(enrollments, times(2)).save(any(Enrollment.class));
		verify(history, times(2)).save(any(EnrollmentLevelHistory.class));
		verify(audit).record(eq(admin.id()), eq("customer.created"), eq("user"), any(), anyMap());

		assertThat(result.email()).isEqualTo("bob@example.com");
		assertThat(result.status()).isEqualTo(User.STATUS_INVITED);
		// ordered by plan sort order, all at level 1
		assertThat(result.enrollments()).extracting("planSlug").containsExactly("plan-a", "plan-b");
		assertThat(result.enrollments()).extracting("levelNumber").containsOnly(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void auditEntryRecordsEmailAndPlans() {
		stubSaves();
		when(plans.findBySlugIn(any())).thenReturn(List.of(planA));
		when(levels.findByPlanIdAndLevelNumber(planA.getId(), 1)).thenReturn(Optional.of(planAFirst));

		service.addCustomer(admin, new AddCustomerRequest("bob@example.com", List.of("plan-a")));

		ArgumentCaptor<Map<String, String>> details = ArgumentCaptor.forClass(Map.class);
		verify(audit).record(eq(admin.id()), eq("customer.created"), eq("user"), any(), details.capture());
		assertThat(details.getValue()).containsEntry("email", "bob@example.com").containsEntry("plans", "plan-a");
	}

	@Test
	void existingEmailIsAConflictAndNothingIsSaved() {
		when(users.existsByEmail("bob@example.com")).thenReturn(true);

		assertThatThrownBy(() -> service.addCustomer(admin, new AddCustomerRequest("BOB@example.com", List.of("plan-b"))))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.getCode()).isEqualTo("email_exists");
				});
		verify(users, never()).save(any());
	}

	@Test
	void unknownPlanIsRejectedAndNothingIsSaved() {
		when(plans.findBySlugIn(any())).thenReturn(List.of(planA));

		assertThatThrownBy(() -> service.addCustomer(admin,
				new AddCustomerRequest("bob@example.com", List.of("plan-a", "does-not-exist"))))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(e.getCode()).isEqualTo("unknown_plan");
				});
		verify(users, never()).save(any());
	}

	@Test
	void atLeastOnePlanIsRequired() {
		assertThatThrownBy(() -> service.addCustomer(admin, new AddCustomerRequest("bob@example.com", List.of())))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("no_plans"));
		verify(users, never()).save(any());
	}

}
