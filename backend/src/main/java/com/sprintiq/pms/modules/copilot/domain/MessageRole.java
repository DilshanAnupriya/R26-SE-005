package com.sprintiq.pms.modules.copilot.domain;

/** The two roles a stored message can have. Tool exchanges are never persisted. */
public enum MessageRole {
    USER,
    ASSISTANT
}
