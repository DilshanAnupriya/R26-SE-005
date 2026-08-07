package com.sprintiq.pms.modules.notifications.web;

import com.sprintiq.pms.common.web.ApiConstants;
import com.sprintiq.pms.common.web.PageQuery;
import com.sprintiq.pms.common.web.PageResponse;
import com.sprintiq.pms.modules.notifications.application.NotificationService;
import com.sprintiq.pms.modules.notifications.domain.NotificationType;
import com.sprintiq.pms.modules.notifications.web.dto.NotificationDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Your inbox.
 *
 * <p>No {@code @PreAuthorize} anywhere: every operation is implicitly scoped to the
 * caller by the service, and there is no permission that would let one member read
 * another's notifications. Adding one would create a way to grant exactly that.</p>
 */
@Tag(name = "Notifications", description = "Personal notification inbox and preferences")
@RestController
@RequestMapping(ApiConstants.API_V1 + "/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Your notifications")
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) NotificationType type,
            @Valid PageQuery query) {
        return notificationService.inbox(unreadOnly, type, query);
    }

    @Operation(summary = "Unread count for the bell badge")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return notificationService.unreadCount();
    }

    @Operation(summary = "Mark one notification read")
    @PatchMapping("/{notificationId}/read")
    public NotificationResponse markRead(@PathVariable UUID notificationId) {
        return notificationService.markRead(notificationId);
    }

    @Operation(summary = "Mark everything read")
    @PostMapping("/read-all")
    public UnreadCountResponse markAllRead() {
        return notificationService.markAllRead();
    }

    @Operation(summary = "Your delivery preferences",
            description = "Returns every notification type, including those you have never changed.")
    @GetMapping("/preferences")
    public List<NotificationPreferenceResponse> preferences() {
        return notificationService.preferencesOf();
    }

    @Operation(summary = "Update delivery preferences")
    @PutMapping("/preferences")
    public List<NotificationPreferenceResponse> updatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return notificationService.updatePreferences(request);
    }
}
