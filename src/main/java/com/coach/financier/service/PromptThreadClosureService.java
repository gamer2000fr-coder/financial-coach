package com.coach.financier.service;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.model.SuiviModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CLÔTURE D'UN SCÉNARIO DE L'ATELIER, avec EXACTEMENT le comportement de la page coach.
 * <p>
 * L'atelier ne vit pas dans une session de chat : il travaille sur un <b>fil de conversation</b>
 * ({@link PromptOptimizationModels.ConversationThread}). Pour clore un test comme une vraie conversation, on
 * reconstruit ici une session du chat à partir du fil (mêmes messages, même projet, mêmes offres compatibles
 * que ceux du dernier cycle, repris du snapshot) puis on appelle le MÊME service de clôture que la page coach :
 * <ul>
 *   <li>dossier de suivi préparé par l'agent de synthèse (mêmes règles, mêmes garde-fous) ;</li>
 *   <li><b>mail au conseiller</b> (score de sens commercial + liens) si {@code sendMail} ;</li>
 *   <li><b>annuaire du centre d'appels</b> alimenté si {@code archive} (sinon aucun dossier n'est écrit : un
 *       test d'atelier ne doit pas polluer la liste des conversations).</li>
 * </ul>
 * Aucune écriture de prompt n'a lieu ici : clore un scénario ne touche pas la production.
 */
@Service
public class PromptThreadClosureService {
    private static final Logger log = LoggerFactory.getLogger(PromptThreadClosureService.class);

    private final PromptOptimizationService promptOptimizationService;
    private final ConversationService conversationService;
    private final ConversationClosureService closureService;

    public PromptThreadClosureService(PromptOptimizationService promptOptimizationService,
                                      ConversationService conversationService,
                                      ConversationClosureService closureService) {
        this.promptOptimizationService = promptOptimizationService;
        this.conversationService = conversationService;
        this.closureService = closureService;
    }

    /** Résultat de la clôture d'un fil : la réponse de la clôture (comme la page coach) + le décompte. */
    public record ThreadClosure(String threadId, String agentId, int messages,
                                SuiviModels.CloseConversationResponse response) {
    }

    /**
     * Clôt le fil de l'atelier comme une conversation du chat.
     *
     * @param threadId identifiant du fil (fil inconnu ou vide ⇒ erreur lisible, rien n'est envoyé)
     * @param sendMail {@code true} = envoie le mail au conseiller (mêmes règles que « Terminer la conversation »)
     * @param archive  {@code true} = écrit le dossier de suivi, donc la conversation apparaît dans la page
     *                 « Centre d'appels »
     * @param provider fournisseur IA de l'agent de synthèse
     */
    public ThreadClosure close(String threadId, boolean sendMail, boolean archive, AIModels.AIProvider provider) {
        PromptOptimizationModels.ConversationThread thread = promptOptimizationService.thread(threadId);
        if (thread.empty()) {
            throw new IllegalArgumentException(
                    "La conversation de l'atelier ne contient aucun échange validé : il n'y a rien à clôturer.");
        }

        // La session de chat est RECONSTRUITE (et non complétée) : rejouer la clôture ne duplique rien.
        ConversationModels.Conversation conversation = conversationService.reset(thread.threadId());
        for (PromptOptimizationModels.Turn turn : thread.turns()) {
            conversation.addMessage(turn.role(), turn.content());
        }
        applyContextFromThread(conversation, thread);

        SuiviModels.CloseConversationResponse response = closureService.close(thread.threadId(),
                new SuiviModels.CloseConversationRequest(null, null, null, sendMail, provider), archive);
        log.info("Clôture du fil d'atelier {} ({} message(s)) : archive={}, mail={}, statut={}",
                thread.threadId(), thread.turns().size(), archive, sendMail, response.status());
        return new ThreadClosure(thread.threadId(), thread.agentId(), thread.turns().size(), response);
    }

    /**
     * Reprend du DERNIER cycle du fil ce que la clôture attend d'une conversation de chat : synthèse financière,
     * projet courant et offres réellement présentées (mêmes valeurs que celles vues par le Coach). Tout est lu
     * de façon défensive : un snapshot incomplet n'empêche jamais la clôture.
     */
    private void applyContextFromThread(ConversationModels.Conversation conversation,
                                        PromptOptimizationModels.ConversationThread thread) {
        List<String> campaignIds = thread.campaignIds();
        if (campaignIds.isEmpty()) {
            return;
        }
        PromptOptimizationModels.Snapshot snapshot;
        try {
            snapshot = promptOptimizationService.snapshot(campaignIds.get(campaignIds.size() - 1));
        } catch (RuntimeException e) {
            log.debug("Contexte du fil {} non relu ({}), clôture sans projet ni offres",
                    thread.threadId(), e.getMessage());
            return;
        }
        if (snapshot.financialSummary() != null) {
            conversation.setFinancialSummary(snapshot.financialSummary());
        }
        Map<String, Object> additional = snapshot.additionalData();
        List<Map<String, Object>> products = mapsOf(additional.get("compatibleProducts"));
        if (!products.isEmpty()) {
            conversation.addDiscussedProducts(new ArrayList<>(products));
        }
        CurrentProject project = projectOf(additional.get("currentProject"));
        if (project != null) {
            conversation.setCurrentProject(project);
        }
    }

    /** Liste de maps si la valeur en est une (les produits compatibles du snapshot). */
    private static List<Map<String, Object>> mapsOf(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(mapOf(map));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** Projet courant reconstruit depuis `additionalData.currentProject` (type, objet, montant, devise). */
    private static CurrentProject projectOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        CurrentProject project = new CurrentProject();
        Object type = map.get("type");
        if (type instanceof String name && !name.isBlank()) {
            try {
                project.setType(ProjectType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                project.setType(ProjectType.UNKNOWN);
            }
        }
        Object object = map.get("object");
        if (object instanceof String text && !text.isBlank()) {
            project.setObject(text);
        }
        BigDecimal amount = amountOf(map.get("amount"));
        if (amount != null) {
            project.setAmount(amount);
        }
        Object currency = map.get("currency");
        if (currency instanceof String text && !text.isBlank()) {
            project.setCurrency(text);
        }
        return project;
    }

    /** Montant lu en {@link BigDecimal} quel que soit le type issu du JSON (`Number` ou texte). */
    private static BigDecimal amountOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
