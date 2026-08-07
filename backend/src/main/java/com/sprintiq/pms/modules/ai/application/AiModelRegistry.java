package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.common.error.ApplicationException;
import com.sprintiq.pms.common.error.ErrorCode;
import com.sprintiq.pms.common.error.ResourceNotFoundException;
import com.sprintiq.pms.modules.ai.domain.AiModel;
import com.sprintiq.pms.modules.ai.domain.AiModelCode;
import com.sprintiq.pms.modules.ai.domain.AiModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Reads and edits the model registry.
 *
 * <p>Cached because every AI call needs the row and it changes perhaps twice a year;
 * evicted on write so a model disabled during an incident stops being called on the
 * next request rather than at the end of a TTL.</p>
 */
@Service
@RequiredArgsConstructor
public class AiModelRegistry {

    public static final String CACHE = "aiModels";

    private final AiModelRepository repository;

    @Transactional(readOnly = true)
    public List<AiModel> all() {
        return repository.findAllByOrderByCodeAsc();
    }

    @Cacheable(cacheNames = CACHE, key = "#code.name()")
    @Transactional(readOnly = true)
    public AiModel require(AiModelCode code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("AiModel", code.name()));
    }

    /**
     * Throws rather than returning a flag: a disabled model is a configuration decision
     * the caller cannot sensibly work around, and the error code maps to a 403 the UI
     * already knows how to render as "this capability is switched off".
     */
    public AiModel requireEnabled(AiModelCode code) {
        AiModel model = require(code);
        if (!model.isEnabled()) {
            throw new ApplicationException(ErrorCode.AI_FEATURE_DISABLED,
                    "%s is currently disabled".formatted(model.getName()));
        }
        return model;
    }

    public Duration latencyBudget(AiModel model) {
        return Duration.ofMillis(model.getLatencyBudgetMs());
    }

    @CacheEvict(cacheNames = CACHE, key = "#code.name()")
    @Transactional
    public AiModel update(AiModelCode code, Boolean enabled, String version, String endpointUrl,
                          Integer latencyBudgetMs) {
        AiModel model = repository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("AiModel", code.name()));
        if (enabled != null) model.setEnabled(enabled);
        if (version != null && !version.isBlank()) model.setVersion(version);
        if (endpointUrl != null) model.setEndpointUrl(endpointUrl.isBlank() ? null : endpointUrl);
        if (latencyBudgetMs != null && latencyBudgetMs > 0) model.setLatencyBudgetMs(latencyBudgetMs);
        return repository.save(model);
    }
}
