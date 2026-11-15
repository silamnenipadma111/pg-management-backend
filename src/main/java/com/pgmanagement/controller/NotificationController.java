package com.pgmanagement.controller;

import com.pgmanagement.security.AuthUser;
import com.pgmanagement.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthUser user) {
        return notificationService.list(user.id());
    }

    @PostMapping("/{id}/read")
    public void read(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        notificationService.markRead(user.id(), id);
    }

    @PostMapping("/read-all")
    public void readAll(@AuthenticationPrincipal AuthUser user) {
        notificationService.markAllRead(user.id());
    }
}
