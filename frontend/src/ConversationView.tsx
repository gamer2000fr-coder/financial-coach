import { useEffect, useState } from 'react'
import { ArrowLeft, BadgeCheck, History, Sparkles } from 'lucide-react'

import { fetchConversation } from './api'
import type { ConversationData } from './types'
import { renderMessageContent } from './messageFormat'

/**
 * VUE « HISTORIQUE DE LA CONVERSATION » ciblée par le lien du mail conseiller
 * (`#/conversation/<sessionId>`) : le conseiller relit les échanges client ↔ Coach avant de reprendre
 * contact. Lecture seule (aucune saisie, aucun envoi au client), l'URL ne contient que le sessionId.
 * <p>
 * L'historique vit en MÉMOIRE côté backend : si le serveur a redémarré depuis la clôture, la vue
 * l'indique honnêtement au lieu d'afficher une erreur technique.
 */
export default function ConversationView({ sessionId }: { sessionId: string }) {
  const [data, setData] = useState<ConversationData | null>(null)
  const [loading, setLoading] = useState(true)
  const [available, setAvailable] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const conversation = await fetchConversation(sessionId)
        setData(conversation)
        setAvailable(conversation !== null)
      } catch (e) {
        setAvailable(false)
        setError(e instanceof Error ? e.message : null)
      } finally {
        setLoading(false)
      }
    })()
  }, [sessionId])

  const messages = data?.messages ?? []
  const summary = data?.summary ?? null

  return (
    <div className="logs-shell">
      <header className="logs-header">
        <a href="#/advisor-feedback" className="logs-back">
          <ArrowLeft size={18} /> Retour au suivi
        </a>
        <h1>
          <History size={22} /> Historique de la conversation
        </h1>
        <span className="logs-status ok">
          <span className="status-dot" /> Session {sessionId}
        </span>
      </header>

      {!loading && available && (
        <div className="logs-toolbar">
          <span className="logs-count">
            {messages.length} message(s) · échanges enregistrés par le Coach IA
          </span>
          <a className="logs-back" href={`#/advisor-feedback/session/${encodeURIComponent(sessionId)}`}>
            <BadgeCheck size={16} /> Évaluer le suivi du Coach
          </a>
        </div>
      )}

      {loading && <div className="logs-empty">Chargement de la conversation…</div>}

      {!loading && !available && (
        <div className="logs-error">
          Cette conversation n'est plus disponible : l'historique des échanges est conservé en mémoire par le
          serveur et a pu être perdu (redémarrage du backend).
          {error ? ` (${error})` : ''}
        </div>
      )}

      {!loading && available && (
        <>
          {summary ? (
            <article className="logs-log">
              <div className="logs-log-head">
                <span className="logs-id">Synthèse</span>
              </div>
              <div className="logs-log-body">
                <p className="logs-message">{summary}</p>
              </div>
            </article>
          ) : null}

          <div className="messages-card">
            <div className="messages-scroll">
              {messages.length === 0 && (
                <div className="logs-empty">Aucun message enregistré pour cette conversation.</div>
              )}
              {messages.map((message, index) => (
                <div key={`conv-${index}`} className={`message-row ${message.role}`}>
                  {message.role === 'assistant' && (
                    <div className="avatar assistant-avatar"><Sparkles size={17} /></div>
                  )}
                  <div className={`message-bubble ${message.role}`}>
                    <div className="message-meta">
                      <span>{message.role === 'user' ? 'Client' : 'Coach IA'}</span>
                      {message.timestamp ? <span>{formatTime(message.timestamp)}</span> : null}
                    </div>
                    <div className="message-text">
                      {message.role === 'assistant'
                        ? renderMessageContent(`conv-${index}`, message.content)
                        : message.content}
                    </div>
                  </div>
                  {message.role === 'user' && <div className="avatar user-avatar">C</div>}
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/** Heure locale du message, ou rien si l'horodatage n'est pas exploitable. */
function formatTime(timestamp: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}
