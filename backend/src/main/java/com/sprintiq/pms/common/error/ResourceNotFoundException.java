package com.sprintiq.pms.common.error;

import java.util.UUID;

public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(String resource, Object identifier) {
        super(ErrorCode.NOT_FOUND, "%s not found: %s".formatted(resource, identifier));
    }

    public static ResourceNotFoundException of(String resource, UUID id) {
        return new ResourceNotFoundException(resource, id);
    }
}
