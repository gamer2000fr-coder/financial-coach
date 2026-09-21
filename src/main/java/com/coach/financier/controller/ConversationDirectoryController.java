package com.coach.financier.controller;

import com.coach.financier.model.DirectoryModels;
import com.coach.financier.service.ConversationDirectoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * API de l'ANNUAIRE DES CONVERSATIONS (page « Centre d'appels ») : liste filtrable et triable des
 * conversations clôturées, puis détail d'une conversation (synthèse conseiller + transcript).
 * <p>
 * Chemin dédié ({@code /api/conversations/directory}) pour ne pas entrer en conflit avec
 * {@code GET /api/conversations/{sessionId}} (historique en mémoire d'une session).
 */
@RestController
@RequestMapping("/api/conversations/directory")
public class ConversationDirectoryController {
    private final ConversationDirectoryService directoryService;

    public ConversationDirectoryController(ConversationDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    /**
     * Liste des conversations clôturées.
     *
     * @param days     période en jours ({@code 0} = tout l'historique) ; défaut {@value ConversationDirectoryService#DEFAULT_DAYS}
     * @param category code de catégorie (CREDIT_CONSO, CREDIT_IMMO, EPARGNE, ASSURANCE, AUTRE)
     * @param q        recherche libre (client, titre, projet, produit)
     * @param sort     clé de tri : date | score | client | categorie | titre
     * @param order    asc | desc
     */
    @GetMapping
    public DirectoryModels.DirectoryList list(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        int period = days == null ? ConversationDirectoryService.DEFAULT_DAYS : days;
        return directoryService.list(period, category, query, sort, order, status);
    }

    /**
     * Corps de mise à jour du STATUT d'avancement d'un dossier : c'est le fil de travail du centre d'appels
     * (« Nouveau » à la clôture, puis Contacté, Qualifié, RDV planifié, Conclu / Sans suite / Clôturé).
     *
     * @param status  code du statut cible (obligatoire ; un code inconnu est refusé)
     * @param comment commentaire facultatif conservé avec le changement de statut
     */
    public record StatusRequest(String status, String comment) {
    }

    /** Change le statut d'un dossier et renvoie le détail à jour (historique inclus). */
    @PostMapping("/{sessionId}/status")
    public DirectoryModels.DirectoryDetail updateStatus(@PathVariable String sessionId,
                                                       @RequestBody(required = false) StatusRequest request) {
        StatusRequest body = request == null ? new StatusRequest(null, null) : request;
        return directoryService.updateStatus(sessionId, body.status(), body.comment())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Aucun dossier de suivi pour la session " + sessionId));
    }

    /**
     * Détail d'une conversation : synthèse envoyée au conseiller, sa pièce jointe (brouillon d'email client)
     * + transcript des échanges.
     */
    @GetMapping("/{sessionId}")
    public DirectoryModels.DirectoryDetail detail(@PathVariable String sessionId) {
        return directoryService.detail(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Aucun dossier de suivi pour la session " + sessionId));
    }

    /**
     * Requête invalide (statut inconnu, paramètre illisible) → <b>400</b> avec le contrat lu par l'IHM
     * ({@code {error, message}}), comme les autres API du projet.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return Map.of("error", "BAD_REQUEST", "message", e.getMessage() == null ? "Requête invalide" : e.getMessage());
    }
}
