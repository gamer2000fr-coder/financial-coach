package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fournisseur LOCAL (LM Studio, Ollama…) : même protocole que GPT/DeepSeek, mais deux différences
 * mesurées sur un vrai serveur LM Studio, qui doivent rester VÉRIFIÉES ici :
 * <ul>
 *   <li>LM Studio refuse {@code response_format: json_object} (« must be 'json_schema' or 'text' ») : le
 *       mode JSON natif n'est donc pas demandé par défaut (réglage {@code app.ai.local.json-mode}) ;</li>
 *   <li>aucune clé API n'est exigée : une clé vide n'est pas une erreur (l'en-tête {@code Authorization}
 *       n'est même pas envoyé), alors qu'un fournisseur distant refuse de partir sans clé.</li>
 * </ul>
 * Aucun appel réseau : seule la construction de la requête est vérifiée.
 */
class LocalAIServiceRequestTest {

    /** Serveur local SANS contrainte de forme (json-mode: none). */
    private static final class LocalProbe extends RemoteAIService {
        LocalProbe(String apiKey, int maxTokens, boolean jsonObjectMode) {
            super(new ObjectMapper(), "http://localhost:1234/v1", apiKey, "qwen3.5-9b",
                    "Local (LM Studio)", maxTokens, false, jsonObjectMode, false, 900_000);
        }
    }

    /** Serveur local avec la contrainte de SCHÉMA (json-mode: schema, défaut). */
    private static final class LocalSchemaProbe extends RemoteAIService {
        LocalSchemaProbe() {
            super(new ObjectMapper(), "http://localhost:1234/v1", "", "qwen3.5-9b",
                    "Local (LM Studio)", 8192, false, false, true, 900_000);
        }
    }

    /** Fournisseur DISTANT de référence (GPT/DeepSeek) : clé obligatoire, mode JSON natif demandé. */
    private static final class RemoteProbe extends RemoteAIService {
        RemoteProbe(String apiKey) {
            super(new ObjectMapper(), "https://api.exemple.test", apiKey, "modele-distant", "Distant", 4096);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    @Test
    void theSchemaModeConstrainsTheShapeWithoutFreezingTheFields() {
        Map<String, Object> body = new LocalSchemaProbe().requestBody("s", "u");

        @SuppressWarnings("unchecked")
        Map<String, Object> format = (Map<String, Object>) body.get("response_format");
        assertEquals("json_schema", format.get("type"),
                "LM Studio refuse json_object : la contrainte passe par un schéma");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) format.get("json_schema")).get("schema");
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.TRUE, schema.get("additionalProperties"),
                "schéma permissif : la forme est garantie, les champs restent décrits par le prompt");
    }

    @Test
    void aLocalRequestNeverAsksForTheNativeJsonObjectMode() {
        Map<String, Object> body = new LocalProbe("", 8192, false).requestBody("system", "user");

        assertNull(body.get("response_format"),
                "LM Studio refuse response_format=json_object : on ne le demande pas");
        assertEquals("qwen3.5-9b", body.get("model"));
        assertEquals(8192, body.get("max_tokens"), "les modèles raisonneurs consomment des jetons avant la réponse");
        assertEquals(2, messages(body).size());
        assertEquals("system", messages(body).get(0).get("role"));
        assertEquals("system", messages(body).get(0).get("content"));
        assertEquals("user", messages(body).get(1).get("role"));
    }

    @Test
    void theNativeJsonModeCanStillBeRequestedForACompatibleLocalServer() {
        Map<String, Object> body = new LocalProbe("", 8192, true).requestBody("s", "u");

        assertEquals(Map.of("type", "json_object"), body.get("response_format"));
    }

    @Test
    void aDistantProviderStillRequiresAKeyAndTheNativeJsonMode() {
        RemoteProbe withoutKey = new RemoteProbe("   ");
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                withoutKey::requireApiKey);
        assertTrue(error.getMessage().contains("Clé API absente"), error.getMessage());

        Map<String, Object> body = new RemoteProbe("cle").requestBody("s", "u");
        assertEquals(Map.of("type", "json_object"), body.get("response_format"),
                "les fournisseurs distants gardent le mode JSON natif");
    }

    @Test
    void aLocalProviderWorksWithoutAnyApiKey() {
        new LocalProbe("", 8192, false).requireApiKey();  // ne lève rien
        new LocalProbe(null, 0, false).requireApiKey();
    }

    @Test
    void theOutOfRangeOutputCeilingFallsBackToTheSharedDefault() {
        Map<String, Object> body = new LocalProbe("", 0, false).requestBody("s", "u");

        assertEquals(RemoteAIService.DEFAULT_MAX_OUTPUT_TOKENS, body.get("max_tokens"));
        assertFalse(String.valueOf(body.get("model")).isBlank());
    }

    @Test
    void theFactoryRoutesTheLocalProviderToItsOwnService() {
        LocalAIService local = new LocalAIService(new ObjectMapper(), "http://localhost:1234/v1", "",
                "qwen3.5-9b", 8192, "schema", 900);
        AIServiceFactory factory = new AIServiceFactory(null, null, local, new MockAIService());

        assertEquals("LOCAL", AIModels.AIProvider.LOCAL.name(),
                "le code transmis par l'IHM est bien « LOCAL »");
        assertTrue(factory.get(AIModels.AIProvider.LOCAL) instanceof LocalAIService);
        assertTrue(factory.get(AIModels.AIProvider.MOCK) instanceof MockAIService);
    }
}
