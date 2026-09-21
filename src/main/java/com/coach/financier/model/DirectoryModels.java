package com.coach.financier.model;

import java.util.List;
import java.util.Map;

/**
 * Modèles de l'ANNUAIRE DES CONVERSATIONS (page « Centre d'appels »).
 * <p>
 * La page s'appuie sur les DOSSIERS DE SUIVI persistés à chaque clôture (mêmes contenus que le mail
 * conseiller) : aucune donnée n'est recalculée ni inventée pour l'affichage.
 */
public final class DirectoryModels {
    private DirectoryModels() {}

    /**
     * Ligne du tableau : ce qu'un conseiller ou un téléconseiller doit voir d'un coup d'œil
     * (qui, quel thème, quel score de sens commercial, quel statut d'avancement, quand, comment ouvrir le dossier).
     */
    public record DirectoryRow(
            String sessionId,
            String customerId,
            String title,
            String mainProject,
            String category,
            String categoryLabel,
            Integer score,
            String priority,
            String priorityLabel,
            String scoreLabel,
            String closedAt,
            int productCount,
            String topProduct,
            boolean evaluated,
            String status,
            String statusLabel,
            String statusUpdatedAt,
            /** Nombre de MESSAGES laissés sur le dossier (journal du centre d'appels). */
            int noteCount
    ) {}

    /** Catégorie proposée dans le filtre, avec son nombre de dossiers sur la période. */
    public record DirectoryCategory(String code, String label, int count) {}

    /**
     * Changement de STATUT d'un dossier, conservé en historique : c'est la trace de l'avancement du dossier
     * (du « nouveau » à la conclusion), avec son commentaire facultatif.
     */
    public record DossierStatusEvent(
            String eventId,
            String sessionId,
            String status,
            String statusLabel,
            String previousStatus,
            String comment,
            String timestamp
    ) {}

    /** Réponse de la liste : lignes filtrées + valeurs de filtre disponibles + compteurs. */
    public record DirectoryList(
            List<DirectoryRow> rows,
            List<DirectoryCategory> categories,
            List<DirectoryCategory> statuses,
            Map<String, Integer> byPriority,
            int days,
            String sort,
            String order,
            int total
    ) {}

    /**
     * Détail d'une conversation pour la pop-in : synthèse identique à celle du mail conseiller, score
     * expliqué, statut et son historique, actions de suivi, transcript, et la PIÈCE JOINTE du mail
     * (brouillon d'email préparé pour le client, jamais envoyé automatiquement).
     */
    public record DirectoryDetail(
            DirectoryRow row,
            String advisorSubject,
            String advisorBody,
            String customerEmailSubject,
            String customerEmailBody,
            List<String> nextActions,
            List<AdvisorFeedbackModels.DossierProduct> products,
            List<String> scoreReasons,
            List<String> scoreCriteria,
            Boolean scoreProposedByAi,
            List<AdvisorFeedbackModels.DossierMessage> transcript,
            String contactPhone,
            String feedbackUrl,
            String conversationUrl,
            boolean evaluated,
            List<DossierStatusEvent> statusHistory
    ) {}
}
