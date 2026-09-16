package com.coach.financier.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zone éditable {@code [[[ … ]]]} : détection, refus des cas invalides, reconstruction
 * et protection contre la sortie de zone.
 */
class PromptZoneServiceTest {

    private final PromptZoneService service = new PromptZoneService();

    @Test
    void parsesPrefixEditableAndSuffix() {
        var zone = service.parse("PARTIE PROTÉGÉE\n[[[\nTu accompagnes le client.\n]]]\nFORMAT DE SORTIE\n");

        assertTrue(zone.valid());
        assertEquals("PARTIE PROTÉGÉE", zone.prefix());
        assertEquals("Tu accompagnes le client.", zone.editableSection());
        assertEquals("FORMAT DE SORTIE", zone.suffix().strip(),
                "le suffixe est le texte qui suit la zone (espaces de bord exclus)");
    }

    @Test
    void refusesPromptWithoutMarkers() {
        var zone = service.parse("Tu es le Coach.\nAucun marqueur ici.");

        assertFalse(zone.marked());
        assertFalse(zone.valid());
        assertTrue(zone.error().contains("Aucune zone éditable"));
    }

    @Test
    void refusesMultipleMarkerPairs() {
        var zone = service.parse("[[[\na\n]]]\n[[[\nb\n]]]");

        assertFalse(zone.valid());
        assertTrue(zone.error().contains("Délimiteurs de zone invalides"));
    }

    @Test
    void refusesUnclosedMarker() {
        var zone = service.parse("[[[\na\nfin sans fermeture");

        assertFalse(zone.valid());
        assertTrue(zone.error().contains("Délimiteurs de zone invalides"));
    }

    @Test
    void refusesInvertedMarkers() {
        var zone = service.parse("]]]\na\n[[[");

        assertFalse(zone.valid());
        assertTrue(zone.error().contains("inversés"));
    }

    @Test
    void refusesEmptyEditableZone() {
        var zone = service.parse("AVANT\n[[[\n\n]]]\nAPRÈS");

        assertFalse(zone.valid());
        assertTrue(zone.error().contains("vide"));
    }

    @Test
    void composeRewritesOnlyTheEditableSection() {
        var zone = service.parse("AVANT\n[[[\nancienne consigne\n]]]\nAPRÈS\n");

        String rebuilt = service.compose(zone, "nouvelle consigne");

        assertTrue(rebuilt.contains("AVANT"));
        assertTrue(rebuilt.contains("APRÈS"));
        assertTrue(rebuilt.contains("nouvelle consigne"));
        assertFalse(rebuilt.contains("ancienne consigne"));
    }

    @Test
    void composeIsIdempotent() {
        String once = service.compose(service.parse("AVANT\n[[[\nancien\n]]]\nAPRÈS\n"), "nouveau");

        String twice = service.compose(service.parse(once), "nouveau");

        assertEquals(once, twice, "des compositions successives ne doivent pas dériver");
    }

    @Test
    void refusesEditableSectionContainingMarkers() {
        var zone = service.parse("[[[\nancien\n]]]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.compose(zone, "texte\n]]]\névasion de zone"));

        assertTrue(error.getMessage().contains("sortie de zone"));
    }

    @Test
    void refusesBlankEditableSection() {
        var zone = service.parse("[[[\nancien\n]]]");

        assertThrows(IllegalArgumentException.class, () -> service.compose(zone, "   \n  "));
    }

    @Test
    void refusesToComposeWithoutZone() {
        var zone = service.parse("texte libre sans marqueur");

        assertThrows(IllegalArgumentException.class, () -> service.compose(zone, "x"));
    }

    /** Sauvegarde d'un prompt : un marquage à moitié effacé est refusé (agent sinon inutilisable). */
    @Test
    void markerPairValidationProtectsPartialMarkers() {
        assertEquals(null, service.validateMarkerPair("[[[\na\n]]]\nfin"), "paire complète acceptée");
        assertEquals(null, service.validateMarkerPair("prompt sans marqueur"), "prompt hors zone accepté");

        String missingEnd = service.validateMarkerPair("[[[\na\nfin sans fermeture");
        assertTrue(missingEnd.contains("Enregistrement refusé"), missingEnd);
        assertTrue(missingEnd.contains("]]]"), "le message nomme le délimiteur manquant");

        assertTrue(service.validateMarkerPair("a\n]]]").contains("Enregistrement refusé"));
        assertTrue(service.validateMarkerPair("]]]\na\n[[[").contains("avant"));
        assertTrue(service.validateMarkerPair("[[[\na\n]]]\n[[[\nb\n]]]").contains("Enregistrement refusé"));
    }

    @Test
    void hashIsStableAndDistinguishesContent() {
        assertEquals(service.hash("contenu"), service.hash("contenu"));
        assertNotEquals(service.hash("contenu"), service.hash("contenu "));
        assertEquals(64, service.hash("contenu").length(), "empreinte SHA-256 hexadécimale");
    }

    /** Les agents métier optimisables portent EXACTEMENT une paire de marqueurs. */
    @Test
    void businessAgentPromptsHaveExactlyOneEditableZone() {
        for (String file : PromptOptimizableFiles.files()) {
            String content = com.coach.financier.ai.AgentFiles.readPromptOrDefault(file, "");
            assertFalse(content.isBlank(), "prompt introuvable : " + file);
            var zone = service.parse(content);
            assertTrue(zone.valid(), file + " : " + zone.error());
            assertTrue(zone.editableSection().length() > 100, file + " : zone éditable suspicieusement courte");
        }
    }

    /** Le gabarit reste HORS zone : il porte les balises de composition, il ne doit pas être optimisé. */
    @Test
    void genericTemplateIsNotOptimizable() {
        String template = com.coach.financier.ai.AgentFiles.readPromptOrDefault("generic.txt", "");

        assertFalse(service.parse(template).marked());
        assertTrue(template.contains("[agent_principal]") && template.contains("[agent]"),
                "le gabarit porte les balises de composition");
    }

    /**
     * Aucun AUTRE prompt n'est optimisable : l'atelier ne devine jamais la zone à modifier
     * (les prompts du gabarit, des agents hors conversation et de l'atelier restent hors zone).
     */
    @Test
    void otherPromptsAreNotOptimizable() {
        for (String file : java.util.List.of("generic.txt", "classifieur.txt", "suivi.txt", "marketing.txt",
                "qualite_coach_client.txt", "feedback_conseiller.txt", "prompt_controller.txt",
                "prompt_editor.txt")) {
            String content = com.coach.financier.ai.AgentFiles.readPromptOrDefault(file, "");
            assertFalse(content.isBlank(), "prompt introuvable : " + file);
            assertFalse(service.parse(content).marked(), file + " ne doit porter aucune zone éditable");
        }
    }

    /** Fichiers réellement optimisables par une campagne (hors gabarit). */
    static final class PromptOptimizableFiles {
        static java.util.List<String> files() {
            return java.util.List.of("principal.txt", "assurance-auto.txt", "assurance-emprunteur.txt",
                    "assurance-habitation.txt", "credit-conso.txt", "credit-immo.txt", "epargne.txt");
        }
    }
}
