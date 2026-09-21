import { useEffect, useRef, useState } from 'react'
import {
  ArrowUpRight,
  BadgeCheck,
  Bot,
  ChevronDown,
  CircleAlert,
  FileText,
  Landmark,
  Menu,
  MessageCircle,
  Mic,
  MicOff,
  PhoneCall,
  Plus,
  Send,
  Settings,
  Sparkles,
  TrendingUp,
  UserCheck,
  Volume2,
  VolumeX,
  Wallet,
  X,
} from 'lucide-react'
import { API_BASE_URL, closeConversation, fetchFinancialSummary, sendChat, sendConversationFeedback } from './api'
import { playWakeCue } from './audioCue'
import FeedbackPopup from './FeedbackPopup'
import { renderMessageContent, stripMarkdown } from './messageFormat'
import type { QualityFeedbackRequest } from './types.quality'
import type { AIProvider, ChatMessage, FinancialSummary } from './types'

const SESSION_STORAGE_KEY = 'financial-coach-session-id'
const HISTORY_STORAGE_KEY = 'financial-coach-chat-history'
const PROVIDER_STORAGE_KEY = 'financial-coach-provider'
const GUARD_STORAGE_KEY = 'financial-coach-guard'
/**
 * Suivi de fin de conversation (dossier conseiller) : **ACTIVÉ par défaut**.
 * <p>
 * La clé a changé de nom (`…-v2`) volontairement : l'ancienne clé était écrite automatiquement avec le
 * défaut précédent (`'false'`), ce qui aurait maintenu la case décochée malgré le nouveau défaut.
 */
const SUIVI_STORAGE_KEY = 'financial-coach-suivi-v2'
/** Clé historique (défaut « décoché ») : sa valeur ne doit plus influencer le comportement. */
const LEGACY_SUIVI_STORAGE_KEY = 'financial-coach-suivi'
const ADVANCED_STORAGE_KEY = 'financial-coach-advanced'
const VOICE_STORAGE_KEY = 'financial-coach-voice'
const VOICE_RATE_STORAGE_KEY = 'financial-coach-voice-rate'
const AUDIO_STORAGE_KEY = 'financial-coach-audio'
const AUTO_AUDIO_STORAGE_KEY = 'financial-coach-auto-audio'
const WAKE_WORD_STORAGE_KEY = 'financial-coach-wake-word'
const SILENCE_STORAGE_KEY = 'financial-coach-silence-delay'
const DEFAULT_WAKE_WORD = 'Chloé'
const DEFAULT_SILENCE_SECONDS = 5
const MIN_SILENCE_SECONDS = 2
const MAX_SILENCE_SECONDS = 10

/**
 * Nombre minimal d'échanges client ↔ IA avant de déclencher la clôture de la conversation
 * (1 échange = 1 message client suivi de la réponse du coach → on compte les messages client).
 */
const MIN_EXCHANGES_TO_CLOSE = 2

const suggestions = [
  'Quel est le solde de mon compte et mes dernières opérations ?',
  'Combien puis-je épargner chaque mois ?',
  'Est-ce que je peux acheter une voiture à 8 700 € ?',
  'Combien puis-je emprunter pour mon projet immobilier ?',
  'Quelle assurance habitation me faut-il ?',
]

const providerLabels: Record<AIProvider, string> = {
  GPT: 'GPT / OpenAI',
  DEEPSEEK: 'DeepSeek',
  LOCAL: 'Local (LM Studio)',
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

// --- Détection du mot-clé de réveil (insensible à la casse et aux accents) ---

/** Normalise pour la comparaison : minuscules + suppression des accents (formes composées et décomposées). */
function normalizeForMatch(text: string): string {
  return (text || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
}

/**
 * Cherche le mot-clé dans un texte, mot par mot (forme composée ET décomposée acceptées,
 * ex. « Chloé » reconnu « chloe », « Chloe\u0301 », « Chloé »…).
 * @returns { found, rest } — rest = texte original qui suit le mot-clé (séparateurs initiaux retirés).
 */
function findWakeWord(text: string, wake: string): { found: boolean; rest: string } {
  const trimmed = (wake || '').trim()
  if (!trimmed || !text) return { found: false, rest: text ?? '' }
  const target = normalizeForMatch(trimmed)
  if (!target) return { found: false, rest: text }
  // Découpe en mots (lettres/chiffres + marques d'accent) et séparateurs, en gardant les positions originales.
  const tokenRegex = /[\p{L}\p{M}\p{N}]+|[^\p{L}\p{M}\p{N}]+/gu
  let match: RegExpExecArray | null
  while ((match = tokenRegex.exec(text)) !== null) {
    const token = match[0]
    if (/^[\p{L}\p{N}]/u.test(token) && normalizeForMatch(token) === target) {
      const after = text.slice(match.index + token.length)
      return { found: true, rest: after.replace(/^[\s.,;:!?«»"'()\-]+/, '') }
    }
  }
  return { found: false, rest: text }
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
    return stored === 'GPT' || stored === 'DEEPSEEK' || stored === 'LOCAL' || stored === 'MOCK'
      ? stored : 'DEEPSEEK'
  })
  const [sessionId, setSessionId] = useState(() => localStorage.getItem(SESSION_STORAGE_KEY) ?? newSessionId())
  const [messages, setMessages] = useState<ChatMessage[]>(loadInitialMessages)
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [summary, setSummary] = useState<FinancialSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** Session déjà clôturée : évite un 2e dossier si l'on re-clique (terminer puis nouvelle conversation). */
  const closedSessionRef = useRef<string | null>(null)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [guardEnabled, setGuardEnabled] = useState(() => localStorage.getItem(GUARD_STORAGE_KEY) !== 'false')
  /**
   * Suivi conseiller (dossier de suivi + email au conseiller à la clôture) : **coché par défaut**.
   * Un décochage volontaire est mémorisé (`'false'`) et respecté aux chargements suivants.
   */
  const [suiviEnabled, setSuiviEnabled] = useState(() => localStorage.getItem(SUIVI_STORAGE_KEY) !== 'false')
  // Pop-in de satisfaction (module Qualité) : ouverte AVANT la clôture, avis facultatif.
  const [feedbackOpen, setFeedbackOpen] = useState(false)
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
  const [autoAudio, setAutoAudio] = useState(() => localStorage.getItem(AUTO_AUDIO_STORAGE_KEY) === 'true')
  const [wakeWord, setWakeWord] = useState(() => localStorage.getItem(WAKE_WORD_STORAGE_KEY) || DEFAULT_WAKE_WORD)
  const [silenceSeconds, setSilenceSeconds] = useState(() => {
    const raw = Number(localStorage.getItem(SILENCE_STORAGE_KEY))
    return raw >= MIN_SILENCE_SECONDS && raw <= MAX_SILENCE_SECONDS ? raw : DEFAULT_SILENCE_SECONDS
  })
  // État du mode auto pour l'IHM : 'idle' (éteint) | 'standby' (veille, attend le mot-clé) | 'listening' (écoute)
  const [autoState, setAutoState] = useState<'idle' | 'standby' | 'listening'>('idle')
  /**
   * DÉCOMPTE AFFICHÉ (5, 4, 3…) pendant l'écoute : secondes restantes avant l'envoi automatique de la
   * question à l'IA. {@code null} = aucun décompte en cours (veille, réponse IA en cours, mode éteint).
   * Il redémarre à chaque parole captée : c'est donc bien « X secondes SANS entrée de voix ».
   */
  const [autoCountdown, setAutoCountdown] = useState<number | null>(null)
  const autoRecognitionRef = useRef<any>(null)
  const autoActiveRef = useRef(false) // la session auto tourne ?
  const autoPhaseRef = useRef<'standby' | 'listening'>('standby')
  const autoBufferRef = useRef('') // texte finalisé capté en mode écoute
  const autoTimerRef = useRef<number | null>(null) // timer de silence
  const autoCountdownRef = useRef<number | null>(null) // intervalle du DÉCOMPTE affiché
  const autoDeadlineRef = useRef(0) // instant (ms) où le silence déclenche l'envoi
  const autoEnabledRef = useRef(autoAudio)
  const wakeRef = useRef(wakeWord)
  const silenceRef = useRef(silenceSeconds)
  const loadingRef = useRef(false)
  const submitRef = useRef<(text: string) => void>(() => {})
  const [voiceEnabled, setVoiceEnabled] = useState(() => localStorage.getItem(VOICE_STORAGE_KEY) !== 'false')
  const [voiceRate, setVoiceRate] = useState(() => {
    const raw = Number(localStorage.getItem(VOICE_RATE_STORAGE_KEY))
    return raw >= 0.5 && raw <= 2 ? raw : 1
  })
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
    localStorage.setItem(SUIVI_STORAGE_KEY, String(suiviEnabled))
  }, [suiviEnabled])

  // Nettoyage de la clé historique (ancien défaut « décoché »), une seule fois au chargement.
  useEffect(() => {
    localStorage.removeItem(LEGACY_SUIVI_STORAGE_KEY)
  }, [])

  useEffect(() => {
    localStorage.setItem(ADVANCED_STORAGE_KEY, advanced ? 'true' : 'false')
  }, [advanced])

  useEffect(() => {
    localStorage.setItem(VOICE_STORAGE_KEY, voiceEnabled ? 'true' : 'false')
  }, [voiceEnabled])

  useEffect(() => {
    localStorage.setItem(VOICE_RATE_STORAGE_KEY, String(voiceRate))
  }, [voiceRate])

  useEffect(() => {
    localStorage.setItem(AUDIO_STORAGE_KEY, audioEnabled ? 'true' : 'false')
  }, [audioEnabled])

  useEffect(() => {
    localStorage.setItem(AUTO_AUDIO_STORAGE_KEY, autoAudio ? 'true' : 'false')
  }, [autoAudio])

  useEffect(() => {
    localStorage.setItem(WAKE_WORD_STORAGE_KEY, wakeWord)
  }, [wakeWord])

  useEffect(() => {
    localStorage.setItem(SILENCE_STORAGE_KEY, String(silenceSeconds))
  }, [silenceSeconds])

  // Miroirs refs pour les callbacks de reconnaissance (pas de closure obsolète).
  useEffect(() => {
    autoEnabledRef.current = autoAudio
  }, [autoAudio])
  useEffect(() => {
    wakeRef.current = wakeWord.trim() || DEFAULT_WAKE_WORD
  }, [wakeWord])
  useEffect(() => {
    silenceRef.current = silenceSeconds
  }, [silenceSeconds])
  useEffect(() => {
    loadingRef.current = loading
  }, [loading])
  useEffect(() => {
    submitRef.current = submitMessage
  })

  // Audio désactivé : on coupe toute écoute micro, le mode auto et toute lecture vocale en cours.
  useEffect(() => {
    if (!audioEnabled) {
      listeningRef.current = false
      setListening(false)
      try {
        recognitionRef.current?.stop?.()
      } catch {
        // ignore
      }
      stopAutoAudio()
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
    utterance.rate = voiceRate
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

  // ---------- Mode auto (mot-clé de réveil, type Siri) ----------

  function clearAutoTimer() {
    if (autoTimerRef.current !== null) {
      window.clearTimeout(autoTimerRef.current)
      autoTimerRef.current = null
    }
  }

  /** Stoppe le décompte affiché (le timer de silence, lui, est armé séparément). */
  function clearAutoCountdown() {
    if (autoCountdownRef.current !== null) {
      window.clearInterval(autoCountdownRef.current)
      autoCountdownRef.current = null
    }
    setAutoCountdown(null)
  }

  /**
   * (Re)démarre le DÉCOMPTE AFFICHÉ en même temps que le timer de silence : l'utilisateur voit les
   * secondes qui restent (5, 4, 3…) avant l'envoi automatique de sa question à l'IA. Appelé à chaque
   * résultat vocal : tant qu'il parle, le décompte repart de zéro. L'échéance est calculée en temps réel
   * (et non par décrément successif) pour rester juste même si le navigateur ralentit le timer.
   */
  function startAutoCountdown() {
    const total = Math.max(MIN_SILENCE_SECONDS, silenceRef.current)
    autoDeadlineRef.current = Date.now() + total * 1000
    setAutoCountdown(total)
    if (autoCountdownRef.current !== null) {
      window.clearInterval(autoCountdownRef.current)
    }
    autoCountdownRef.current = window.setInterval(() => {
      const remaining = Math.ceil((autoDeadlineRef.current - Date.now()) / 1000)
      setAutoCountdown(remaining > 0 ? remaining : 1)
    }, 200)
  }

  /** Timer de silence écoulé : envoie la phrase captée, ou revient en veille si rien n'a été dit. */
  function onAutoSilence() {
    autoTimerRef.current = null
    clearAutoCountdown()
    if (!autoActiveRef.current) return
    if (autoPhaseRef.current !== 'listening') {
      setInput('')
      setAutoState('standby')
      return
    }
    const text = autoBufferRef.current.trim()
    if (loadingRef.current) {
      // Réponse IA en cours : on garde la phrase et on réarme le timer (pas de décompte : rien ne
      // partira tant que l'IA n'a pas répondu).
      autoTimerRef.current = window.setTimeout(onAutoSilence, silenceRef.current * 1000)
      return
    }
    autoPhaseRef.current = 'standby'
    autoBufferRef.current = ''
    setInput('')
    setAutoState('standby')
    if (text) submitRef.current(text) // on reste en écoute continue ensuite
  }

  function stopAutoAudio() {
    if (!autoActiveRef.current) {
      setAutoState('idle')
      return
    }
    autoActiveRef.current = false
    clearAutoTimer()
    clearAutoCountdown()
    const recognition = autoRecognitionRef.current
    autoRecognitionRef.current = null
    try {
      recognition?.stop?.()
    } catch {
      // ignore
    }
    autoPhaseRef.current = 'standby'
    autoBufferRef.current = ''
    setAutoState('idle')
  }

  function startAutoAudio() {
    if (autoActiveRef.current || !speechSupported) return
    // Un seul SpeechRecognition actif à la fois : on coupe le push-to-talk s'il tournait.
    try {
      recognitionRef.current?.stop?.()
    } catch {
      // ignore
    }
    listeningRef.current = false
    setListening(false)
    const SpeechRecognitionCtor =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    if (!SpeechRecognitionCtor) return
    const recognition = new SpeechRecognitionCtor()
    recognition.lang = 'fr-FR'
    recognition.interimResults = true
    recognition.continuous = true
    autoActiveRef.current = true
    autoPhaseRef.current = 'standby'
    autoBufferRef.current = ''
    autoRecognitionRef.current = recognition
    setAutoState('standby')

    recognition.onresult = (event: any) => {
      if (!autoActiveRef.current) return
      let interim = ''
      for (let index = event.resultIndex; index < event.results.length; index++) {
        const result = event.results[index]
        const transcript = (result[0]?.transcript as string) || ''
        if (result.isFinal) {
          if (autoPhaseRef.current === 'listening') {
            autoBufferRef.current = `${autoBufferRef.current} ${transcript}`.trim()
          } else {
            // Veille : on attend le mot-clé (ignore tout le reste).
            const hit = findWakeWord(transcript, wakeRef.current)
            if (hit.found) {
              // Barge-in : le mot-clé interrompt immédiatement la lecture vocale en cours,
              // puis on écoute la nouvelle question.
              stopSpeaking()
              // Repère SONORE (façon Siri) : le mot-clé est reconnu, l'écoute commence. Sans lui,
              // l'utilisateur ne peut pas savoir si « Chloé » a été entendu et parle dans le vide.
              playWakeCue()
              autoPhaseRef.current = 'listening'
              autoBufferRef.current = hit.rest || ''
              setAutoState('listening')
            }
          }
        } else if (autoPhaseRef.current === 'listening') {
          interim = transcript
        }
      }
      if (autoPhaseRef.current === 'listening') {
        setInput(`${autoBufferRef.current} ${interim}`.trim())
        clearAutoTimer()
        autoTimerRef.current = window.setTimeout(onAutoSilence, silenceRef.current * 1000)
        // Chaque parole (même partielle) relance le décompte : il ne s'écoule que pendant le SILENCE.
        startAutoCountdown()
      }
    }
    recognition.onerror = (event: any) => {
      if (!autoActiveRef.current) return
      if (event?.error === 'not-allowed' || event?.error === 'service-not-allowed') {
        stopAutoAudio()
        setAutoState('idle')
        setError('Micro refusé (mode auto). Autorisez l’accès au micro puis réactivez le mode auto.')
      }
      // 'no-speech' / 'aborted' : silencieux, on relance via onend.
    }
    recognition.onend = () => {
      if (autoActiveRef.current) {
        window.setTimeout(() => {
          if (autoActiveRef.current) {
            try {
              recognition.start()
            } catch {
              // ignore
            }
          }
        }, 150)
      }
    }
    try {
      recognition.start()
    } catch {
      stopAutoAudio()
    }
  }

  // Cycle de vie : le mode auto tourne tant que Audio + Auto sont activés et que le navigateur le supporte.
  useEffect(() => {
    if (!audioEnabled || !speechSupported || !autoAudio) {
      stopAutoAudio()
      return
    }
    startAutoAudio()
    return () => stopAutoAudio()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioEnabled, speechSupported, autoAudio])

  useEffect(() => () => {
    try {
      recognitionRef.current?.stop?.()
      autoRecognitionRef.current?.stop?.()
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
          content: 'Je n’ai pas pu contacter le serveur.',
          timestamp: new Date().toISOString(),
        },
      ])
    } finally {
      setLoading(false)
    }
  }

  /**
   * Déclenche la clôture en ARRIÈRE-PLAN (fire-and-forget) : le backend prépare le dossier de suivi et
   * envoie UN SEUL email (au conseiller) avec le brouillon d'email client en pièce jointe. On n'attend PAS
   * la réponse et on n'affiche aucun état : la clôture ne doit jamais bloquer l'IHM.
   */
  function fireClose() {
    if (!suiviEnabled) {
      console.warn('[suivi] clôture ignorée : « Suivi conseiller » est désactivé (case à cocher décochée).')
      return
    }
    if (closedSessionRef.current === sessionId) {
      console.warn('[suivi] clôture ignorée : cette session a déjà été clôturée.')
      return
    }
    closedSessionRef.current = sessionId
    // Fire-and-forget : aucun état d'IHM. En cas d'échec (backend indisponible, session inconnue,
    // mail non configuré, authentification SMTP refusée...), tout reste visible dans la console
    // du navigateur et, côté serveur, dans la page Logs (bloc [SUIVI].mailError).
    void closeConversation(sessionId, { send: true, provider })
      .then((result) => console.info('[suivi] clôture traitée :', result.status, result))
      .catch((error) => console.error('[suivi] échec de la clôture :', error))
  }

  /**
   * Clôture automatique à l'abandon d'une conversation : uniquement si elle contient au moins
   * MIN_EXCHANGES_TO_CLOSE échanges client ↔ IA.
   */
  function closeInBackground() {
    const exchanges = messages.filter((message) => message.role === 'user').length
    if (exchanges < MIN_EXCHANGES_TO_CLOSE) {
      console.warn(
        `[suivi] clôture ignorée : ${exchanges} échange(s) client (minimum requis : ${MIN_EXCHANGES_TO_CLOSE}).`,
      )
      return
    }
    fireClose()
  }

  /**
   * Réinitialise l'IHM pour une nouvelle conversation : nouvelle session + messages d'accueil.
   * Partagé par « Nouvelle conversation » (menu mobile) et par « Terminer la conversation ».
   */
  function resetConversation() {
    setSessionId(newSessionId())
    setMessages(welcomeMessages())
    setInput('')
    setError(null)
  }

  /**
   * Termine la conversation : clôture FIRE-AND-FORGET (uniquement si ≥ MIN_EXCHANGES_TO_CLOSE échanges
   * client ↔ IA) puis vide le chat. Appelé par « Nouvelle conversation » (menu mobile) et par le bouton
   * « Terminer la conversation » : même comportement dans les deux cas.
   */
  function finishConversation() {
    closeInBackground()
    resetConversation()
  }

  /**
   * Clic sur « Terminer la conversation » : la pop-in de satisfaction s'ouvre d'abord (§43),
   * puis la clôture suit (avec ou sans avis). Suivi désactivé ou conversation trop courte :
   * aucune question n'est posée, le comportement « nouvelle conversation » est conservé.
   */
  function requestFinish() {
    const exchanges = messages.filter((message) => message.role === 'user').length
    if (!suiviEnabled || exchanges < MIN_EXCHANGES_TO_CLOSE) {
      finishConversation()
      return
    }
    setFeedbackOpen(true)
  }

  /**
   * Envoi de l'avis puis clôture : l'appel est en FIRE-AND-FORGET et son échec est sans conséquence
   * (un avis non enregistré ne doit jamais empêcher la clôture — §43).
   */
  function submitFeedbackAndFinish(payload: QualityFeedbackRequest) {
    setFeedbackOpen(false)
    void sendConversationFeedback(sessionId, payload)
      .then((result) => console.info('[qualité] avis traité :', result.status, result))
      .catch((error) => console.error('[qualité] avis non enregistré :', error))
    finishConversation()
  }

  /** « Passer » : aucun avis, la conversation se clôture normalement. */
  function skipFeedbackAndFinish() {
    setFeedbackOpen(false)
    finishConversation()
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
              <label
                className="adv-toggle"
                title="Mode auto : dites le mot-clé puis votre question ; elle sera envoyée après le délai de silence choisi"
              >
                <input
                  type="checkbox"
                  checked={autoAudio}
                  disabled={!speechSupported || !audioEnabled}
                  onChange={(event) => setAutoAudio(event.target.checked)}
                />
                <span className="adv-toggle-ui" aria-hidden="true" />
                <span className="adv-toggle-label">Auto</span>
              </label>
              {!speechSupported && (
                <span className="auto-warn">Reconnaissance vocale non supportée</span>
              )}
              <div className="provider-select-wrap">
                <Bot size={16} />
                <select
                  aria-label="Fournisseur IA"
                  value={provider}
                  onChange={(event) => setProvider(event.target.value as AIProvider)}
                >
                  <option value="GPT">GPT / OpenAI</option>
                  <option value="DEEPSEEK">DeepSeek</option>
                  <option value="LOCAL">Local (LM Studio)</option>
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
                title="À la fin d'une conversation, préparer le dossier de suivi et l'envoyer au conseiller (le brouillon d'email client est joint, jamais envoyé au client) — coché par défaut"
              >
                <input
                  type="checkbox"
                  checked={suiviEnabled}
                  onChange={(event) => setSuiviEnabled(event.target.checked)}
                />
                <span>Suivi conseiller</span>
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
              {ttsSupported && audioEnabled && (
                <select
                  className="voice-rate-select"
                  value={voiceRate}
                  aria-label="Vitesse de la voix"
                  title="Vitesse de lecture des réponses"
                  onChange={(event) => setVoiceRate(Number(event.target.value))}
                >
                  <option value={0.5}>🐢 0,5×</option>
                  <option value={0.75}>0,75×</option>
                  <option value={1}>1×</option>
                  <option value={1.25}>1,25×</option>
                  <option value={1.5}>1,5×</option>
                  <option value={2}>🐇 2×</option>
                </select>
              )}
              {autoAudio && audioEnabled && speechSupported && (
                <span className="auto-config" title="Configuration du mode auto">
                  <input
                    className="auto-wake-input"
                    type="text"
                    value={wakeWord}
                    maxLength={30}
                    aria-label="Mot-clé de réveil"
                    placeholder="Mot-clé (ex. Chloé)"
                    onChange={(event) => setWakeWord(event.target.value)}
                  />
                  <select
                    className="auto-delay-select"
                    value={silenceSeconds}
                    aria-label="Délai de silence en secondes"
                    title="Délai de silence avant envoi"
                    onChange={(event) => setSilenceSeconds(Number(event.target.value))}
                  >
                    {Array.from({ length: MAX_SILENCE_SECONDS - MIN_SILENCE_SECONDS + 1 },
                      (_, index) => index + MIN_SILENCE_SECONDS).map((seconds) => (
                      <option key={seconds} value={seconds}>{seconds}s</option>
                    ))}
                  </select>
                </span>
              )}
              <a className="icon-button logs-link" href="#/agents" target="_blank" rel="noopener noreferrer" title="Agents IA">
                <FileText size={20} />
              </a>
              <a className="icon-button logs-link" href="#/marketing" target="_blank" rel="noopener noreferrer" title="Marketing Intelligence">
                <TrendingUp size={20} />
              </a>
              <a className="icon-button logs-link" href="#/quality" target="_blank" rel="noopener noreferrer" title="Qualité & Satisfaction du Coach">
                <BadgeCheck size={20} />
              </a>
              <a className="icon-button logs-link" href="#/advisor-feedback" target="_blank" rel="noopener noreferrer" title="Feedback Conseillers">
                <UserCheck size={20} />
              </a>
              <a className="icon-button logs-link" href="#/centre-appels" target="_blank" rel="noopener noreferrer" title="Centre d'appels — conversations et score commercial">
                <PhoneCall size={20} />
              </a>
              <a className="icon-button logs-link" href="#/prompt-lab" target="_blank" rel="noopener noreferrer" title="Atelier d'optimisation des prompts">
                <Sparkles size={20} />
              </a>
              <a className="icon-button logs-link" href="#/logs" target="_blank" rel="noopener noreferrer" title="Logs des appels IA">
                <Settings size={20} />
              </a>
            </div>
          )}
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
          <label
            className="guard-toggle"
            title="Préparer et envoyer le dossier de suivi au conseiller à la clôture — coché par défaut"
          >
            <input
              type="checkbox"
              checked={suiviEnabled}
              onChange={(event) => setSuiviEnabled(event.target.checked)}
            />
            <span>Suivi conseiller</span>
          </label>
          <button onClick={finishConversation} type="button"><Plus size={17} /> Nouvelle conversation</button>
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
            {/* Bouton unique (remplace l'ancien « Nouveau chat ») :
                - suivi activé  → clôture (dossier conseiller) + vidage du chat ;
                - suivi désactivé → simple nouvelle conversation (aucun envoi). */}
            <button
              className="close-conversation-button"
              type="button"
              onClick={requestFinish}
              title={
                suiviEnabled
                  ? "Terminer la conversation : préparer le dossier de suivi et l'envoyer au conseiller (fire-and-forget, si au moins 2 échanges client), puis vider le chat. Le brouillon d'email client est joint : il n'est jamais envoyé automatiquement."
                  : 'Démarrer une nouvelle conversation (vide le chat). Le suivi conseiller est désactivé : aucun dossier ne sera préparé ni envoyé.'
              }
            >
              {suiviEnabled ? <Send size={16} /> : <Plus size={17} />}
              <span>{suiviEnabled ? 'Terminer la conversation' : 'Nouvelle conversation'}</span>
            </button>
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

            {audioEnabled && autoAudio && speechSupported && (autoState === 'standby' || autoState === 'listening') && (
              <div className={`auto-hud ${autoState}`} role="status">
                {autoState === 'listening' ? (
                  <>
                    <span className="auto-dot listening" />
                    <span>🎙️ Écoute…</span>
                    {autoCountdown !== null && !loading ? (
                      <>
                        {/* DÉCOMPTE : le nombre de secondes qui restent avant l'envoi automatique à l'IA.
                            Il repart à chaque parole captée — il ne s'écoule donc que pendant le silence. */}
                        <span
                          className={`auto-countdown${autoCountdown <= 2 ? ' urgent' : ''}`}
                          title="Votre question est envoyée à l'IA à la fin du décompte ; parlez pour le relancer"
                        >
                          <strong>{autoCountdown}</strong>
                          <span>s</span>
                        </span>
                        <span className="auto-countdown-track" aria-hidden="true">
                          <span
                            className="auto-countdown-fill"
                            style={{ width: `${Math.max(0, Math.min(100, Math.round((autoCountdown / Math.max(MIN_SILENCE_SECONDS, silenceSeconds)) * 100)))}%` }}
                          />
                        </span>
                        <span className="auto-countdown-hint">
                          envoi automatique à l&rsquo;IA si vous ne parlez plus
                        </span>
                      </>
                    ) : (
                      <span>
                        {loading
                          ? 'réponse de l’IA en cours — votre question partira ensuite'
                          : `envoi automatique après ${silenceSeconds}s de silence`}
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    <span className="auto-dot" />
                    <span>🎧 En veille — dites «&nbsp;{wakeWord.trim() || DEFAULT_WAKE_WORD}&nbsp;» puis votre question</span>
                  </>
                )}
              </div>
            )}

            <div className="composer-wrap">
              {error && (
                <div className="error-banner">
                  <CircleAlert size={16} />
                  <span>{error}</span>
                  <button type="button" onClick={() => setError(null)}><X size={15} /></button>
                </div>
              )}
              <div className="composer">
                {audioEnabled && !autoAudio && (
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
                )}
                {audioEnabled && (
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

      {/* Pop-in de satisfaction : ouverte au clic sur « Terminer la conversation »,
          AVANT la clôture. « Envoyer mon avis » comme « Passer » mènent à la clôture. */}
      {feedbackOpen && (
        <FeedbackPopup onSubmit={submitFeedbackAndFinish} onSkip={skipFeedbackAndFinish} />
      )}
    </div>
  )
}

export default App
