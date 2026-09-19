package com.coach.financier.service;

import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AgentDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Édition des prompts d'agents (./agent/&lt;file&gt;) pour la page « Agents » :
 * l'« agent principal » (principal.txt), l'agent générique, les agents spécialisés
 * déclarés dans agents.json, l'« agent de suivi » (suivi.txt, synthèse de fin de conversation),
 * l'« agent analyste marketing » (marketing.txt), l'« agent analyste qualité »
 * (qualite_coach_client.txt, rapport qualité quotidien) et les DEUX agents de l'atelier d'optimisation
 * des prompts (prompt_controller.txt = contrôleur qualité, prompt_editor.txt = éditeur de prompts).
 * Lecture fichiersystem puis classpath ; écriture fichiersystem — le contenu est
 * relu à chaque appel IA (prise en compte immédiate).
 */
@Component
public class AgentPromptStore {
    public static final String PRINCIPAL_KEY = "principal";
    public static final String PRINCIPAL_FILE = "principal.txt";
    /** Agent de synthèse de FIN DE CONVERSATION (dossier conseiller + brouillon client). */
    public static final String SUIVI_KEY = "suivi";
    /** Agent ANALYSTE MARKETING (rapport quotidien à partir des agrégats). */
    public static final String MARKETING_KEY = "marketing";
    /** Agent ANALYSTE QUALITÉ & SATISFACTION (rapport qualité quotidien à partir des agrégats). */
    public static final String QUALITY_KEY = "qualite";
    /** Agent ANALYSTE FEEDBACK CONSEILLER (pertinence du travail du Coach vue par les conseillers). */
    public static final String ADVISOR_FEEDBACK_KEY = "feedback_conseiller";
    /** Agent CONTRÔLEUR QUALITÉ de l'ATELIER d'optimisation des prompts (« Agent B »). */
    public static final String PROMPT_CONTROLLER_KEY = "prompt_controller";
    /** Agent ÉDITEUR DE PROMPTS de l'ATELIER d'optimisation des prompts (« Agent A »). */
    public static final String PROMPT_EDITOR_KEY = "prompt_editor";
    /** CLIENT SIMULÉ de l'ATELIER d'optimisation des prompts (« Agent C »). */
    public static final String PROMPT_CLIENT_KEY = "prompt_client";
    /** CONCEPTION DU PROJET du client simulé de l'atelier (bouton « Générer projet »). */
    public static final String PROMPT_CLIENT_BRIEF_KEY = "prompt_client_brief";
    private static final String GENERIC_THEME = "generic";

    /**
     * Entrées éditables de la page : générique (défaut), agent principal, agent de suivi, puis les
     * agents spécialisés (ordre d'agents.json). Chaque entrée : {key, libelle, file}.
     * <p>
     * L'agent de suivi n'est PAS déclaré dans agents.json : il n'est pas sélectionnable comme agent
     * de coach (il n'intervient qu'à la clôture, via {@code AgentFiles.suiviSystemPrompt()}).
     */
    public List<Map<String, String>> entries() {
        List<Map<String, String>> result = new ArrayList<>();
        List<AgentDefinition> agents = AgentFiles.agents();
        AgentDefinition generic = null;
        for (AgentDefinition agent : agents) {
            if (GENERIC_THEME.equalsIgnoreCase(agent.getTheme())) {
                generic = agent;
            }
        }
        if (generic != null) {
            result.add(entry(generic.getTheme(), generic.getLibelle(), generic.getPrompt()));
        }
        result.add(entry(PRINCIPAL_KEY, "Agent principal", PRINCIPAL_FILE));
        result.add(entry(SUIVI_KEY, "Agent de suivi (fin de conversation)", AgentFiles.SUIVI_PROMPT_FILE));
        result.add(entry(MARKETING_KEY, "Agent analyste marketing", AgentFiles.MARKETING_PROMPT_FILE));
        result.add(entry(QUALITY_KEY, "Agent analyste qualité & satisfaction", AgentFiles.QUALITY_PROMPT_FILE));
        result.add(entry(ADVISOR_FEEDBACK_KEY, "Analyste Feedback Conseiller",
                AgentFiles.ADVISOR_FEEDBACK_PROMPT_FILE));
        result.add(entry(PROMPT_CONTROLLER_KEY, "Atelier prompts — agent contrôleur (Agent B)",
                AgentFiles.PROMPT_CONTROLLER_PROMPT_FILE));
        result.add(entry(PROMPT_EDITOR_KEY, "Atelier prompts — agent éditeur (Agent A)",
                AgentFiles.PROMPT_EDITOR_PROMPT_FILE));
        result.add(entry(PROMPT_CLIENT_KEY, "Atelier prompts — client simulé (Agent C)",
                AgentFiles.PROMPT_CLIENT_PROMPT_FILE));
        result.add(entry(PROMPT_CLIENT_BRIEF_KEY, "Atelier prompts — projet du client (Agent C, « Générer projet »)",
                AgentFiles.PROMPT_CLIENT_BRIEF_PROMPT_FILE));
        for (AgentDefinition agent : agents) {
            if (GENERIC_THEME.equalsIgnoreCase(agent.getTheme())) {
                continue;
            }
            result.add(entry(agent.getTheme(), agent.getLibelle(), agent.getPrompt()));
        }
        return result;
    }

    private static Map<String, String> entry(String key, String libelle, String file) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("key", key);
        e.put("libelle", libelle);
        e.put("file", file);
        return e;
    }

    /** Nom de fichier (sous /agent) associé à la clé d'un agent éditable, sinon null. */
    public String fileNameOf(String key) {
        if (key == null) {
            return null;
        }
        for (Map<String, String> entry : entries()) {
            if (key.equals(entry.get("key"))) {
                return entry.get("file");
            }
        }
        return null;
    }

    /** Contenu du prompt d'un agent (fichiersystem puis classpath) ; null si agent inconnu. */
    public String read(String key) {
        String file = fileNameOf(key);
        if (file == null) {
            return null;
        }
        try {
            Path path = Path.of("agent", file);
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Repli classpath ci-dessous.
        }
        try (InputStream in = new ClassPathResource("agent/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Écrit le prompt d'un agent dans {@code ./agent/<file>} — SOURCE DE VÉRITÉ unique (recommandé à chaque
     * appel IA, donc prise en compte immédiate). Les prompts ne sont plus dupliqués dans
     * {@code src/main/resources/agent} : deux copies finissaient par diverger.
     */
    public void write(String key, String content) {
        String file = fileNameOf(key);
        if (file == null) {
            throw new IllegalArgumentException("Agent inconnu : " + key);
        }
        String text = content == null ? "" : content;
        try {
            Path dir = Path.of("agent");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(file), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'écrire agent/" + file, e);
        }
    }
}
