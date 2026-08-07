package com.sprintiq.pms.common.collaboration;

import com.sprintiq.pms.common.domain.EntityType;
import com.sprintiq.pms.common.error.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Routes an {@code (entityType, entityId)} to whichever module owns that type.
 *
 * <p>Spring injects every {@link ItemReferenceResolver} on the classpath, so adding a new
 * commentable item type is one new resolver in the module that owns it — no edit here, no
 * switch statement to keep in step with the enum.</p>
 */
@Component
@RequiredArgsConstructor
public class ItemReferenceRegistry {

    private final List<ItemReferenceResolver> resolvers;

    public ItemReferenceResolver.ItemReference resolve(EntityType entityType, UUID entityId) {
        return resolvers.stream()
                .filter(resolver -> resolver.supports(entityType))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "%s items do not support this operation".formatted(entityType)))
                .resolve(entityId);
    }

    public boolean supports(EntityType entityType) {
        return resolvers.stream().anyMatch(resolver -> resolver.supports(entityType));
    }
}
