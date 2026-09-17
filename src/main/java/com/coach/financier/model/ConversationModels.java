package com.coach.financier.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConversationModels {
    private ConversationModels() {}

    public record Message(String role, String content, Instant timestamp) {}

    public static class Conversation {
        /**
         * Fenêtre d'historique envoyée au coach à chaque tour.
         * <p>
         * <b>0 = TOUT l'historique</b> (défaut) : le Coach doit savoir tout ce qui a déjà été échangé avec le
         * client. Une valeur &gt; 0 borne volontairement le contexte (coût / taille de requête) ; la valeur
         * vient de {@code app.chat.history-limit}. L'historique COMPLET reste dans {@link #transcript()}.
         */
        private int historyLimit;

        private final String sessionId;
        private final List<Message> messages = new ArrayList<>();
        /** Historique COMPLET (jamais tronqué) : sert à la synthèse de fin de conversation. */
        private final List<Message> transcript = new ArrayList<>();
        /** Projets précédents (archivés quand le projet courant change de nature). */
        private final List<CurrentProject> previousProjects = new ArrayList<>();
        /** Produits/offres présentés pendant l'échange (clé = id produit). */
        private final Map<String, Map<String, Object>> discussedProducts = new LinkedHashMap<>();
        private String summary;
        private FinancialSummary financialSummary;
        private CurrentProject currentProject;

        public Conversation(String sessionId) {
            this.sessionId = sessionId;
        }

        public String sessionId() { return sessionId; }
        public List<Message> messages() { return List.copyOf(messages); }
        /** Historique complet de la conversation (non tronqué). */
        public List<Message> transcript() { return List.copyOf(transcript); }
        public String summary() { return summary; }
        public FinancialSummary financialSummary() { return financialSummary; }
        public CurrentProject currentProject() { return currentProject; }
        public List<CurrentProject> previousProjects() { return List.copyOf(previousProjects); }
        public List<Map<String, Object>> discussedProducts() { return List.copyOf(discussedProducts.values()); }

        public synchronized void addMessage(String role, String content) {
            Message message = new Message(role, content, Instant.now());
            transcript.add(message);
            messages.add(message);
            trimHistory();
        }

        /**
         * Borne l'historique envoyé au coach. {@code 0} (ou une valeur négative) = aucun bornage : tout
         * l'historique est transmis à l'IA à chaque appel.
         */
        public synchronized void setHistoryLimit(int limit) {
            this.historyLimit = Math.max(0, limit);
            trimHistory();
        }

        /** Nombre de messages d'historique transmis au coach ({@code 0} = illimité). */
        public int historyLimit() { return historyLimit; }

        private void trimHistory() {
            if (historyLimit > 0) {
                while (messages.size() > historyLimit) {
                    messages.remove(0);
                }
            }
        }

        public synchronized void setSummary(String summary) {
            this.summary = summary;
        }

        public synchronized void setFinancialSummary(FinancialSummary financialSummary) {
            this.financialSummary = financialSummary;
        }

        public synchronized void setCurrentProject(CurrentProject currentProject) {
            if (currentProject != null && currentProject != this.currentProject
                    && this.currentProject != null && this.currentProject.getType() != null) {
                archiveProject(this.currentProject);
            }
            this.currentProject = currentProject;
        }

        /** Mémorise les produits présentés/chargés pendant l'échange (dédupliqués par id). */
        public synchronized void addDiscussedProducts(List<Map<String, Object>> products) {
            if (products == null) {
                return;
            }
            for (Map<String, Object> product : products) {
                if (product == null) {
                    continue;
                }
                Object id = product.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                discussedProducts.putIfAbsent(String.valueOf(id), product);
            }
        }

        /** Projets distincts évoqués (par type), en commençant par le projet courant. */
        public synchronized List<CurrentProject> projects() {
            List<CurrentProject> result = new ArrayList<>();
            if (currentProject != null) {
                result.add(currentProject);
            }
            for (CurrentProject previous : previousProjects) {
                if (result.stream().noneMatch(p -> sameType(p, previous))) {
                    result.add(previous);
                }
            }
            return List.copyOf(result);
        }

        private void archiveProject(CurrentProject project) {
            boolean alreadyKnown = previousProjects.stream().anyMatch(p -> sameType(p, project));
            if (!alreadyKnown) {
                previousProjects.add(project);
            }
        }

        private static boolean sameType(CurrentProject a, CurrentProject b) {
            return a.getType() == b.getType();
        }
    }
}
