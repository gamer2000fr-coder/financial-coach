package com.coach.financier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie que les URLs produit proviennent bien des fiches catalogue officielles. */
class ProductUrlIndexTest {

    private final ProductUrlIndex index = new ProductUrlIndex(new ObjectMapper(), "data");

    @Test
    void resolvesOfficialUrlForCreditOffer() {
        assertEquals("https://particuliers.sg.fr/emprunter/credit-auto-deux-roues/credit-auto-expresso",
                index.urlFor("sg_credit_auto_expresso"));
    }

    @Test
    void resolvesOfficialUrlForInsuranceFormula() {
        String url = index.urlFor("sg_auto_tous_risques");
        assertTrue(url != null && url.startsWith("https://"), "URL officielle attendue : " + url);
    }

    @Test
    void neverInventsUrlForUnknownProduct() {
        assertNull(index.urlFor("produit_qui_n_existe_pas"));
        assertNull(index.urlFor(null));
    }

    @Test
    void exposesOnlyHttpUrlsForWhitelist() {
        assertTrue(!index.allUrls().isEmpty());
        assertTrue(index.allUrls().stream().allMatch(url -> url.startsWith("http")));
    }
}
