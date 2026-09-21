package com.coach.financier.controller;

import com.coach.financier.model.DirectoryModels;
import com.coach.financier.service.ConversationDirectoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        int period = days == null ? ConversationDirectoryService.DEFAULT_DAYS : days;
        return directoryService.list(period, category, query, sort, order);
    }

    /** Détail d'une conversation : synthèse envoyée au conseiller (sans le brouillon client) + transcript. */
    @GetMapping("/{sessionId}")
    public DirectoryModels.DirectoryDetail detail(@PathVariable String sessionId) {
        return directoryService.detail(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Aucun dossier de suivi pour la session " + sessionId));
    }
}
