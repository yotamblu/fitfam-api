package com.fitfam.api.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	public static final String ROLE_CUSTOMER = "customer";
	public static final String ROLE_ADMIN = "admin";
	public static final String STATUS_INVITED = "invited";
	public static final String STATUS_ACTIVE = "active";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String role;

	@Column(nullable = false)
	private String status;

	@Column(name = "display_name")
	private String displayName;

	@Column(name = "avatar_url")
	private String avatarUrl;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "first_login_at")
	private Instant firstLoginAt;

	protected User() {
	}

	public User(String email, String role, String status) {
		this.email = email;
		this.role = role;
		this.status = status;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getRole() {
		return role;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public void setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getFirstLoginAt() {
		return firstLoginAt;
	}

	public void setFirstLoginAt(Instant firstLoginAt) {
		this.firstLoginAt = firstLoginAt;
	}

}
