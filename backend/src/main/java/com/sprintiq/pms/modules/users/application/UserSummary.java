package com.sprintiq.pms.modules.users.application;

import java.util.UUID;

/**
 * The users module's published mini-profile: just enough to render an avatar chip.
 *
 * <p>Every delivery module shows people — assignees, project members, comment authors,
 * the workload board. They need a name and a face, not the full {@code UserResponse}
 * with status, MFA and login history, and certainly not the {@code User} entity.</p>
 */
public record UserSummary(UUID id, String fullName, String email, String avatarUrl) {}
