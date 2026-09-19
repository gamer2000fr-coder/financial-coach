package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Délai de LECTURE du fournisseur LOCAL : c'est lui qui décide si une réponse lente est ATTENDUE ou
 * PERDUE. Le comportement est vérifié contre un vrai serveur HTTP (celui du JDK), volontairement lent —
 * aucun modèle, aucune clé, aucun accès réseau externe.
 * <p>
 * Deux propriétés comptent, et ce sont exactement les deux questions posées par un modèle local lent :
 * <ul>
 *   <li>un délai TROP COURT produit un message qui DIT le délai expiré et le RÉGLAGE à modifier (au lieu
 *       d'un « Read timed out » opaque) ;</li>
 *   <li>un délai de {@code 0} signifie AUCUN délai : une réponse lente est attendue jusqu'au bout.</li>
 * </ul>
 */
class LocalAIServiceTimeoutTest {

    /** Serveur local factice : répond après {@code delayMs}, comme un modèle qui génère lentement. */
    private HttpServer server;

    private String slowServerUrl(long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"Solde du compte courant : 1 516,89 EUR\"},"
                    + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AIModels.AIAnswer ask(LocalAIService service) {
        return service.answerWithSystemPrompt("Prompt système de test", "Quel est mon solde ?", null, null,
                null, AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, Map.of(), List.of(),
                AIModels.AIProvider.LOCAL);
    }

    @Test
    void aTooShortReadTimeoutIsReportedWithTheSettingToChange() throws Exception {
        String baseUrl = slowServerUrl(3_000);
        LocalAIService service = new LocalAIService(new ObjectMapper(), baseUrl, "", "modele-de-test",
                512, "none", 1);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ask(service));

        String message = failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("Délai de lecture dépassé après 1 s"),
                "le délai RÉELLEMENT expiré doit être cité : " + message);
        assertTrue(message.contains("LOCAL_READ_TIMEOUT_SECONDS"),
                "le message doit désigner le réglage à modifier : " + message);
        assertTrue(RemoteAIService.isTimeout(failure), "la cause doit rester identifiable comme un délai");
    }

    @Test
    void aZeroReadTimeoutWaitsForAsLongAsTheModelNeeds() throws Exception {
        String baseUrl = slowServerUrl(2_000);
        // 0 = AUCUN délai : le même serveur lent doit cette fois aboutir (la prose est utilisée telle
        // quelle, le modèle de test ne respectant pas le contrat JSON).
        LocalAIService service = new LocalAIService(new ObjectMapper(), baseUrl, "", "modele-de-test",
                512, "none", 0);

        AIModels.AIAnswer answer = ask(service);

        assertEquals(AIModels.AIStatus.ANSWER, answer.status());
        assertTrue(answer.answer().contains("1 516,89"), "la réponse lente doit être conservée : "
                + answer.answer());
    }

    @Test
    void aTimeoutIsRecognizedEvenWhenTheClientWrapsIt() {
        assertTrue(RemoteAIService.isTimeout(new HttpTimeoutException("request timed out")));
        assertTrue(RemoteAIService.isTimeout(new RuntimeException(
                new IllegalStateException(new java.net.SocketTimeoutException("read timed out")))));
        assertFalse(RemoteAIService.isTimeout(new RuntimeException("connexion refusée")));
        assertFalse(RemoteAIService.isTimeout(null));
    }

    @Test
    void aFailedCallKeepsTheProviderErrorWhenItIsNotATimeout() throws Exception {
        // Serveur qui répond 400 (comme LM Studio quand le contexte demandé est trop grand) : le message
        // du fournisseur ne doit PAS être remplacé par un message de délai.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"The number of tokens to keep from the initial prompt is greater "
                    .concat("than the context length\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        LocalAIService service = new LocalAIService(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "modele-de-test",
                512, "none", 5);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> ask(service));

        assertFalse(RemoteAIService.isTimeout(failure), "une erreur 400 n'est pas un délai expiré");
        assertTrue(String.valueOf(failure.getMessage()).contains("400")
                        || String.valueOf(failure.getMessage()).contains("context length"),
                "l'erreur du fournisseur doit rester lisible : " + failure.getMessage());
        assertEquals(1, calls.get());
    }
}
