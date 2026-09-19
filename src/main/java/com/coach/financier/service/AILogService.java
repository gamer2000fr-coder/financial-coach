package com.coach.financier.service;

import com.coach.financier.model.LogEntry;
import com.coach.financier.model.AIModels;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Mémoire tampon des appels IA, exposée à la page Logs (polling).
 * Conservé en mémoire, plafonné au nombre {@link #MAX_ENTRIES} dernières traces.
 */
@Service
public class AILogService {
    private static final int MAX_ENTRIES = 500;

    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long counter = 0;

    public synchronized void log(String sessionId, String clientMessage,
                                 List<String> dataSent, int historyCount, long charCount,
                                 AIModels.AIStatus status, List<String> requestedData,
                                 String agent, String prompt, String debug, String answer) {
        log(sessionId, clientMessage, dataSent, historyCount, charCount, status, requestedData,
                agent, prompt, debug, answer, null);
    }

    /**
     * Variante avec l'état d'envoi du mail de notification (tracé SEULEMENT à la clôture de conversation) :
     * {@code SENT} / {@code PREPARED} / {@code MAIL_UNAVAILABLE} / {@code SEND_FAILED} / {@code AI_FAILED}.
     * {@code null} ou vide = trace sans notification à afficher.
     */
    public synchronized void log(String sessionId, String clientMessage,
                                 List<String> dataSent, int historyCount, long charCount,
                                 AIModels.AIStatus status, List<String> requestedData,
                                 String agent, String prompt, String debug, String answer,
                                 String mailStatus) {
        LogEntry entry = new LogEntry(
                ++counter,
                Instant.now().toString(),
                sessionId,
                clientMessage,
                dataSent == null ? List.of() : dataSent,
                historyCount,
                charCount,
                status == null ? "ANSWER" : status.name(),
                agent == null ? "" : agent,
                requestedData == null ? List.of() : requestedData,
                prompt == null ? "" : prompt,
                debug == null ? "" : debug,
                answer == null ? "" : answer,
                mailStatus == null ? "" : mailStatus
        );
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /** Prompt stocké pour une trace donnée, ou {@code null} si introuvable. */
    public synchronized String promptOf(long id) {
        for (LogEntry entry : entries) {
            if (entry.id() == id) {
                return entry.prompt();
            }
        }
        return null;
    }

    /** Réponse de l'IA stockée pour une trace donnée, ou {@code null} si introuvable. */
    public synchronized String answerOf(long id) {
        for (LogEntry entry : entries) {
            if (entry.id() == id) {
                return entry.answer();
            }
        }
        return null;
    }

    /** Dernières traces, de la plus récente à la plus ancienne. */
    public synchronized List<LogEntry> latest() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Nom de fichier sans extension, ex. "/data/transaction/transactions_2025_09.json" -> "transactions_2025_09". */
    public static String stem(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }
}
