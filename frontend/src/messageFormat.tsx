import type { ReactNode } from 'react'

import { requestAdvisorCallback } from './advisorCallback'

/**
 * Mise en forme des textes de l'IA — PARTAGÉE par la page coach et l'atelier (mêmes règles, même rendu).
 * <p>
 * Le modèle écrit du Markdown très simple : `**gras**`, `*italique*`, `` `code` `` et les liens au format
 * imposé par le prompt `[URL|nom|lien]` ou le jeton de rappel `[RAPPEL|nom]`. On ne génère JAMAIS de HTML brut
 * (aucun `dangerouslySetInnerHTML`) : tout est découpé en segments React, donc aucun risque d'injection.
 */
export function renderInline(text: string, key: string, depth = 0): ReactNode[] {
  // Découpe **gras**, *italique*, `code`, [URL|nom|url] et [RAPPEL|nom] en segments React (aucun HTML brut).
  const parts = text.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[URL\|[^|\]]+\|[^\]]+\]|\[RAPPEL\|[^|\]]+\])/gi)
  return parts.map((part, index) => {
    const k = `${key}-${index}`
    // Demande de rappel par un conseiller : jeton SANS URL (aucune adresse dans les données) ⇒ le clic ouvre
    // la pop-in d'information au lieu de naviguer.
    const callback = /^\[RAPPEL\|([^|\]]+)\]$/i.exec(part)
    if (callback) {
      return (
        <button
          key={k}
          type="button"
          className="callback-link"
          title="Un conseiller vous recontactera dans les plus brefs délais"
          onClick={() => requestAdvisorCallback()}
        >
          {callback[1].trim()}
        </button>
      )
    }
    // Lien au format imposé par le prompt : [URL|nom du lien|https://...]
    const link = /^\[URL\|([^|\]]+)\|([^\]]+)\]$/i.exec(part)
    if (link) {
      const label = link[1].trim()
      const url = link[2].trim()
      // Sécurité : http(s) pour les liens web, tel: pour le lien d'APPEL ajouté par le backend
      // (numéro de la configuration, jamais produit par l'IA) — sinon on n'affiche que le libellé.
      if (/^https?:\/\//i.test(url)) {
        return (
          <a key={k} href={url} target="_blank" rel="noopener noreferrer">{label}</a>
        )
      }
      if (/^tel:\+?[0-9 ().-]{6,20}$/i.test(url)) {
        return (
          <a key={k} className="phone-link" href={url}>{label}</a>
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
 * Contenu d'une bulle : chaque ligne est mise en forme, les retours à la ligne sont conservés, et les
 * TABLEAUX Markdown (le Coach en produit dès qu'il chiffre plusieurs durées) sont rendus comme de vrais
 * tableaux — sinon les barres verticales s'afficheraient en texte brut.
 * {@code id} sert de base aux clés React : il doit être unique pour le message affiché.
 */
export function renderMessageContent(id: string, content: string): ReactNode[] {
  const lines = content.split('\n')
  const blocks: ReactNode[] = []
  let index = 0
  while (index < lines.length) {
    const end = tableEndIndex(lines, index)
    if (end >= index) {
      blocks.push(renderTable(lines, index, end, `${id}-table${index}`))
      index = end + 1
      continue
    }
    blocks.push(
      <span key={`${id}-${index}`}>
        {renderInline(lines[index], `${id}-${index}`)}
        {index < lines.length - 1 && <br />}
      </span>,
    )
    index += 1
  }
  return blocks
}

/** Une ligne de tableau : au moins deux cellules (donc au moins deux barres verticales). */
function isTableLine(line: string | undefined): boolean {
  if (!line) return false
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.split('|').length >= 4
}

/** Ligne de séparation Markdown (`|---|:--:|`). */
function isSeparatorLine(line: string | undefined): boolean {
  if (!line) return false
  const trimmed = line.trim()
  return trimmed.includes('-') && /^\|[\s:|-]+\|?$/.test(trimmed)
}

/** Cellules d'une ligne de tableau (barres de début/fin retirées). */
function parseCells(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

/**
 * Fin (incluse) du bloc tableau commençant à {@code start}, ou {@code start - 1} si la ligne n'ouvre pas
 * un tableau (il faut au moins deux lignes : un en-tête et une ligne de données).
 */
function tableEndIndex(lines: string[], start: number): number {
  if (!isTableLine(lines[start])) return start - 1
  let end = start
  while (end + 1 < lines.length && isTableLine(lines[end + 1])) end += 1
  return end > start ? end : start - 1
}

/** Tableau Markdown → tableau HTML (les cellules acceptent gras, code et liens). */
function renderTable(lines: string[], start: number, end: number, key: string): ReactNode {
  const header = parseCells(lines[start])
  let firstRow = start + 1
  if (firstRow <= end && isSeparatorLine(lines[firstRow])) firstRow += 1
  const rows: string[][] = []
  for (let index = firstRow; index <= end; index += 1) {
    rows.push(parseCells(lines[index]))
  }
  const numeric = numericColumns(rows)
  return (
    <div key={key} className="md-table-wrap">
      <table className="md-table">
        <thead>
          <tr>
            {header.map((cell, column) => (
              <th key={`${key}-h${column}`} className={cellClassName(cell, numeric[column])}>
                {renderInline(cell, `${key}-h${column}`)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={`${key}-r${rowIndex}`}>
              {row.map((cell, column) => (
                <td key={`${key}-r${rowIndex}c${column}`} className={cellClassName(cell, numeric[column])}>
                  {renderInline(cell, `${key}-r${rowIndex}c${column}`)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Colonnes NUMÉRIQUES : dès qu'une cellule de données porte un montant ou un pourcentage, toute la colonne
 * est alignée à droite — en-tête compris. Sans cela, « Montant total dû » resterait collé à gauche
 * au-dessus de chiffres alignés à droite, ce qui rend le tableau difficile à lire.
 */
function numericColumns(rows: string[][]): boolean[] {
  const width = rows.reduce((max, row) => Math.max(max, row.length), 0)
  const flags: boolean[] = []
  for (let column = 0; column < width; column += 1) {
    flags.push(rows.some((row) => isNumericCell(row[column] ?? '')))
  }
  return flags
}

/** Montant ou pourcentage court : colonne de chiffres (les phrases contenant « € » ne comptent pas). */
function isNumericCell(cell: string): boolean {
  return /€|%/.test(cell) && cell.length <= 16
}

/** Classe d'une cellule : `md-num` si sa colonne est numérique (alignement à droite, chiffres tabulaires). */
function cellClassName(cell: string, numericColumn?: boolean): string | undefined {
  if (numericColumn) return 'md-num'
  return cell.length <= 16 && isNumericCell(cell) ? 'md-num' : undefined
}

/** Version TEXTE BRUT d'un message (synthèse vocale) : le Markdown n'est jamais lu à voix haute. */
export function stripMarkdown(text: string): string {
  return text
    .split('\n')
    .filter((line) => !isSeparatorLine(line)) // pas de « |---|---| » lu à voix haute
    .map((line) => (isTableLine(line) ? parseCells(line).join(', ') : line)) // tableau -> valeurs à la suite
    .join('\n')
    .replace(/\[URL\|([^|\]]+)\|[^\]]+\]/gi, '$1') // lien [URL|nom|url] -> nom (pour la synthèse vocale)
    .replace(/\[RAPPEL\|([^|\]]+)\]/gi, '$1') // jeton de rappel -> libellé
    .replace(/\*\*/g, '')
    .replace(/\*/g, '')
    .replace(/`/g, '')
    .replace(/^[#]+\s*/gm, '')
    .trim()
}
