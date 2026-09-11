package com.coach.financier.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie que l'agent de suivi est bien proposé (et éditable) sur la page « Agents ». */
class AgentPromptStoreTest {

    private final AgentPromptStore store = new AgentPromptStore();

    @Test
    void listsTheSuiviAgent() {
        Map<String, String> suivi = store.entries().stream()
                .filter(entry -> AgentPromptStore.SUIVI_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(suivi, "L'agent de suivi doit apparaître dans la liste déroulante");
        assertEquals("Agent de suivi (fin de conversation)", suivi.get("libelle"));
        assertEquals("suivi.txt", suivi.get("file"));
    }

    @Test
    void suiviAgentIsNotDeclaredAsACoachAgentInAgentsJson() {
        // Il ne doit PAS être déclaré dans agents.json, sinon il deviendrait sélectionnable comme
        // agent de coach (il n'intervient qu'à la clôture, via AgentFiles.suiviSystemPrompt()).
        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.SUIVI_KEY.equalsIgnoreCase(agent.getTheme())),
                "suivi ne doit pas être un thème d'agent de coach (agents.json)");
    }

    @Test
    void readsTheSuiviPromptContent() {
        String content = store.read(AgentPromptStore.SUIVI_KEY);

        assertNotNull(content);
        assertTrue(content.contains("BROUILLON"), "Le prompt de suivi doit décrire le brouillon d'email client");
        assertTrue(content.contains("conseiller"));
    }

    @Test
    void listsTheMarketingAgentAndItsPromptIsEditable() {
        Map<String, String> marketing = store.entries().stream()
                .filter(entry -> AgentPromptStore.MARKETING_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(marketing, "L'agent analyste marketing doit apparaître dans la liste déroulante");
        assertEquals("Agent analyste marketing", marketing.get("libelle"));
        assertEquals("marketing.txt", marketing.get("file"));

        // La clé doit résoudre le fichier éditable ./agent/marketing.txt
        String fileName = store.fileNameOf(AgentPromptStore.MARKETING_KEY);
        assertEquals("marketing.txt", fileName);

        String content = store.read(AgentPromptStore.MARKETING_KEY);
        assertNotNull(content);
        assertTrue(content.length() > 100, "Le prompt marketing doit être un vrai prompt (pas le repli vide)");

        // Il ne doit PAS être déclaré dans agents.json (il n'est jamais un agent de coach).
        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.MARKETING_KEY.equalsIgnoreCase(agent.getTheme())),
                "marketing ne doit pas être un thème d'agent de coach (agents.json)");
    }
}
