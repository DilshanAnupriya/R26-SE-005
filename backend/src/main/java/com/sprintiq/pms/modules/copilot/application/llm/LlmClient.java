package com.sprintiq.pms.modules.copilot.application.llm;

import java.util.List;

/**
 * A chat model that can call tools.
 *
 * <p>An interface so the agent loop can be tested against a scripted model, and so a
 * provider whose API is not OpenAI-shaped can be added without touching the loop.</p>
 */
public interface LlmClient {

    /** False when no key is configured; the Copilot is then off rather than broken. */
    boolean isConfigured();

    /** The configured model name, for display before any call has been made. */
    String modelName();

    /**
     * One round trip. {@code tools} may be empty, which forces a text answer.
     *
     * @throws LlmUnavailableException when the provider cannot be reached or refuses
     */
    LlmReply chat(List<LlmMessage> messages, List<ToolSpec> tools);
}
