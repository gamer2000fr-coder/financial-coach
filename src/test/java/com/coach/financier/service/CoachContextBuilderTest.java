package com.coach.financier.service;

import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.ProjectType;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filet de NON-RÉGRESSION du {@link CoachContextBuilder}, extrait de {@code ChatController}.
 * <p>
 * Le contexte est construit à partir des données RÉELLES du POC ({@code ./data}) : les assertions
 * portent donc sur des faits stables (agent sélectionné, restriction de catalogue) et jamais sur des
 * listes figées qui changeraient avec le catalogue produit.
 */
class CoachContextBuilderTest {

    private static final String DATA_DIR = "./data";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private CoachContextBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        ProjectProductMappingService mapping = new ProjectProductMappingService();
        BankingDataRepository banking =
                new BankingDataRepository(objectMapper, DATA_DIR + "/banking_demo_normalized.json");
        builder = new CoachContextBuilder(
                new FinancialAnalysisService(banking),
                new DataRequestService(objectMapper, DATA_DIR, true),
                new FinancialSynthesisStore(objectMapper, DATA_DIR + "/synthese_financier.json"),
                new ProductCatalogueService(objectMapper, mapping, DATA_DIR),
                mapping,
                banking,
                objectMapper);
    }

    private static IntentClassification classification(FinancialIntent intent, ProjectType type, BigDecimal amount) {
        IntentClassification c = new IntentClassification();
        c.setInScope(true);
        c.setIntent(intent);
        c.setProjectType(type);
        c.setAmount(amount);
        c.setCurrency("EUR");
        c.setConfidence(ConfidenceLevel.HIGH);
        return c;
    }

    private static CurrentProject project(IntentClassification classification) {
        CurrentProject p = new CurrentProject();
        p.apply(classification);
        return p;
    }

    /** Financement véhicule : agent « crédit conso » + catalogue MONTRÉ restreint (pas de crédit immo). */
    @Test
    void vehicleFinancingIsRoutedToConsumerCreditAgentAndRestrictsCatalogue() {
        IntentClassification c = classification(FinancialIntent.FINANCING_REQUEST, ProjectType.VEHICLE,
                new BigDecimal("15000"));
        CurrentProject p = project(c);

        CoachContext ctx = builder.build("Je veux financer une voiture à 15000 euros", c, p, List.of());

        assertEquals("credit_conso", ctx.agentTheme());
        assertEquals("Crédit à la consommation", ctx.agentLibelle());
        assertFalse(ctx.clarificationRequired());
        assertTrue(ctx.restrictedCatalog());
        // Tout fichier du catalogue est FOURNISSABLE sur demande (« s'il le demande, on l'autorise ») : la
        // restriction ne porte que sur ce qui est MONTRÉ spontanément à l'IA (le catalogue envoyé).
        assertNotNull(ctx.allowedCatalogPaths());
        assertTrue(ctx.allowedCatalogPaths().contains("/data/catalogue/credit_conso.json"));
        assertTrue(ctx.allowedCatalogPaths().contains("/data/catalogue/credit_immo.json"),
                "fournissable si le Coach le demande explicitement");
        assertTrue(ctx.allowedCatalogPaths().contains("/data/transaction/transactions_2026_08.json"));
        assertFalse(catalogPaths(ctx).contains("/data/catalogue/credit_immo.json"),
                "mais la fiche crédit immo n'est PAS montrée hors périmètre");
        assertFalse(catalogPaths(ctx).contains("/data/catalogue/assurance_auto.json"),
                "les fiches d'assurance ne sont pas montrées hors périmètre");
        assertTrue(catalogPaths(ctx).contains("/data/catalogue/credit_conso.json"),
                "la fiche crédit conso est montrée");
        assertFalse(ctx.compatibleProducts().isEmpty(), "des produits compatibles sont filtrés par le backend");
        assertTrue(ctx.allowedFamilies().contains(ProductFamily.AUTO_LOAN));
        assertTrue(ctx.debug().contains("[AGENT]") && ctx.debug().contains("theme=credit_conso"));
        assertTrue(ctx.debug().contains("[PRODUCT_FILTER]") && ctx.debug().contains("restrictedCatalog=true"));
        assertTrue(ctx.debug().contains("[COACH]"));
    }

    /**
     * Délai de mise à disposition des fonds : il vient du CATALOGUE, il est transmis au Coach quand il est
     * renseigné, et il est ABSENT quand le catalogue ne le documente pas — jamais de délai inventé.
     */
    @Test
    void theFundAvailabilityDelayIsForwardedOnlyWhenTheCatalogueDocumentsIt() {
        IntentClassification c = classification(FinancialIntent.FINANCING_REQUEST, ProjectType.VEHICLE,
                new BigDecimal("15000"));

        CoachContext ctx = builder.build("Je veux financer une voiture", c, project(c), List.of());

        Map<String, Object> expresso = compactProduct(ctx, "sg_credit_expresso");
        Map<String, Object> auto = compactProduct(ctx, "sg_credit_auto_expresso");

        assertEquals(Map.of("minDays", 8), expresso.get("fundAvailabilityDelay"),
                "le délai documenté (fonds.delai_minimum_jours = 8) est transmis au Coach");
        assertFalse(auto.containsKey("fundAvailabilityDelay"),
                "aucun délai inventé pour un produit qui n'en documente pas");
    }

    private static Map<String, Object> compactProduct(CoachContext ctx, String productId) {
        return ctx.compatibleProducts().stream()
                .filter(product -> productId.equals(product.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Produit absent du contexte : " + productId));
    }

    /** Chemins réellement MONTRÉS à l'IA (le catalogue envoyé est une liste de {path, description}). */
    private static Set<String> catalogPaths(CoachContext ctx) {
        Set<String> paths = new LinkedHashSet<>();
        if (ctx.catalog() instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("path") != null) {
                    paths.add(String.valueOf(map.get("path")));
                }
            }
        }
        return paths;
    }

    /** Question générique : agent générique, aucun produit, aucune restriction de catalogue montré. */
    @Test
    void genericQuestionUsesGenericAgentAndKeepsTheFullCatalogue() {
        IntentClassification c = classification(FinancialIntent.BUDGET_ANALYSIS, ProjectType.UNKNOWN, null);

        CoachContext ctx = builder.build("Combien ai-je dépensé le mois dernier ?", c, null, List.of());

        assertEquals("generic", ctx.agentTheme());
        assertFalse(ctx.requiresProducts());
        assertFalse(ctx.restrictedCatalog());
        assertNotNull(ctx.allowedCatalogPaths(), "tout le catalogue reste fournissable sur demande");
        assertTrue(ctx.allowedCatalogPaths().contains("/data/catalogue/credit_immo.json"));
        assertTrue(ctx.compatibleProducts().isEmpty());
        assertTrue(ctx.allowedFamilies().isEmpty());
        assertNull(ctx.project());
        assertFalse(ctx.additionalData().containsKey("currentProject"),
                "aucun projet courant : la clé n'est pas ajoutée au contexte envoyé au Coach");
    }

    /** Financement sans projet utilisable : le chat répond une clarification SANS appeler le Coach. */
    @Test
    void unknownProjectOnFinancingRequestRequiresClarification() {
        IntentClassification c = classification(FinancialIntent.FINANCING_REQUEST, ProjectType.UNKNOWN, null);

        CoachContext ctx = builder.build("Je veux financer un achat", c, null, List.of());

        assertTrue(ctx.clarificationRequired());
        assertTrue(ctx.compatibleProducts().isEmpty());
        assertNotNull(CoachContextBuilder.CLARIFICATION_MESSAGE);
    }

    /**
     * Assurance : le SOUS-TYPE vient du besoin RÉEL, pas de la première mention trouvée.
     * <p>
     * Cas réel signalé : « assurer mon appartement que je viens d'acheter avec un crédit immobilier » était
     * routé vers l'agent ASSURANCE EMPRUNTEUR (à cause des mots « crédit immobilier ») alors que le client
     * veut assurer son LOGEMENT.
     */
    @Test
    void homeInsuranceBeatsAnIncidentalMortgageMention() {
        IntentClassification c = classification(FinancialIntent.PRODUCT_INFORMATION, ProjectType.INSURANCE, null);
        c.setProjectObject("assurance habitation");

        CoachContext ctx = builder.build(
                "Je voudrais assurer mon appartement que je viens d'acheter avec un crédit immobilier.",
                c, null, List.of());

        assertEquals("assurance_habitation", ctx.agentTheme(),
                "le besoin réel (logement) prime sur la mention incidente du crédit");
        assertTrue(ctx.debug().contains("theme=assurance_habitation"));
    }

    /** « prélèvement automatique » ne doit PAS déclencher l'assurance auto (mot entier, pas sous-chaîne). */
    @Test
    void automaticIsNotAVehicleKeyword() {
        IntentClassification c = classification(FinancialIntent.PRODUCT_INFORMATION, ProjectType.INSURANCE, null);

        CoachContext ctx = builder.build(
                "Mon prélèvement automatique d'assurance a augmenté, que faire ?", c, null, List.of());

        assertEquals("generic", ctx.agentTheme(),
                "aucun sous-type identifiable : agent générique plutôt qu'une assurance auto inventée");
    }

    /** « remplacer » ne doit pas être pris pour « placer » (sinon bascule parasite vers l'épargne). */
    @Test
    void replacingAnInsuranceIsNotASavingsRequest() {
        IntentClassification c = classification(FinancialIntent.PRODUCT_INFORMATION, ProjectType.INSURANCE, null);

        CoachContext ctx = builder.build("Je veux remplacer mon assurance habitation", c, null, List.of());

        assertEquals("assurance_habitation", ctx.agentTheme());
    }

    /** Les sous-types explicites restent correctement routés (non-régression). */
    @Test
    void insuranceSubTypesAreRoutedToTheirAgent() {
        assertEquals("assurance_emprunteur", themeFor("Quelle assurance pour mon prêt immobilier ?"));
        assertEquals("assurance_auto", themeFor("Combien coûte l'assurance de ma voiture ?"));
        assertEquals("assurance_habitation", themeFor("Je cherche une assurance pour ma maison."));
        assertEquals("epargne", themeFor("Je veux placer mon épargne sur un PEA."));
        assertEquals("epargne", themeFor("Je veux ouvrir une assurance-vie pour préparer ma retraite."));
    }

    private String themeFor(String message) {
        IntentClassification c = classification(FinancialIntent.PRODUCT_INFORMATION, ProjectType.INSURANCE, null);
        return builder.build(message, c, null, List.of()).agentTheme();
    }

    /** Le contexte expose exactement les clés attendues, dans l'ordre attendu par le payload du Coach. */    @Test
    void additionalDataExposesTheFrozenContextInOrder() {
        IntentClassification c = classification(FinancialIntent.FINANCING_REQUEST, ProjectType.VEHICLE,
                new BigDecimal("15000"));
        CurrentProject p = project(c);

        CoachContext ctx = builder.build("Je veux financer une voiture", c, p, List.of());

        assertEquals(List.of("providedData", "currentProject", "existingCredits", "compatibleProducts",
                        "agent", "agentLibelle"),
                new ArrayList<>(ctx.additionalData().keySet()));

        assertEquals("Synthèse financière", ctx.providedData().get(0).get("description"));
        assertTrue(ctx.providedData().size() > 1, "les données de l'agent actif sont injectées d'office");
        assertEquals(ctx.providedData(), ctx.additionalData().get("providedData"),
                "additionalData.providedData référence la MÊME liste (alimentée par la boucle NEED_DATA)");
        assertFalse(ctx.existingCredits().isEmpty(), "les engagements réels du client sont fournis");
        assertEquals("MORTGAGE", ctx.existingCredits().get(0).get("type"));
    }

    /** Les compteurs et le prompt de l'agent sont exposés pour les Logs (page « Voir le prompt »). */
    @Test
    void payloadMetricsAndAgentPromptAreAvailableForLogs() {
        IntentClassification c = classification(FinancialIntent.FINANCING_REQUEST, ProjectType.VEHICLE,
                new BigDecimal("15000"));

        CoachContext ctx = builder.build("Je veux financer une voiture", c, project(c), List.of());

        long chars = builder.payloadCharCount(ctx, "Je veux financer une voiture");
        assertTrue(chars > 0, "le compteur de caractères est calculé sur le prompt réel");
        assertEquals(chars, builder.payloadCharCount(ctx, "Je veux financer une voiture"),
                "le compteur est déterministe pour un même contexte");
        String logged = builder.loggedPrompt(ctx, "Je veux financer une voiture");
        assertTrue(logged.startsWith("=== PROMPT SYSTÈME ==="));
        assertTrue(logged.contains("=== PAYLOAD UTILISATEUR"));
        assertTrue(ctx.systemPrompt().contains("Crédit à la consommation"), "prompt de l'agent actif");
        assertFalse(ctx.systemPrompt().contains("[["), "aucun marqueur de zone éditable n'est envoyé au LLM");
        assertTrue(ctx.catalogAfter() <= ctx.catalogBefore(),
                "le catalogue restreint expose moins d'entrées que le catalogue complet");
    }
}
