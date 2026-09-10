package com.coach.financier.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Service d'envoi de mails (SMTP, Gmail par défaut).
 * <p>
 * Configuration dans {@code application.yml} :
 * <ul>
 *   <li>{@code spring.mail.*} : hôte, port, identifiants (MAIL_USERNAME / CLE_GOOGLE_COACH_FINANCIER) ;</li>
 *   <li>{@code app.mail.from} / {@code app.mail.from-name} : expéditeur affiché ;</li>
 *   <li>{@code app.mail.enabled} : activer/désactiver l'envoi.</li>
 * </ul>
 * Pour Gmail, {@code CLE_GOOGLE_COACH_FINANCIER} doit être un <b>mot de passe d'application</b>
 * (et non le mot de passe du compte) : https://myaccount.google.com/apppasswords
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String fromName;
    private final String password;
    private final String host;
    private final int port;

    public MailService(JavaMailSender mailSender,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${spring.mail.port:0}") int port,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.from-name:Coach financier}") String fromName,
                       @Value("${app.mail.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.password = password;
        this.host = host;
        this.port = port;
        this.from = (from == null || from.isBlank()) ? username : from;
        this.fromName = fromName;
        this.enabled = enabled;
    }

    /** L'envoi est-il activé par configuration ? */
    public boolean isEnabled() {
        return enabled;
    }

    /** L'adresse d'expédition configurée (peut être vide). */
    public String getFrom() {
        return from;
    }

    /** Le service peut-il envoyer (activé + expéditeur + mot de passe présents) ? */
    public boolean isAvailable() {
        return unavailabilityReason().isEmpty();
    }

    /**
     * Origine PRÉCISE de l'indisponibilité du service mail (chaîne vide s'il peut envoyer).
     * Utilisée pour expliquer, dans les logs, pourquoi un dossier n'a pas pu être envoyé.
     */
    public String unavailabilityReason() {
        if (!enabled) {
            return "envoi désactivé (app.mail.enabled=false / MAIL_ENABLED)";
        }
        if (from == null || from.isBlank()) {
            return "expéditeur non configuré (spring.mail.username / MAIL_USERNAME, ou app.mail.from)";
        }
        if (password == null || password.isBlank()) {
            return "mot de passe d'application non configuré (spring.mail.password / MAIL_PASSWORD)";
        }
        return "";
    }

    /** Cible SMTP utilisée, pour diagnostiquer une erreur d'envoi (ex. « smtp.gmail.com:587 »). */
    public String describeTarget() {
        String h = (host == null || host.isBlank()) ? "hôte SMTP non configuré" : host;
        return port > 0 ? h + ":" + port : h;
    }

    /** Envoie un mail texte. */
    public void sendText(String to, String subject, String body) {
        send(to, subject, body, false);
    }

    /** Envoie un mail HTML. */
    public void sendHtml(String to, String subject, String html) {
        send(to, subject, html, true);
    }

    /**
     * Envoie un mail.
     *
     * @param to      destinataire(s) — plusieurs adresses séparées par des virgules
     * @param subject objet
     * @param body    contenu (texte ou HTML)
     * @param html    true si {@code body} est du HTML
     */
    public void send(String to, String subject, String body, boolean html) {
        sendWithAttachments(to, subject, body, html, null);
    }

    /**
     * Envoie un mail avec une ou plusieurs pièces jointes.
     *
     * @param to           destinataire(s) — plusieurs adresses séparées par des virgules
     * @param subject      objet
     * @param body         contenu (texte ou HTML)
     * @param html         true si {@code body} est du HTML
     * @param attachments  pièces jointes (peut être {@code null} ou vide)
     */
    public void sendWithAttachments(String to, String subject, String body, boolean html,
                                    java.util.List<com.coach.financier.model.SuiviModels.Attachment> attachments) {
        ensureConfigured(to);
        boolean multipart = attachments != null && !attachments.isEmpty();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(splitRecipients(to));
            helper.setSubject(subject);
            helper.setText(body == null ? "" : body, html);
            if (multipart) {
                for (com.coach.financier.model.SuiviModels.Attachment attachment : attachments) {
                    helper.addAttachment(attachment.filename(),
                            new org.springframework.core.io.ByteArrayResource(attachment.content()),
                            attachment.contentType());
                }
            }
            mailSender.send(message);
            log.info("Mail envoyé à {} (sujet : {}, pièces jointes : {})", to, subject,
                    multipart ? attachments.size() : 0);
        } catch (MailException | jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("Échec de l'envoi du mail à " + to + " via " + describeTarget()
                    + " : " + rootCause(e), e);
        }
    }

    /** Cause racine d'un échec SMTP, sous forme « TypeException — message » (diagnostic des logs). */
    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().replaceAll("\\s+", " ").trim();
        return cause.getClass().getSimpleName() + (message.isEmpty() ? "" : " — " + message);
    }

    private void ensureConfigured(String to) {
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("Destinataire manquant.");
        }
        if (!enabled) {
            throw new IllegalStateException("Service mail désactivé (app.mail.enabled=false).");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("Adresse d'expédition non configurée (MAIL_USERNAME ou app.mail.from).");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Mot de passe mail non configuré (CLE_GOOGLE_COACH_FINANCIER — mot de passe d'application Gmail).");
        }
    }

    private static String[] splitRecipients(String to) {
        String[] parts = to.split("\\s*,\\s*");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
