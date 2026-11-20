package com.pgmanagement.service;

import com.pgmanagement.config.MessagingProperties;
import com.pgmanagement.service.MailService.Mail;
import com.pgmanagement.support.Mails;
import com.pgmanagement.support.TestProps;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    private static final String TO = "anil@example.com";
    private static final String SUBJECT = "Booking approved";
    private static final String PARAGRAPH = "Your request for Room 101 Bed A is approved.";
    private static final String LINK = "https://pg-stay.in/my-rents?tab=upcoming";
    private static final String DEMO = "customer@pgstay.in";

    @Mock
    private ObjectProvider<JavaMailSender> senderProvider;
    @Mock
    private JavaMailSender sender;

    private final DemoAccounts demoAccounts = new DemoAccounts();
    private MailService service;

    @BeforeEach
    void setUp() {
        service = service(TestProps.messaging());
    }

    @Test
    void mailIsNotConfiguredWithoutASenderBean() {
        when(senderProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.isConfigured()).isFalse();
        assertThat(service.send(Mail.of("Anil", TO, SUBJECT, PARAGRAPH))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void mailIsNotConfiguredWhenTheSmtpHostIsBlank(String host) {
        JavaMailSenderImpl withoutHost = new JavaMailSenderImpl();
        withoutHost.setHost(host);
        when(senderProvider.getIfAvailable()).thenReturn(withoutHost);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void mailIsConfiguredOnceAnSmtpHostIsSet() {
        JavaMailSenderImpl withHost = new JavaMailSenderImpl();
        withHost.setHost("smtp.example.com");
        when(senderProvider.getIfAvailable()).thenReturn(withHost);

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void composesABrandedMultipartMailFromTheSupportMailbox() throws Exception {
        MimeMessage message = send(Mail.of("Anil", TO, SUBJECT, PARAGRAPH).withAction("View details", LINK));

        assertThat(Mails.to(message)).isEqualTo(TO);
        assertThat(Mails.from(message).getAddress()).isEqualTo(TestProps.SUPPORT_EMAIL);
        assertThat(Mails.from(message).getPersonal()).isEqualTo("PG-STAY.IN");
        assertThat(Mails.replyTo(message)).isEqualTo(TestProps.SUPPORT_EMAIL);
        assertThat(message.getSubject()).isEqualTo("PG-STAY.IN - " + SUBJECT);
        assertThat(Mails.plainText(message)).startsWith("Hi Anil,").contains(PARAGRAPH, LINK, TestProps.SUPPORT_EMAIL);
        assertThat(Mails.html(message)).contains("Hi Anil,", PARAGRAPH, ">View details</a>", "href=\"" + LINK + "\"",
                "href=\"" + TestProps.SITE + "\"");
    }

    @Test
    void usesTheConfiguredFromAddressAndACustomReplyTo() {
        service = service(TestProps.messaging(null, "no-reply@pg-stay.in", null));

        MimeMessage message = send(Mail.of("Support team", TestProps.SUPPORT_EMAIL, "New enquiry #7", "Details")
                .withReplyTo(" priya@example.com "));

        assertThat(Mails.from(message).getAddress()).isEqualTo("no-reply@pg-stay.in");
        assertThat(Mails.replyTo(message)).isEqualTo("priya@example.com");
    }

    @Test
    void lineBreaksCannotSmuggleExtraHeadersThroughTheSubject() throws Exception {
        MimeMessage message = send(Mail.of("Anil", TO, "Hello\r\nBcc: attacker@evil.example\n", PARAGRAPH));

        assertThat(message.getSubject()).isEqualTo("PG-STAY.IN - Hello Bcc: attacker@evil.example");
        assertThat(message.getHeader("Bcc")).isNull();
    }

    @Test
    void hostileTextIsEscapedInTheHtmlPart() {
        MimeMessage message = send(Mail.of("<b>Anil</b>", TO, SUBJECT, "<script>alert(1)</script>"));

        assertThat(Mails.html(message)).doesNotContain("<script>", "<b>Anil</b>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void greetsRecipientsWithoutANameGenerically(String name) {
        MimeMessage message = send(Mail.of(name, TO, SUBJECT, PARAGRAPH));

        assertThat(Mails.plainText(message)).startsWith("Hi there,");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void mailWithoutARecipientIsNeverSent(String recipient) {
        assertThat(service.send(Mail.of("Anil", recipient, SUBJECT, PARAGRAPH))).isFalse();

        verifyNoInteractions(senderProvider);
    }

    @Test
    void demoAccountsNeverReceiveRealMail() {
        demoAccounts.register(List.of(DEMO));

        assertThat(service.send(Mail.of("Anil Varma", "Customer@PGstay.in", SUBJECT, PARAGRAPH))).isFalse();

        verifyNoInteractions(senderProvider);
    }

    @Test
    void aRejectedMessageIsReportedAsNotSent() {
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(Mails.blank());
        doThrow(new MailSendException("Connection refused")).when(sender).send(any(MimeMessage.class));

        assertThat(service.send(Mail.of("Anil", TO, SUBJECT, PARAGRAPH))).isFalse();
    }

    @Test
    void anUnparseableRecipientAddressIsReportedAsNotSent() {
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(Mails.blank());

        assertThat(service.send(Mail.of("Anil", "not an address <<", SUBJECT, PARAGRAPH))).isFalse();

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendAsyncDeliversTheSameMail() {
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(Mails.blank());

        service.sendAsync(Mail.of("Anil", TO, SUBJECT, PARAGRAPH));

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void mailBuildersKeepEarlierValues() {
        Mail mail = Mail.of("Anil", TO, SUBJECT, "one", "two").withReplyTo("reply@example.com")
                .withAction("Open", LINK);

        assertThat(mail.paragraphs()).containsExactly("one", "two");
        assertThat(mail.replyTo()).isEqualTo("reply@example.com");
        assertThat(mail.actionLabel()).isEqualTo("Open");
        assertThat(mail.actionUrl()).isEqualTo(LINK);
        assertThat(mail.withReplyTo("other@example.com").actionUrl()).isEqualTo(LINK);
    }

    private MailService service(MessagingProperties messaging) {
        return new MailService(senderProvider, messaging, TestProps.app(), demoAccounts);
    }

    /** Sends through a working mail server and returns the message that was handed to it. */
    private MimeMessage send(Mail mail) {
        when(senderProvider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(Mails.blank());

        assertThat(service.send(mail)).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }
}
