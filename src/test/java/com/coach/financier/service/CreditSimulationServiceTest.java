package com.coach.financier.service;

import com.coach.financier.model.CreditSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulation de crédit adossée à la GRILLE DE TAUX (`data/catalogue/taux/grilles_taux_credit_conso.json`).
 * <p>
 * Ce qui est verrouillé ici : aucun taux inventé (produit inconnu, couple montant/durée hors grille,
 * crédit renouvelable ⇒ pas de chiffrage), la méthode de calcul de la grille (taux mensuel actuariel et non
 * TAEG/12), le TAEG choisi par tranche de montant, la mention « la souscription fait foi » et le refus au-delà
 * du taux d'usure — un seuil lu DANS la grille, jamais codé en dur.
 */
class CreditSimulationServiceTest {

    /** Racine de données réelle du projet (les autres tests chargent aussi ./data). */
    private static final String DATA_DIR = "./data";

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CreditSimulationService serviceFor(String dataDir) {
        return new CreditSimulationService(new CreditRateGridService(MAPPER, dataDir));
    }

    @Test
    void theRateComesFromTheGridBracketOfTheAmountAndDuration() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        // Crédit Auto Expresso : 6000,01–75 000 € sur 25–48 mois ⇒ règle __08 (TAEG 5,49 %).
        CreditSimulation simulation = service
                .simulateFromGrid("sg_credit_auto_expresso", new BigDecimal("15000"), 48).orElseThrow();

        assertEquals("sg_credit_auto_expresso__08", simulation.getRuleId());
        assertEquals("Crédit Auto Expresso", simulation.getProductName());
        assertEquals(0, new BigDecimal("5.49").compareTo(simulation.getTaegPercent()));
        assertEquals(48, simulation.getDurationMonths());
        assertTrue(simulation.getMonthlyPayment().compareTo(new BigDecimal("340")) > 0
                        && simulation.getMonthlyPayment().compareTo(new BigDecimal("355")) < 0,
                "Mensualité attendue autour de 348 €, obtenue : " + simulation.getMonthlyPayment());
        assertTrue(simulation.getTotalRepayment().compareTo(new BigDecimal("15000")) > 0);
        assertTrue(simulation.getTotalCreditCost().signum() > 0);
        assertTrue(simulation.getLastInstallment().subtract(simulation.getMonthlyPayment()).abs()
                        .compareTo(new BigDecimal("5")) < 0,
                "La dernière échéance solde le crédit, elle reste proche de la mensualité");
    }

    @Test
    void theMonthlyRateIsActuarial_andNotTheAnnualRateDividedByTwelve() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        BigDecimal monthly = service.monthlyRatePercent(new BigDecimal("5.49"));
        BigDecimal naive = new BigDecimal("5.49").divide(BigDecimal.valueOf(12), 6, java.math.RoundingMode.HALF_UP);

        // (1+0,0549)^(1/12)-1 = 0,4464 % et non 0,4575 % : la méthode de la grille est bien appliquée.
        assertTrue(monthly.compareTo(new BigDecimal("0.44")) > 0
                        && monthly.compareTo(new BigDecimal("0.45")) < 0,
                "Taux mensuel attendu ~0,4464 %, obtenu : " + monthly);
        assertTrue(monthly.compareTo(naive) < 0);
    }

    @Test
    void theRateBracketFollowsTheAmountBoundary() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        CreditSimulation low = service.simulateFromGrid("sg_credit_expresso", new BigDecimal("3000"), 24).orElseThrow();
        CreditSimulation high = service.simulateFromGrid("sg_credit_expresso", new BigDecimal("3000.01"), 24).orElseThrow();

        assertEquals("sg_credit_expresso__01", low.getRuleId());
        assertEquals("sg_credit_expresso__04", high.getRuleId());
        assertEquals(0, new BigDecimal("9.9").compareTo(low.getTaegPercent()));
        assertEquals(0, new BigDecimal("7.5").compareTo(high.getTaegPercent()));
    }

    @Test
    void aZeroRateLoanPaysCapitalOnly() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        // Prêt Permis à 1 € par jour : 1 000 € sur 34 mois, TAEG officiel 0 %.
        CreditSimulation simulation = service
                .simulateFromGrid("sg_permis_1_euro_jour", new BigDecimal("1000"), 34).orElseThrow();

        assertEquals(0, BigDecimal.ZERO.compareTo(simulation.getTaegPercent()));
        assertEquals(0, new BigDecimal("29.41").compareTo(simulation.getMonthlyPayment()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(simulation.getTotalRepayment()));
        assertEquals(0, BigDecimal.ZERO.compareTo(simulation.getTotalCreditCost()));
    }

    @Test
    void everySimulationCarriesTheSouscriptionDisclaimerAndTheGridHypotheses() {
        CreditSimulation simulation = serviceFor(DATA_DIR)
                .simulateFromGrid("sg_credit_expresso", new BigDecimal("10000"), 48).orElseThrow();

        assertTrue(simulation.getDisclaimer().contains("non contractuelle"));
        assertTrue(simulation.getDisclaimer().contains("souscription"));
        assertTrue(simulation.getHypotheses().contains("assurance facultative"));
    }

    @Test
    void aRevolvingCreditIsNeverSimulatedAsAFixedInstalmentLoan() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        assertTrue(service.simulateFromGrid("sg_alterna", new BigDecimal("5000"), 24).isEmpty());
        Optional<String> refusal = service.refusal("sg_alterna", new BigDecimal("5000"), 24);

        assertTrue(refusal.isPresent());
        assertTrue(refusal.get().contains("renouvelable"), "Motif attendu : " + refusal.get());
    }

    @Test
    void anUnknownProductOrAnOutOfGridBracketIsRefusedInsteadOfBeingInvented() {
        CreditSimulationService service = serviceFor(DATA_DIR);

        assertTrue(service.simulateFromGrid("produit_inexistant", new BigDecimal("10000"), 48).isEmpty());
        assertTrue(service.refusal("produit_inexistant", new BigDecimal("10000"), 48).orElseThrow()
                .contains("absent de la grille"));

        // Crédit Auto Expresso : 12 à 84 mois selon la tranche ⇒ 6 mois n'est couvert par aucune règle.
        assertTrue(service.simulateFromGrid("sg_credit_auto_expresso", new BigDecimal("15000"), 6).isEmpty());
        assertTrue(service.refusal("sg_credit_auto_expresso", new BigDecimal("15000"), 6).orElseThrow()
                .contains("Aucune règle"));

        // Montant et durée absents : on ne suppose jamais une valeur manquante.
        assertTrue(service.refusal("sg_credit_auto_expresso", null, 48).orElseThrow().contains("Montant"));
        assertTrue(service.refusal("sg_credit_auto_expresso", new BigDecimal("15000"), null).orElseThrow()
                .contains("Durée"));
    }

    @Test
    void theUsuryCeilingIsReadInTheGrid() {
        CreditRateGridService grid = new CreditRateGridService(MAPPER, DATA_DIR);

        assertEquals(0, new BigDecimal("23.53").compareTo(grid.usuryCeilingPercent(new BigDecimal("2000")).orElseThrow()));
        assertEquals(0, new BigDecimal("15.67").compareTo(grid.usuryCeilingPercent(new BigDecimal("5000")).orElseThrow()));
        assertEquals(0, new BigDecimal("8.56").compareTo(grid.usuryCeilingPercent(new BigDecimal("20000")).orElseThrow()));
    }

    @Test
    void aRateAboveTheUsuryCeilingOfTheGridIsRefused() throws Exception {
        // Grille de test : produit unique, TAEG 25 % pour la tranche « jusqu'à 3 000 € » (plafond 23,53 %).
        Path catalogue = tempDir.resolve("catalogue/taux");
        Files.createDirectories(catalogue);
        Files.writeString(catalogue.resolve("grilles_taux_credit_conso.json"), """
                {
                  "status": "TEST",
                  "as_of": "2026-09-19",
                  "usury_q3_2026": { "up_to_3000_eur": 23.53, "over_3000_up_to_6000_eur": 15.67, "over_6000_eur": 8.56 },
                  "products": [
                    { "id": "prod_trop_cher", "nom": "Produit trop cher", "loan_type": "fixed",
                      "rules": [ { "amount_min_eur": 1000, "amount_max_eur": 3000,
                                   "duration_min_months": 12, "duration_max_months": 24,
                                   "taeg_percent": 25.0, "product_id": "prod_trop_cher",
                                   "rule_id": "prod_trop_cher__01" } ] }
                  ]
                }
                """, StandardCharsets.UTF_8);

        CreditSimulationService service = serviceFor(tempDir.toString());

        assertTrue(service.simulateFromGrid("prod_trop_cher", new BigDecimal("2000"), 24).isEmpty(),
                "Un TAEG au-delà du taux d'usure de la grille n'est pas chiffré");
        assertTrue(service.refusal("prod_trop_cher", new BigDecimal("2000"), 24).orElseThrow().contains("usure"));
    }

    @Test
    void aMissingGridNeverInventARate() {
        // Aucune grille dans ce répertoire : le service ne peut rien chiffrer et le dit.
        CreditSimulationService service = serviceFor(tempDir.toString());

        assertTrue(service.simulateFromGrid("sg_credit_expresso", new BigDecimal("10000"), 48).isEmpty());
        Optional<String> refusal = service.refusal("sg_credit_expresso", new BigDecimal("10000"), 48);

        assertTrue(refusal.isPresent());
        assertFalse(refusal.get().isBlank());
    }
}
