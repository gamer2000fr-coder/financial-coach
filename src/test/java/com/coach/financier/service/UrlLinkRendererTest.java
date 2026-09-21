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

    @Test
    void toText_removesBoldMarkers() {
        // Le mail en texte brut ne doit jamais exposer les astérisques du gras Markdown.
        assertEquals("Score de sens commercial : 72/100 — Priorité haute",
                UrlLinkRenderer.toText("**Score de sens commercial : 72/100 — Priorité haute**"));
    }

    @Test
    void toHtml_buildsStrongForBoldMarkers() {
        String out = UrlLinkRenderer.toHtml("**Score de sens commercial : 72/100**");
        assertTrue(out.contains("<strong>Score de sens commercial : 72/100</strong>"));
    }

    @Test
    void toHtml_buildsAClickableCallLinkForATelUrl() {
        // Lien d'APPEL ajouté par le backend (page Centre d'appels / mail conseiller) : le numéro vient
        // de la configuration, jamais du LLM.
        String out = UrlLinkRenderer.toHtml("Contacter le client : [URL|Appeler le client|tel:0644910925]");
        assertTrue(out.contains("<a href=\"tel:0644910925\">Appeler le client</a>"));
    }

    @Test
    void toText_showsThePhoneNumberOfACallLink() {
        assertEquals("Contacter le client : Appeler le client : 0644910925",
                UrlLinkRenderer.toText("Contacter le client : [URL|Appeler le client|tel:0644910925]"));
    }

    @Test
    void telLink_withUnexpectedCharactersIsNotRendered() {
        String out = UrlLinkRenderer.toHtml("[URL|Appeler|tel:06\" onmouseover=\"alert(1)]");
        assertFalse(out.contains("<a "), "Un lien d'appel au format inattendu n'est jamais rendu cliquable");
    }

    @Test
    void sanitize_neutralisesATelUrlProposedByTheModel() {
        // Anti-invention : le LLM ne peut pas proposer de lien d'appel (seuls http(s) sont whitelistés).
        UrlLinkRenderer.SanitizeResult result = UrlLinkRenderer.sanitize(
                "Appelez-moi [URL|Appeler|tel:0600000000]", Set.of());
        assertEquals("Appelez-moi Appeler", result.text());
        assertEquals(1, result.violations().size());
    }
}
