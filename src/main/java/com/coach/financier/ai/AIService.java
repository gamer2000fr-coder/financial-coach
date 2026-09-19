package com.coach.financier.ai;

import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.model.QualityModels;
import com.coach.financier.model.SuiviModels;

import java.util.List;
import java.util.Map;

public interface AIService {
    /**
     * Premier appel IA (compréhension) : classe le périmètre, l'intention, le type de
     * projet, le montant et l'objet. Retourne une structure JSON. Ne sélectionne aucun produit.
     *
     * @param currentProjectDescription description texte du projet courant (ou chaîne vide)
     */
    IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                        AIModels.AIProvider provider);

    AIModels.AIAnswer answer(String customerMessage,
                             AIModels.Classification classification,
                             FinancialSummary financialSummary,
                             Object bankingData,
                             AIModels.BankingContextMode contextMode,
                             Map<String, Object> additionalData,
                             List<ConversationModels.Message> history,
                             AIModels.AIProvider provider);

    /**
     * Variante de {@link #answer} avec un prompt système EXPLICITE : utilisée par l'ATELIER
     * d'optimisation des prompts pour REJOUER une version FIGÉE du prompt (reproductibilité d'une
     * campagne : seule la zone éditable varie).
     * <p>
     * {@code systemPrompt} {@code null} ou vide = comportement normal : prompt de l'agent actif, relu
     * depuis {@code ./agent} à chaque appel.
     */
    AIModels.AIAnswer answerWithSystemPrompt(String systemPrompt,
                                            String customerMessage,
                                            AIModels.Classification classification,
                                            FinancialSummary financialSummary,
                                            Object bankingData,
                                            AIModels.BankingContextMode contextMode,
                                            Map<String, Object> additionalData,
                                            List<ConversationModels.Message> history,
                                            AIModels.AIProvider provider);

    /**
     * Appel IA de FIN DE CONVERSATION : l'agent de synthèse analyse l'historique complet et
     * prépare (1) l'email du conseiller et (2) un brouillon d'email client (jamais envoyé
     * automatiquement). Le contexte est exactement celui décrit par le dossier de suivi
     * (historique, contexte client, contexte conseiller, produits, URLs utiles).
     */
    SuiviModels.SuiviResult summarizeConversation(Map<String, Object> context, AIModels.AIProvider provider);

    /**
     * Appel IA de l'ANALYSTE MARKETING ({@code agent/marketing.txt}) : l'IA INTERPRÈTE des statistiques
     * déjà calculées par le backend (agrégats fournis). Elle ne reçoit aucune conversation brute et ne
     * calcule aucun chiffre. Le rapport est ensuite stocké dans {@code data/marketing/reports}.</n     */
    MarketingModels.MarketingReport analyzeMarketing(MarketingModels.MarketingAggregates aggregates,
                                                     AIModels.AIProvider provider);

    /**
     * Appel IA de l'ANALYSTE QUALITÉ ({@code agent/qualite_coach_client.txt}) : l'IA INTERPRÈTE des
     * statistiques déjà calculées (satisfaction client ET conformité du Coach, tenues séparées) et des
     * commentaires anonymisés. Elle ne calcule aucun chiffre, ne reçoit aucune donnée bancaire et ne
     * modifie jamais le Coach : elle propose des améliorations, un humain décide.
     */
    QualityModels.QualityReport analyzeQuality(QualityModels.QualityAggregates aggregates,
                                               AIModels.AIProvider provider);

    /**
     * Appel IA de l'ANALYSTE FEEDBACK CONSEILLER ({@code agent/feedback_conseiller.txt}) : l'IA
     * INTERPRÈTE des statistiques déjà calculées (évaluations, zones corrigées, produits, niveaux
     * d'intérêt, suivi, emails) et des commentaires anonymisés. Elle ne calcule aucun chiffre, ne
     * reçoit aucune donnée personnelle et ne modifie jamais le Coach : elle propose, l'humain décide.
     */
    AdvisorFeedbackModels.AdvisorFeedbackReport analyzeAdvisorFeedback(
            AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates, AIModels.AIProvider provider);

    /**
     * Appel IA du CONTRÔLEUR QUALITÉ de l'atelier d'optimisation des prompts (« Agent B »,
     * {@code agent/prompt_controller.txt}) : il DIAGNOSTIQUE la réponse produite par le Coach pour une
     * question de test dans un contexte FIGÉ. Il ne modifie jamais un prompt et ne répond jamais au
     * client. Le contexte (question, réponse, prompt, zone éditable, données) est construit par
     * l'appelant : cette couche ne fait que l'envoyer et parser un diagnostic structuré.
     */
    PromptOptimizationModels.ControllerFeedback reviewCoachAnswer(Map<String, Object> context,
                                                                AIModels.AIProvider provider);

    /**
     * Appel IA de l'ÉDITEUR DE PROMPTS de l'atelier (« Agent A », {@code agent/prompt_editor.txt}) :
     * il propose une NOUVELLE zone éditable à partir du diagnostic du contrôleur et, s'il existe, du
     * feedback humain (prioritaire). Il ne renvoie jamais le prompt complet : le backend reconstruit et
     * valide la version candidate (parties protégées garanties techniquement).
     */
    PromptOptimizationModels.EditorResult editPromptSection(Map<String, Object> context,
                                                           AIModels.AIProvider provider);

    /**
     * Appel IA du CLIENT SIMULÉ de l'atelier (« Agent C », {@code agent/prompt_client.txt}) : il JOUE LE CLIENT
     * qui parle au Coach et renvoie <b>une seule</b> question (ou la fin du scénario). Il ne donne jamais de
     * conseil et n'invente aucun chiffre : l'appelant lui fournit le brief client, les quelques chiffres du
     * dossier et la conversation déjà échangée.
     */
    PromptOptimizationModels.ClientTurn clientTurn(Map<String, Object> context, AIModels.AIProvider provider);

    /**
     * Appel IA de CONCEPTION DU PROJET du client simulé (« Agent C », {@code agent/prompt_client_brief.txt}) :
     * il invente le CLIENT et la raison pour laquelle il vient voir sa banque, dans le périmètre de l'agent de
     * coach sélectionné, et évite les projets qu'on lui présente dans {@code previousBriefs}.
     */
    PromptOptimizationModels.ClientBrief clientBrief(Map<String, Object> context, AIModels.AIProvider provider);
}
