package com.coach.financier.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ConversationModels {
    private ConversationModels() {}

    public record Message(String role, String content, Instant timestamp) {}

    public static class Conversation {
        private final String sessionId;
        private final List<Message> messages = new ArrayList<>();
        private String summary;
        private FinancialSummary financialSummary;
        private CurrentProject currentProject;

        public Conversation(String sessionId) {
            this.sessionId = sessionId;
        }

        public String sessionId() { return sessionId; }
        public List<Message> messages() { return List.copyOf(messages); }
        public String summary() { return summary; }
        public FinancialSummary financialSummary() { return financialSummary; }
        public CurrentProject currentProject() { return currentProject; }

        public synchronized void addMessage(String role, String content) {
            messages.add(new Message(role, content, Instant.now()));
            if (messages.size() > 20) {
                messages.remove(0);
            }
        }

        public synchronized void setSummary(String summary) {
            this.summary = summary;
        }

        public synchronized void setFinancialSummary(FinancialSummary financialSummary) {
            this.financialSummary = financialSummary;
        }

        public synchronized void setCurrentProject(CurrentProject currentProject) {
            this.currentProject = currentProject;
        }
    }
}
