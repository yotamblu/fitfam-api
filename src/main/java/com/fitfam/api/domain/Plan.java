package com.fitfam.api.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "plans")
public class Plan {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String slug;

	@Column(name = "name_he")
	private String nameHe;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	protected Plan() {
	}

	public UUID getId() {
		return id;
	}

	public String getSlug() {
		return slug;
	}

	public String getNameHe() {
		return nameHe;
	}

	public int getSortOrder() {
		return sortOrder;
	}

}
