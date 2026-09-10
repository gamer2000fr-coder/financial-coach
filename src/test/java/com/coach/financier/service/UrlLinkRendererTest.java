package com.coach.financier.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie la conversion et la sécurisation du format de lien interne [URL|nom|url]. */
class UrlLinkRendererTest {

    @Test
    void toText_convertsInternalLinkToLabelAndUrl() {
        String out = UrlLinkRenderer.toText("Voir [URL|Découvrir le Prêt Auto|https://exemple.test/auto] maintenant.");
        assertEquals("Voir Découvrir le Prêt Auto : https://exemple.test/auto maintenant.", out);
    }

    @Test
    void toHtml_escapesContentAndBuildsClickableLink() {
        String out = UrlLinkRenderer.toHtml("Bonjour <Jean> [URL|Devis|https://exemple.test/devis]");
        assertTrue(out.contains("Bonjour &lt;Jean&gt;"));
        assertTrue(out.contains("<a href=\"https://exemple.test/devis\""));
        assertTrue(out.contains(">Devis</a>"));
    }

    @Test
    void toHtml_doesNotLinkNonHttpUrl() {
        String out = UrlLinkRenderer.toHtml("[URL|Cliquer|javascript:alert(1)]");
        assertFalse(out.contains("<a "));
        assertEquals("Cliquer", out);
    }

    @Test
    void sanitize_neutralisesUrlNotProvidedBySystem() {
        UrlLinkRenderer.SanitizeResult result = UrlLinkRenderer.sanitize(
                "Offre [URL|Prêt|https://faux.test/pret] et [URL|Bon|https://ok.test/bon]",
                Set.of("https://ok.test/bon"));

        assertEquals("Offre Prêt et [URL|Bon|https://ok.test/bon]", result.text());
        assertEquals(1, result.violations().size());
        assertEquals("https://faux.test/pret", result.violations().get(0));
    }

    @Test
    void extractUrls_returnsEveryUrlIncludingInvalid() {
        assertEquals(2, UrlLinkRenderer.extractUrls(
                "[URL|a|https://a.test] [URL|b|not-a-url]").size());
    }
}
