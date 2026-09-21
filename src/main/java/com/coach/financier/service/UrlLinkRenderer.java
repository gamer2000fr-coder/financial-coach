package com.coach.financier.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convertit et sécurise le format de lien interne imposé au LLM : {@code [URL|nom|url]}.
 * <ul>
 *   <li>{@link #toText(String)} → {@code nom : url} (pièce jointe .txt) ;</li>
 *   <li>{@link #toHtml(String)} → {@code <a href="url">nom</a>} (email HTML / .eml / .html) ;</li>
 *   <li>{@link #sanitize(String, Set)} → neutralise toute URL non fournie par le système
 *       (anti-invention) en ne conservant que le libellé.</li>
 * </ul>
 * Toutes les valeurs sont échappées : aucune injection HTML possible depuis le LLM.
 */
public final class UrlLinkRenderer {
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[URL\\|([^|\\]]+)\\|([^\\]]*)\\]");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    /** Lien d'appel : {@code tel:} suivi d'un numéro plausible (jamais un caractère d'attribut HTML). */
    private static final Pattern TEL_PATTERN = Pattern.compile("^tel:\\+?[0-9 ().\\-]{6,20}$");

    private UrlLinkRenderer() {}

    /** Résultat d'un nettoyage : texte corrigé + URLs rejetées (non fournies par le système). */
    public record SanitizeResult(String text, List<String> violations) {}

    /** Toutes les URLs présentes au format {@code [URL|nom|url]} (y compris invalides). */
    public static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        Matcher matcher = LINK_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group(2).trim());
        }
        return urls;
    }

    /**
     * Neutralise les liens dont l'URL n'est pas fournie par le système : le lien devient
     * son seul libellé. Renvoie aussi la liste des URLs rejetées (pour avertir le conseiller).
     */
    public static SanitizeResult sanitize(String text, Set<String> allowedUrls) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult(text == null ? "" : text, List.of());
        }
        Set<String> allowed = normalizeAll(allowedUrls);
        List<String> violations = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            String url = matcher.group(2).trim();
            boolean ok = isHttpUrl(url) && allowed.contains(url);
            if (ok) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                violations.add(url.isBlank() ? label : url);
                matcher.appendReplacement(out, Matcher.quoteReplacement(label));
            }
        }
        matcher.appendTail(out);
        return new SanitizeResult(out.toString(), violations);
    }

    /**
     * Rendu texte brut : {@code [URL|nom|url]} → {@code nom : url} (numéro seul pour un lien d'appel) et
     * suppression du gras Markdown ({@code **texte**} → {@code texte}), pour que le mail en texte brut ne
     * contienne jamais d'astérisques.
     */
    public static String toText(String text) {
        String converted = replaceLinks(text, (label, url) -> isHttpUrl(url) ? label + " : " + url
                : isTelUrl(url) ? label + " : " + telNumber(url)
                : label);
        return converted == null ? "" : BOLD_PATTERN.matcher(converted).replaceAll("$1");
    }

    /** Rendu HTML : échappement complet puis liens cliquables (http/https et tel: uniquement). */
    public static String toHtml(String text) {
        if (text == null) {
            return "";
        }
        String escaped = replaceLinks(escapeHtml(text), (label, url) ->
                isHttpUrl(url)
                        ? "<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener\">" + label + "</a>"
                        : isTelUrl(url)
                        ? "<a href=\"" + url + "\">" + label + "</a>"
                        : label);
        Matcher bold = BOLD_PATTERN.matcher(escaped);
        StringBuilder bolded = new StringBuilder();
        while (bold.find()) {
            bold.appendReplacement(bolded, Matcher.quoteReplacement("<strong>" + bold.group(1) + "</strong>"));
        }
        bold.appendTail(bolded);
        return bolded.toString().replace("\r\n", "\n").replace("\n", "<br>\n");
    }

    public static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    /**
     * Lien d'APPEL téléphonique ({@code tel:0612345678}). Il n'est jamais produit par le LLM (le contrôle
     * anti-invention ne reconnaît que les URL http(s)) : il est ajouté par le BACKEND à partir de la
     * configuration. Le format est strictement vérifié (chiffres, espaces, {@code + . ( ) -}) pour qu'aucun
     * caractère dangereux ne puisse entrer dans un attribut {@code href}.
     */
    public static boolean isTelUrl(String url) {
        if (url == null) {
            return false;
        }
        return TEL_PATTERN.matcher(url.trim()).matches();
    }

    /** Numéro affiché (sans le préfixe {@code tel:}) pour le rendu texte. */
    public static String telNumber(String url) {
        return url == null ? "" : url.trim().substring("tel:".length()).trim();
    }

    public static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private interface LinkReplacer {
        String apply(String label, String url);
    }

    private static String replaceLinks(String text, LinkReplacer replacer) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = LINK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            String url = matcher.group(2).trim();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacer.apply(label, url)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Set<String> normalizeAll(Set<String> urls) {
        Set<String> result = new LinkedHashSet<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.isBlank()) {
                    result.add(url.trim());
                }
            }
        }
        return result;
    }
}
