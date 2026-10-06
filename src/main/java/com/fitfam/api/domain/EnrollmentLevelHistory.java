package com.fitfam.api.domain;

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
@Table(name = "enrollment_level_history")
public class EnrollmentLevelHistory {

	public static final String REASON_ASSIGNED = "assigned";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "enrollment_id")
	private Enrollment enrollment;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "from_level_id")
	private PlanLevel fromLevel;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "to_level_id")
	private PlanLevel toLevel;

	@Column(nullable = false)
	private String reason;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "changed_by")
	private User changedBy;

	private String note;

	protected EnrollmentLevelHistory() {
	}

	public EnrollmentLevelHistory(Enrollment enrollment, PlanLevel fromLevel, PlanLevel toLevel, String reason,
			User changedBy) {
		this.enrollment = enrollment;
		this.fromLevel = fromLevel;
		this.toLevel = toLevel;
		this.reason = reason;
		this.changedBy = changedBy;
	}

	public UUID getId() {
		return id;
	}

}
