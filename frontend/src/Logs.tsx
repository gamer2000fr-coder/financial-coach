import { useEffect, useState } from 'react'
import { ArrowLeft, Bot, Braces, Database, Eye, FileSearch, History, Mail, MessageSquare, Trash2, Type } from 'lucide-react'
import { clearLogs, fetchConversation, fetchLogAnswer, fetchLogPrompt, fetchLogs } from './api'
import type { AiLog, ConversationData } from './types'

/**
 * État d'envoi du mail de notification au conseiller (trace de clôture « dossier de suivi »).
 * Le statut vient du backend (bloc [SUIVI] de la clôture) : SENT = envoyé sans erreur ;
 * PREPARED = envoi volontairement désactivé (send=false, dry-run) ; les autres = non envoyé.
 */
const MAIL_STATUS_LABELS: Record<string, { label: string; kind: 'ok' | 'warn' | 'ko' }> = {
  SENT: { label: 'Mail conseiller : envoyé sans erreur', kind: 'ok' },
  PREPARED: { label: 'Mail conseiller : non envoyé (mode préparation)', kind: 'warn' },
  MAIL_UNAVAILABLE: { label: 'Mail conseiller : NON envoyé (service mail indisponible)', kind: 'ko' },
  SEND_FAILED: { label: 'Mail conseiller : NON envoyé (échec de l’envoi)', kind: 'ko' },
  AI_FAILED: { label: 'Mail conseiller : NON envoyé (échec de la synthèse IA)', kind: 'ko' },
}

function mailStatusInfo(status: string | undefined) {
  if (!status) return null
  const key = status.toUpperCase()
  return MAIL_STATUS_LABELS[key] ?? { label: `Mail conseiller : ${status}`, kind: 'warn' as const }
}

export default function Logs() {
  const [logs, setLogs] = useState<AiLog[]>([])
  const [error, setError] = useState<string | null>(null)
  const [connected, setConnected] = useState(false)
  const [openPrompts, setOpenPrompts] = useState<Set<number>>(new Set())
  const [openDebug, setOpenDebug] = useState<Set<number>>(new Set())
  const [openAnswers, setOpenAnswers] = useState<Set<number>>(new Set())
  const [openHistory, setOpenHistory] = useState<Set<number>>(new Set())
  const [prompts, setPrompts] = useState<Record<number, string>>({})
  const [answers, setAnswers] = useState<Record<number, string>>({})
  const [histories, setHistories] = useState<Record<number, ConversationData>>({})
  const [loadingPromptId, setLoadingPromptId] = useState<number | null>(null)
  const [loadingAnswerId, setLoadingAnswerId] = useState<number | null>(null)
  const [loadingHistoryId, setLoadingHistoryId] = useState<number | null>(null)

  useEffect(() => {
    let active = true
    async function refresh() {
      try {
        const data = await fetchLogs()
        if (!active) return
        setLogs(data)
        setError(null)
        setConnected(true)
      } catch (err) {
        if (!active) return
        setError(err instanceof Error ? err.message : 'Erreur de connexion au backend.')
        setConnected(false)
      }
    }
    refresh()
    const timer = setInterval(refresh, 2000)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [])

  async function handleClear() {
    try {
      await clearLogs()
      setLogs([])
      setPrompts({})
      setAnswers({})
      setHistories({})
      setOpenPrompts(new Set())
      setOpenDebug(new Set())
      setOpenAnswers(new Set())
      setOpenHistory(new Set())
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur lors de la suppression des logs.')
    }
  }

  function toggleDebug(id: number) {
    setOpenDebug((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function togglePrompt(id: number) {
    if (openPrompts.has(id)) {
      setOpenPrompts((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      return
    }
    setOpenPrompts((prev) => new Set(prev).add(id))
    if (prompts[id] === undefined) {
      setLoadingPromptId(id)
      try {
        const prompt = await fetchLogPrompt(id)
        setPrompts((prev) => ({ ...prev, [id]: prompt }))
      } catch (err) {
        const detail = err instanceof Error ? err.message : String(err)
        setPrompts((prev) => ({ ...prev, [id]: `(Impossible de charger le prompt pour cette trace. ${detail})` }))
      } finally {
        setLoadingPromptId(null)
      }
    }
  }

  async function toggleAnswer(id: number) {
    if (openAnswers.has(id)) {
      setOpenAnswers((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      return
    }
    setOpenAnswers((prev) => new Set(prev).add(id))
    if (answers[id] === undefined) {
      setLoadingAnswerId(id)
      try {
        const answer = await fetchLogAnswer(id)
        setAnswers((prev) => ({ ...prev, [id]: answer }))
      } catch (err) {
        const detail = err instanceof Error ? err.message : String(err)
        setAnswers((prev) => ({ ...prev, [id]: `(Impossible de charger la réponse pour cette trace. ${detail})` }))
      } finally {
        setLoadingAnswerId(null)
      }
    }
  }

  async function toggleHistory(id: number, sessionId: string) {
    if (openHistory.has(id)) {
      setOpenHistory((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      return
    }
    setOpenHistory((prev) => new Set(prev).add(id))
    if (histories[id] === undefined) {
      setLoadingHistoryId(id)
      try {
        const data = await fetchConversation(sessionId)
        setHistories((prev) => ({ ...prev, [id]: data }))
      } catch (err) {
        const detail = err instanceof Error ? err.message : String(err)
        setHistories((prev) => ({
          ...prev,
          [id]: { sessionId, messages: [], summary: `(Impossible de charger l'historique. ${detail})` },
        }))
      } finally {
        setLoadingHistoryId(null)
      }
    }
  }

  return (
    <div className="logs-shell">
      <header className="logs-header">
        <a href="#/" className="logs-back"><ArrowLeft size={18} /> Retour au chat</a>
        <h1><Bot size={22} /> Logs des appels IA</h1>
        <span className={`logs-status ${connected ? 'ok' : 'ko'}`}>
          <span className="status-dot" /> {connected ? 'Backend connecté' : 'Hors ligne'}
        </span>
      </header>

      {error && <div className="logs-error">Impossible de charger les logs : {error}</div>}

      <div className="logs-toolbar">
        <span className="logs-count">{logs.length} trace(s) · actualisation toutes les 2 s</span>
        <button className="logs-clear" type="button" onClick={handleClear} disabled={logs.length === 0}>
          <Trash2 size={16} /> Vider les logs
        </button>
      </div>

      <div className="logs-list">
        {!error && logs.length === 0 && (
          <div className="logs-empty">Aucun appel IA pour le moment. Envoyez un message dans le chat.</div>
        )}

        {logs.map((log) => (
          <article key={log.id} className="logs-log">
            <div className="logs-log-head">
              <span className="logs-id">#{log.id}</span>
              <span className={`logs-status-badge ${log.status.toLowerCase()}`}>{log.status}</span>
              {log.agent ? (
                <span className="logs-status-badge agent" title="Agent IA utilisé">
                  <Bot size={12} /> {log.agent}
                </span>
              ) : null}
              {mailStatusInfo(log.mailStatus) ? (
                <span
                  className={`logs-status-badge mail-${mailStatusInfo(log.mailStatus)!.kind}`}
                  title="Envoi du mail de notification au conseiller (dossier de suivi)"
                >
                  <Mail size={12} /> {mailStatusInfo(log.mailStatus)!.label}
                </span>
              ) : null}
              <time>{new Date(log.timestamp).toLocaleString('fr-FR')}</time>
              <button
                className="logs-prompt-btn"
                type="button"
                onClick={() => togglePrompt(log.id)}
                disabled={loadingPromptId === log.id}
                title="Afficher le prompt envoyé à l'IA (sans les données jointes)"
              >
                <Eye size={14} />
                {openPrompts.has(log.id) ? 'Masquer le prompt' : 'Voir le prompt'}
              </button>
              {log.debug ? (
                <button
                  className="logs-prompt-btn"
                  type="button"
                  onClick={() => toggleDebug(log.id)}
                  title="Afficher le détail INTENT / PRODUCT_FILTER / COACH"
                >
                  <Braces size={14} />
                  {openDebug.has(log.id) ? 'Masquer le filtrage' : 'Voir le filtrage'}
                </button>
              ) : null}
              <button
                className="logs-prompt-btn"
                type="button"
                onClick={() => toggleAnswer(log.id)}
                disabled={loadingAnswerId === log.id}
                title="Afficher la réponse envoyée par l'IA"
              >
                <MessageSquare size={14} />
                {openAnswers.has(log.id) ? 'Masquer la réponse' : 'Voir la réponse'}
              </button>
              <button
                className="logs-prompt-btn"
                type="button"
                onClick={() => toggleHistory(log.id, log.sessionId)}
                disabled={loadingHistoryId === log.id}
                title="Afficher l'historique de la conversation avec le client"
              >
                <History size={14} />
                {openHistory.has(log.id) ? 'Masquer l\'historique' : 'Historique'}
              </button>
              <span className="logs-session">{log.sessionId}</span>
            </div>

            <div className="logs-log-body">
              <p className="logs-message"><strong>Client&nbsp;:</strong> {log.clientMessage}</p>
              <div className="logs-line">
                <History size={15} /> <strong>Historique&nbsp;:</strong> {log.historyCount} message(s)
              </div>
              <div className="logs-line">
                <Type size={15} /> <strong>Caractères envoyés&nbsp;:</strong> {log.charCount.toLocaleString('fr-FR')}
              </div>

              <div className="logs-sub"><Database size={15} /> Données envoyées ({log.dataSent.length})</div>
              <div className="chips">
                {log.dataSent.length === 0 && <span className="chip empty">aucune</span>}
                {log.dataSent.map((d, i) => <span key={i} className="chip">{d}</span>)}
              </div>

              <div className="logs-sub"><FileSearch size={15} /> Données demandées par l'IA</div>
              <div className="chips">
                {log.requestedData.length === 0 && <span className="chip empty">aucune</span>}
                {log.requestedData.map((r, i) => <span key={i} className="chip accent">{r}</span>)}
              </div>
            </div>

            {openPrompts.has(log.id) && (
              <div className="logs-prompt-box">
                {loadingPromptId === log.id && !prompts[log.id] ? (
                  <div className="logs-prompt-loading">Chargement du prompt…</div>
                ) : (
                  <pre className="logs-prompt">{prompts[log.id] ?? ''}</pre>
                )}
              </div>
            )}

            {openDebug.has(log.id) && log.debug && (
              <div className="logs-prompt-box">
                <div className="logs-sub">Classification &amp; filtrage métier</div>
                <pre className="logs-prompt">{log.debug}</pre>
              </div>
            )}

            {openAnswers.has(log.id) && (
              <div className="logs-prompt-box">
                <div className="logs-sub">Réponse de l'IA</div>
                {loadingAnswerId === log.id && answers[log.id] === undefined ? (
                  <div className="logs-prompt-loading">Chargement de la réponse…</div>
                ) : (
                  <pre className="logs-prompt">{answers[log.id] ?? ''}</pre>
                )}
              </div>
            )}

            {openHistory.has(log.id) && (
              <div className="logs-prompt-box">
                <div className="logs-sub">Historique de la conversation avec le client</div>
                {loadingHistoryId === log.id && histories[log.id] === undefined ? (
                  <div className="logs-prompt-loading">Chargement de l'historique…</div>
                ) : (
                  <div className="history-list">
                    {histories[log.id]?.summary ? (
                      <div className="history-summary">Résumé : {histories[log.id]?.summary}</div>
                    ) : null}
                    {histories[log.id]?.messages.length === 0 && (
                      <span className="chip empty">aucun message</span>
                    )}
                    {histories[log.id]?.messages.map((msg, index) => (
                      <div key={index} className={`history-line ${msg.role}`}>
                        <span className="history-role">{msg.role === 'user' ? 'Client' : 'Coach'}</span>
                        <span className="history-content">{msg.content}</span>
                        {msg.timestamp && (
                          <span className="history-time">
                            {new Date(msg.timestamp).toLocaleString('fr-FR')}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </article>
        ))}
      </div>
    </div>
  )
}
