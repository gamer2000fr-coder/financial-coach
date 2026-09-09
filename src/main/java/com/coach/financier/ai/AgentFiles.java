package com.coach.financier.ai;

import com.coach.financier.model.AgentDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Charge le modèle « agents » (./agent/agents.json puis classpath) et lit le prompt
 * système d'un agent (./agent/&lt;prompt&gt; puis classpath). L'agent générique inclut le
 * contenu de l'agent principal (principal.txt) via la balise [agent_principal].
 * <p>
 * Source UNIQUE partagée entre {@link RemoteAIService} (envoi réel) et
 * {@code ChatController} (sélection d'agent, compteur de caractères, logs), comme
 * l'ancien {@code PromptFiles} qu'il remplace. Les fichiers sont relus à chaque appel
 * (édition sans redémarrage). Thème "generic" = agent générique par défaut.
 */
public final class AgentFiles {
    public static final String GENERIC_THEME = "generic";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentFiles() {
    }

    /** Liste des agents déclarés dans agents.json (fichiersystem puis classpath). */
    public static List<AgentDefinition> agents() {
        byte[] raw = readBytes("agents.json");
        if (raw == null) {
            return List.of();
        }
        try {
            List<AgentDefinition> list = MAPPER.readValue(raw, new TypeReference<List<AgentDefinition>>() { });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Agent dont le {@code theme} ou l'{@code id} correspond ; sinon l'agent générique. */
    public static AgentDefinition agentFor(String themeOrId) {
        List<AgentDefinition> list = agents();
        if (themeOrId != null) {
            for (AgentDefinition agent : list) {
                if (themeOrId.equalsIgnoreCase(agent.getTheme())
                        || themeOrId.equalsIgnoreCase(agent.getId())) {
                    return agent;
                }
            }
        }
        AgentDefinition generic = generic();
        if (generic != null) {
            return generic;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /** Agent générique déclaré dans agents.json (theme "generic"), ou {@code null}. */
    public static AgentDefinition generic() {
        for (AgentDefinition agent : agents()) {
            if (GENERIC_THEME.equalsIgnoreCase(agent.getTheme())) {
                return agent;
            }
        }
        return null;
    }

    /** Libellé lisible de l'agent d'un thème (repli : le thème lui-même). */
    public static String libelleFor(String theme) {
        AgentDefinition agent = agentFor(theme);
        return agent == null || agent.getLibelle() == null || agent.getLibelle().isBlank()
                ? theme : agent.getLibelle();
    }

    /**
     * Prompt système : on charge TOUJOURS le prompt générique (generic.txt) comme gabarit.
     * La balise [agent_principal] est remplacée par principal.txt ; la balise [agent] est
     * remplacée par le prompt de l'agent spécialisé concerné (vide si l'agent actif est le
     * générique lui-même, pour éviter une inclusion récursive).
     */
    public static String systemPromptFor(String theme) {
        AgentDefinition active = agentFor(theme);
        AgentDefinition generic = generic();
        String template = generic == null || generic.getPrompt() == null
                ? "" : readPromptOrDefault(generic.getPrompt(), "");
        if (template.isBlank()) {
            template = defaultGenericPrompt();
        }
        boolean selfGeneric = active != null && GENERIC_THEME.equalsIgnoreCase(active.getTheme());
        String specialized = (!selfGeneric && active != null && active.getPrompt() != null)
                ? readPromptOrDefault(active.getPrompt(), "") : "";
        return injectPrincipal(template).replace("[agent]", specialized);
    }

    /** Prompt système par défaut du coach = agent générique + règles (compatibilité). */
    public static String mainSystemPrompt() {
        return systemPromptFor(GENERIC_THEME);
    }

    /**
     * Lit un fichier d'agent depuis {@code ./agent} (système de fichiers), puis le
     * classpath {@code agent/...}, sinon renvoie le texte par défaut.
     */
    public static String readPromptOrDefault(String name, String defaultPrompt) {
        byte[] raw = readBytes(name);
        return raw == null ? defaultPrompt : new String(raw, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String name) {
        try {
            Path path = Path.of("agent", name);
            if (Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
        } catch (IOException ignored) {
            // Repli classpath ci-dessous.
        }
        try (InputStream in = new ClassPathResource("agent/" + name).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Remplace la balise [agent_principal] par principal.txt (agent principal, relu à chaque appel). */
    private static String injectPrincipal(String prompt) {
        if (prompt == null || !prompt.contains("[agent_principal]")) {
            return prompt;
        }
        String principal = readPromptOrDefault("principal.txt", "").strip();
        return prompt.replace("[agent_principal]", principal);
    }

    /** Prompt générique de repli si generic.txt (ou agents.json) est absent. */
    private static String defaultGenericPrompt() {
        return """
                Tu es un coach financier bancaire (agent générique).

                Le champ "bankingData" contient le CATALOGUE des fichiers de données disponibles.
                Le champ "financialSummary" contient la synthèse agrégée calculée par le backend.
                Le champ "additionalData.providedData" contient le contenu des fichiers déjà fournis.

                N'invente jamais de transaction, revenu, crédit, solde, épargne, taux ou mensualité.
                Tu réponds en français, de manière claire et pédagogique.

                Règle :
                - Si tu disposes déjà des données suffisantes pour répondre, renvoie status=ANSWER.
                - Si une information précise te manque et correspond à un fichier du catalogue, renvoie
                  status=NEED_DATA et indique dans "dataRequest.paths" les chemins EXACTS des fichiers
                  dont tu as besoin (jamais un chemin inventé).

                Réponds UNIQUEMENT avec ce JSON :
                {"status":"ANSWER|NEED_DATA","answer":"...","dataRequest":null|{"paths":["/data/..."]},"conversationSummary":"..."}
                """;
    }
}
