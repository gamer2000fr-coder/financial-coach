package com.coach.financier.service;

import com.coach.financier.model.ConversationModels;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {
    private final ConcurrentHashMap<String, ConversationModels.Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationModels.Conversation getOrCreate(String sessionId) {
        return conversations.computeIfAbsent(sessionId, ConversationModels.Conversation::new);
    }

    /** Conversation existante pour une session, ou {@code null} si aucune. */
    public ConversationModels.Conversation find(String sessionId) {
        return conversations.get(sessionId);
    }
}
