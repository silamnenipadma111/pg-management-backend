package com.pgmanagement.service;

import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds the branded HTML email and its plain-text alternative. Every dynamic value is HTML escaped. */
final class EmailTemplates {

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private EmailTemplates() {
    }

    /**
     * @param actionLabel optional button text; ignored when {@code actionUrl} is blank
     */
    static String html(String name, List<String> paragraphs, String actionLabel, String actionUrl,
                       String supportEmail, String siteUrl) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<p style=\"margin:0 0 14px;font-size:15px;line-height:1.65;color:#3d4239;\">")
                    .append(escape(paragraph).replace("\n", "<br>"))
                    .append("</p>");
        }
        if (hasText(actionUrl)) {
            body.append("<p style=\"margin:26px 0 8px;\"><a href=\"").append(escape(actionUrl))
                    .append("\" style=\"display:inline-block;background:#242921;color:#ffffff;text-decoration:none;")
                    .append("font-size:12px;font-weight:600;letter-spacing:2px;text-transform:uppercase;")
                    .append("padding:14px 26px;border-radius:10px;\">")
                    .append(escape(hasText(actionLabel) ? actionLabel : "Open PG-STAY.IN")).append("</a></p>")
                    .append("<p style=\"margin:0 0 14px;font-size:12px;line-height:1.6;color:#6e685f;\">")
                    .append("If the button does not work, copy this link into your browser:<br>")
                    .append(escape(actionUrl)).append("</p>");
        }
        return """
                <!DOCTYPE html>
                <html lang="en">
                <body style="margin:0;padding:0;background:#faf7f2;font-family:'Segoe UI',Arial,sans-serif;">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#faf7f2;padding:28px 12px;">
                <tr><td align="center">
                <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="max-width:600px;width:100%%;background:#ffffff;border:1px solid #eae3d8;border-radius:18px;">
                <tr><td style="padding:26px 34px 18px;border-bottom:1px solid #eae3d8;">
                <div style="font-family:Georgia,'Times New Roman',serif;font-size:24px;letter-spacing:3px;color:#242921;">PG-STAY<span style="color:#9e7b4f;">.IN</span></div>
                <div style="font-size:10px;letter-spacing:3px;text-transform:uppercase;color:#9e7b4f;margin-top:4px;">Trusted PG stays</div>
                </td></tr>
                <tr><td style="padding:28px 34px 22px;">
                <p style="margin:0 0 16px;font-family:Georgia,'Times New Roman',serif;font-size:22px;color:#242921;">Hi %s,</p>
                %s
                </td></tr>
                <tr><td style="padding:18px 34px 26px;border-top:1px solid #eae3d8;font-size:12px;line-height:1.6;color:#6e685f;">
                Need help? Write to <a href="mailto:%s" style="color:#7f603a;">%s</a> and our team will get back to you.<br>
                <a href="%s" style="color:#7f603a;">%s</a>
                </td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(escape(name), body, escape(supportEmail), escape(supportEmail), escape(siteUrl),
                escape(siteUrl));
    }

    static String text(String name, List<String> paragraphs, String actionUrl, String supportEmail) {
        StringBuilder text = new StringBuilder("Hi ").append(name).append(",\n\n");
        paragraphs.forEach(p -> text.append(p).append("\n\n"));
        if (hasText(actionUrl)) {
            text.append(actionUrl).append("\n\n");
        }
        return text.append("Need help? Write to ").append(supportEmail).append("\n\nThank you,\nPG-STAY.IN").toString();
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, CHARSET);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
