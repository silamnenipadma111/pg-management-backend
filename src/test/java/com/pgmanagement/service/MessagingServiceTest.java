package com.pgmanagement.service;

import com.pgmanagement.config.MessagingProperties;
import com.pgmanagement.service.MailService.Mail;
import com.pgmanagement.service.MessagingService.Recipient;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class MessagingServiceTest {

    private static final String TWILIO_URL = "https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json";
    private static final String SMS_FROM = "+15550001111";
    private static final String WHATSAPP_FROM = "whatsapp:+14155238886";
    private static final String PHONE_E164 = "+919876543210";
    private static final String SUBJECT = "Booking approved";
    private static final String TEXT = "Your request for Room 101 Bed A is approved.";
    private static final String LINK = "https://pg-stay.in/my-rents?tab=upcoming";
    private static final String SITE_URL = "https://pg-stay.in/my-rents";
    private static final String EMAIL = "EMAIL";
    private static final String FORM_FROM = "From";
    private static final String FORM_TO = "To";
    private static final String FORM_BODY = "Body";

    @Mock
    private MailService mailService;

    private final DemoAccounts demoAccounts = new DemoAccounts();
    private final Recipient recipient = new Recipient("Anil Varma", "anil@example.com", "98765 43210");
    private RestClient.Builder builder;
    private MockRestServiceServer twilio;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        twilio = MockRestServiceServer.bindTo(builder).build();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "9876543210, +919876543210",
            "98765 43210, +919876543210",
            "98765-43210, +919876543210",
            "919876543210, +919876543210",
            "+91 98765 43210, +919876543210",
            "+14155550100, +14155550100",
            "12345, NULL",
            "18001234567, NULL",
            "0987654321, NULL",
            "+0123456789, NULL",
            "not-a-number, NULL",
            "NULL, NULL"}, nullValues = "NULL")
    void phoneNumbersAreNormalisedToE164(String phone, String expected) {
        assertThat(MessagingService.e164(phone)).isEqualTo(expected);
    }

    @Test
    void onlyEmailIsUsedWhenTwilioIsNotConfigured() {
        when(mailService.send(any(Mail.class))).thenReturn(true);

        List<String> channels = service(TestProps.messaging()).send(recipient, SUBJECT, TEXT, LINK);

        assertThat(channels).containsExactly(EMAIL);
        Mail mail = sentMail();
        assertThat(mail.toName()).isEqualTo("Anil Varma");
        assertThat(mail.toEmail()).isEqualTo("anil@example.com");
        assertThat(mail.subject()).isEqualTo(SUBJECT);
        assertThat(mail.paragraphs()).containsExactly(TEXT);
        assertThat(mail.actionLabel()).isEqualTo("View details");
        assertThat(mail.actionUrl()).isEqualTo(LINK);
        twilio.verify();
    }

    @Test
    void reportsNoChannelWhenNothingCouldBeSent() {
        when(mailService.send(any(Mail.class))).thenReturn(false);

        assertThat(service(TestProps.messaging()).send(recipient, SUBJECT, TEXT, LINK)).isEmpty();
    }

    @Test
    void demoAccountsAreSkippedOnEveryChannel() {
        demoAccounts.register(List.of("anil@example.com"));

        List<String> channels = service(twilioProps(SMS_FROM, WHATSAPP_FROM)).send(recipient, SUBJECT, TEXT, LINK);

        assertThat(channels).isEmpty();
        verifyNoInteractions(mailService);
        twilio.verify();
    }

    @Test
    void sendsSmsAndWhatsAppThroughTwilioWithTheLinkAppended() {
        when(mailService.send(any(Mail.class))).thenReturn(true);
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString("AC123:secret".getBytes(StandardCharsets.UTF_8));
        twilio.expect(requestTo(TWILIO_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth))
                .andExpect(content().formDataContains(Map.of(FORM_FROM, SMS_FROM, FORM_TO, PHONE_E164,
                        FORM_BODY, SUBJECT + ": " + TEXT + "\n" + LINK)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        twilio.expect(requestTo(TWILIO_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(FORM_FROM, WHATSAPP_FROM,
                        FORM_TO, "whatsapp:" + PHONE_E164, FORM_BODY, "*" + SUBJECT + "*\n" + TEXT + "\n" + LINK)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<String> channels = service(twilioProps(SMS_FROM, "+14155238886")).send(recipient, SUBJECT, TEXT, LINK);

        assertThat(channels).containsExactly(EMAIL, "SMS", "WHATSAPP");
        twilio.verify();
    }

    @Test
    void aFailingProviderOnlyDropsItsOwnChannel() {
        when(mailService.send(any(Mail.class))).thenReturn(false);
        twilio.expect(requestTo(TWILIO_URL)).andRespond(withServerError());
        twilio.expect(requestTo(TWILIO_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<String> channels = service(twilioProps(SMS_FROM, WHATSAPP_FROM)).send(recipient, SUBJECT, TEXT, LINK);

        assertThat(channels).containsExactly("WHATSAPP");
        twilio.verify();
    }

    @Test
    void smsIsSkippedWhenOnlyWhatsAppIsConfigured() {
        when(mailService.send(any(Mail.class))).thenReturn(true);
        twilio.expect(requestTo(TWILIO_URL))
                .andExpect(content().formDataContains(Map.of(FORM_FROM, WHATSAPP_FROM)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<String> channels = service(twilioProps(" ", WHATSAPP_FROM)).send(recipient, SUBJECT, TEXT, LINK);

        assertThat(channels).containsExactly(EMAIL, "WHATSAPP");
        twilio.verify();
    }

    @Test
    void recipientsWithoutAUsableMobileNumberOnlyGetEmail() {
        when(mailService.send(any(Mail.class))).thenReturn(true);
        Recipient landline = new Recipient("Customer Care", "care@example.com", "18001234567");

        List<String> channels = service(twilioProps(SMS_FROM, WHATSAPP_FROM)).send(landline, SUBJECT, TEXT, LINK);

        assertThat(channels).containsExactly(EMAIL);
        twilio.verify();
    }

    @Test
    void textMessagesFallBackToTheSiteLinkAndAreCutAtTheProviderLimit() {
        when(mailService.send(any(Mail.class))).thenReturn(true);
        RequestMatcher truncatedBody = request -> {
            String form = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
            String body = UriComponentsBuilder.newInstance().query(form).build().getQueryParams().getFirst(FORM_BODY);
            assertThat(URLDecoder.decode(body, StandardCharsets.UTF_8)).hasSize(1500).startsWith(SUBJECT + ": xxx");
        };
        twilio.expect(requestTo(TWILIO_URL)).andExpect(truncatedBody)
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        MessagingProperties props = TestProps.messaging(SITE_URL, null,
                new MessagingProperties.Twilio("AC123", "secret", SMS_FROM, null));

        List<String> channels = service(props).send(recipient, SUBJECT, "x".repeat(2000), null);

        assertThat(channels).containsExactly(EMAIL, "SMS");
        assertThat(sentMail().actionUrl()).as("the email button needs a real link").isNull();
        twilio.verify();
    }

    @Test
    void withoutAnyLinkTheTextMessageIsJustTheText() {
        when(mailService.send(any(Mail.class))).thenReturn(false);
        twilio.expect(requestTo(TWILIO_URL))
                .andExpect(content().formDataContains(Map.of(FORM_BODY, SUBJECT + ": " + TEXT)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        MessagingProperties props = TestProps.messaging(" ", null,
                new MessagingProperties.Twilio("AC123", "secret", SMS_FROM, ""));

        List<String> channels = service(props).send(recipient, SUBJECT, TEXT, " ");

        assertThat(channels).containsExactly("SMS");
        twilio.verify();
    }

    @Test
    void sendAsyncUsesEveryChannelAndSendEmailAsyncOnlyEmail() {
        when(mailService.send(any(Mail.class))).thenReturn(true);
        twilio.expect(requestTo(TWILIO_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        MessagingService service = service(twilioProps(SMS_FROM, null));

        service.sendAsync(recipient, SUBJECT, TEXT, LINK);
        twilio.verify();
        twilio.reset();
        service.sendEmailAsync(recipient, "Booking request sent", TEXT, LINK);

        ArgumentCaptor<Mail> mails = ArgumentCaptor.forClass(Mail.class);
        verify(mailService, times(2)).send(mails.capture());
        assertThat(mails.getAllValues()).extracting(Mail::subject).containsExactly(SUBJECT, "Booking request sent");
        assertThat(mails.getAllValues()).extracting(Mail::actionUrl).containsOnly(LINK);
        twilio.verify();
    }

    private MessagingService service(MessagingProperties props) {
        return new MessagingService(props, mailService, demoAccounts, builder);
    }

    private static MessagingProperties twilioProps(String smsFrom, String whatsappFrom) {
        return TestProps.messaging(SITE_URL, null, new MessagingProperties.Twilio("AC123", "secret", smsFrom, whatsappFrom));
    }

    private Mail sentMail() {
        ArgumentCaptor<Mail> captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailService).send(captor.capture());
        return captor.getValue();
    }
}
