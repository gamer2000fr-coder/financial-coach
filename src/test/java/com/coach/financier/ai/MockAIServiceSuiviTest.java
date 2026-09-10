package com.coach.financier.ai;

import com.coach.financier.model.SuiviModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cas de test du dossier de suivi en mode démo (déterministe) : intérêt HIGH/MEDIUM/LOW,
 * refus explicite, absence d'URL, projets multiples.
 */
class MockAIServiceSuiviTest {
    private final MockAIService ai = new MockAIService();

    @Test
    void high_whenClientAsksForDetails_andAppearsInCustomerDraft() {
        Map<String, Object> product = product("sg_auto_tous_risques", "Assurance Auto SG – Tous Risques",
                "INSURANCE_AUTO", "https://exemple.test/auto-tous-risques");
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(message("user", "Quelles sont les garanties de l'assurance auto Tous Risques ?")),
                List.of(product),
                List.of(project("VEHICLE", "Clio 5", 8700))), null);

        SuiviModels.ProductOfInterest interest = result.productsOfInterest().get(0);
        assertEquals("HIGH", interest.interestLevel());
        assertEquals("https://exemple.test/auto-tous-risques", interest.productUrl());
        assertTrue(result.preparedCustomerEmail().body().contains("Assurance Auto SG – Tous Risques"));
        assertTrue(result.advisorEmail().body().toLowerCase().contains("pièce jointe"));
    }

    @Test
    void medium_whenRecommendedButNotRequested() {
        Map<String, Object> product = product("sg_credit_auto_expresso", "Crédit Auto Expresso",
                "AUTO_LOAN", "https://exemple.test/credit-auto");
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(
                        message("user", "Je veux acheter une Clio 5."),
                        message("assistant", "Le Crédit Auto Expresso peut financer votre véhicule.")),
                List.of(product),
                List.of(project("VEHICLE", "Clio 5", 8700))), null);

        assertEquals("MEDIUM", result.productsOfInterest().get(0).interestLevel());
        assertTrue(result.preparedCustomerEmail().body().contains("Crédit Auto Expresso"));
    }

    @Test
    void low_whenLoadedButNeverDiscussed_andNotPromotedToCustomer() {
        Map<String, Object> product = product("sg_credit_expresso", "Crédit Expresso",
                "PERSONAL_LOAN", "https://exemple.test/credit-expresso");
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(message("user", "Je veux acheter une Clio 5.")),
                List.of(product),
                List.of(project("VEHICLE", "Clio 5", 8700))), null);

        assertEquals("LOW", result.productsOfInterest().get(0).interestLevel());
        assertFalse(result.preparedCustomerEmail().body().contains("Crédit Expresso"));
    }

    @Test
    void rejected_whenClientExplicitlyDeclines() {
        Map<String, Object> product = product("sg_credit_expresso", "Crédit Expresso",
                "PERSONAL_LOAN", "https://exemple.test/credit-expresso");
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(message("user", "Finalement, non merci, je ne veux pas du Crédit Expresso.")),
                List.of(product),
                List.of(project("VEHICLE", "Clio 5", 8700))), null);

        assertEquals("REJECTED", result.productsOfInterest().get(0).interestLevel());
        assertFalse(result.preparedCustomerEmail().body().contains("Crédit Expresso"));
    }

    @Test
    void noUrl_noProductLinkInCustomerDraft() {
        Map<String, Object> product = product("sg_epargne_test", "Livret Épargne Plus",
                "SAVINGS_PRODUCT", null);
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(message("user", "Parlez-moi du Livret Épargne Plus.")),
                List.of(product),
                List.of(project("SAVINGS", null, null))), null);

        assertNull(result.productsOfInterest().get(0).productUrl());
        assertFalse(result.preparedCustomerEmail().body().contains("En savoir plus"));
    }

    @Test
    void multipleProjects_areReportedSeparately() {
        SuiviModels.SuiviResult result = ai.summarizeConversation(context(
                List.of(message("user", "Je veux acheter une Clio 5 et épargner pour les études.")),
                List.of(),
                List.of(project("VEHICLE", "Clio 5", 8700), project("EDUCATION", "Études des enfants", null))), null);

        assertEquals("Clio 5 — 8700 €", result.conversationSummary().mainProject());
        assertEquals(1, result.conversationSummary().otherProjects().size());
        assertTrue(result.conversationSummary().otherProjects().get(0).contains("Études des enfants"));
    }

    // --- Helpers ---

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> product(String id, String name, String family, String url) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", id);
        product.put("name", name);
        product.put("family", family);
        if (url != null) {
            product.put("productUrl", url);
        }
        return product;
    }

    private static Map<String, Object> project(String type, String object, Integer amount) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("type", type);
        project.put("object", object);
        project.put("amount", amount);
        project.put("currency", "EUR");
        return project;
    }

    private static Map<String, Object> context(List<Map<String, Object>> history,
                                               List<Map<String, Object>> products,
                                               List<Map<String, Object>> projects) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversationHistory", new ArrayList<>(history));
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerName", "Jean Martin");
        customer.put("customerReference", "DEMO001");
        customer.put("currentProjects", new ArrayList<>(projects));
        context.put("customerContext", customer);
        Map<String, Object> advisor = new LinkedHashMap<>();
        advisor.put("advisorName", "Conseiller SG");
        advisor.put("advisorEmail", "conseiller@example.com");
        context.put("advisorContext", advisor);
        context.put("products", new ArrayList<>(products));
        Map<String, Object> urls = new LinkedHashMap<>();
        urls.put("advisorAppointment", "https://particuliers.sg.fr/vos-rendez-vous");
        urls.put("other", List.of());
        context.put("usefulUrls", urls);
        return context;
    }
}
