package com.fitfam.api.fitbot;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The one place the rest of the app knows about an AI provider. {@link GeminiChatModel} is the only implementation;
 * switching vendor means writing another class that implements this and changing the bean, nothing else.
 */
public interface ChatModel {

	/** One earlier turn of the conversation. {@code role} is {@code user} or {@code assistant}. */
	record Turn(String role, String text) {
	}

	/** A function the model may ask us to run. {@code parameters} is a JSON-schema-like object (see {@code FitbotTools}). */
	record ToolSpec(String name, String description, JsonNode parameters) {
	}

	/** Runs a tool for the model; the result is shown back to the model as JSON. Must never throw for bad arguments. */
	interface ToolRunner {

		JsonNode run(String name, JsonNode arguments);
	}

	record Reply(String text, int inputTokens, int outputTokens, List<String> toolsUsed) {
	}

	/** True when a key and a model are configured. */
	boolean configured();

	String modelName();

	/**
	 * Answers the last turn of {@code history}. The model may call tools (at most a few rounds) through {@code runner};
	 * {@code tools} may be empty.
	 */
	Reply chat(String systemPrompt, List<Turn> history, List<ToolSpec> tools, ToolRunner runner);

	/** A single short completion with no tools (used for conversation titles). */
	Reply complete(String systemPrompt, String userText, int maxOutputTokens);

}
