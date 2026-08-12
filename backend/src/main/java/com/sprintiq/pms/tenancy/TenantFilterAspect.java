package com.sprintiq.pms.tenancy;

import com.sprintiq.pms.common.domain.TenantAwareEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enables the Hibernate tenant filter for the duration of every repository call,
 * so {@code AND organization_id = ?} is appended to all generated SQL without any
 * repository author having to remember it.
 */
@Slf4j
@Aspect
@Component
@Order(100)
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* com.sprintiq.pms.modules..*Repository+.*(..))")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID organizationId = TenantContext.organizationId().orElse(null);
        boolean systemContext = TenantContext.get().map(TenantContext.Context::systemContext).orElse(false);

        if (organizationId == null || systemContext) {
            // Unauthenticated flows (registration, login, invitation acceptance) and
            // system jobs operate outside the filter; they use explicitly scoped queries.
            return joinPoint.proceed();
        }

        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(TenantAwareEntity.TENANT_FILTER)
                .setParameter(TenantAwareEntity.TENANT_PARAM, organizationId);
        try {
            return joinPoint.proceed();
        } finally {
            session.disableFilter(TenantAwareEntity.TENANT_FILTER);
        }
    }
}
