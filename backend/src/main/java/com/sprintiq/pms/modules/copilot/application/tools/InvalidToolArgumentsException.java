package com.sprintiq.pms.modules.copilot.application.tools;

/** The model called a tool with arguments that do not fit its schema. */
public class InvalidToolArgumentsException extends RuntimeException {
    public InvalidToolArgumentsException(String message) {
        super(message);
    }
}
