package com.coach.financier.controller;

import com.coach.financier.model.ConversationModels;
import com.coach.financier.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expose l'historique d'une conversation (messages client/coach) pour une session,
 * utilisé par la page Logs (« Historique »). Les messages assistant n'étant pas
 * taggés par agent, seul le rôle est renvoyé.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** Historique d'une session : {sessionId, summary, messages:[{role, content, timestamp}]}. */
    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String sessionId) {
        ConversationModels.Conversation conversation = conversationService.find(sessionId);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation inconnue : " + sessionId);
        }
        List<Map<String, Object>> messages = conversation.messages().stream().map(message -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());
            entry.put("content", message.content());
            entry.put("timestamp", message.timestamp() == null ? null : message.timestamp().toString());
            return entry;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", conversation.sessionId());
        out.put("summary", conversation.summary());
        out.put("messages", messages);
        return out;
    }
}
