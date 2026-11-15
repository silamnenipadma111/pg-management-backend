package com.pgmanagement.service;

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

    @Transactional
    public void notify(User user, String title, String message, String link) {
        Notification n = new Notification();
        n.setUser(user);
        n.setTitle(title);
        n.setMessage(message);
        n.setLink(link);
        notificationRepository.save(n);
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
}
