package com.fitfam.api.auth;

import java.util.UUID;

import com.fitfam.api.domain.User;

/** The logged-in user, available as the security principal. Rebuilt from the database on every request. */
public record AuthenticatedUser(UUID id, String email, String role, String displayName, String avatarUrl) {

	public static AuthenticatedUser of(User user) {
		return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(),
				user.getAvatarUrl());
	}

	public boolean isAdmin() {
		return User.ROLE_ADMIN.equals(role);
	}

}
