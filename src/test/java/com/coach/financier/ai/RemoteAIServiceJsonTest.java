package com.coach.financier.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Certains modèles encadrent leur JSON par un bloc Markdown : le contenu doit être récupéré tel quel. */
class RemoteAIServiceJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stripsAMarkdownCodeFence() {
        String fenced = "```json\n{\"status\":\"ANSWER\",\"answer\":\"Bonjour\"}\n```";

        assertEquals("{\"status\":\"ANSWER\",\"answer\":\"Bonjour\"}", RemoteAIService.stripCodeFence(fenced));
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("```\n{\"status\":\"ANSWER\"}\n```"));
    }

    @Test
    void keepsPlainJsonUntouched() {
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("{\"status\":\"ANSWER\"}"));
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("  {\"status\":\"ANSWER\"}  "));
        assertEquals("", RemoteAIService.stripCodeFence(null));
    }

    /**
     * Un modèle qui répond du TEXTE au lieu de l'objet JSON attendu (constaté avec un modèle local sur un
     * contexte de plusieurs dizaines de milliers de jetons) ne doit pas faire échouer l'échange : le texte est
     * utilisé TEL QUEL comme réponse au client. Un JSON mal formé garde, lui, son erreur explicite.
     */
    @Test
    void recognizesAPlainTextAnswerWhereJsonWasExpected() {
        assertTrue(RemoteAIService.isPlainTextAnswer(
                "Votre projet de rénovation de cuisine relève d'un financement à la consommation."));
        assertTrue(RemoteAIService.isPlainTextAnswer("  Bonjour, voici mon analyse.  "));
        assertFalse(RemoteAIService.isPlainTextAnswer("{\"status\":\"ANSWER\",\"answer\":\"Bonjour\"}"),
                "un objet JSON, même hors contrat, garde son erreur détaillée");
        assertFalse(RemoteAIService.isPlainTextAnswer("[{\"a\":1}]"));
        assertFalse(RemoteAIService.isPlainTextAnswer("{ ceci n'est pas du JSON }"),
                "JSON invalide : le diagnostic explicite est plus utile qu'un repli silencieux");
        assertFalse(RemoteAIService.isPlainTextAnswer("   "));
        assertFalse(RemoteAIService.isPlainTextAnswer(null));
    }

    /** Le rappel de FORMAT est placé à la FIN du message utilisateur (dernière consigne lue par le modèle). */
    @Test
    void appendsAJsonFormatReminderAtTheEndOfTheUserMessage() {
        RemoteAIService probe = new RemoteAIService(new ObjectMapper(), "http://localhost:1", "cle",
                "modele", "Test", 1024) { };

        @SuppressWarnings("unchecked")
        var messages = (java.util.List<Map<String, Object>>) probe.requestBody("system", "user").get("messages");

        assertEquals("system", messages.get(0).get("role"));
        String user = String.valueOf(messages.get(1).get("content"));
        assertTrue(user.startsWith("user"), user);
        assertTrue(user.endsWith(RemoteAIService.JSON_FORMAT_REMINDER), user);
        assertTrue(user.contains("objet JSON valide"), user);
    }

    /**
     * Réponse TRONQUÉE par le modèle : le bout de JSON reste exploitable (`Unexpected end-of-input` en
     * production sur une itération COACH de l'atelier).
     */
    @Test
    void closesAStringCutInTheMiddle() throws Exception {
        String broken = "{\"status\":\"ANSWER\",\"answer\":\"Bonjour, voici votre synthèse";

        JsonNode repaired = mapper.readTree(JsonRepair.repair(broken, mapper));

        assertEquals("ANSWER", repaired.path("status").asText());
        assertEquals("Bonjour, voici votre synthèse", repaired.path("answer").asText());
    }

    @Test
    void closesOpenStructuresAndDropsADanglingSeparator() throws Exception {
        String broken = "{\"status\":\"ANSWER\",\"answer\":\"ok\",\"dataRequest\":{\"paths\":[\"/data/data.json\"],";

        JsonNode repaired = mapper.readTree(JsonRepair.repair(broken, mapper));

        assertEquals("ANSWER", repaired.path("status").asText());
        assertEquals("/data/data.json", repaired.path("dataRequest").path("paths").path(0).asText());
    }

    /** Un dernier membre INCOMPLET (clé sans valeur) est abandonné : les membres complets sont conservés. */
    @Test
    void dropsTheLastIncompleteMember() throws Exception {
        String broken = "{\"status\":\"NEED_DATA\",\"answer\":\"\",\"dataRequest\"";

        JsonNode repaired = mapper.readTree(JsonRepair.repair(broken, mapper));

        assertEquals("NEED_DATA", repaired.path("status").asText());
        assertTrue(repaired.path("dataRequest").isMissingNode());
    }

    @Test
    void leavesValidJsonUntouchedAndDoesNotInventRepairs() {
        String valid = "{\"status\":\"ANSWER\",\"answer\":\"a, b\"}";
        assertEquals(valid, JsonRepair.repair(valid, mapper), "un JSON valide n'est jamais modifié");

        // Rien d'exploitable : le texte d'origine est renvoyé (l'appelant garde son erreur de parsing).
        assertEquals("pas du json du tout", JsonRepair.repair("pas du json du tout", mapper));
        assertEquals("{\"answer\"", JsonRepair.repair("{\"answer\"", mapper));
        assertFalse(JsonRepair.repair("pas du json du tout", mapper).startsWith("{"));
    }

    /** Une chaîne contenant un guillemet échappé ne doit pas être coupée au mauvais endroit. */
    @Test
    void closesAStringEndingWithAnEscapedQuote() throws Exception {
        String broken = "{\"status\":\"ANSWER\",\"answer\":\"Il dit \\\"bonjour\\\"";

        JsonNode repaired = mapper.readTree(JsonRepair.repair(broken, mapper));

        assertEquals("ANSWER", repaired.path("status").asText());
        assertEquals("Il dit \"bonjour\"", repaired.path("answer").asText(),
                "un guillemet échappé reste DANS la chaîne, la fermeture est ajoutée à la fin");
    }
}
