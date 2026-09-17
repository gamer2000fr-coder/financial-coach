package com.coach.financier.service;

import com.coach.financier.model.ConversationModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {
    private final ConcurrentHashMap<String, ConversationModels.Conversation> conversations = new ConcurrentHashMap<>();

    /**
     * Nombre de messages d'historique transmis au coach ({@code app.chat.history-limit}) :
     * {@code 0} = TOUT l'historique (défaut), une valeur &gt; 0 borne volontairement le contexte.
     */
    private final int historyLimit;

    public ConversationService(@Value("${app.chat.history-limit:0}") int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public ConversationModels.Conversation getOrCreate(String sessionId) {
        ConversationModels.Conversation conversation =
                conversations.computeIfAbsent(sessionId, ConversationModels.Conversation::new);
        conversation.setHistoryLimit(historyLimit);
        return conversation;
    }

    /** Conversation existante pour une session, ou {@code null} si aucune. */
    public ConversationModels.Conversation find(String sessionId) {
        return conversations.get(sessionId);
    }
}
