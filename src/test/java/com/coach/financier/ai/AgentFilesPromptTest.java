package com.coach.financier.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition du prompt système : gabarit {@code generic.txt} + agent principal + agent spécialisé.
 * Vérifie aussi que les marqueurs de zone éditable (atelier d'optimisation des prompts) ne sont
 * JAMAIS envoyés au LLM — le prompt de production doit rester identique à l'existant.
 */
class AgentFilesPromptTest {

    @Test
    void compositionIncludesTemplatePrincipalAndSpecializedPrompt() {
        String prompt = AgentFiles.composeSystemPrompt("GABARIT\n[agent_principal]\n[agent]\nFIN",
                "PRINCIPAL", "SPECIALISE");

        assertTrue(prompt.contains("GABARIT"));
        assertTrue(prompt.contains("PRINCIPAL"));
        assertTrue(prompt.contains("SPECIALISE"));
        assertTrue(prompt.contains("FIN"));
        assertTrue(prompt.indexOf("PRINCIPAL") < prompt.indexOf("SPECIALISE"),
                "l'agent principal précède l'agent spécialisé");
    }

    @Test
    void zoneMarkersAreNeverSentToTheLlm() {
        String prompt = AgentFiles.composeSystemPrompt("GABARIT\n[agent_principal]\n[agent]",
                "[[[\nPRINCIPAL\n]]]", "[[[\nSPECIALISE\n]]]");

        assertFalse(prompt.contains("[[["), "les marqueurs de zone ne doivent jamais être envoyés au LLM");
        assertFalse(prompt.contains("]]]"), "les marqueurs de zone ne doivent jamais être envoyés au LLM");
        assertTrue(prompt.contains("PRINCIPAL"));
        assertTrue(prompt.contains("SPECIALISE"));
    }

    @Test
    void stripZoneMarkersKeepsTheRestIntact() {
        String stripped = AgentFiles.stripZoneMarkers("ligne 1\n[[[\nligne 2\n]]]\nligne 3\n");

        assertTrue(stripped.contains("ligne 1"));
        assertTrue(stripped.contains("ligne 2"));
        assertTrue(stripped.contains("ligne 3"));
        assertFalse(stripped.contains("[[["));
        assertFalse(stripped.contains("]]]"));
    }

    @Test
    void stripZoneMarkersLeavesAnUnmarkedPromptUnchanged() {
        String prompt = "Tu es un coach financier.\nRéponds en français.";

        assertTrue(prompt.equals(AgentFiles.stripZoneMarkers(prompt)));
    }

    /** Agent générique : le gabarit + l'agent principal, sans agent spécialisé (pas d'inclusion récursive). */
    @Test
    void genericAgentReceivesPrincipalWithoutSpecializedPrompt() {
        String prompt = AgentFiles.systemPromptFor(AgentFiles.GENERIC_THEME);

        assertFalse(prompt.contains("[agent_principal]"));
        assertFalse(prompt.contains("[agent]"));
        assertFalse(prompt.contains("[[["));
        assertFalse(prompt.contains("]]]"));
        assertTrue(prompt.contains("CONTINUITÉ DE CONVERSATION"), "l'agent principal est inclus");
        assertFalse(prompt.contains("Crédit à la consommation"), "aucun agent spécialisé n'est inclus");
    }

    /** Agent spécialisé : gabarit + agent principal + agent du thème, sans marqueur visible. */
    @Test
    void specializedAgentReceivesItsOwnPromptAndNoMarker() {
        String prompt = AgentFiles.systemPromptFor("credit_conso");

        assertFalse(prompt.contains("[[["));
        assertFalse(prompt.contains("]]]"));
        assertTrue(prompt.contains("CONTINUITÉ DE CONVERSATION"), "l'agent principal est inclus");
        assertTrue(prompt.contains("Crédit à la consommation"), "l'agent spécialisé est inclus");
    }

    /** Les deux agents de l'atelier sont chargés depuis ./agent (et non depuis le repli de sécurité). */
    @Test
    void promptLabAgentsUseTheirRealPromptFiles() {
        assertTrue(AgentFiles.promptControllerSystemPrompt().contains("STABILITÉ"),
                "le vrai prompt du contrôleur est chargé (pas le repli)");
        assertTrue(AgentFiles.promptEditorSystemPrompt().contains("ANTI-SURAPPRENTISSAGE"),
                "le vrai prompt de l'éditeur est chargé (pas le repli)");
    }

    /** L'atelier n'est pas lui-même optimisable : aucun de ses prompts ne porte de marqueur. */
    @Test
    void promptLabAgentsCarryNoEditableZone() {
        assertFalse(AgentFiles.promptControllerSystemPrompt().contains("[[["));
        assertFalse(AgentFiles.promptEditorSystemPrompt().contains("[[["));
    }

    /**
     * CONTRAT DU CLIENT SIMULÉ (« Agent C ») : la DERNIÈRE question autorisée (`turnNumber == depth`) doit être
     * POSÉE, et non remplacée par une phrase de clôture — sinon le scénario s'arrête une question trop tôt.
     * <p>
     * Bogue constaté en réel par l'utilisateur : « quand je choisis 3, le client a posé 2 questions ; 5 → 4 ».
     * Le prompt demandait au client de conclure (`endConversation` à `true`) dès que `turnNumber` atteignait
     * `depth` : sa dernière question était donc consommée par une clôture, et l'IHM fermait le scénario.
     */
    @Test
    void theSimulatedClientAsksItsLastAllowedQuestion() {
        String prompt = AgentFiles.promptClientSystemPrompt();

        assertTrue(prompt.contains("La question numéro `depth` est ta DERNIÈRE question AUTORISÉE"),
                "la dernière question autorisée doit être posée : " + prompt);
        assertTrue(prompt.contains("n'anticipe JAMAIS la clôture à cause du compteur"),
                "aucune clôture ne doit être provoquée par le compteur de profondeur");
        assertFalse(prompt.contains("tu conclus le scénario (`endConversation` à `true`) au lieu de relancer"),
                "la règle qui faisait perdre la dernière question ne doit pas revenir");
    }
}
