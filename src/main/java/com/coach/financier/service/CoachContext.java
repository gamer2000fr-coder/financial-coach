package com.coach.financier.service;

import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProductFamily;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CONTEXTE transmis au Coach pour un tour de conversation, construit par {@link CoachContextBuilder}.
 * <p>
 * Cet objet est le point de partage entre le chat normal ({@code ChatController}) et l'ATELIER
 * d'optimisation des prompts : l'atelier persiste ce contexte comme SNAPSHOT de référence puis le
 * rejoue à l'identique pour chaque itération (seule la zone éditable du prompt varie).
 * Points d'attention :
 * <ul>
 *   <li>{@code classification} est la classification d'intention d'ORIGINE : dans une campagne elle est
 *   gelée (rejouée telle quelle, jamais recalculée) ;</li>
 *   <li>{@code providedData} reste une liste MUTABLE alimentée par la boucle {@code NEED_DATA} du chat :
 *   {@code additionalData.providedData} référence la MÊME instance (comportement du chat inchangé) ;</li>
 *   <li>{@code allowedCatalogPaths} = FICHIERS FOURNISSABLES : tous les chemins déclarés par le catalogue.
 *   « S'il le demande, on l'autorise » : aucune demande légitime n'est refusée, la whitelist du catalogue
 *   restant la seule barrière (aucun fichier hors catalogue, aucun fichier inventé). La restriction de
 *   périmètre ne limite plus que ce qui est MONTRÉ spontanément à l'IA ({@code catalog} / {@code restrictedCatalog}).</li>
 *   <li>{@code clarificationRequired} signifie que le chat répond une question de clarification SANS
 *   appeler le Coach (le contexte n'est alors pas destiné à un appel LLM).</li>
 * </ul>
 */
public record CoachContext(
        IntentClassification classification,
        AIModels.Classification legacyClassification,
        FinancialSummary financialSummary,
        CurrentProject project,
        List<Map<String, Object>> existingCredits,
        List<Map<String, Object>> compatibleProducts,
        Object catalog,
        Set<String> allowedCatalogPaths,
        List<Map<String, Object>> providedData,
        Map<String, Object> additionalData,
        String agentTheme,
        String agentLibelle,
        int agentDataCount,
        boolean requiresProducts,
        boolean projectUsable,
        boolean clarificationRequired,
        boolean restrictedCatalog,
        Set<ProductFamily> allowedFamilies,
        int catalogBefore,
        int catalogAfter,
        List<ConversationModels.Message> history,
        String debug
) {

    /** Prompt système de l'agent actif (relu depuis {@code ./agent} à chaque appel en production). */
    public String systemPrompt() {
        return AgentFiles.systemPromptFor(agentTheme);
    }
}
