package com.coach.financier.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Réparation d'une réponse JSON TRONQUÉE par un modèle de langage.
 * <p>
 * Les fournisseurs coupent régulièrement une réponse en plein milieu (le modèle s'arrête sur une chaîne
 * ouverte, une limite de sortie est atteinte, la connexion est interrompue) : Jackson lève alors
 * {@code Unexpected end-of-input: expected close marker for Object} et l'itération entière échoue, alors
 * qu'une réponse amputée de sa fin reste largement exploitable.
 * <p>
 * Stratégie, dans l'ordre : ① la réponse est déjà valide → inchangée ; ② on referme la chaîne en cours et
 * les objets/tableaux ouverts ; ③ sinon on ABANDONNE le dernier membre incomplet (coupe au dernier
 * séparateur de premier niveau) avant de refermer.
 * <p>
 * Convention : {@link #repair(String, ObjectMapper)} renvoie le texte d'ORIGINE quand rien n'est
 * réparable — l'appelant lève alors l'erreur de parsing habituelle (aucun échec masqué).
 */
final class JsonRepair {

    private JsonRepair() {
    }

    /**
     * Réponse exploitable à partir d'une réponse éventuellement tronquée.
     *
     * @return le JSON réparé, ou le texte d'origine s'il n'est pas réparable
     */
    static String repair(String content, ObjectMapper mapper) {
        if (content == null || content.isBlank() || isValid(content, mapper)) {
            return content;
        }
        String closed = closeOpenStructures(content);
        if (isValid(closed, mapper)) {
            return closed;
        }
        int cut = lastTopLevelMemberCut(content);
        if (cut > 0) {
            String withoutTail = closeOpenStructures(content.substring(0, cut));
            if (isValid(withoutTail, mapper)) {
                return withoutTail;
            }
        }
        return content;
    }

    private static boolean isValid(String content, ObjectMapper mapper) {
        try {
            mapper.readTree(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Referme la chaîne en cours (si le modèle s'est arrêté dedans) puis les structures ouvertes, en
     * retirant un éventuel séparateur laissé en suspens (« {"a":1, ») qui rendrait le JSON invalide.
     */
    private static String closeOpenStructures(String content) {
        Deque<Character> closers = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                closers.push('}');
            } else if (c == '[') {
                closers.push(']');
            } else if ((c == '}' || c == ']') && !closers.isEmpty()) {
                closers.pop();
            }
        }
        if (closers.isEmpty() && !inString) {
            return content;
        }
        String text = inString ? content + '"' : content;
        StringBuilder builder = new StringBuilder(text);
        // Séparateur en suspens : sans cette coupe « {"a":1,} » resterait invalide pour un mapper strict.
        while (builder.length() > 0) {
            char last = builder.charAt(builder.length() - 1);
            if (last == ',' || last == ':' || Character.isWhitespace(last)) {
                builder.setLength(builder.length() - 1);
            } else {
                break;
            }
        }
        while (!closers.isEmpty()) {
            builder.append(closers.pop());
        }
        return builder.toString();
    }

    /**
     * Index juste après le dernier séparateur de PREMIER niveau (hors chaînes) : point de coupe sûr, où
     * tous les membres déjà écrits sont complets.
     *
     * @return -1 s'il n'existe aucun membre complet à conserver
     */
    private static int lastTopLevelMemberCut(String content) {
        int depth = 0;
        int cut = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ',' -> {
                    if (depth == 1) {
                        cut = i + 1;
                    }
                }
                default -> {
                    // Les autres caractères n'influencent pas la profondeur.
                }
            }
        }
        return cut;
    }
}
