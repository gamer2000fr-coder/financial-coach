package com.coach.financier.service;

import com.coach.financier.model.SuiviModels;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie la transformation du brouillon client en pièce jointe (txt / html / eml). */
class EmailAttachmentBuilderTest {

    private final EmailAttachmentBuilder builder = new EmailAttachmentBuilder();
    private final SuiviModels.EmailContent email = new SuiviModels.EmailContent(
            "Votre projet automobile",
            "Bonjour Jean,\n\nVoir [URL|Découvrir le Prêt Auto|https://exemple.test/auto].\n\nCordialement.");

    @Test
    void txt_containsSubjectAndReadableLink() {
        SuiviModels.Attachment attachment = builder.build("txt", email, "client@example.com");
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().startsWith("email_client_prepare_"));
        assertTrue(attachment.filename().endsWith(".txt"));
        assertEquals("text/plain", attachment.contentType());
        assertTrue(content.startsWith("Objet : Votre projet automobile"));
        assertTrue(content.contains("Découvrir le Prêt Auto : https://exemple.test/auto"));
    }

    @Test
    void html_containsClickableLinkAndEscapedSubject() {
        SuiviModels.Attachment attachment = builder.build("html", email, null);
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().endsWith(".html"));
        assertEquals("text/html", attachment.contentType());
        assertTrue(content.contains("<a href=\"https://exemple.test/auto\""));
    }

    @Test
    void eml_isRfc822WithPrefilledRecipient() {
        SuiviModels.Attachment attachment = builder.build("eml", email, "jean@example.com");
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().endsWith(".eml"));
        assertEquals("message/rfc822", attachment.contentType());
        assertTrue(content.contains("jean@example.com"));
        assertTrue(content.contains("Votre projet automobile"));
    }

    @Test
    void unknownFormatFallsBackToTxt() {
        SuiviModels.Attachment attachment = builder.build("pdf", email, null);
        assertTrue(attachment.filename().endsWith(".txt"));
    }
}
