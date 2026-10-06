package com.fitfam.api.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "enrollments")
public class Enrollment {

	public static final String STATUS_ACTIVE = "active";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "plan_id")
	private Plan plan;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "current_level_id")
	private PlanLevel currentLevel;

	@Column(nullable = false)
	private String status;

	@Column(name = "started_at", insertable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	protected Enrollment() {
	}

	public Enrollment(User user, Plan plan, PlanLevel currentLevel) {
		this.user = user;
		this.plan = plan;
		this.currentLevel = currentLevel;
		this.status = STATUS_ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public Plan getPlan() {
		return plan;
	}

	public PlanLevel getCurrentLevel() {
		return currentLevel;
	}

	public String getStatus() {
		return status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

}
