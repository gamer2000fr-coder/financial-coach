package com.coach.financier.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
