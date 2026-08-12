package com.sprintiq.pms.tenancy;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/** Propagates tenant context and MDC into {@code @Async} executors. */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.Context context = TenantContext.get().orElse(null);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) TenantContext.set(context);
                if (mdc != null) MDC.setContextMap(mdc);
                runnable.run();
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        };
    }
}
