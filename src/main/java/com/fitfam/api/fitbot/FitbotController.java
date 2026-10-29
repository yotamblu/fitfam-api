package com.fitfam.api.fitbot;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.fitbot.FitbotService.ChatDto;
import com.fitfam.api.fitbot.FitbotService.ChatSummary;
import com.fitfam.api.fitbot.FitbotService.Overview;
import com.fitfam.api.fitbot.FitbotService.RenameRequest;
import com.fitfam.api.fitbot.FitbotService.SendRequest;
import com.fitfam.api.fitbot.FitbotService.SendResult;
import com.fitfam.api.fitbot.KnowledgeService.Section;
import com.fitfam.api.fitbot.KnowledgeService.SectionRequest;

import jakarta.validation.Valid;

/** Fitbot for customers (`/me/chats`) and the coaches' knowledge sections (`/admin/fitbot/knowledge`, admin only). */
@RestController
public class FitbotController {

	private final FitbotService fitbot;
	private final KnowledgeService knowledge;

	public FitbotController(FitbotService fitbot, KnowledgeService knowledge) {
		this.fitbot = fitbot;
		this.knowledge = knowledge;
	}

	/** Whether Fitbot is available to this customer, their conversations, and today's message count. */
	@GetMapping("/me/chats")
	public Overview overview(@AuthenticationPrincipal AuthenticatedUser user) {
		return fitbot.overview(user);
	}

	/** The customer agrees that their chats are processed by an AI provider. */
	@PostMapping("/me/chats/consent")
	public Overview consent(@AuthenticationPrincipal AuthenticatedUser user) {
		return fitbot.acceptAiConsent(user);
	}

	@PostMapping("/me/chats")
	@ResponseStatus(HttpStatus.CREATED)
	public ChatSummary create(@AuthenticationPrincipal AuthenticatedUser user) {
		return fitbot.create(user);
	}

	/** Opens the conversation about a finished workout (Fitbot writes the first message); asking again returns the same one. */
	@PostMapping("/me/chats/after-workout/{workoutId}")
	public ChatDto afterWorkout(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workoutId) {
		return fitbot.afterWorkout(user, workoutId);
	}

	@GetMapping("/me/chats/{id}")
	public ChatDto chat(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return fitbot.get(user, id);
	}

	@PostMapping("/me/chats/{id}/messages")
	public SendResult send(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody SendRequest request) {
		return fitbot.send(user, id, request.message());
	}

	/** Asks the model for a short subject after the first answer. Never fails because of the model. */
	@PostMapping("/me/chats/{id}/title")
	public ChatSummary title(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return fitbot.generateTitle(user, id);
	}

	@PutMapping("/me/chats/{id}")
	public ChatSummary rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody RenameRequest request) {
		return fitbot.rename(user, id, request.title());
	}

	@DeleteMapping("/me/chats/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		fitbot.delete(user, id);
	}

	// ---- coaches ----

	@GetMapping("/admin/fitbot/knowledge")
	public List<Section> sections() {
		return knowledge.listAll();
	}

	@PostMapping("/admin/fitbot/knowledge")
	@ResponseStatus(HttpStatus.CREATED)
	public Section createSection(@AuthenticationPrincipal AuthenticatedUser admin, @Valid @RequestBody SectionRequest request) {
		return knowledge.create(admin, request);
	}

	@PutMapping("/admin/fitbot/knowledge/{id}")
	public Section updateSection(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
			@Valid @RequestBody SectionRequest request) {
		return knowledge.update(admin, id, request);
	}

	@DeleteMapping("/admin/fitbot/knowledge/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSection(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		knowledge.delete(admin, id);
	}

}
