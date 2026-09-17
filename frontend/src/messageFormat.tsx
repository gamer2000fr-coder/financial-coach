import type { ReactNode } from 'react'

/**
 * Mise en forme des textes de l'IA — PARTAGÉE par la page coach et l'atelier (mêmes règles, même rendu).
 * <p>
 * Le modèle écrit du Markdown très simple : `**gras**`, `*italique*`, `` `code` `` et les liens au format
 * imposé par le prompt `[URL|nom|lien]`. On ne génère JAMAIS de HTML brut (aucun `dangerouslySetInnerHTML`) :
 * tout est découpé en segments React, donc aucun risque d'injection.
 */
export function renderInline(text: string, key: string, depth = 0): ReactNode[] {
  // Découpe **gras**, *italique*, `code` et [URL|nom|url] en segments React (aucun HTML brut => pas de XSS).
  const parts = text.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[URL\|[^|\]]+\|[^\]]+\])/gi)
  return parts.map((part, index) => {
    const k = `${key}-${index}`
    // Lien au format imposé par le prompt : [URL|nom du lien|https://...]
    const link = /^\[URL\|([^|\]]+)\|([^\]]+)\]$/i.exec(part)
    if (link) {
      const label = link[1].trim()
      const url = link[2].trim()
      // Sécurité : on n'accepte que http(s) — sinon on n'affiche que le libellé.
      if (/^https?:\/\//i.test(url)) {
        return (
          <a key={k} href={url} target="_blank" rel="noopener noreferrer">{label}</a>
        )
      }
      return label
    }
    // Rendu récursif pour que les liens restent cliquables même dans du gras/italique.
    if (depth < 3 && part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={k}>{renderInline(part.slice(2, -2), k, depth + 1)}</strong>
    }
    if (depth < 3 && part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return <code key={k}>{part.slice(1, -1)}</code>
    }
    if (depth < 3 && part.startsWith('*') && part.endsWith('*') && part.length > 2) {
      return <em key={k}>{renderInline(part.slice(1, -1), k, depth + 1)}</em>
    }
    return part
  })
}

/**
 * Contenu d'une bulle : chaque ligne est mise en forme, les retours à la ligne sont conservés.
 * {@code id} sert de base aux clés React : il doit être unique pour le message affiché.
 */
export function renderMessageContent(id: string, content: string): ReactNode[] {
  const lines = content.split('\n')
  return lines.map((line, index) => (
    <span key={`${id}-${index}`}>
      {renderInline(line, `${id}-${index}`)}
      {index < lines.length - 1 && <br />}
    </span>
  ))
}

/** Version TEXTE BRUT d'un message (synthèse vocale) : le Markdown n'est jamais lu à voix haute. */
export function stripMarkdown(text: string): string {
  return text
    .replace(/\[URL\|([^|\]]+)\|[^\]]+\]/gi, '$1') // lien [URL|nom|url] -> nom (pour la synthèse vocale)
    .replace(/\*\*/g, '')
    .replace(/\*/g, '')
    .replace(/`/g, '')
    .replace(/^[#]+\s*/gm, '')
    .trim()
}
