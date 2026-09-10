package com.coach.financier.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Modèles de l'API d'envoi de mail. */
public final class MailModels {
    private MailModels() {}

    /**
     * Demande d'envoi d'un mail.
     *
     * @param to      destinataire(s) — une ou plusieurs adresses séparées par des virgules
     * @param subject objet du mail
     * @param body    contenu (texte brut, ou HTML si {@code html=true})
     * @param html    true pour envoyer {@code body} au format HTML
     */
    public record MailRequest(
            @NotBlank @Email String to,
            @NotBlank String subject,
            String body,
            Boolean html
    ) {}

    /** Réponse d'un envoi réussi. */
    public record MailResponse(String status, String to, String subject) {}
}
