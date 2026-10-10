package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeLinksTests {

	@ParameterizedTest
	@ValueSource(strings = {
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
			"https://youtube.com/watch?v=dQw4w9WgXcQ&t=30s",
			"https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
			"https://youtu.be/dQw4w9WgXcQ",
			"https://youtu.be/dQw4w9WgXcQ?si=abc",
			"https://www.youtube.com/shorts/dQw4w9WgXcQ",
			"https://www.youtube.com/embed/dQw4w9WgXcQ",
			"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
			"  https://youtu.be/dQw4w9WgXcQ  " })
	void acceptsYouTubeLinks(String link) {
		assertThat(YouTubeLinks.extractId(link)).isEqualTo("dQw4w9WgXcQ");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://www.youtube.com/watch?v=dQw4w9WgXcQ",
			"https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
			"https://evil.example/watch?v=dQw4w9WgXcQ",
			"https://user@www.youtube.com/watch?v=dQw4w9WgXcQ",
			"https://www.youtube.com/watch?v=short",
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ%22%3E",
			"https://www.youtube.com/channel/dQw4w9WgXcQ",
			"javascript:alert(1)",
			"dQw4w9WgXcQ",
			"https://vimeo.com/123456789",
			"" })
	void rejectsEverythingElse(String link) {
		assertThat(YouTubeLinks.extractId(link)).isNull();
	}

	@Test
	void nullIsRejectedAndCanonicalUrlIsRebuiltFromTheId() {
		assertThat(YouTubeLinks.extractId(null)).isNull();
		assertThat(YouTubeLinks.canonicalUrl("dQw4w9WgXcQ")).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
		assertThat(YouTubeLinks.canonicalUrl(null)).isNull();
	}

}
