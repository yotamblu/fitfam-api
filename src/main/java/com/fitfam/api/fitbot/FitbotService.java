package com.fitfam.api.fitbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.legal.ConsentService;
import com.fitfam.api.web.ApiException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Fitbot conversations. One message in, one answer out; the answer is built from fixed rules, the coaches' knowledge
 * sections, what is known about the customer, and the recent history. Nothing here holds a database transaction while
 * the model is working (the connection pool is small). Prompts and answers are never logged.
 */
@Service
public class FitbotService {

	private static final Logger log = LoggerFactory.getLogger(FitbotService.class);
	private static final String DEFAULT_TITLE = "שיחה חדשה";
	/** How many earlier messages go to the model with each new one. */
	private static final int HISTORY_MESSAGES = 20;
	private static final int MAX_MESSAGE_CHARS = 2000;
	private static final Pattern OPEN_WORKOUT = Pattern.compile("\\[\\[open_workout:([0-9a-fA-F-]{36})\\]\\]");
	private static final Pattern ANY_MARKER = Pattern.compile("\\[\\[[^\\]]{0,80}\\]\\]");

	public record ChatSummary(String id, String title, String updatedAt) {
	}

	public record Action(String type, String workoutId, String title) {
	}

	public record MessageDto(String id, String role, String content, List<Action> actions, String createdAt) {
	}

	public record ChatDto(String id, String title, String updatedAt, List<MessageDto> messages) {
	}

	public record Overview(boolean available, boolean aiConsent, int dailyLimit, int usedToday, int retentionDays,
			List<ChatSummary> chats) {
	}

	public record SendRequest(@NotBlank @Size(max = MAX_MESSAGE_CHARS) String message) {
	}

	public record RenameRequest(@NotBlank @Size(max = 80) String title) {
	}

	public record SendResult(MessageDto userMessage, MessageDto reply, int usedToday, int dailyLimit) {
	}

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final ChatModel model;
	private final FitbotTools tools;
	private final KnowledgeService knowledge;
	private final ConsentService consents;
	private final boolean enabled;
	private final boolean adminsOnly;
	private final int dailyLimit;
	private final int retentionDays;

	public FitbotService(JdbcTemplate jdbc, ObjectMapper mapper, ChatModel model, FitbotTools tools,
			KnowledgeService knowledge, ConsentService consents,
			@Value("${app.fitbot.enabled:true}") boolean enabled,
			@Value("${app.fitbot.admins-only:true}") boolean adminsOnly,
			@Value("${app.fitbot.daily-messages:30}") int dailyLimit,
			@Value("${app.fitbot.retention-days:100}") int retentionDays) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.model = model;
		this.tools = tools;
		this.knowledge = knowledge;
		this.consents = consents;
		this.enabled = enabled;
		this.adminsOnly = adminsOnly;
		this.dailyLimit = dailyLimit;
		this.retentionDays = retentionDays;
	}

	// ---- availability and consent ----

	/** Switched on, a key is set, and (while on a free provider tier) the customer is a team member. */
	public boolean available(AuthenticatedUser user) {
		return enabled && model.configured() && (!adminsOnly || user.isAdmin());
	}

	private void requireAvailable(AuthenticatedUser user) {
		if (!available(user)) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fitbot_unavailable");
		}
	}

	private boolean hasAiConsent(UUID userId) {
		Boolean ok = jdbc.queryForObject(
				"select exists (select 1 from user_consents where user_id = ? and kind = 'ai_chat' and version = ?)",
				Boolean.class, userId, LegalVersionsAi.AI_CHAT);
		return Boolean.TRUE.equals(ok);
	}

	public Overview acceptAiConsent(AuthenticatedUser user) {
		requireAvailable(user);
		if (!hasAiConsent(user.id())) {
			jdbc.update("insert into user_consents (user_id, kind, version, details) values (?, 'ai_chat', ?, '{}'::jsonb)",
					user.id(), LegalVersionsAi.AI_CHAT);
		}
		return overview(user);
	}

	// ---- reading ----

	public Overview overview(AuthenticatedUser user) {
		if (!available(user)) {
			return new Overview(false, false, dailyLimit, 0, retentionDays, List.of());
		}
		List<ChatSummary> chats = jdbc.query("""
				select id::text as id, title, updated_at::text as updated_at from chat_threads
				where user_id = ? order by updated_at desc limit 200
				""", (rs, i) -> new ChatSummary(rs.getString("id"), rs.getString("title"), rs.getString("updated_at")),
				user.id());
		return new Overview(true, hasAiConsent(user.id()), dailyLimit, usedToday(user.id()), retentionDays, chats);
	}

	public ChatDto get(AuthenticatedUser user, UUID chatId) {
		requireAvailable(user);
		ChatSummary thread = owned(user.id(), chatId);
		List<MessageDto> messages = jdbc.query("""
				select id::text as id, role, content, actions::text as actions, created_at::text as created_at
				from chat_messages where thread_id = ? order by created_at, id
				""", (rs, i) -> new MessageDto(rs.getString("id"), rs.getString("role"), rs.getString("content"),
				parseActions(rs.getString("actions")), rs.getString("created_at")), chatId);
		return new ChatDto(thread.id(), thread.title(), thread.updatedAt(), messages);
	}

	// ---- writing ----

	public ChatSummary create(AuthenticatedUser user) {
		requireAvailable(user);
		UUID id = jdbc.queryForObject("insert into chat_threads (user_id, title) values (?, ?) returning id", UUID.class,
				user.id(), DEFAULT_TITLE);
		return owned(user.id(), id);
	}

	public ChatSummary rename(AuthenticatedUser user, UUID chatId, String title) {
		requireAvailable(user);
		owned(user.id(), chatId);
		String clean = title.replaceAll("\\s+", " ").strip();
		if (clean.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
		}
		jdbc.update("update chat_threads set title = ?, title_source = 'user' where id = ? and user_id = ?", clean, chatId,
				user.id());
		return owned(user.id(), chatId);
	}

	public void delete(AuthenticatedUser user, UUID chatId) {
		owned(user.id(), chatId);
		jdbc.update("delete from chat_threads where id = ? and user_id = ?", chatId, user.id());
	}

	/** One customer message in, one Fitbot answer out. */
	public SendResult send(AuthenticatedUser user, UUID chatId, String rawMessage) {
		requireAvailable(user);
		consents.requireCurrent(user);
		if (!hasAiConsent(user.id())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ai_consent_required");
		}
		String message = rawMessage == null ? "" : rawMessage.strip();
		if (message.isEmpty() || message.length() > MAX_MESSAGE_CHARS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
		}
		ChatSummary thread = owned(user.id(), chatId);
		int used = usedToday(user.id());
		if (used >= dailyLimit) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "daily_limit");
		}

		UUID userMessageId = jdbc.queryForObject(
				"insert into chat_messages (thread_id, user_id, role, content) values (?, ?, 'user', ?) returning id",
				UUID.class, chatId, user.id(), message);
		if (DEFAULT_TITLE.equals(thread.title())) {
			String fallback = message.replaceAll("\\s+", " ");
			jdbc.update("update chat_threads set title = ? where id = ? and title_source = 'fallback'",
					fallback.length() > 40 ? fallback.substring(0, 40).strip() + "…" : fallback, chatId);
		}

		ChatModel.Reply reply;
		FitbotTools.Session session = tools.session(user.id());
		try {
			reply = model.chat(systemPrompt(user, session), history(chatId), tools.specs(), session);
		}
		catch (RuntimeException e) {
			// nothing was answered: take the question back so the history stays clean and the daily quota is not used
			jdbc.update("delete from chat_messages where id = ?", userMessageId);
			throw e;
		}

		Answer answer = finish(reply.text(), session.workouts());
		UUID replyId = jdbc.queryForObject("""
				insert into chat_messages (thread_id, user_id, role, content, actions, model, input_tokens, output_tokens)
				values (?, ?, 'assistant', ?, ?::jsonb, ?, ?, ?) returning id
				""", UUID.class, chatId, user.id(), answer.text(), mapper.writeValueAsString(answer.actions()),
				model.modelName(), reply.inputTokens(), reply.outputTokens());
		jdbc.update("update chat_threads set updated_at = now() where id = ?", chatId);
		log.info("Fitbot answered: tokens in={} out={} tools={}", reply.inputTokens(), reply.outputTokens(),
				reply.toolsUsed().size());

		return new SendResult(message(userMessageId), message(replyId), used + 1, dailyLimit);
	}

	/**
	 * Gives a conversation a short subject once it has its first answer. Safe to call more than once and never fails the
	 * caller: if the model is unavailable the first words of the first message stay as the title.
	 */
	public ChatSummary generateTitle(AuthenticatedUser user, UUID chatId) {
		requireAvailable(user);
		ChatSummary thread = owned(user.id(), chatId);
		String source = jdbc.queryForObject("select title_source from chat_threads where id = ?", String.class, chatId);
		if (!"fallback".equals(source)) {
			return thread;
		}
		List<String[]> first = jdbc.query("""
				select role, content from chat_messages where thread_id = ? order by created_at, id limit 2
				""", (rs, i) -> new String[] { rs.getString("role"), rs.getString("content") }, chatId);
		if (first.size() < 2) {
			return thread;
		}
		try {
			String text = "משתמש/ת: " + clip(first.get(0)[1], 400) + "\nFitbot: " + clip(first.get(1)[1], 400);
			String title = model.complete(FitbotPrompts.TITLE_PROMPT, text, 40).text();
			title = title.replaceAll("[\\r\\n\"“”'׳]+", " ").replaceAll("\\s+", " ").strip();
			title = title.replaceAll("[.。]+$", "");
			if (title.length() >= 2 && title.length() <= 60) {
				jdbc.update("update chat_threads set title = ?, title_source = 'auto' where id = ? and title_source = 'fallback'",
						title, chatId);
			}
		}
		catch (RuntimeException e) {
			log.warn("Fitbot: could not title a conversation ({})", e.getClass().getSimpleName());
		}
		return owned(user.id(), chatId);
	}

	/** Deletes conversations nobody has touched for the retention period (messages go with them). */
	public int deleteExpired() {
		return jdbc.update("delete from chat_threads where updated_at < now() - make_interval(days => ?)", retentionDays);
	}

	// ---- helpers ----

	private String systemPrompt(AuthenticatedUser user, FitbotTools.Session session) {
		StringBuilder context = new StringBuilder();
		String first = firstName(user.displayName());
		if (first != null) {
			context.append("שם פרטי: ").append(first).append('\n');
		}
		context.append(session.run("get_my_progress", mapper.createObjectNode()).path("text").stringValue());
		return FitbotPrompts.system(knowledge.listEnabled(), context.toString());
	}

	private List<ChatModel.Turn> history(UUID chatId) {
		List<ChatModel.Turn> turns = new ArrayList<>(jdbc.query("""
				select role, content from (
				    select role, content, created_at, id from chat_messages where thread_id = ?
				    order by created_at desc, id desc limit ?
				) recent order by created_at, id
				""", (rs, i) -> new ChatModel.Turn(rs.getString("role"), rs.getString("content")), chatId,
				HISTORY_MESSAGES));
		while (!turns.isEmpty() && !"user".equals(turns.get(0).role())) {
			turns.remove(0); // the model needs the first turn to be the customer's
		}
		return turns;
	}

	record Answer(String text, List<Action> actions) {
	}

	/**
	 * Applies the markers the model may add: the nutrition marker becomes the fixed disclaimer, and workout buttons are
	 * kept only for workouts a tool actually returned in this turn. All markers are removed from the visible text.
	 */
	static Answer finish(String raw, Map<String, String> knownWorkouts) {
		String text = raw;
		boolean nutrition = text.contains(FitbotPrompts.NUTRITION_MARKER);
		Map<String, Action> actions = new LinkedHashMap<>();
		Matcher m = OPEN_WORKOUT.matcher(text);
		while (m.find()) {
			String id = m.group(1).toLowerCase();
			String title = knownWorkouts.get(id);
			if (title != null) {
				actions.putIfAbsent(id, new Action("open_workout", id, title));
			}
		}
		text = ANY_MARKER.matcher(text).replaceAll("").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
		if (nutrition) {
			text = text + "\n\n" + FitbotPrompts.NUTRITION_DISCLAIMER;
		}
		return new Answer(text, List.copyOf(actions.values()));
	}

	private MessageDto message(UUID id) {
		return jdbc.queryForObject("""
				select id::text as id, role, content, actions::text as actions, created_at::text as created_at
				from chat_messages where id = ?
				""", (rs, i) -> new MessageDto(rs.getString("id"), rs.getString("role"), rs.getString("content"),
				parseActions(rs.getString("actions")), rs.getString("created_at")), id);
	}

	private List<Action> parseActions(String json) {
		try {
			return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<Action>>() {
			});
		}
		catch (RuntimeException e) {
			return List.of();
		}
	}

	private ChatSummary owned(UUID userId, UUID chatId) {
		return jdbc.query("""
				select id::text as id, title, updated_at::text as updated_at from chat_threads where id = ? and user_id = ?
				""", (rs, i) -> new ChatSummary(rs.getString("id"), rs.getString("title"), rs.getString("updated_at")),
				chatId, userId).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
	}

	/** Messages the customer sent today (Israel time); this is what the daily limit counts. */
	private int usedToday(UUID userId) {
		Integer n = jdbc.queryForObject("""
				select count(*) from chat_messages where user_id = ? and role = 'user'
				  and created_at >= (date_trunc('day', now() at time zone 'Asia/Jerusalem') at time zone 'Asia/Jerusalem')
				""", Integer.class, userId);
		return n == null ? 0 : n;
	}

	private static String firstName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			return null;
		}
		String first = displayName.strip().split("\\s+")[0];
		return first.length() > 30 ? null : first;
	}

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

}
