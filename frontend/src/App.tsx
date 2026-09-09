import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  ArrowUpRight,
  Bot,
  ChevronDown,
  CircleAlert,
  FileText,
  Landmark,
  Menu,
  MessageCircle,
  Mic,
  MicOff,
  Plus,
  Send,
  Settings,
  Sparkles,
  TrendingUp,
  Volume2,
  VolumeX,
  Wallet,
  X,
} from 'lucide-react'
import { API_BASE_URL, fetchFinancialSummary, sendChat } from './api'
import type { AIProvider, ChatMessage, FinancialSummary } from './types'

const SESSION_STORAGE_KEY = 'financial-coach-session-id'
const HISTORY_STORAGE_KEY = 'financial-coach-chat-history'
const PROVIDER_STORAGE_KEY = 'financial-coach-provider'
const GUARD_STORAGE_KEY = 'financial-coach-guard'
const ADVANCED_STORAGE_KEY = 'financial-coach-advanced'
const VOICE_STORAGE_KEY = 'financial-coach-voice'
const AUDIO_STORAGE_KEY = 'financial-coach-audio'

const suggestions = [
  'Est-ce que je peux acheter un ordinateur à 1 500 € ?',
  'Analyse mes dépenses des 3 derniers mois.',
  'Combien puis-je épargner chaque mois ?',
  'Est-ce que mon niveau de crédit est raisonnable ?',
]

const providerLabels: Record<AIProvider, string> = {
  GPT: 'GPT / OpenAI',
  DEEPSEEK: 'DeepSeek',
  MOCK: 'Mode démo',
}

function newSessionId(): string {
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function newMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Contexte non sécurisé (HTTP via IP publique) : crypto.randomUUID indisponible.
  return `msg-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function renderInline(text: string, key: string): ReactNode[] {
  // Découpe **gras**, *italique* et `code` en segments React (aucun HTML brut => pas de XSS).
  const parts = text.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)/g)
  return parts.map((part, index) => {
    const k = `${key}-${index}`
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={k}>{part.slice(2, -2)}</strong>
    }
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return <code key={k}>{part.slice(1, -1)}</code>
    }
    if (part.startsWith('*') && part.endsWith('*') && part.length > 2) {
      return <em key={k}>{part.slice(1, -1)}</em>
    }
    return part
  })
}

function renderMessageContent(id: string, content: string): ReactNode[] {
  const lines = content.split('\n')
  return lines.map((line, index) => (
    <span key={`${id}-${index}`}>
      {renderInline(line, `${id}-${index}`)}
      {index < lines.length - 1 && <br />}
    </span>
  ))
}

function stripMarkdown(text: string): string {
  return text
    .replace(/\*\*/g, '')
    .replace(/\*/g, '')
    .replace(/`/g, '')
    .replace(/^[#]+\s*/gm, '')
    .trim()
}

function welcomeMessages(): ChatMessage[] {
  return [
    {
      id: 'welcome',
      role: 'assistant',
      content:
        'Bonjour ! Je suis votre coach financier. Posez-moi une question sur votre budget, votre épargne, vos crédits ou un projet d’achat. Je m’appuie sur vos données bancaires de démonstration pour répondre.',
      timestamp: new Date().toISOString(),
      provider: 'MOCK',
    },
  ]
}

function loadInitialMessages(): ChatMessage[] {
  try {
    const raw = localStorage.getItem(HISTORY_STORAGE_KEY)
    if (raw) return JSON.parse(raw) as ChatMessage[]
  } catch {
    // Ignore malformed local history.
  }
  return welcomeMessages()
}

function formatMoney(value: number): string {
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatMoneyCents(value: number): string {
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

function formatRatio(value: number): string {
  return `${(value * 100).toFixed(1)} %`
}

function formatPercent(value: number): string {
  return `${(value * 100).toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} %`
}

function formatPeriod(start: string, end: string): string {
  const from = new Date(`${start}T00:00:00`)
  const to = new Date(`${end}T00:00:00`)
  return `${from.toLocaleDateString('fr-FR')} → ${to.toLocaleDateString('fr-FR')}`
}

function App() {
  const [provider, setProvider] = useState<AIProvider>(() => {
    const stored = localStorage.getItem(PROVIDER_STORAGE_KEY)
    return stored === 'GPT' || stored === 'DEEPSEEK' || stored === 'MOCK' ? stored : 'DEEPSEEK'
  })
  const [sessionId, setSessionId] = useState(() => localStorage.getItem(SESSION_STORAGE_KEY) ?? newSessionId())
  const [messages, setMessages] = useState<ChatMessage[]>(loadInitialMessages)
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [summary, setSummary] = useState<FinancialSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [guardEnabled, setGuardEnabled] = useState(() => localStorage.getItem(GUARD_STORAGE_KEY) !== 'false')
  const [advanced, setAdvanced] = useState(() => localStorage.getItem(ADVANCED_STORAGE_KEY) === 'true')
  const [listening, setListening] = useState(false)
  const [speechSupported] = useState<boolean>(
    () => typeof window !== 'undefined'
      && Boolean((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition),
  )
  const recognitionRef = useRef<any>(null)
  const listeningRef = useRef(false)
  const manualStopRef = useRef(false)
  const finalTextRef = useRef('')
  const [voiceEnabled, setVoiceEnabled] = useState(() => localStorage.getItem(VOICE_STORAGE_KEY) !== 'false')
  const [audioEnabled, setAudioEnabled] = useState(() => localStorage.getItem(AUDIO_STORAGE_KEY) === 'true')
  const [ttsSupported] = useState<boolean>(() => typeof window !== 'undefined'
    && 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window)
  const [speakingId, setSpeakingId] = useState<string | null>(null)
  const speakingIdRef = useRef<string | null>(null)

  useEffect(() => {
    localStorage.setItem(SESSION_STORAGE_KEY, sessionId)
  }, [sessionId])

  useEffect(() => {
    localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(messages.slice(-60)))
  }, [messages])

  useEffect(() => {
    localStorage.setItem(PROVIDER_STORAGE_KEY, provider)
  }, [provider])

  useEffect(() => {
    localStorage.setItem(GUARD_STORAGE_KEY, String(guardEnabled))
  }, [guardEnabled])

  useEffect(() => {
    localStorage.setItem(ADVANCED_STORAGE_KEY, advanced ? 'true' : 'false')
  }, [advanced])

  useEffect(() => {
    localStorage.setItem(VOICE_STORAGE_KEY, voiceEnabled ? 'true' : 'false')
  }, [voiceEnabled])

  useEffect(() => {
    localStorage.setItem(AUDIO_STORAGE_KEY, audioEnabled ? 'true' : 'false')
  }, [audioEnabled])

  // Audio désactivé : on coupe toute écoute micro et toute lecture vocale en cours.
  useEffect(() => {
    if (!audioEnabled) {
      listeningRef.current = false
      setListening(false)
      try {
        recognitionRef.current?.stop?.()
      } catch {
        // ignore
      }
      stopSpeaking()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioEnabled])

  useEffect(() => {
    let active = true
    fetchFinancialSummary()
      .then((data) => {
        if (active) setSummary(data)
      })
      .catch(() => {
        if (active) setError('Le backend Spring Boot n’est pas accessible. Vérifiez qu’il tourne sur le port 9797.')
      })
    return () => {
      active = false
    }
  }, [])

  function ensureRecognition(): any {
    if (recognitionRef.current) return recognitionRef.current
    const SpeechRecognitionCtor =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    if (!SpeechRecognitionCtor) return null
    const recognition = new SpeechRecognitionCtor()
    recognition.lang = 'fr-FR'
    recognition.interimResults = true
    recognition.continuous = true
    recognition.onresult = (event: any) => {
      let interim = ''
      for (let index = event.resultIndex; index < event.results.length; index++) {
        const result = event.results[index]
        if (result.isFinal) finalTextRef.current += ` ${result[0].transcript}`.trim()
        else interim += result[0].transcript
      }
      setInput(`${finalTextRef.current} ${interim}`.trim())
    }
    recognition.onerror = (event: any) => {
      if (event?.error === 'not-allowed' || event?.error === 'service-not-allowed') {
        listeningRef.current = false
        setListening(false)
        setError('Micro refusé. Cliquez sur l’icône 🔒/ⓘ à gauche de l’URL → « Microphone » → « Autoriser », puis rechargez la page. Vérifiez aussi l’autorisation micro dans les Paramètres Windows (Confidentialité → Microphone).')
      }
    }
    recognition.onend = () => {
      if (manualStopRef.current) {
        // Arrêt demandé par le bouton : on envoie la question transcrite.
        manualStopRef.current = false
        listeningRef.current = false
        setListening(false)
        const text = finalTextRef.current.trim()
        if (text) submitMessage(text)
        else setInput('')
        return
      }
      // Fin non demandée (pause/réinitialisation) : on continue d'écouter si le bouton est actif.
      if (listeningRef.current) {
        try {
          recognition.start()
        } catch {
          // ignore
        }
      }
    }
    recognitionRef.current = recognition
    return recognition
  }

  function toggleMic() {
    const recognition = ensureRecognition()
    if (!recognition) {
      setError('Votre navigateur ne supporte pas la reconnaissance vocale (Chrome/Edge recommandé).')
      return
    }
    if (listeningRef.current) {
      // Fin de la question : arrêt + envoi automatique.
      manualStopRef.current = true
      try {
        recognition.stop()
      } catch {
        // ignore
      }
      return
    }
    finalTextRef.current = ''
    listeningRef.current = true
    setListening(true)
    setError(null)
    setInput('')
    try {
      recognition.start()
    } catch {
      listeningRef.current = false
      setListening(false)
      setError('Impossible de démarrer le micro. Autorisez l’accès : icône 🔒/ⓘ à gauche de l’URL → « Microphone » → « Autoriser », puis rechargez la page et réessayez.')
    }
  }

  function speakMessage(id: string, text: string) {
    if (!audioEnabled || !window.speechSynthesis || !text) return
    window.speechSynthesis.cancel()
    speakingIdRef.current = id
    setSpeakingId(id)
    const utterance = new SpeechSynthesisUtterance(stripMarkdown(text))
    utterance.lang = 'fr-FR'
    utterance.rate = 1
    utterance.onend = () => {
      speakingIdRef.current = null
      setSpeakingId(null)
    }
    utterance.onerror = () => {
      speakingIdRef.current = null
      setSpeakingId(null)
    }
    window.speechSynthesis.speak(utterance)
  }

  function stopSpeaking() {
    if (window.speechSynthesis) window.speechSynthesis.cancel()
    speakingIdRef.current = null
    setSpeakingId(null)
  }

  function speakLastAnswer() {
    const last = [...messages].reverse().find((messageItem) => messageItem.role === 'assistant')
    if (last) speakMessage(last.id, last.content)
  }

  useEffect(() => () => {
    try {
      recognitionRef.current?.stop?.()
    } catch {
      // ignore
    }
    if (window.speechSynthesis) window.speechSynthesis.cancel()
  }, [])

  async function submitMessage(rawMessage?: string) {
    const message = (rawMessage ?? input).trim()
    if (!message || loading) return

    stopSpeaking()

    setInput('')
    setError(null)
    const userMessage: ChatMessage = {
      id: newMessageId(),
      role: 'user',
      content: message,
      timestamp: new Date().toISOString(),
    }
    setMessages((current) => [...current, userMessage])
    setLoading(true)

    try {
      const response = await sendChat(sessionId, message, provider, !guardEnabled)
      setSummary(response.financialSummary ?? summary)
      const assistantMessage: ChatMessage = {
        id: newMessageId(),
        role: 'assistant',
        content: response.answer,
        timestamp: new Date().toISOString(),
        provider: response.provider,
        category: response.category,
        agent: response.agent,
      }
      setMessages((current) => [...current, assistantMessage])
      if (audioEnabled && voiceEnabled && ttsSupported) speakMessage(assistantMessage.id, assistantMessage.content)
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Une erreur inattendue est survenue.'
      setError(text)
      setMessages((current) => [
        ...current,
        {
          id: newMessageId(),
          role: 'assistant',
          content: 'Je n’ai pas pu contacter le backend. Vérifiez Spring Boot et réessayez.',
          timestamp: new Date().toISOString(),
        },
      ])
    } finally {
      setLoading(false)
    }
  }

  function startNewConversation() {
    const newId = newSessionId()
    setSessionId(newId)
    setMessages(welcomeMessages())
    setInput('')
    setError(null)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-wrap">
          <div className="brand-mark"><Sparkles size={20} /></div>
          <div>
            <div className="brand-title">Coach financier</div>
            <div className="brand-subtitle">Votre assistant financier personnel</div>
          </div>
        </div>

        <div className="topbar-actions">
          <label className="adv-toggle" title="Afficher les réglages avancés (fournisseur IA, garde-fou, pages Logs et Agents)">
            <input
              type="checkbox"
              checked={advanced}
              onChange={(event) => setAdvanced(event.target.checked)}
            />
            <span className="adv-toggle-ui" aria-hidden="true" />
            <span className="adv-toggle-label">Avancé</span>
          </label>
          {advanced && (
            <div className="advanced-actions">
              <label className="adv-toggle" title="Activer le micro (dictée) et la lecture vocale des réponses">
                <input
                  type="checkbox"
                  checked={audioEnabled}
                  onChange={(event) => setAudioEnabled(event.target.checked)}
                />
                <span className="adv-toggle-ui" aria-hidden="true" />
                <span className="adv-toggle-label">Audio</span>
              </label>
              <div className="provider-select-wrap">
                <Bot size={16} />
                <select
                  aria-label="Fournisseur IA"
                  value={provider}
                  onChange={(event) => setProvider(event.target.value as AIProvider)}
                >
                  <option value="GPT">GPT / OpenAI</option>
                  <option value="DEEPSEEK">DeepSeek</option>
                  <option value="MOCK">Mode démo</option>
                </select>
                <ChevronDown size={14} />
              </div>
              <label
                className="guard-toggle"
                title="Active/désactive le garde-fou qui refuse les questions hors sujet financier (OUT_OF_SCOPE)"
              >
                <input
                  type="checkbox"
                  checked={guardEnabled}
                  onChange={(event) => setGuardEnabled(event.target.checked)}
                />
                <span>Contrôle hors-sujet</span>
              </label>
              <label
                className="guard-toggle"
                title="Lire automatiquement les réponses de l'IA à voix haute (synthèse vocale)"
              >
                <input
                  type="checkbox"
                  checked={voiceEnabled}
                  onChange={(event) => setVoiceEnabled(event.target.checked)}
                  disabled={!ttsSupported}
                />
                <span>Réponses vocales</span>
              </label>
              <a className="icon-button logs-link" href="#/agents" target="_blank" rel="noopener noreferrer" title="Agents IA">
                <FileText size={20} />
              </a>
              <a className="icon-button logs-link" href="#/logs" target="_blank" rel="noopener noreferrer" title="Logs des appels IA">
                <Settings size={20} />
              </a>
            </div>
          )}
          <button className="new-chat-button" onClick={startNewConversation} type="button">
            <Plus size={17} />
            <span>Nouveau chat</span>
          </button>
          <button className="icon-button mobile-menu-button" onClick={() => setMobileMenuOpen((open) => !open)} type="button">
            {mobileMenuOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
        </div>
      </header>

      {mobileMenuOpen && (
        <div className="mobile-panel">
          <label
            className="guard-toggle"
            title="Active/désactive le garde-fou OUT_OF_SCOPE"
          >
            <input
              type="checkbox"
              checked={guardEnabled}
              onChange={(event) => setGuardEnabled(event.target.checked)}
            />
            <span>Contrôle hors-sujet</span>
          </label>
          <button onClick={startNewConversation} type="button"><Plus size={17} /> Nouvelle conversation</button>
          <div className="mobile-provider">
            <Bot size={17} />
            <span>IA : {providerLabels[provider]}</span>
          </div>
        </div>
      )}

      <main className="main-content">
        <section className="chat-column">
          <div className="chat-heading">
            <div>
              <p className="eyebrow">CONVERSATION</p>
              <h1>Comment puis-je vous aider ?</h1>
            </div>
          </div>

          <div className="suggestions">
            {suggestions.map((suggestion) => (
              <button key={suggestion} type="button" onClick={() => submitMessage(suggestion)} disabled={loading}>
                <MessageCircle size={15} />
                {suggestion}
              </button>
            ))}
          </div>

          <div className="messages-card">
            <div className="messages-scroll">
              {messages.map((message) => (
                <div key={message.id} className={`message-row ${message.role}`}>
                  {message.role === 'assistant' && (
                    <div className="avatar assistant-avatar"><Sparkles size={17} /></div>
                  )}
                  <div className={`message-bubble ${message.role}`}>
                    <div className="message-meta">
                      {message.role === 'user' && <span>Vous</span>}
                      {message.role === 'assistant' && audioEnabled && ttsSupported && (
                        <button
                          type="button"
                          className={`message-speak${speakingId === message.id ? ' active' : ''}`}
                          title={speakingId === message.id ? 'Arrêter la lecture' : 'Lire la réponse à voix haute'}
                          onClick={() => (speakingId === message.id
                            ? stopSpeaking()
                            : speakMessage(message.id, message.content))}
                        >
                          {speakingId === message.id ? <VolumeX size={13} /> : <Volume2 size={13} />}
                        </button>
                      )}
                    </div>
                    <div className="message-text">
                      {renderMessageContent(message.id, message.content)}
                    </div>
                  </div>
                  {message.role === 'user' && <div className="avatar user-avatar">V</div>}
                </div>
              ))}
              {loading && (
                <div className="message-row assistant">
                  <div className="avatar assistant-avatar"><Sparkles size={17} /></div>
                  <div className="message-bubble assistant typing-bubble">
                    <div className="typing"><span /><span /><span /></div>
                  </div>
                </div>
              )}
            </div>

            <div className="composer-wrap">
              {error && (
                <div className="error-banner">
                  <CircleAlert size={16} />
                  <span>{error}</span>
                  <button type="button" onClick={() => setError(null)}><X size={15} /></button>
                </div>
              )}
              <div className="composer">
                {audioEnabled && (
                  <>
                <button
                  className={`composer-icon mic-button${listening ? ' listening' : ''}`}
                  type="button"
                  onClick={toggleMic}
                  disabled={!speechSupported || (loading && !listening)}
                  title={
                    listening
                      ? 'Arrêter et envoyer la question'
                      : speechSupported
                        ? 'Parler au micro (dictée)'
                        : 'Reconnaissance vocale non supportée'
                  }
                >
                  {listening ? <MicOff size={19} /> : <Mic size={19} />}
                </button>
                <button
                  className={`composer-icon mic-button speak-button${speakingId ? ' active' : ''}`}
                  type="button"
                  onClick={() => (speakingId ? stopSpeaking() : speakLastAnswer())}
                  disabled={!ttsSupported}
                  title={
                    speakingId
                      ? 'Arrêter la lecture vocale'
                      : ttsSupported
                        ? 'Écouter la dernière réponse'
                        : 'Lecture vocale non supportée'
                  }
                >
                  {speakingId ? <VolumeX size={19} /> : <Volume2 size={19} />}
                </button>
                  </>
                )}
                <textarea
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && !event.shiftKey) {
                      event.preventDefault()
                      submitMessage()
                    }
                  }}
                  placeholder="Écrivez votre question…"
                  rows={1}
                />
                <button
                  className="send-button"
                  type="button"
                  onClick={() => submitMessage()}
                  disabled={loading || !input.trim()}
                  title="Envoyer"
                >
                  <Send size={18} />
                </button>
              </div>
              <div className="composer-footer">
                <span>Entrée pour envoyer · Maj + Entrée pour une nouvelle ligne</span>
                <span>Session : {sessionId.slice(0, 18)}…</span>
              </div>
            </div>
          </div>
        </section>

        <aside className="summary-column">
          <div className="summary-header">
            <div>
              <p className="eyebrow">VUE D’ENSEMBLE</p>
              <h2>Votre situation</h2>
            </div>
            <Landmark size={21} />
          </div>

          {summary ? (
            <>
              <div className="balance-card">
                <div className="balance-label"><Wallet size={16} /> Solde du compte courant</div>
                <div className="balance-value">{formatMoneyCents(summary.currentAccountBalance)}</div>
                <div className="balance-period">Données du {formatPeriod(summary.periodStart, summary.periodEnd)}</div>
              </div>

              <div className="metric-grid">
                <div className="metric-card">
                  <span className="metric-icon"><Wallet size={16} /></span>
                  <span className="metric-label">Épargne</span>
                  <strong>{formatMoney(summary.savingsBalance)}</strong>
                </div>
                <div className="metric-card">
                  <span className="metric-icon"><TrendingUp size={16} /></span>
                  <span className="metric-label">Revenu mensuel moyen</span>
                  <strong>{formatMoney(summary.averageMonthlyIncome)}</strong>
                </div>
                <div className="metric-card">
                  <span className="metric-label">Dépenses mensuelles</span>
                  <strong>{formatMoney(summary.averageMonthlyExpenses)}</strong>
                </div>
                <div className="metric-card">
                  <span className="metric-label">Crédits / mois</span>
                  <strong>{formatMoney(summary.monthlyLoanPayments)}</strong>
                </div>
              </div>

              <div className="health-card">
                <div className="health-title"><span>Santé financière</span><ArrowUpRight size={16} /></div>
                <div className="health-row">
                  <span>Taux d’endettement</span>
                  <strong>{formatRatio(summary.debtServiceToIncomeRatio)}</strong>
                </div>
                <div className="progress"><span style={{ width: `${Math.min(summary.debtServiceToIncomeRatio * 100, 100)}%` }} /></div>
                <div className="health-row">
                  <span>Taux d’épargne</span>
                  <div className="health-value">
                    <strong>{formatPercent(summary.savingsToIncomeRatio3Months)}</strong>
                    <small className="health-month">3 derniers mois · {summary.savingsRatePeriodLabel}</small>
                  </div>
                </div>
                <div className="progress"><span style={{ width: `${Math.min(summary.savingsToIncomeRatio3Months * 100, 100)}%` }} /></div>
              </div>

              <div className="context-card">
                <div className="context-title">Contexte utilisé par l’IA</div>
                <div className="context-line"><span>Période</span><strong>{summary.periodMonths} mois</strong></div>
                <div className="context-line"><span>Transactions</span><strong>{summary.transactionCount.toLocaleString('fr-FR')}</strong></div>
              </div>
            </>
          ) : (
            <div className="loading-summary">
              <div className="skeleton large" />
              <div className="skeleton" />
              <div className="skeleton" />
              <div className="skeleton wide" />
            </div>
          )}

        </aside>
      </main>

      <footer className="app-footer">
        <span>POC — Coach financier conversationnel</span>
        <span>API : {API_BASE_URL}</span>
      </footer>
    </div>
  )
}

export default App
