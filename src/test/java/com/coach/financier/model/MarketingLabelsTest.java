package com.coach.financier.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Libellés métier du module Marketing : la page ne doit jamais afficher un code technique brut
 * comme {@code REAL_ESTATE · NO_SUITABLE_PRODUCT} (retour utilisateur).
 */
class MarketingLabelsTest {

    @Test
    @DisplayName("les codes connus sont traduits en libellés métier lisibles")
    void knownCodesAreTranslated() {
        assertThat(MarketingModels.projectTypeLabel("REAL_ESTATE")).isEqualTo("Projet immobilier");
        assertThat(MarketingModels.projectTypeLabel("VEHICLE")).isEqualTo("Achat d'un véhicule");
        assertThat(MarketingModels.unmetReasonLabel("NO_SUITABLE_PRODUCT")).isEqualTo("Aucune offre adaptée au besoin");
        assertThat(MarketingModels.rejectionReasonLabel("PRICE")).isEqualTo("Prix / coût trop élevé");
        assertThat(MarketingModels.missingInfoReasonLabel("MISSING_PRICING_INFORMATION")).isEqualTo("Tarif non documenté");
        assertThat(MarketingModels.interestReasonLabel("RATE_REQUEST")).isEqualTo("Demande de taux");
        assertThat(MarketingModels.productFamilyLabel("MORTGAGE")).isEqualTo("Crédit immobilier");
        assertThat(MarketingModels.productFamilyLabel("SAVINGS_PRODUCT")).isEqualTo("Produit d'épargne");
    }

    @Test
    @DisplayName("un code inconnu est humanisé, jamais renvoyé brut")
    void unknownCodesAreHumanized() {
        assertThat(MarketingModels.projectTypeLabel("SOLAR_PANELS")).isEqualTo("Solar panels");
        assertThat(MarketingModels.unmetReasonLabel("budget_too_low")).isEqualTo("Budget too low");
    }

    @Test
    @DisplayName("les entrées vides ou nulles ne produisent pas de libellé technique")
    void blankAndNullAreSafe() {
        assertThat(MarketingModels.projectTypeLabel(null)).isEmpty();
        assertThat(MarketingModels.projectTypeLabel("   ")).isEmpty();
        assertThat(MarketingModels.unmetReasonLabel(null)).isEmpty();
    }

    @Test
    @DisplayName("les tables de libellés exposées par /api/marketing/status sont complètes et non vides")
    void statusMapsAreExposed() {
        assertThat(MarketingModels.projectTypeLabels())
                .containsKeys("REAL_ESTATE", "VEHICLE", "UNKNOWN")
                .allSatisfy((code, label) -> assertThat(label).isNotBlank());
        assertThat(MarketingModels.productFamilyLabels())
                .containsEntry("MORTGAGE", "Crédit immobilier")
                .containsKey("INSURANCE_AUTO");
        assertThat(MarketingModels.unmetReasonLabels())
                .containsEntry("NO_SUITABLE_PRODUCT", "Aucune offre adaptée au besoin");
        assertThat(MarketingModels.rejectionReasonLabels()).isNotEmpty();
        assertThat(MarketingModels.missingInfoReasonLabels()).isNotEmpty();
        assertThat(MarketingModels.interestReasonLabels()).isNotEmpty();
    }
}
