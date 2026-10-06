package com.fitfam.api.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "plan_levels")
public class PlanLevel {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "plan_id")
	private Plan plan;

	@Column(name = "level_number", nullable = false)
	private int levelNumber;

	@Column(nullable = false)
	private String slug;

	@Column(name = "name_he")
	private String nameHe;

	protected PlanLevel() {
	}

	public UUID getId() {
		return id;
	}

	public Plan getPlan() {
		return plan;
	}

	public int getLevelNumber() {
		return levelNumber;
	}

	public String getSlug() {
		return slug;
	}

	public String getNameHe() {
		return nameHe;
	}

}
