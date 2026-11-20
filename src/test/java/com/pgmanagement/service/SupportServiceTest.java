package com.pgmanagement.service;

import com.pgmanagement.dto.CommonDtos.AssistedPgRequest;
import com.pgmanagement.dto.CommonDtos.AssistedPgResponse;
import com.pgmanagement.dto.CommonDtos.SupportRequestCreate;
import com.pgmanagement.dto.CommonDtos.SupportRequestDto;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.dto.PgDtos.PgRequest;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.SupportRequest;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.SupportRequestRepository;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.service.MailService.Mail;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.TestProps;
import com.pgmanagement.validation.StrongPasswordValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportServiceTest {

    private static final String OWNER_EMAIL = "new-owner@example.com";
    private static final String PG_NAME = "Call Center PG";
    private static final String TITLE = "PG registered by customer care";

    @Mock
    private SupportRequestRepository supportRequestRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthService authService;
    @Mock
    private PgService pgService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private MailService mailService;

    private SupportService service;

    @BeforeEach
    void setUp() {
        service = new SupportService(supportRequestRepository, userRepository, authService, pgService,
                notificationService, mailService, TestProps.app());
    }

    @Test
    void anEnquiryIsForwardedToTheSupportMailboxAndAcknowledged() {
        savingAssignsId(7L);
        SupportRequestCreate request = new SupportRequestCreate(" Priya ", " 9123456781 ", " priya@example.com ",
                Enums.SupportRequestType.REGISTER_PG, "Please list my PG");

        SupportRequestDto saved = service.create(request);

        assertThat(saved.name()).isEqualTo("Priya");
        assertThat(saved.phone()).isEqualTo("9123456781");
        assertThat(saved.email()).isEqualTo("priya@example.com");
        assertThat(saved.status()).isEqualTo(Enums.SupportRequestStatus.OPEN);
        ArgumentCaptor<Mail> mails = ArgumentCaptor.forClass(Mail.class);
        verify(mailService, times(2)).sendAsync(mails.capture());
        Mail forwarded = mails.getAllValues().get(0);
        Mail acknowledgement = mails.getAllValues().get(1);
        assertThat(forwarded.toEmail()).isEqualTo(TestProps.SUPPORT_EMAIL);
        assertThat(forwarded.replyTo()).isEqualTo("priya@example.com");
        assertThat(forwarded.subject()).isEqualTo("New enquiry #7 - Register my PG");
        assertThat(String.join(" ", forwarded.paragraphs())).contains("Priya", "9123456781", "Please list my PG");
        assertThat(forwarded.actionUrl()).isEqualTo(TestProps.SITE + "/support");
        assertThat(acknowledgement.toEmail()).isEqualTo("priya@example.com");
        assertThat(acknowledgement.subject()).contains("#7");
    }

    @Test
    void anEnquiryWithoutEmailIsOnlyForwarded() {
        savingAssignsId(8L);
        SupportRequestCreate request = new SupportRequestCreate("Ravi", "9876543210", " ",
                Enums.SupportRequestType.CALLBACK, null);

        assertThat(service.create(request).email()).isNull();

        ArgumentCaptor<Mail> mail = ArgumentCaptor.forClass(Mail.class);
        verify(mailService).sendAsync(mail.capture());
        assertThat(mail.getValue().replyTo()).isNull();
        assertThat(mail.getValue().subject()).endsWith("Call back");
    }

    @Test
    void listsAndResolvesRequests() {
        SupportRequest open = new SupportRequest();
        open.setId(1L);
        open.setRequestType(Enums.SupportRequestType.GENERAL);
        when(supportRequestRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(open));
        when(supportRequestRepository.findById(1L)).thenReturn(Optional.of(open));
        when(supportRequestRepository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.list()).hasSize(1);
        assertThat(service.resolve(1L).status()).isEqualTo(Enums.SupportRequestStatus.RESOLVED);
        assertThatThrownBy(() -> service.resolve(2L)).isInstanceOf(ApiException.class)
                .hasMessage("Support request not found");
    }

    @Test
    void registeringForANewOwnerCreatesTheAccountAndMailsASetupLink() {
        User owner = Fixtures.user(5L, Enums.UserRole.OWNER, "Phone Owner", OWNER_EMAIL);
        SupportRequest linked = new SupportRequest();
        when(userRepository.findByEmailIgnoreCase(OWNER_EMAIL)).thenReturn(Optional.empty());
        when(authService.createUser(eq("Phone Owner"), eq(OWNER_EMAIL), eq("9000000001"), anyString(),
                eq(Enums.UserRole.OWNER), eq(null))).thenReturn(owner);
        when(supportRequestRepository.findById(3L)).thenReturn(Optional.of(linked));
        PgRequest pgRequest = mock(PgRequest.class);
        registeredPg(owner, pgRequest);

        AssistedPgResponse response = service.registerForOwner(
                new AssistedPgRequest("Phone Owner", OWNER_EMAIL, "9000000001", 3L, pgRequest));

        assertThat(response.ownerAccountCreated()).isTrue();
        assertThat(response.ownerEmail()).isEqualTo(OWNER_EMAIL);
        assertThat(StrongPasswordValidator.isStrong(response.temporaryPassword())).isTrue();
        assertThat(linked.getStatus()).isEqualTo(Enums.SupportRequestStatus.RESOLVED);
        verify(notificationService).notify(eq(owner), eq(TITLE), anyString(), eq("/owner/dashboard"));
        verify(authService).sendAccountSetupMail(owner, PG_NAME);
    }

    @Test
    void registeringForAnExistingOwnerNotifiesOnEveryChannel() {
        User owner = Fixtures.user(5L, Enums.UserRole.OWNER, "Ravi Kumar", OWNER_EMAIL);
        when(userRepository.findByEmailIgnoreCase(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        PgRequest pgRequest = mock(PgRequest.class);
        registeredPg(owner, pgRequest);

        AssistedPgResponse response = service.registerForOwner(
                new AssistedPgRequest("Ravi Kumar", OWNER_EMAIL, "9000000001", null, pgRequest));

        assertThat(response.ownerAccountCreated()).isFalse();
        assertThat(response.temporaryPassword()).isNull();
        verify(notificationService).notifyAllChannels(eq(owner), eq(TITLE), anyString(), eq("/owner/dashboard"));
        verify(authService, never()).sendAccountSetupMail(any(), anyString());
    }

    @Test
    void anEmailOfANonOwnerAccountCannotBeUsed() {
        User customer = Fixtures.user(5L, Enums.UserRole.CUSTOMER, "Anil", OWNER_EMAIL);
        when(userRepository.findByEmailIgnoreCase(OWNER_EMAIL)).thenReturn(Optional.of(customer));
        AssistedPgRequest request = new AssistedPgRequest("Anil", OWNER_EMAIL, "9000000001", null, mock(PgRequest.class));

        assertThatThrownBy(() -> service.registerForOwner(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining("belongs to a customer account")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @RepeatedTest(50)
    void temporaryPasswordsAlwaysSatisfyThePasswordPolicy() {
        String password = SupportService.randomPassword();

        assertThat(password).hasSize(13).startsWith("Pg@");
        assertThat(StrongPasswordValidator.isStrong(password)).isTrue();
    }

    private void savingAssignsId(long id) {
        when(supportRequestRepository.save(any(SupportRequest.class))).thenAnswer(invocation -> {
            SupportRequest request = invocation.getArgument(0);
            request.setId(id);
            return request;
        });
    }

    private void registeredPg(User owner, PgRequest pgRequest) {
        PgDetailDto pg = mock(PgDetailDto.class);
        when(pg.name()).thenReturn(PG_NAME);
        when(pgService.create(owner, pgRequest, Enums.RegisteredVia.CUSTOMER_CARE)).thenReturn(pg);
    }
}
