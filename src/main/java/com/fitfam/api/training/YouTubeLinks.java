package com.fitfam.api.training;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts only https links on YouTube hosts and reduces them to the 11-character video id. Only the id is stored, so
 * a coach cannot put an arbitrary address into the customer app.
 */
public final class YouTubeLinks {

	private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
	private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
			"youtube-nocookie.com", "www.youtube-nocookie.com");
	private static final Set<String> PATH_PREFIXES = Set.of("shorts", "embed", "live", "v");

	private YouTubeLinks() {
	}

	/** Returns the video id, or {@code null} if the link is not an acceptable YouTube link. */
	public static String extractId(String link) {
		if (link == null || link.isBlank() || link.length() > 300) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(link.trim());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
			return null;
		}
		String host = uri.getHost().toLowerCase();
		String path = uri.getPath() == null ? "" : uri.getPath();
		String candidate = null;
		if ("youtu.be".equals(host)) {
			candidate = firstSegment(path);
		}
		else if (HOSTS.contains(host)) {
			if (path.equals("/watch")) {
				candidate = queryParam(uri.getRawQuery(), "v");
			}
			else {
				String[] parts = path.split("/");
				if (parts.length == 3 && PATH_PREFIXES.contains(parts[1])) {
					candidate = parts[2];
				}
			}
		}
		return candidate != null && ID.matcher(candidate).matches() ? candidate : null;
	}

	public static String canonicalUrl(String id) {
		return id == null ? null : "https://www.youtube.com/watch?v=" + id;
	}

	private static String firstSegment(String path) {
		String[] parts = path.split("/");
		return parts.length == 2 ? parts[1] : null;
	}

	private static String queryParam(String rawQuery, String name) {
		if (rawQuery == null) {
			return null;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return pair.substring(eq + 1);
			}
		}
		return null;
	}

}
