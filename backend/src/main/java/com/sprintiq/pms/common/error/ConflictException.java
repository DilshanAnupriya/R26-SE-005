package com.sprintiq.pms.common.error;

public class ConflictException extends ApplicationException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public static ConflictException duplicate(String resource, String field, Object value) {
        return new ConflictException("%s with %s '%s' already exists".formatted(resource, field, value));
    }

    public static ConflictException illegalTransition(String entity, String from, String to) {
        return new ConflictException("%s cannot move from %s to %s".formatted(entity, from, to));
    }
}
