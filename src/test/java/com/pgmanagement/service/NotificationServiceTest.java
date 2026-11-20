package com.pgmanagement.service;

import com.pgmanagement.dto.CommonDtos.NotificationDto;
import com.pgmanagement.entity.Notification;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.NotificationRepository;
import com.pgmanagement.service.MessagingService.Recipient;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String TITLE = "Booking approved";
    private static final String MESSAGE = "Your request for Room 101 Bed A is approved.";
    private static final String LINK = "/my-rents?tab=upcoming";
    private static final String ABSOLUTE_LINK = TestProps.SITE + LINK;

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private MessagingService messagingService;

    private final User customer = Fixtures.customer(3L);
    private final Recipient recipient = new Recipient(customer.getName(), customer.getEmail(), customer.getPhone());
    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, messagingService, TestProps.app());
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void notifyStoresAnUnreadWebsiteNotificationOnly() {
        service.notify(customer, TITLE, MESSAGE, LINK);

        Notification saved = savedNotification();
        assertThat(saved.getUser()).isSameAs(customer);
        assertThat(saved.getTitle()).isEqualTo(TITLE);
        assertThat(saved.getMessage()).isEqualTo(MESSAGE);
        assertThat(saved.getLink()).isEqualTo(LINK);
        assertThat(saved.isRead()).isFalse();
        verifyNoInteractions(messagingService);
    }

    @Test
    void notifyAllChannelsAlsoSendsExternalMessagesWithAnAbsoluteLink() {
        service.notifyAllChannels(customer, TITLE, MESSAGE, LINK);

        assertThat(savedNotification().getLink()).as("the website keeps the relative link").isEqualTo(LINK);
        verify(messagingService).sendAsync(recipient, TITLE, MESSAGE, ABSOLUTE_LINK);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "/owner/dashboard?tab=approvals, https://pg-stay.in/owner/dashboard?tab=approvals",
            "https://elsewhere.example/page, https://elsewhere.example/page",
            "'', https://pg-stay.in",
            "NULL, https://pg-stay.in"}, nullValues = "NULL")
    void linksInExternalMessagesAlwaysPointToTheWebsite(String link, String expected) {
        service.emailConfirmation(customer, TITLE, MESSAGE, link);

        verify(messagingService).sendEmailAsync(recipient, TITLE, MESSAGE, expected);
    }

    @Test
    void emailConfirmationCreatesNoWebsiteNotification() {
        service.emailConfirmation(customer, TITLE, MESSAGE, LINK);

        verify(messagingService).sendEmailAsync(recipient, TITLE, MESSAGE, ABSOLUTE_LINK);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void externalMessagesWaitForTheTransactionToCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.notifyAllChannels(customer, TITLE, MESSAGE, LINK);
        service.emailConfirmation(customer, TITLE, MESSAGE, LINK);

        verify(notificationRepository).save(any(Notification.class));
        verifyNoInteractions(messagingService);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(messagingService).sendAsync(recipient, TITLE, MESSAGE, ABSOLUTE_LINK);
        verify(messagingService).sendEmailAsync(recipient, TITLE, MESSAGE, ABSOLUTE_LINK);
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        service.notifyAllChannels(customer, TITLE, MESSAGE, LINK);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(messagingService, never()).sendAsync(any(), any(), any(), any());
    }

    @Test
    void listReturnsTheLatestNotificationsWithTheUnreadCount() {
        Notification unread = notification(1L, customer, false);
        Notification read = notification(2L, customer, true);
        when(notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(unread, read));
        when(notificationRepository.countByUserIdAndReadFalse(3L)).thenReturn(1L);

        Map<String, Object> result = service.list(3L);

        assertThat(result).containsEntry("unread", 1L);
        assertThat(result.get("items")).asInstanceOf(list(NotificationDto.class))
                .extracting(NotificationDto::id, NotificationDto::read)
                .containsExactly(tuple(1L, false), tuple(2L, true));
    }

    @Test
    void markReadOnlyWorksOnTheUsersOwnNotifications() {
        Notification own = notification(1L, customer, false);
        Notification foreign = notification(2L, Fixtures.customer(99L), false);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(own));
        when(notificationRepository.findById(2L)).thenReturn(Optional.of(foreign));
        when(notificationRepository.findById(404L)).thenReturn(Optional.empty());

        service.markRead(3L, 1L);

        assertThat(own.isRead()).isTrue();
        assertThatThrownBy(() -> service.markRead(3L, 2L)).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.markRead(3L, 404L)).isInstanceOf(ApiException.class)
                .hasMessage("Notification not found");
        assertThat(foreign.isRead()).isFalse();
    }

    @Test
    void markAllReadClearsEveryUnreadNotification() {
        Notification first = notification(1L, customer, false);
        Notification second = notification(2L, customer, false);
        when(notificationRepository.findByUserIdAndReadFalse(3L)).thenReturn(List.of(first, second));

        service.markAllRead(3L);

        assertThat(List.of(first, second)).allMatch(Notification::isRead);
    }

    private Notification savedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Notification notification(long id, User user, boolean read) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUser(user);
        notification.setTitle(TITLE);
        notification.setMessage(MESSAGE);
        notification.setLink(LINK);
        notification.setRead(read);
        return notification;
    }
}
