package com.coach.financier.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historique de conversation transmis à l'IA à chaque appel.
 * <p>
 * Exigence : le Coach doit savoir TOUT ce qui a déjà été échangé avec le client — par défaut, aucune
 * troncature ({@code app.chat.history-limit = 0}). Le bornage reste possible par configuration (coût,
 * taille de requête), et le transcript complet n'est jamais tronqué (il alimente le dossier de suivi).
 */
class ConversationModelsTest {

    @Test
    void allMessagesAreKeptByDefaultForTheCoach() {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s1");

        for (int i = 1; i <= 30; i++) {
            conversation.addMessage(i % 2 == 1 ? "user" : "assistant", "message " + i);
        }

        assertEquals(30, conversation.messages().size(),
                "par défaut, l'IA reçoit TOUT l'historique (aucune fenêtre glissante)");
        assertEquals(30, conversation.transcript().size());
        assertEquals("message 1", conversation.messages().get(0).content(),
                "le premier échange reste transmis à l'IA");
        assertEquals(0, conversation.historyLimit());
    }

    @Test
    void anExplicitLimitStillTrimsOnlyTheCoachWindow() {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s1");
        conversation.setHistoryLimit(20);

        for (int i = 1; i <= 30; i++) {
            conversation.addMessage("user", "message " + i);
        }

        assertEquals(20, conversation.messages().size(), "la fenêtre configurée est respectée");
        assertEquals("message 11", conversation.messages().get(0).content(),
                "ce sont les messages les plus RÉCENTS qui sont conservés");
        assertEquals(30, conversation.transcript().size(),
                "le transcript complet reste intact (dossier de suivi)");
    }

    /** Une limite absurde (0 ou négative) ne tronque rien : sécurité contre une mauvaise configuration. */
    @Test
    void aNonPositiveLimitMeansNoTrimming() {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s1");
        conversation.setHistoryLimit(-5);

        conversation.addMessage("user", "bonjour");
        conversation.addMessage("assistant", "bonjour, comment puis-je vous aider ?");

        assertEquals(2, conversation.messages().size());
        assertTrue(conversation.historyLimit() >= 0);
    }
}
