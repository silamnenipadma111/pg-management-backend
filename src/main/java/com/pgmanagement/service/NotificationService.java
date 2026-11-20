package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.CommonDtos.NotificationDto;
import com.pgmanagement.entity.Notification;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MessagingService messagingService;
    private final AppProperties appProperties;

    /** In-app (website) notification only. */
    @Transactional
    public void notify(User user, String title, String message, String link) {
        Notification n = new Notification();
        n.setUser(user);
        n.setTitle(title);
        n.setMessage(message);
        n.setLink(link);
        notificationRepository.save(n);
    }

    /**
     * In-app notification plus Email / SMS / WhatsApp. External messages are sent only after the
     * surrounding transaction commits, so a rolled back action never produces a message.
     */
    @Transactional
    public void notifyAllChannels(User user, String title, String message, String link) {
        notify(user, title, message, link);
        MessagingService.Recipient to = recipient(user);
        String url = absolute(link);
        AfterCommit.run(() -> messagingService.sendAsync(to, title, message, url));
    }

    /**
     * Email confirmation for the person who performed the action (they are already looking at the result on
     * the website, so no in-app notification, SMS or WhatsApp message is created).
     */
    public void emailConfirmation(User user, String title, String message, String link) {
        MessagingService.Recipient to = recipient(user);
        String url = absolute(link);
        AfterCommit.run(() -> messagingService.sendEmailAsync(to, title, message, url));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Long userId) {
        List<NotificationDto> items = notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DtoMapper::toNotificationDto).toList();
        return Map.of("unread", notificationRepository.countByUserIdAndReadFalse(userId), "items", items);
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = notificationRepository.findById(id)
                .filter(x -> x.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Notification"));
        n.setRead(true);
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.findByUserIdAndReadFalse(userId).forEach(n -> n.setRead(true));
    }

    private static MessagingService.Recipient recipient(User user) {
        return new MessagingService.Recipient(user.getName(), user.getEmail(), user.getPhone());
    }

    /** Website links stored with notifications are relative ("/my-rents?tab=upcoming"); emails need the full URL. */
    private String absolute(String link) {
        if (link == null || link.isBlank()) {
            return appProperties.siteBaseUrl();
        }
        return link.startsWith("/") ? appProperties.siteBaseUrl() + link : link;
    }

}
