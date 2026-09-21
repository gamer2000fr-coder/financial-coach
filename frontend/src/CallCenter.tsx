import { useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  BadgeCheck,
  ChevronDown,
  ChevronRight,
  History,
  PhoneCall,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from 'lucide-react'

import { fetchConversationDirectory, fetchConversationDirectoryDetail } from './api'
import type {
  DirectoryDetail,
  DirectoryList,
  DirectoryOrder,
  DirectorySort,
} from './types.directory'
import { renderMessageContent } from './messageFormat'

/** Périodes proposées (jours) ; `0` = tout l'historique disponible. */
const PERIODS: { days: number; label: string }[] = [
  { days: 5, label: '5 derniers jours' },
  { days: 10, label: '10 derniers jours' },
  { days: 30, label: '30 derniers jours' },
  { days: 0, label: 'Tout' },
]

const COLUMNS: { key: DirectorySort; label: string; sortable: boolean }[] = [
  { key: 'client', label: 'Client', sortable: true },
  { key: 'categorie', label: 'Catégorie', sortable: true },
  { key: 'titre', label: 'Conversation', sortable: true },
  { key: 'score', label: 'Score commercial', sortable: true },
  { key: 'date', label: 'Clôturée le', sortable: true },
]

/**
 * PAGE CENTRE D'APPELS (`#/centre-appels`) : annuaire des conversations clôturées pour l'équipe
 * commerciale. Tableau filtrable (période, catégorie, recherche) et triable (client, catégorie, titre,
 * score commercial, date), et pop-in de détail qui reprend la synthèse envoyée au conseiller par mail
 * (sans le brouillon destiné au client), avec le transcript repliable.
 */
export default function CallCenter() {
  const [days, setDays] = useState(10)
  const [category, setCategory] = useState('')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<DirectorySort>('date')
  const [order, setOrder] = useState<DirectoryOrder>('desc')
  const [list, setList] = useState<DirectoryList | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [openSession, setOpenSession] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchConversationDirectory({
        days,
        category: category || undefined,
        q: query.trim() || undefined,
        sort,
        order,
      })
      setList(data)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Chargement impossible')
    } finally {
      setLoading(false)
    }
  }, [days, category, query, sort, order])

  useEffect(() => {
    void load()
  }, [load])

  /** Clic sur un en-tête : bascule le sens du tri (ou passe sur la nouvelle colonne). */
  function toggleSort(key: DirectorySort) {
    if (sort === key) {
      setOrder((current) => (current === 'desc' ? 'asc' : 'desc'))
      return
    }
    setSort(key)
    setOrder(key === 'date' || key === 'score' ? 'desc' : 'asc')
  }

  const rows = list?.rows ?? []
  const categories = list?.categories ?? []
  const byPriority = list?.byPriority ?? {}

  return (
    <div className="logs-shell marketing-page">
      <header className="logs-header">
        <a className="logs-back" href="#/">
          <ArrowLeft size={16} /> Retour au chat
        </a>
        <h1>
          <PhoneCall size={20} /> Centre d&rsquo;appels — conversations
        </h1>
        <span className="logs-status ok">
          <span className="status-dot" /> {rows.length} conversation(s)
        </span>
      </header>

      <p className="mkt-subtitle">
        Toutes les conversations clôturées avec le Coach IA, avec leur <strong>score de sens commercial</strong>{' '}
        (priorisation des relances). Le détail affiche la même synthèse que le mail envoyé au conseiller —
        <strong> sans le brouillon destiné au client</strong> — et l&rsquo;intégralité des échanges.
      </p>

      <div className="mkt-toolbar">
        <div className="mkt-periods">
          {PERIODS.map((item) => (
            <button
              key={item.days}
              type="button"
              className={days === item.days ? 'mkt-period active' : 'mkt-period'}
              onClick={() => setDays(item.days)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <select
          aria-label="Filtrer par catégorie"
          value={category}
          onChange={(event) => setCategory(event.target.value)}
        >
          <option value="">Toutes les catégories</option>
          {categories.map((item) => (
            <option key={item.code} value={item.code}>
              {item.label} ({item.count})
            </option>
          ))}
        </select>
        <label className="cc-search">
          <Search size={15} />
          <input
            type="search"
            placeholder="Client, titre, projet, produit…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <button type="button" className="mkt-action" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={15} /> Rafraîchir
        </button>
      </div>

      {(Object.keys(byPriority).length > 0 || list) && (
        <div className="cc-kpis">
          <span className="mkt-kpi">
            <strong>{list?.total ?? 0}</strong> conversation(s)
          </span>
          {['VERY_HIGH', 'HIGH', 'MEDIUM', 'LOW'].map((priority) => (
            <span key={priority} className={`cc-kpi ${scoreClass(priority)}`}>
              {priorityLabel(priority)} : <strong>{byPriority[priority] ?? 0}</strong>
            </span>
          ))}
        </div>
      )}

      {loading && <div className="logs-empty">Chargement des conversations…</div>}
      {error && <div className="logs-error">{error}</div>}

      {!loading && !error && rows.length === 0 && (
        <div className="logs-empty">
          Aucune conversation clôturée sur cette période. Les conversations apparaissent ici après leur
          clôture (bouton « Terminer » du chat), y compris en mode démonstration.
        </div>
      )}

      {!loading && !error && rows.length > 0 && (
        <div className="mkt-table-scroll">
          <table className="mkt-table compact">
            <thead>
              <tr>
                {COLUMNS.map((column) => (
                  <th key={column.key}>
                    <button
                      type="button"
                      className="cc-sort"
                      onClick={() => toggleSort(column.key)}
                      title={`Trier par ${column.label.toLowerCase()}`}
                    >
                      {column.label}
                      {sort === column.key ? (order === 'asc' ? ' ▲' : ' ▼') : ''}
                    </button>
                  </th>
                ))}
                <th>Offres</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.sessionId}>
                  <td>{row.customerId ?? '—'}</td>
                  <td>
                    <span className="cc-chip">{row.categoryLabel}</span>
                  </td>
                  <td className="cc-title">{row.title}</td>
                  <td>
                    <span className={`cc-score ${scoreClass(row.priority)}`}>
                      {row.score === null ? '—' : `${row.score}/100`}
                    </span>
                    <span className="cc-score-label">{row.priorityLabel ?? ''}</span>
                  </td>
                  <td>{formatDateTime(row.closedAt)}</td>
                  <td>
                    {row.productCount > 0 ? row.topProduct ?? `${row.productCount} offre(s)` : '—'}
                  </td>
                  <td className="cc-actions">
                    <button type="button" className="mkt-action" onClick={() => setOpenSession(row.sessionId)}>
                      Voir le détail
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {openSession && <ConversationPopup sessionId={openSession} onClose={() => setOpenSession(null)} />}
    </div>
  )
}

/** Pop-in de détail : synthèse conseiller, score expliqué, actions et transcript repliable. */
function ConversationPopup({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<DirectoryDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showTranscript, setShowTranscript] = useState(false)
  const [showCriteria, setShowCriteria] = useState(false)

  useEffect(() => {
    void (async () => {
      setLoading(true)
      try {
        setDetail(await fetchConversationDirectoryDetail(sessionId))
        setError(null)
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Dossier indisponible')
      } finally {
        setLoading(false)
      }
    })()
  }, [sessionId])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const row = detail?.row
  const messages = detail?.transcript ?? []

  return (
    <div className="qlt-popup-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="cc-popup" onClick={(event) => event.stopPropagation()}>
        <div className="cc-popup-head">
          <div>
            <h2>{row?.title ?? 'Conversation'}</h2>
            <p className="mkt-subtitle">
              {row?.customerId ?? 'Client inconnu'} · {row?.categoryLabel ?? '—'} · clôturée le{' '}
              {formatDateTime(row?.closedAt ?? null)}
            </p>
          </div>
          <button type="button" className="icon-button" onClick={onClose} title="Fermer">
            <X size={18} />
          </button>
        </div>

        {loading && <div className="logs-empty">Chargement du dossier…</div>}
        {error && <div className="logs-error">{error}</div>}

        {!loading && !error && detail && (
          <div className="cc-popup-body">
            <div className="cc-score-box">
              <span className={`cc-score big ${scoreClass(row?.priority ?? null)}`}>
                {row?.score === null || row?.score === undefined ? '—' : `${row.score}/100`}
              </span>
              <div>
                <strong>Score de sens commercial — {row?.priorityLabel ?? 'non calculé'}</strong>
                <ul className="cc-list">
                  {(detail.scoreReasons ?? []).map((reason, index) => (
                    <li key={`reason-${index}`}>{reason}</li>
                  ))}
                  {(detail.scoreReasons ?? []).length === 0 && (
                    <li>Aucune explication enregistrée pour ce dossier.</li>
                  )}
                </ul>
                {(detail.scoreCriteria ?? []).length > 0 && (
                  <>
                    <button type="button" className="cc-toggle" onClick={() => setShowCriteria((v) => !v)}>
                      {showCriteria ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                      Critères mesurés ({detail.scoreCriteria.length})
                      {detail.scoreProposedByAi ? ' — score affiné par l’IA' : ' — score calculé par le système'}
                    </button>
                    {showCriteria && (
                      <ul className="cc-list small">
                        {detail.scoreCriteria.map((criterion, index) => (
                          <li key={`criterion-${index}`}>{criterion}</li>
                        ))}
                      </ul>
                    )}
                  </>
                )}
              </div>
              {detail.contactPhone ? (
                <a className="mkt-action cc-call" href={`tel:${detail.contactPhone}`}>
                  <PhoneCall size={15} /> Appeler le client ({detail.contactPhone})
                </a>
              ) : null}
            </div>

            {(detail.nextActions ?? []).length > 0 && (
              <section className="cc-section">
                <h3>Prochaines actions de suivi</h3>
                <ul className="cc-list">
                  {detail.nextActions.map((action, index) => (
                    <li key={`action-${index}`}>{action}</li>
                  ))}
                </ul>
              </section>
            )}

            {(detail.products ?? []).length > 0 && (
              <section className="cc-section">
                <h3>Offres d&rsquo;intérêt</h3>
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Offre</th>
                      <th>Famille</th>
                      <th>Intérêt</th>
                      <th>Pourquoi</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.products.map((product, index) => (
                      <tr key={`product-${index}`}>
                        <td>{product.name ?? '—'}</td>
                        <td>{product.category ?? '—'}</td>
                        <td>
                          <span className={`cc-chip interest-${(product.interestLevel ?? '').toLowerCase()}`}>
                            {product.interestLevel ?? '—'}
                          </span>
                        </td>
                        <td>{product.interestReason ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            )}

            <section className="cc-section">
              <h3>
                <Sparkles size={16} /> Synthèse envoyée au conseiller
              </h3>
              <p className="cc-subject">{detail.advisorSubject ?? '—'}</p>
              <div className="cc-mail">{renderMessageContent(`dossier-${sessionId}`, detail.advisorBody ?? '')}</div>
            </section>

            <section className="cc-section">
              <button type="button" className="cc-toggle" onClick={() => setShowTranscript((v) => !v)}>
                {showTranscript ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                <History size={15} /> Conversation complète ({messages.length} message(s))
              </button>
              {showTranscript && (
                <div className="cc-transcript">
                  {messages.length === 0 && (
                    <div className="logs-empty">Aucun échange enregistré avec ce dossier.</div>
                  )}
                  {messages.map((message, index) => (
                    <div key={`msg-${index}`} className={`message-row ${message.role ?? 'user'}`}>
                      {message.role === 'assistant' && (
                        <div className="avatar assistant-avatar">
                          <Sparkles size={15} />
                        </div>
                      )}
                      <div className={`message-bubble ${message.role ?? 'user'}`}>
                        <div className="message-meta">
                          <span>{message.role === 'user' ? 'Client' : 'Coach IA'}</span>
                          {message.timestamp ? <span>{formatTime(message.timestamp)}</span> : null}
                        </div>
                        <div className="message-text">
                          {message.role === 'assistant'
                            ? renderMessageContent(`dossier-${sessionId}-${index}`, message.content ?? '')
                            : message.content}
                        </div>
                      </div>
                      {message.role === 'user' && <div className="avatar user-avatar">C</div>}
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>
        )}

        <div className="cc-popup-foot">
          {detail?.feedbackUrl ? (
            <a className="logs-back" href={`#/advisor-feedback/session/${encodeURIComponent(sessionId)}`}>
              <BadgeCheck size={15} /> {detail.evaluated ? 'Avis conseiller enregistré' : 'Évaluer le suivi'}
            </a>
          ) : null}
          <button type="button" className="mkt-action" onClick={onClose}>
            Fermer
          </button>
        </div>
      </div>
    </div>
  )
}

/** Classe CSS du badge de score à partir de la priorité (ou du score). */
function scoreClass(priority: string | null): string {
  switch ((priority ?? '').toUpperCase()) {
    case 'VERY_HIGH':
      return 'very-high'
    case 'HIGH':
      return 'high'
    case 'MEDIUM':
      return 'medium'
    case 'LOW':
      return 'low'
    default:
      return 'unknown'
  }
}

function priorityLabel(priority: string): string {
  switch (priority) {
    case 'VERY_HIGH':
      return 'Très haute'
    case 'HIGH':
      return 'Haute'
    case 'MEDIUM':
      return 'Moyenne'
    default:
      return 'Faible'
  }
}

function formatDateTime(timestamp: string | null): string {
  if (!timestamp) return '—'
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return timestamp
  return date.toLocaleString('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatTime(timestamp: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}
