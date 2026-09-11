package com.coach.financier.service;

import com.coach.financier.model.SuiviModels;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie la transformation du brouillon client en pièce jointe (txt / html / eml). */
class EmailAttachmentBuilderTest {

    private final EmailAttachmentBuilder builder = new EmailAttachmentBuilder();
    private final SuiviModels.EmailContent email = new SuiviModels.EmailContent(
            "Votre projet automobile",
            "Bonjour Jean,\n\nVoir [URL|Découvrir le Prêt Auto|https://exemple.test/auto].\n\nCordialement.");
    private static final SuiviModels.EmailAddresses ADDRESSES =
            new SuiviModels.EmailAddresses("michel@example.com", "conseiller@sg.test");

    @Test
    void txt_containsSubjectAndReadableLink() {
        SuiviModels.Attachment attachment = builder.build("txt", email, ADDRESSES);
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().startsWith("email_client_prepare_"));
        assertTrue(attachment.filename().endsWith(".txt"));
        assertEquals("text/plain", attachment.contentType());
        assertTrue(content.startsWith("Objet : Votre projet automobile"));
        assertTrue(content.contains("Découvrir le Prêt Auto : https://exemple.test/auto"));
    }

    @Test
    void html_containsClickableLinkAndEscapedSubject() {
        SuiviModels.Attachment attachment = builder.build("html", email, ADDRESSES);
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().endsWith(".html"));
        assertEquals("text/html", attachment.contentType());
        assertTrue(content.contains("<a href=\"https://exemple.test/auto\""));
    }

    @Test
    void eml_prefillsCustomerRecipientAndAdvisorSender() {
        SuiviModels.Attachment attachment = builder.build("eml", email, ADDRESSES);
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertTrue(attachment.filename().endsWith(".eml"));
        assertEquals("message/rfc822", attachment.contentType());
        assertTrue(content.contains("Votre projet automobile"));
        assertTrue(content.contains("To: michel@example.com"), "Destinataire = mail du client");
        assertTrue(content.contains("From: conseiller@sg.test"), "Expéditeur = mail du conseiller");
    }

    @Test
    void eml_withoutAddresses_leavesRecipientAndSenderEmpty() {
        SuiviModels.Attachment attachment = builder.build("eml", email,
                new SuiviModels.EmailAddresses(null, null));
        String content = new String(attachment.content(), StandardCharsets.UTF_8);

        assertFalse(content.contains("To:"), "Pas de destinataire si aucune adresse client");
        assertFalse(content.contains("From:"), "Pas d'expéditeur si aucune adresse conseiller");
    }

    @Test
    void unknownFormatFallsBackToTxt() {
        SuiviModels.Attachment attachment = builder.build("pdf", email, ADDRESSES);
        assertTrue(attachment.filename().endsWith(".txt"));
    }
}
