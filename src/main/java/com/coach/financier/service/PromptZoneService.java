package com.coach.financier.service;

import com.coach.financier.ai.AgentFiles;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Zone MODIFIABLE d'un prompt d'agent, pour l'atelier d'amélioration itérative des prompts.
 * <p>
 * Convention : le prompt d'un agent métier contient une paire de lignes de délimitation
 * <pre>
 * [[[
 * … contenu modifiable par l'Agent A …
 * ]]]
 * </pre>
 * Tout ce qui est HORS de ces lignes est IMMUABLE pendant une campagne : le backend reconstruit
 * lui-même le prompt ({@code prefix + editableSection + suffix}) et ne fait jamais confiance au
 * texte renvoyé par l'Agent A.
 * <p>
 * Les LIGNES de marqueur sont retirées du prompt envoyé au LLM (voir
 * {@link AgentFiles#stripZoneMarkers(String)}) : le prompt de production reste strictement
 * identique à ce qu'il était avant l'introduction des marqueurs.
 * <p>
 * Un prompt sans marqueur n'est PAS optimisable (campagne refusée avec un message explicite) :
 * l'atelier ne devine jamais la zone à modifier.
 */
@Service
public class PromptZoneService {

    /** Zone détectée dans un prompt. {@code error} non nul = prompt non optimisable. */
    public record Zone(boolean marked, String prefix, String editableSection, String suffix, String error) {

        /** Une zone est valide si les marqueurs sont présents, uniques, ordonnés et non vides. */
        public boolean valid() {
            return marked && error == null;
        }
    }

    /**
     * Détecte la zone éditable d'un prompt.
     * <p>
     * Contrôles : aucune paire de marqueurs, marqueurs multiples, marqueurs inversés ou zone vide
     * ⇒ {@link Zone#valid()} est faux et {@link Zone#error()} explique le refus.
     */
    public Zone parse(String content) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        List<String> lines = List.of(text.split("\n", -1));
        int start = -1;
        int end = -1;
        int startCount = 0;
        int endCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (AgentFiles.ZONE_START.equals(trimmed)) {
                startCount++;
                if (start < 0) {
                    start = i;
                }
            } else if (AgentFiles.ZONE_END.equals(trimmed)) {
                endCount++;
                if (end < 0) {
                    end = i;
                }
            }
        }
        if (startCount == 0 && endCount == 0) {
            return new Zone(false, text, "", "", "Aucune zone éditable détectée : le prompt doit contenir "
                    + "une ligne " + AgentFiles.ZONE_START + " et une ligne " + AgentFiles.ZONE_END + ".");
        }
        if (startCount != 1 || endCount != 1) {
            return new Zone(false, text, "", "", "Délimiteurs de zone invalides : " + startCount
                    + " ligne(s) " + AgentFiles.ZONE_START + " et " + endCount + " ligne(s) "
                    + AgentFiles.ZONE_END + " (une seule paire attendue).");
        }
        if (end < start) {
            return new Zone(false, text, "", "", "Délimiteurs de zone inversés : " + AgentFiles.ZONE_END
                    + " apparaît avant " + AgentFiles.ZONE_START + ".");
        }
        String prefix = String.join("\n", lines.subList(0, start));
        String editable = String.join("\n", lines.subList(start + 1, end));
        String suffix = String.join("\n", lines.subList(end + 1, lines.size()));
        if (editable.isBlank()) {
            return new Zone(false, prefix, "", suffix, "Zone éditable vide (rien à optimiser entre "
                    + AgentFiles.ZONE_START + " et " + AgentFiles.ZONE_END + ").");
        }
        return new Zone(true, prefix, editable, suffix, null);
    }

    /** Première erreur rendant une zone éditable inacceptable, ou {@code null} si elle est valide. */
    public String validateEditableSection(String section) {
        if (section == null || section.isBlank()) {
            return "Zone éditable vide.";
        }
        for (String line : section.split("\n", -1)) {
            String trimmed = line.strip();
            if (AgentFiles.ZONE_START.equals(trimmed) || AgentFiles.ZONE_END.equals(trimmed)) {
                return "La zone éditable ne doit pas contenir de délimiteur " + AgentFiles.ZONE_START
                        + " / " + AgentFiles.ZONE_END + " (tentative de sortie de zone refusée).";
            }
        }
        return null;
    }

    /**
     * Reconstruit un prompt marqué à partir d'une zone et d'une NOUVELLE zone éditable.
     * Les lignes de marqueur sont normalisées (mise en forme idempotente).
     *
     * @throws IllegalArgumentException si la zone n'est pas valide ou si la section est inacceptable
     */
    public String compose(Zone zone, String editableSection) {
        if (zone == null || !zone.valid()) {
            throw new IllegalArgumentException("Zone éditable invalide : "
                    + (zone == null ? "zone absente" : zone.error()));
        }
        String error = validateEditableSection(editableSection);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        StringBuilder sb = new StringBuilder();
        if (!zone.prefix().isBlank()) {
            sb.append(zone.prefix().stripTrailing()).append('\n');
        }
        sb.append(AgentFiles.ZONE_START).append('\n')
                .append(editableSection.strip()).append('\n')
                .append(AgentFiles.ZONE_END).append('\n');
        if (!zone.suffix().isBlank()) {
            sb.append(zone.suffix().strip());
        }
        return sb.toString();
    }

    /**
     * Contrôle des délimiteurs à la SAUVEGARDE d'un prompt (page Agents) : refuse un contenu
     * partiellement marqué — une seule ligne {@code [[[} ou {@code ]]]}, paire inversée, paires
     * multiples — mais laisse passer un prompt SANS zone (certains agents n'en ont pas).
     *
     * @return première erreur rendant le contenu inacceptable, ou {@code null} s'il est acceptable
     */
    public String validateMarkerPair(String content) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        int starts = 0;
        int ends = 0;
        int firstStart = -1;
        int firstEnd = -1;
        List<String> lines = List.of(text.split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (AgentFiles.ZONE_START.equals(trimmed)) {
                starts++;
                if (firstStart < 0) {
                    firstStart = i;
                }
            } else if (AgentFiles.ZONE_END.equals(trimmed)) {
                ends++;
                if (firstEnd < 0) {
                    firstEnd = i;
                }
            }
        }
        if (starts == 0 && ends == 0) {
            return null;
        }
        if (starts != 1 || ends != 1) {
            return "Enregistrement refusé : le prompt contient " + starts + " ligne(s) "
                    + AgentFiles.ZONE_START + " et " + ends + " ligne(s) " + AgentFiles.ZONE_END
                    + " (une seule paire complète attendue, ou aucune).";
        }
        if (firstEnd < firstStart) {
            return "Enregistrement refusé : " + AgentFiles.ZONE_END + " apparaît avant "
                    + AgentFiles.ZONE_START + ".";
        }
        return null;
    }

    /** Empreinte SHA-256 (hexadécimale) d'un contenu : sert de version/hash de prompt et de snapshot. */
    public String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Empreinte SHA-256 indisponible", e);
        }
    }
}
