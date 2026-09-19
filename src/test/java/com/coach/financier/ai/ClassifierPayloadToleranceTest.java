package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Panne RÉELLE remontée par l'utilisateur : « Réponse classification invalide: { "inScope": true, …,
 * "confidence": HIGH, … } » — le JSON paraît correct… sauf que le modèle a écrit <b>HIGH sans
 * guillemets</b>, ce qui n'est pas du JSON. Jackson refuse alors tout l'appel.
 * <p>
 * Le classifieur est le PREMIER appel de chaque échange : son échec bloque la conversation entière. Il est
 * donc vérifié ici de bout en bout (vrai serveur HTTP, vraie désérialisation), et pas seulement sur la
 * réparation de texte.
 */
class ClassifierPayloadToleranceTest {

    /** Exactement la réponse fautive signalée (énumérations sans guillemets). */
    private static final String PAYLOAD_WITH_BARE_ENUMS = """
            { "inScope": true, "intent": "PROJECT_UPDATE", "projectType": "VEHICLE",
            "projectObject": "voiture", "amount": 6000, "currency": "EUR",
            "refersToCurrentProject": true, "projectChanged": true, "confidence": HIGH,
            "reason": "Le client précise que le besoin de trésorerie est destiné à l'achat d'un véhicule." }
            """;

    private HttpServer server;

    private String serverUrl(String modelContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = new ObjectMapper().writeValueAsString(
                java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", modelContent),
                        "finish_reason", "stop"))));
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private LocalAIService service(String baseUrl) {
        return new LocalAIService(new ObjectMapper(), baseUrl, "", "modele-de-test", 1024, "none", 60);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void theClassifierAcceptsEnumValuesWithoutQuotes() throws Exception {
        LocalAIService service = service(serverUrl(PAYLOAD_WITH_BARE_ENUMS));

        IntentClassification classification = service.classifyIntent(
                "En fait j'ai besoin de trésorerie pour acheter une voiture à 6 000 €", null,
                AIModels.AIProvider.LOCAL);

        assertEquals(FinancialIntent.PROJECT_UPDATE, classification.getIntent());
        assertEquals(ProjectType.VEHICLE, classification.getProjectType());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence(),
                "« confidence »: HIGH sans guillemets doit être lu HIGH, pas perdu");
        assertEquals(new BigDecimal("6000"), classification.getAmount(),
                "un NOMBRE ne doit jamais être transformé en chaîne");
        assertTrue(classification.isRefersToCurrentProject());
        assertTrue(classification.isProjectChanged());
    }

    @Test
    void aReasonContainingAColonIsNotRewritten() {
        String payload = "{\"reason\": \"il a dit : HIGH, mais aussi : LOW, ensuite\", \"confidence\": HIGH}";

        String quoted = JsonRepair.quoteBareFieldValues(payload);

        assertTrue(quoted.contains("\"il a dit : HIGH, mais aussi : LOW, ensuite\""),
                "l'intérieur d'une chaîne ne doit JAMAIS être modifié : " + quoted);
        assertTrue(quoted.contains("\"confidence\": \"HIGH\""), quoted);
    }

    @Test
    void literalsAndNumbersKeepTheirType() {
        String payload = "{\"a\": true, \"b\": false, \"c\": null, \"d\": 6000, \"e\": 1.5e3, \"f\": \"HIGH\"}";

        assertEquals(payload, JsonRepair.quoteBareFieldValues(payload),
                "littéraux JSON, nombres et chaînes déjà citées restent inchangés");
    }

    @Test
    void aBareValueThatIsNotFinishedIsLeftAlone() {
        // Deux mots après « : » : on ne devine pas. Mieux vaut échouer explicitement que produire un JSON
        // dont on aurait inventé la valeur.
        String payload = "{\"confidence\": HIGH CONFIDENCE}";

        assertFalse(JsonRepair.quoteBareFieldValues(payload).contains("\"HIGH\""));
    }
}
