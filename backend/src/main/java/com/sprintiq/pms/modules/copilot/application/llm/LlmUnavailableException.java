package com.sprintiq.pms.modules.copilot.application.llm;

/**
 * The provider could not answer. The message is written for the person using the app —
 * "the provider rejected the API key" — because they are the one who has to act on it.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
