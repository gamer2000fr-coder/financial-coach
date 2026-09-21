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

    /**
     * CONTRAT DU RAPPEL CONSEILLER : à côté du lien de rendez-vous, le Coach doit proposer un jeton SANS URL
     * (`[RAPPEL|Être rappelé par un conseiller]`) — c'est lui qui ouvre la pop-in « un conseiller vous
     * recontactera ». Le format exact est verrouillé ici : l'IHM ne reconnaît que celui-là.
     */
    @Test
    void theCallbackTokenIsDocumentedInThePrincipalPrompt() {
        String prompt = AgentFiles.systemPromptFor(AgentFiles.GENERIC_THEME);

        assertTrue(prompt.contains("[RAPPEL|Être rappelé par un conseiller]"),
                "le jeton de rappel fait partie du format des liens : " + prompt);
        assertTrue(prompt.contains("[URL|Prendre rendez-vous avec un conseiller|"),
                "le lien de rendez-vous reste proposé en même temps que le rappel");
        assertTrue(prompt.contains("dans les plus brefs délais"),
                "le prompt décrit le message de la pop-in (recontacter dans les plus brefs délais)");
        assertTrue(prompt.contains("SANS URL"),
                "le jeton ne doit jamais être transformé en lien ni accompagné d'une adresse inventée");
    }

    /** L'agent crédit conso rappelle le jeton dans ses règles de prochaine étape (RDV + rappel). */
    @Test
    void theConsumerCreditAgentOffersTheCallbackTokenToo() {
        String prompt = AgentFiles.systemPromptFor("credit_conso");

        assertTrue(prompt.contains("[RAPPEL|Être rappelé par un conseiller]"),
                "l'agent crédit conso propose aussi le rappel conseiller");
        assertTrue(prompt.contains("url_souscription"),
                "la souscription s'appuie sur l'URL déclarée par la fiche (url_souscription)");
    }

    /**
     * CONTRAT DU VOCABULAIRE INTERNE : le Coach s'appuie sur ses documents (arbre de décision, catalogue,
     * grille de taux) mais ne les NOMME jamais au client — « d'après l'arbre de décision » ne veut rien dire
     * pour lui. Règle transverse (agent principal) + rappel dans l'agent crédit conso.
     */
    @Test
    void theInternalVocabularyIsNeverShownToTheClient() {
        String principal = AgentFiles.systemPromptFor(AgentFiles.GENERIC_THEME);

        assertTrue(principal.contains("Vocabulaire interne"),
                "la règle est présente dans l'agent principal (chargé par tous les agents)");
        assertTrue(principal.contains("d'après l'arbre de décision"),
                "le terme interdit est cité en exemple de ce qu'il ne faut pas écrire");
        assertTrue(principal.contains("d'après votre projet et votre situation"),
                "une formulation de remplacement est fournie au Coach");
        assertTrue(AgentFiles.systemPromptFor("credit_conso").contains("« arbre de décision »"),
                "l'agent crédit conso rappelle de ne pas nommer l'arbre de décision");
    }

    /**
     * CONTRAT DE TON HUMAIN : le Coach ne réutilise pas la même accroche d'un tour à l'autre (ce qui fait
     * « robot ») et évite les tournures de rapport. Constaté en réel : « Bonne nouvelle : sur la base des
     * données dont je dispose, votre projet est réalisable. » revenait à chaque réponse.
     */
    @Test
    void theCoachMustSoundHumanAndVaryItsOpening() {
        String principal = AgentFiles.systemPromptFor(AgentFiles.GENERIC_THEME);

        assertTrue(principal.contains("Ne jamais réutiliser la même phrase d'ouverture"),
                "la règle anti-répétition de l'accroche est présente");
        assertTrue(principal.contains("« Bonne nouvelle : ... »"),
                "l'accroche constatée en réel est citée comme formule à ne pas répéter");
        assertTrue(principal.contains("Parler comme une personne, pas comme un robot"),
                "la section de ton humain est présente");
        assertTrue(principal.contains("la même phrase recopiée mot pour mot"),
                "les mentions obligatoires restent dues, mais reformulées");
        assertTrue(principal.contains("ne reprend pas celle de ma réponse précédente"),
                "la checklist finale fait vérifier l'accroche");
        assertTrue(AgentFiles.systemPromptFor("credit_conso").contains("même phrase qu'au tour précédent"),
                "l'agent crédit conso porte la règle pour ses réponses de suivi");
    }
}
