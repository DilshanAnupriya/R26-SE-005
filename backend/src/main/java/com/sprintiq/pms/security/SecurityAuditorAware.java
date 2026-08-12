package com.sprintiq.pms.security;

import com.sprintiq.pms.tenancy.TenantContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Feeds {@code created_by} / {@code updated_by} from the request context. */
@Component("auditorAware")
public class SecurityAuditorAware implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return TenantContext.userId();
    }
}
