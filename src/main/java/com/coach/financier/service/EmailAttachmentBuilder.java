package com.coach.financier.service;

import com.coach.financier.model.SuiviModels;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Transforme le BROUILLON d'email client (produit par l'IA) en PIÈCE JOINTE exploitable
 * par le conseiller. Formats POC supportés : {@code txt} (défaut), {@code html} et {@code eml}.
 * <p>
 * Les liens internes {@code [URL|nom|url]} sont convertis selon le format :
 * {@code nom : url} en texte, {@code <a href="url">nom</a>} en HTML/EML.
 * Ce service ne DÉCLENCHE AUCUN ENVOI : il ne fait que produire un fichier.
 */
@Service
public class EmailAttachmentBuilder {
    private static final Logger log = LoggerFactory.getLogger(EmailAttachmentBuilder.class);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String BASE_NAME = "email_client_prepare_";

    /**
     * @param format          {@code txt}, {@code html} ou {@code eml}
     * @param email           brouillon client (objet + corps)
     * @param customerAddress adresse du client (utilisée uniquement pour préremplir un .eml)
     */
    public SuiviModels.Attachment build(String format, SuiviModels.EmailContent email, String customerAddress) {
        String normalized = format == null ? "txt" : format.trim().toLowerCase(java.util.Locale.ROOT);
        String baseName = BASE_NAME + LocalDate.now().format(DATE_STAMP);
        String subject = email == null || email.subject() == null ? "" : email.subject();
        String body = email == null || email.body() == null ? "" : email.body();
        return switch (normalized) {
            case "html" -> new SuiviModels.Attachment(baseName + ".html",
                    htmlDocument(subject, body).getBytes(StandardCharsets.UTF_8), "text/html");
            case "eml" -> buildEml(baseName, subject, body, customerAddress);
            default -> new SuiviModels.Attachment(baseName + ".txt",
                    textDocument(subject, body).getBytes(StandardCharsets.UTF_8), "text/plain");
        };
    }

    /** Rendu texte brut : {@code Objet : ...} puis le corps, liens en {@code nom : url}. */
    static String textDocument(String subject, String body) {
        return "Objet : " + (subject == null ? "" : subject) + "\n\n" + UrlLinkRenderer.toText(body);
    }

    /** Rendu HTML autonome (pièce jointe .html) : contenu échappé, liens cliquables. */
    static String htmlDocument(String subject, String body) {
        String htmlBody = UrlLinkRenderer.toHtml(body);
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                </head>
                <body style="font-family: Arial, Helvetica, sans-serif; font-size: 14px; color: #1f2937;">
                <h2 style="font-size: 16px;">Objet : %s</h2>
                <div>%s</div>
                </body>
                </html>
                """.formatted(UrlLinkRenderer.escapeHtml(subject), UrlLinkRenderer.escapeHtml(subject), htmlBody);
    }

    /** Rendu .eml (message RFC 822) : le conseiller peut l'ouvrir et l'adapter avant envoi. */
    private SuiviModels.Attachment buildEml(String baseName, String subject, String body, String customerAddress) {
        try {
            Session session = Session.getInstance(new Properties());
            MimeMessage message = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setSubject(subject == null ? "" : subject);
            if (customerAddress != null && customerAddress.contains("@")) {
                helper.setTo(customerAddress.trim());
            }
            helper.setText(UrlLinkRenderer.toHtml(body), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return new SuiviModels.Attachment(baseName + ".eml", out.toByteArray(), "message/rfc822");
        } catch (Exception e) {
            log.warn("Génération .eml impossible, repli .txt : {}", e.getMessage());
            return new SuiviModels.Attachment(baseName + ".txt",
                    textDocument(subject, body).getBytes(StandardCharsets.UTF_8), "text/plain");
        }
    }
}
