package com.coach.financier.controller;

import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.SuiviModels;
import com.coach.financier.service.ConversationClosureService;
import com.coach.financier.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API des conversations : relecture d'un historique et CLÔTURE (dossier de suivi conseiller).
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversationService;
    private final ConversationClosureService closureService;

    public ConversationController(ConversationService conversationService,
                                  ConversationClosureService closureService) {
        this.conversationService = conversationService;
        this.closureService = closureService;
    }

    /** Historique complet d'une session (messages client/coach), pour la page Logs. */
    @GetMapping("/{sessionId}")
    public Map<String, Object> history(@PathVariable String sessionId) {
        ConversationModels.Conversation conversation = conversationService.find(sessionId);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation inconnue : " + sessionId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("summary", conversation.summary());
        out.put("messages", conversation.transcript());
        return out;
    }

    /**
     * Clôture d'une conversation : génère le dossier de suivi (email conseiller + brouillon
     * client en pièce jointe) et envoie UNIQUEMENT l'email au conseiller.
     * <p>
     * Corps optionnel : {@code {advisorEmail, advisorName, attachmentFormat, send, provider}}.
     * {@code send=false} = dry-run (prépare sans envoyer).
     */
    @PostMapping("/{sessionId}/close")
    public SuiviModels.CloseConversationResponse close(
            @PathVariable String sessionId,
            @RequestBody(required = false) SuiviModels.CloseConversationRequest request) {
        return closureService.close(sessionId, request);
    }
}
