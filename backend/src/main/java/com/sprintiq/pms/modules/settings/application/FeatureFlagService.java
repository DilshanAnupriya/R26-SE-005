package com.sprintiq.pms.modules.settings.application;

import com.sprintiq.pms.modules.settings.domain.FeatureFlag;
import com.sprintiq.pms.modules.settings.domain.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Resolves capability switches: workspace override first, organization default second,
 * enabled if neither exists.
 *
 * <p>Reads are not cached. The obvious optimisation is a Redis entry per (workspace,
 * key), but a flag that stays on for ninety seconds after an administrator switches it
 * off is exactly the failure the switch exists to prevent — an AI capability turned off
 * because it is producing bad numbers, or because a customer's contract does not cover
 * it. Two indexed lookups against a small table are the cheaper trade.</p>
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    public static final String AI_STORY_POINTS = "ai.story_points";
    public static final String AI_RISK = "ai.risk";
    public static final String AI_ALLOCATION = "ai.allocation";
    public static final String AI_COST = "ai.cost";

    /** Every flag the product knows about, so the admin UI can list them all. */
    public static final List<String> KNOWN_FLAGS =
            List.of(AI_STORY_POINTS, AI_RISK, AI_ALLOCATION, AI_COST);

    private final FeatureFlagRepository repository;

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID workspaceId, String flagKey) {
        if (workspaceId != null) {
            Optional<FeatureFlag> override = repository.findByWorkspaceIdAndFlagKey(workspaceId, flagKey);
            if (override.isPresent()) {
                return override.get().isEnabled();
            }
        }
        return repository.findByWorkspaceIdIsNullAndFlagKey(flagKey)
                .map(FeatureFlag::isEnabled)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> configOf(UUID workspaceId, String flagKey) {
        if (workspaceId != null) {
            Optional<FeatureFlag> override = repository.findByWorkspaceIdAndFlagKey(workspaceId, flagKey);
            if (override.isPresent()) {
                return override.get().getConfig();
            }
        }
        return repository.findByWorkspaceIdIsNullAndFlagKey(flagKey)
                .map(FeatureFlag::getConfig)
                .orElseGet(Map::of);
    }

    /**
     * Where a flag's value came from, alongside the value.
     *
     * <p>{@code overridden} matters to the settings screen: an administrator needs to
     * see the difference between "this workspace has chosen to switch cost forecasting
     * off" and "the organization has it off and this workspace has said nothing", because
     * only the first has anything to clear.</p>
     */
    public record EffectiveFlag(String flagKey, boolean enabled, boolean overridden,
                                Map<String, Object> config) {}

    /** Effective state of every known flag for a workspace — what the settings UI renders. */
    @Transactional(readOnly = true)
    public List<EffectiveFlag> effectiveFlags(UUID workspaceId) {
        List<EffectiveFlag> resolved = new ArrayList<>(KNOWN_FLAGS.size());
        for (String key : KNOWN_FLAGS) {
            Optional<FeatureFlag> override = workspaceId == null
                    ? Optional.empty()
                    : repository.findByWorkspaceIdAndFlagKey(workspaceId, key);
            Optional<FeatureFlag> fallback = override.isPresent()
                    ? override
                    : repository.findByWorkspaceIdIsNullAndFlagKey(key);
            resolved.add(new EffectiveFlag(key,
                    fallback.map(FeatureFlag::isEnabled).orElse(true),
                    override.isPresent(),
                    fallback.map(FeatureFlag::getConfig).orElseGet(Map::of)));
        }
        return resolved;
    }

    @Transactional
    public FeatureFlag set(UUID workspaceId, String flagKey, boolean enabled, Map<String, Object> config) {
        FeatureFlag flag = (workspaceId == null
                ? repository.findByWorkspaceIdIsNullAndFlagKey(flagKey)
                : repository.findByWorkspaceIdAndFlagKey(workspaceId, flagKey))
                .orElseGet(() -> FeatureFlag.builder()
                        .workspaceId(workspaceId)
                        .flagKey(flagKey)
                        .build());
        flag.setEnabled(enabled);
        if (config != null) {
            flag.setConfig(new HashMap<>(config));
        }
        return repository.save(flag);
    }

    @Transactional
    public void clearOverride(UUID workspaceId, String flagKey) {
        repository.findByWorkspaceIdAndFlagKey(workspaceId, flagKey).ifPresent(repository::delete);
    }
}
