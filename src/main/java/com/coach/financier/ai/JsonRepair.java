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
 * Stratégie, dans l'ordre : ① la réponse est déjà valide → inchangée ; ② les valeurs d'énumération
 * laissées SANS GUILLEMETS sont citées (« "confidence": HIGH » → « "confidence": "HIGH" », faute très
 * fréquente d'un petit modèle) ; ③ on referme la chaîne en cours et les objets/tableaux ouverts ; ④ sinon
 * on ABANDONNE le dernier membre incomplet (coupe au dernier séparateur de premier niveau) avant de refermer.
 * <p>
 * Convention : {@link #repair(String, ObjectMapper)} renvoie le texte d'ORIGINE quand rien n'est
 * réparable — l'appelant lève alors l'erreur de parsing habituelle (aucun échec masqué).
 */
final class JsonRepair {

    private JsonRepair() {
    }

    /**
     * Corrige les valeurs laissées SANS guillemets : le modèle écrit « "confidence": HIGH » (ou
     * « "projectType": VEHICLE ») alors que JSON n'autorise que {@code true}, {@code false}, {@code null}
     * et les nombres sans guillemets. Jackson n'offre AUCUNE option pour accepter cela : il faut réécrire
     * le texte avant l'analyse.
     * <p>
     * La réécriture ne touche QUE les valeurs de champ (après {@code :}) et JAMAIS l'intérieur d'une
     * chaîne (une raison contenant « : HIGH, » n'est pas modifiée) : le parcours suit l'état « dans une
     * chaîne » et les échappements caractère par caractère.
     *
     * @return le texte corrigé, ou le texte d'origine si aucune valeur n'était à citer
     */
    static String quoteBareFieldValues(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length() + 16);
        boolean inString = false;
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < content.length()) {
                    out.append(content.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c != ':') {
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
            int start = i;
            while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < content.length() && isBareValueChar(content.charAt(end))) {
                end++;
            }
            if (end > start) {
                String token = content.substring(start, end);
                if (isQuotableBareValue(token) && followedBySeparator(content, end)) {
                    out.append(content, i, start).append('"').append(token).append('"');
                    i = end;
                    continue;
                }
            }
            // Rien à citer : le reste est recopié tel quel par la boucle principale.
        }
        return out.toString();
    }

    private static boolean isBareValueChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+' || c == '.';
    }

    /**
     * Une valeur « nue » n'est citable que si ce n'est NI un littéral JSON ({@code true}, {@code false},
     * {@code null}) NI un nombre : citer un nombre le transformerait en chaîne et casserait les champs
     * numériques (montants, taux).
     */
    private static boolean isQuotableBareValue(String token) {
        if (token.equals("true") || token.equals("false") || token.equals("null")) {
            return false;
        }
        return !token.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    /** La valeur nue doit être TERMINÉE : « : HIGH, » ou « : HIGH } » — jamais « : HIGH WORD} », ambigu. */
    private static boolean followedBySeparator(String content, int from) {
        int k = from;
        while (k < content.length() && Character.isWhitespace(content.charAt(k))) {
            k++;
        }
        if (k >= content.length()) {
            return true;
        }
        char next = content.charAt(k);
        return next == ',' || next == '}' || next == ']';
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
        // ② valeurs d'énumération non citées : le reste de la réparation repart de ce texte corrigé,
        //    une valeur muette pouvant coexister avec une troncature.
        String base = quoteBareFieldValues(content);
        if (isValid(base, mapper)) {
            return base;
        }
        String closed = closeOpenStructures(base);
        if (isValid(closed, mapper)) {
            return closed;
        }
        int cut = lastTopLevelMemberCut(base);
        if (cut > 0) {
            String withoutTail = closeOpenStructures(base.substring(0, cut));
            if (isValid(withoutTail, mapper)) {
                return withoutTail;
            }
        }
        return content;
    }

    /** Le texte est-il un JSON analysable ? (exposé aux appelants qui doivent TRACER la réparation.) */
    static boolean isValidJson(String content, ObjectMapper mapper) {
        return isValid(content, mapper);
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
