package com.sprintiq.pms.common.domain;

import java.lang.annotation.*;

/**
 * Marks an entity that is deliberately <em>not</em> tenant-scoped because it is
 * platform reference data shared by every organization — the AI model registry, the
 * permission catalogue, the system roles.
 *
 * <p>The architecture test refuses any entity that neither extends a tenant-scoped
 * superclass nor carries this annotation. Without it the escape hatch would be a list
 * of class-name prefixes in the test, which is exactly the kind of rule that gets
 * quietly widened until it stops meaning anything. An annotation on the class puts the
 * exemption next to the thing being exempted, where a reviewer will see it.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GlobalReferenceEntity {

    /** Why this table is shared across tenants. */
    String value();
}
