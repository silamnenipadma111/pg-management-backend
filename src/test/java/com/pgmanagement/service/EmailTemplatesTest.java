package com.pgmanagement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesTest {

    private static final String SUPPORT = "support@pg-stay.in";
    private static final String SITE = "https://pg-stay.in";
    private static final String LINK = "https://pg-stay.in/verify-email?token=abc123";
    private static final String SCRIPT = "<script>alert('x')</script>";

    @Test
    void htmlContainsGreetingParagraphsButtonAndSupportFooter() {
        String html = EmailTemplates.html("Anil", List.of("Your account has been created.", "Welcome aboard."),
                "Verify email", LINK, SUPPORT, SITE);

        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("Hi Anil,", "Your account has been created.", "Welcome aboard.", ">Verify email</a>",
                        "href=\"" + LINK + "\"", "mailto:" + SUPPORT, "href=\"" + SITE + "\"", "PG-STAY");
    }

    @Test
    void everyDynamicValueIsHtmlEscaped() {
        String html = EmailTemplates.html(SCRIPT, List.of("Note: " + SCRIPT + " & <b>bold</b>"),
                "<img src=x onerror=alert(1)>", "https://pg-stay.in/?a=\"><script>", SUPPORT + "<i>", SITE + "\"x");

        assertThat(html)
                .doesNotContain("<script>", "<b>bold</b>", "<img src=x", "\"><script>", "<i>")
                .contains("Hi &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;,", "&amp; &lt;b&gt;bold&lt;/b&gt;",
                        "&lt;img src=x onerror=alert(1)&gt;", "?a=&quot;&gt;&lt;script&gt;");
    }

    @Test
    void lineBreaksInsideAParagraphAreKept() {
        String html = EmailTemplates.html("Team", List.of("Name: Anil\nPhone: 9876543210"), null, null, SUPPORT, SITE);

        assertThat(html).contains("Name: Anil<br>Phone: 9876543210");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void theButtonIsRenderedOnlyWhenThereIsALink(String noLink) {
        String html = EmailTemplates.html("Anil", List.of("Hello"), "Verify email", noLink, SUPPORT, SITE);

        assertThat(html).doesNotContain("Verify email", "copy this link");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void aLinkWithoutLabelGetsADefaultButtonText(String noLabel) {
        String html = EmailTemplates.html("Anil", List.of("Hello"), noLabel, LINK, SUPPORT, SITE);

        assertThat(html).contains(">Open PG-STAY.IN</a>", "copy this link");
    }

    @Test
    void percentSignsInTheContentAreNotTreatedAsFormatSpecifiers() {
        String html = EmailTemplates.html("Anil", List.of("Occupancy is 100% and %s %d stay literal."), null, null,
                SUPPORT, SITE);

        assertThat(html).contains("Occupancy is 100% and %s %d stay literal.", "width=\"100%\"");
    }

    @Test
    void plainTextVersionCarriesTheSameInformation() {
        String text = EmailTemplates.text("Anil", List.of("First paragraph.", "Second paragraph."), LINK, SUPPORT);

        assertThat(text).isEqualTo("Hi Anil,\n\nFirst paragraph.\n\nSecond paragraph.\n\n" + LINK
                + "\n\nNeed help? Write to " + SUPPORT + "\n\nThank you,\nPG-STAY.IN");
    }

    @Test
    void plainTextVersionOmitsTheLinkWhenThereIsNone() {
        String text = EmailTemplates.text("Anil", List.of("Only paragraph."), null, SUPPORT);

        assertThat(text).isEqualTo("Hi Anil,\n\nOnly paragraph.\n\nNeed help? Write to " + SUPPORT
                + "\n\nThank you,\nPG-STAY.IN");
    }

    @Test
    void missingValuesRenderAsEmptyText() {
        String html = EmailTemplates.html(null, List.of(), null, null, null, null);

        assertThat(html).contains("Hi ,").doesNotContain("null");
    }
}
