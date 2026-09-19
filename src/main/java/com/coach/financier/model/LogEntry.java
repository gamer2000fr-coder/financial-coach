package com.coach.financier.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * Trace d'un appel IA : parole du client, données envoyées (descriptions),
 * taille de l'historique de conversation, caractères envoyés, statut, agent utilisé,
 * fichiers demandés en retour (stems), et prompt / réponse envoyés par l'IA (sans le contenu
 * des données jointes) — prompt et réponse sont exclus du JSON de la liste ({@link JsonIgnore})
 * et récupérés à la demande via {@code GET /api/logs/{id}/prompt} et {@code GET /api/logs/{id}/answer}.
 * <p>
 * {@code mailStatus} n'est renseigné QUE pour la trace de clôture de conversation : il indique si le
 * mail de notification au conseiller a bien été ENVOYÉ ({@code SENT}) ou non ({@code PREPARED},
 * {@code MAIL_UNAVAILABLE}, {@code SEND_FAILED}, {@code AI_FAILED}) — vide pour les autres traces.
 */
public record LogEntry(
        long id,
        String timestamp,
        String sessionId,
        String clientMessage,
        List<String> dataSent,
        int historyCount,
        long charCount,
        String status,
        String agent,
        List<String> requestedData,
        @JsonIgnore String prompt,
        String debug,
        @JsonIgnore String answer,
        String mailStatus
) {}
