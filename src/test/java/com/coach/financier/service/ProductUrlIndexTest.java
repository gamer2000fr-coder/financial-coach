package com.coach.financier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie que les URLs produit proviennent bien des fiches catalogue officielles. */
class ProductUrlIndexTest {

    private final ProductUrlIndex index = new ProductUrlIndex(new ObjectMapper(), "data");

    @TempDir
    Path tempDir;

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

    /**
     * Une URL de SOUSCRIPTION déclarée par une fiche est whitelistée (le Coach la cite après une simulation),
     * même lorsqu'elle diffère de l'URL de la fiche : sinon le contrôle d'invention d'URL la signalerait à tort.
     */
    @Test
    void whitelistsTheSubscriptionUrlDeclaredByTheSheet() throws Exception {
        Path catalogue = tempDir.resolve("catalogue");
        Files.createDirectories(catalogue);
        Files.writeString(catalogue.resolve("test_offres.json"), """
                {
                  "offres": [
                    { "id": "prod_1", "nom": "Produit test",
                      "url": "https://exemple.test/fiche-produit",
                      "url_souscription": "https://exemple.test/souscription-produit" }
                  ]
                }
                """, StandardCharsets.UTF_8);

        ProductUrlIndex local = new ProductUrlIndex(new ObjectMapper(), tempDir.toString());

        assertEquals("https://exemple.test/fiche-produit", local.urlFor("prod_1"),
                "l'URL de la fiche reste celle de référence");
        assertTrue(local.allUrls().contains("https://exemple.test/souscription-produit"),
                "l'URL de souscription est autorisée pour le Coach");
    }
}
