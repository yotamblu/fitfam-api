package com.fitfam.api.fitbot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini over plain HTTPS ({@code generateContent}). The API key travels in a header, is never logged and never
 * leaves this class. Errors are mapped to {@link ApiException} codes the customer app can explain; prompts, answers and
 * the key are never written to the log.
 */
@Component
public class GeminiChatModel implements ChatModel {

	private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);
	private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
	/** A model answer plus the tool rounds it needs: keep the loop short, every round costs time and tokens. */
	private static final int MAX_ROUNDS = 4;

	private final ObjectMapper mapper;
	private final String apiKey;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public GeminiChatModel(ObjectMapper mapper, @Value("${app.fitbot.api-key:}") String apiKey,
			@Value("${app.fitbot.model:}") String model) {
		this.mapper = mapper;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model == null ? "" : model.trim();
	}

	@Override
	public boolean configured() {
		return !apiKey.isEmpty() && !model.isEmpty();
	}

	@Override
	public String modelName() {
		return model;
	}

	@Override
	public Reply chat(String systemPrompt, List<Turn> history, List<ToolSpec> tools, ToolRunner runner) {
		ArrayNode contents = mapper.createArrayNode();
		for (Turn turn : history) {
			contents.add(textContent("assistant".equals(turn.role()) ? "model" : "user", turn.text()));
		}
		ObjectNode body = baseBody(systemPrompt, 1200, 0.6);
		if (!tools.isEmpty()) {
			ArrayNode declarations = mapper.createArrayNode();
			for (ToolSpec tool : tools) {
				ObjectNode d = mapper.createObjectNode();
				d.put("name", tool.name());
				d.put("description", tool.description());
				if (tool.parameters() != null) {
					d.set("parameters", tool.parameters());
				}
				declarations.add(d);
			}
			body.putArray("tools").addObject().set("functionDeclarations", declarations);
		}
		body.set("contents", contents);

		int in = 0;
		int out = 0;
		List<String> used = new ArrayList<>();
		for (int round = 0; round < MAX_ROUNDS; round++) {
			JsonNode response = post(body);
			in += response.path("usageMetadata").path("promptTokenCount").intValue(0);
			out += response.path("usageMetadata").path("candidatesTokenCount").intValue(0);
			JsonNode candidate = response.path("candidates").path(0);
			JsonNode content = candidate.path("content");
			List<JsonNode> calls = new ArrayList<>();
			StringBuilder text = new StringBuilder();
			for (JsonNode part : content.path("parts")) {
				if (part.has("functionCall")) {
					calls.add(part.path("functionCall"));
				}
				else if (part.has("text") && !part.path("thought").booleanValue(false)) {
					text.append(part.path("text").stringValue());
				}
			}
			if (calls.isEmpty() || round == MAX_ROUNDS - 1) {
				String answer = text.toString().trim();
				if (answer.isEmpty()) {
					// nothing usable: a safety block, an empty answer or too many tool rounds
					log.warn("Fitbot: model returned no text (finishReason={})", candidate.path("finishReason").stringValue());
					throw new ApiException(HttpStatus.BAD_GATEWAY, "fitbot_empty");
				}
				return new Reply(answer, in, out, used);
			}
			// echo the model's own turn back untouched (it may carry provider bookkeeping), then answer each call
			contents.add(content);
			ObjectNode results = mapper.createObjectNode();
			results.put("role", "user");
			ArrayNode parts = results.putArray("parts");
			for (JsonNode call : calls) {
				String name = call.path("name").stringValue();
				used.add(name);
				JsonNode result;
				try {
					result = runner.run(name, call.path("args"));
				}
				catch (RuntimeException e) {
					log.warn("Fitbot: tool {} failed: {}", name, e.getClass().getSimpleName());
					result = mapper.createObjectNode().put("error", "tool_failed");
				}
				ObjectNode fr = parts.addObject().putObject("functionResponse");
				fr.put("name", name);
				ObjectNode wrapped = fr.putObject("response");
				wrapped.set("result", result);
			}
			contents.add(results);
		}
		throw new ApiException(HttpStatus.BAD_GATEWAY, "fitbot_empty");
	}

	@Override
	public Reply complete(String systemPrompt, String userText, int maxOutputTokens) {
		ObjectNode body = baseBody(systemPrompt, maxOutputTokens, 0.2);
		body.putArray("contents").add(textContent("user", userText));
		JsonNode response = post(body);
		StringBuilder text = new StringBuilder();
		for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
			if (part.has("text") && !part.path("thought").booleanValue(false)) {
				text.append(part.path("text").stringValue());
			}
		}
		return new Reply(text.toString().trim(), response.path("usageMetadata").path("promptTokenCount").intValue(0),
				response.path("usageMetadata").path("candidatesTokenCount").intValue(0), List.of());
	}

	private ObjectNode baseBody(String systemPrompt, int maxOutputTokens, double temperature) {
		ObjectNode body = mapper.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("maxOutputTokens", maxOutputTokens);
		generation.put("temperature", temperature);
		return body;
	}

	private ObjectNode textContent(String role, String text) {
		ObjectNode content = mapper.createObjectNode();
		content.put("role", role);
		content.putArray("parts").addObject().put("text", text);
		return content;
	}

	private JsonNode post(ObjectNode body) {
		if (!configured()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fitbot_unavailable");
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
					.timeout(Duration.ofSeconds(45))
					.header("Content-Type", "application/json")
					.header("x-goog-api-key", apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 429) {
				log.warn("Fitbot: provider rate limit reached");
				throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "fitbot_busy");
			}
			if (status == 402 || status == 403) {
				log.warn("Fitbot: provider refused the request (status {}): check the key, the quota or the billing", status);
				throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fitbot_unavailable");
			}
			if (status < 200 || status >= 300) {
				log.warn("Fitbot: provider error status {}", status);
				throw new ApiException(HttpStatus.BAD_GATEWAY, "fitbot_failed");
			}
			return mapper.readTree(response.body());
		}
		catch (IOException e) {
			log.warn("Fitbot: provider unreachable: {}", e.getClass().getSimpleName());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "fitbot_failed");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.BAD_GATEWAY, "fitbot_failed");
		}
	}

}
