import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowLeft,
  Bot,
  Braces,
  Check,
  Eye,
  History as HistoryIcon,
  MessageSquare,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Send,
  Sparkles,
  Square,
  ThumbsDown,
  ThumbsUp,
  Type,
  Users,
  Wand2,
  X,
} from 'lucide-react'
import {
  acceptPromptVersion,
  closePromptThread,
  fetchPromptCampaign,
  fetchClientQuestion,
  fetchGeneratedClientBrief,
  fetchPromptComparison,
  fetchPromptOptimizationAgents,
  fetchPromptThreadComparison,
  fetchPromptThreads,
  iteratePromptCampaign,
  promotePromptVersion,
  rejectPromptCampaign,
  resumePromptCampaign,
  sendPromptHumanFeedback,
  startPromptCampaign,
  stopPromptCampaign,
  updatePromptTurn,
} from './api'
import type { AIProvider } from './types'
import { renderMessageContent } from './messageFormat'
import type {
  CampaignStatus,
  ConversationComparison,
  PromptCampaign,
  PromptCampaignDetail,
  PromptComparison,
  PromptIteration,
  PromptOptimizationAgents,
  PromptThread,
  PromptTurn,
  PromptVersionView,
  PromptZoneInfo,
  PromptZoneKey,
} from './types.promptopt'

const STATUS_LABELS: Record<CampaignStatus, string> = {
  CREATED: 'Créée',
  RUNNING: 'En cours',
  STOP_REQUESTED: 'Arrêt demandé',
  PAUSED: 'En pause',
  COMPLETED: 'Terminée',
  ACCEPTED: 'Version acceptée',
  REJECTED: 'Campagne refusée',
  ERROR: 'Erreur',
  CANCELLED: 'Annulée',
}

const SEVERITY_LABELS: Record<string, string> = { LOW: 'Mineure', MEDIUM: 'Significative', HIGH: 'Majeure' }
const SOURCE_LABELS: Record<string, string> = {
  PROMPT: 'Prompt (zone éditable)',
  DATA: 'Données',
  BACKEND_RULE: 'Règle backend',
  MODEL_VARIABILITY: 'Variabilité du modèle',
  UNKNOWN: 'Indéterminée',
}

/** Aucun code technique n'est affiché brut (même règle que les autres pages du POC). */
function humanize(code: string): string {
  if (!code) return ''
  const text = code.replace(/_/g, ' ').toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** Nom lisible d'un fournisseur, avec repli sur la valeur technique reçue. */
function providerLabel(code: string): string {
  if (code === 'DEEPSEEK') return 'DeepSeek'
  if (code === 'GPT') return 'OpenAI'
  if (code === 'LOCAL') return 'Local (LM Studio)'
  if (code === 'MOCK') return 'Mode démo'
  return code || '—'
}

/**
 * Message affiché après la CLÔTURE d'un scénario d'atelier : mêmes statuts que la page coach
 * (`SENT`, `PREPARED`, `MAIL_UNAVAILABLE`, `SEND_FAILED`), plus l'état de l'archivage.
 */
function closureNotice(status: string, sendMail: boolean, archive: boolean, warnings: string[]): string {
  const parts: string[] = []
  switch (status) {
    case 'SENT':
      parts.push('mail conseiller envoyé')
      break
    case 'PREPARED':
      parts.push(sendMail ? 'mail conseiller préparé' : 'pas de mail demandé')
      break
    case 'MAIL_UNAVAILABLE':
      parts.push("mail conseiller NON envoyé (service mail indisponible) — dossier préparé")
      break
    case 'SEND_FAILED':
      parts.push("mail conseiller NON envoyé (erreur d'envoi) — dossier préparé")
      break
    default:
      parts.push(`statut ${status || 'inconnu'}`)
  }
  parts.push(archive
    ? "conversation archivée, à retrouver dans la page Centre d'appels"
    : "conversation non archivée (aucune trace au centre d'appels)")
  const text = `Test clôturé — ${parts.join(' · ')}.`
  return warnings.length > 0 ? `${text} ${warnings.join(' ')}` : text
}

type DiffLine = { type: 'same' | 'add' | 'remove'; text: string }

/** Diff ligne à ligne (LCS) : suffisant pour comparer deux zones éditables (§19), sans dépendance. */
function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split('\n')
  const b = after.split('\n')
  const table: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }
  const lines: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      lines.push({ type: 'same', text: a[i] })
      i += 1
      j += 1
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      lines.push({ type: 'remove', text: a[i] })
      i += 1
    } else {
      lines.push({ type: 'add', text: b[j] })
      j += 1
    }
  }
  while (i < a.length) lines.push({ type: 'remove', text: a[i++] })
  while (j < b.length) lines.push({ type: 'add', text: b[j++] })
  return lines
}

/** Diff compact : seules les lignes modifiées et 2 lignes de contexte, les blocs identiques sont repliés. */
function compactDiff(lines: DiffLine[]): DiffLine[] {
  const CHANGED = 2
  const keep = new Set<number>()
  lines.forEach((line, index) => {
    if (line.type !== 'same') {
      for (let k = Math.max(0, index - CHANGED); k <= Math.min(lines.length - 1, index + CHANGED); k += 1) {
        keep.add(k)
      }
    }
  })
  const result: DiffLine[] = []
  let skipped = 0
  lines.forEach((line, index) => {
    if (keep.has(index)) {
      if (skipped > 0) {
        result.push({ type: 'same', text: `… ${skipped} ligne(s) identique(s) …` })
        skipped = 0
      }
      result.push(line)
    } else {
      skipped += 1
    }
  })
  if (skipped > 0) result.push({ type: 'same', text: `… ${skipped} ligne(s) identique(s) …` })
  return result
}

/**
 * L'agent GÉNÉRIQUE n'est pas proposé dans « Agent à optimiser » : il ne porte pas de zone propre
 * (seule la zone transverse « agent principal » le concerne, et celle-ci reste accessible depuis
 * n'importe quel autre agent).
 */
const NO_DEDICATED_ZONE_AGENTS = ['generic']

/**
 * Zone optimisée par l'atelier : FIGÉE au prompt de l'agent SPÉCIALISÉ (demande explicite — le sélecteur a
 * été retiré). La zone transverse « agent principal » reste connue de l'API (tests, appel direct), mais
 * l'IHM ne la propose plus : un seul choix, donc plus d'ambiguïté sur ce qui est réécrit.
 */
const ZONE_KEY: PromptZoneKey = 'agent'

/**
 * REMISE À ZÉRO de la mémoire de l'atelier, PAR AGENT (bouton « Nouvelle conversation »).
 * <p>
 * Le fil de conversation vit sur le SERVEUR : il est repris au chargement de la page (le plus récent de
 * l'agent sélectionné). Sans mémoire de l'abandon, un simple F5 faisait donc réapparaître une conversation
 * volontairement abandonnée — constaté par l'utilisateur. On horodate l'abandon dans `localStorage` : les
 * fils modifiés **avant** cet instant ne sont plus repris, ceux créés **après** le sont toujours (la reprise
 * au rechargement reste donc utile).
 */
const MEMORY_CLEARED_STORAGE_KEY = 'financial-coach-plab-memory-cleared'

/** Instant (ms) de la dernière remise à zéro de la mémoire pour un agent ; 0 = jamais remise à zéro. */
function memoryClearedAt(agentId: string): number {
  if (typeof window === 'undefined' || !agentId) return 0
  try {
    const raw = window.localStorage.getItem(MEMORY_CLEARED_STORAGE_KEY)
    const marks = raw ? (JSON.parse(raw) as Record<string, string>) : {}
    const value = marks[agentId]
    return value ? Date.parse(value) || 0 : 0
  } catch {
    return 0
  }
}

/**
 * Mémorise l'abandon de la conversation courante pour un agent. Aucun échec n'est possible côté IHM : si
 * `localStorage` est indisponible (navigation privée, quota), la remise à zéro reste valable pour la session
 * en cours.
 */
function markMemoryCleared(agentId: string): void {
  if (typeof window === 'undefined' || !agentId) return
  try {
    const raw = window.localStorage.getItem(MEMORY_CLEARED_STORAGE_KEY)
    const marks = raw ? (JSON.parse(raw) as Record<string, string>) : {}
    marks[agentId] = new Date().toISOString()
    window.localStorage.setItem(MEMORY_CLEARED_STORAGE_KEY, JSON.stringify(marks))
  } catch {
    // Stockage indisponible : la remise à zéro vaut pour la session en cours.
  }
}

/** Agents réellement sélectionnables pour une campagne. */
function selectableZones(agents: PromptOptimizationAgents | null): PromptZoneInfo[] {
  return (agents?.zones ?? []).filter((zone) => !NO_DEDICATED_ZONE_AGENTS.includes(zone.agentId))
}

/**
 * Panneau de détail ouvert : sa VERSION et l'itération qui l'a demandé.
 * <p>
 * {@code iteration === null} ⇒ ouvert depuis le tableau des versions (le panneau s'affiche sous le tableau) ;
 * sinon il s'affiche **juste sous le bloc de cette itération**, comme l'analyse Agent B — plus besoin de
 * remonter en haut de la page.
 */
type OpenPanel = { version: string; iteration: number | null }

/**
 * Version CANDIDATE à la promotion depuis le bilan de conversation : la dernière version RETENUE par la
 * conversation qui n'est pas (encore) celle du prompt de production.
 * <p>
 * Elle peut différer de la « version finale » du bilan : quand les derniers cycles n'ont rien changé (l'Agent A
 * n'a rien proposé, la version retenue est donc identique au fichier), c'est une version d'un cycle ANTÉRIEUR
 * qui porte l'amélioration jamais écrite — c'est celle-là que l'humain veut pouvoir adopter à la fin.
 */
type PromotionTarget = VersionRef & { editableSection: string; prompt: string }

/** Référence d'une version dans une campagne : ce qu'il faut pour la promouvoir ou en hériter la zone. */
type VersionRef = { campaignId: string; version: string }

/**
 * ATELIER d'amélioration itérative des prompts (`#/prompt-lab`).
 * <p>
 * La boucle est PILOTÉE PAR L'IHM : GO démarre la campagne puis enchaîne une requête = une itération
 * complète. STOP n'interrompt jamais brutalement un appel : la réponse en cours est toujours sauvegardée,
 * puis la campagne passe en pause. Aucune version candidate n'affecte la production sans promotion
 * explicite de l'administrateur.
 */
export default function PromptLab() {
  const [agents, setAgents] = useState<PromptOptimizationAgents | null>(null)
  const [detail, setDetail] = useState<PromptCampaignDetail | null>(null)
  const [comparison, setComparison] = useState<PromptComparison | null>(null)
  /** BILAN début ↔ fin de la conversation (bouton « comparer le prompt initial et le prompt final »). */
  const [threadComparison, setThreadComparison] = useState<ConversationComparison | null>(null)
  /** La version FINALE du bilan est-elle DÉJÀ celle du fichier de production ? (message du bilan) */
  const [threadComparisonApplied, setThreadComparisonApplied] = useState(false)
  /** Version à PROMOUVOIR d'après le bilan (dernière version retenue qui n'est pas en production). */
  const [threadTarget, setThreadTarget] = useState<PromotionTarget | null>(null)
  const threadRef = useRef<PromptThread | null>(null)
  const [agentId, setAgentId] = useState('')
  const [question, setQuestion] = useState('')
  const [iterations, setIterations] = useState(3)
  const [provider, setProvider] = useState<AIProvider>('DEEPSEEK')
  const [controllerProvider, setControllerProvider] = useState<AIProvider>('DEEPSEEK')
  const [editorProvider, setEditorProvider] = useState<AIProvider>('DEEPSEEK')
  const [extraIterations, setExtraIterations] = useState(3)
  const [busy, setBusy] = useState(false)
  const [closing, setClosing] = useState(false)
  /** Clôture du test : envoyer le mail au conseiller (comme « Terminer la conversation » du chat). */
  const [closeMail, setCloseMail] = useState(false)
  /** Clôture du test : archiver le dossier pour la page « Centre d'appels ». */
  const [closeArchive, setCloseArchive] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [openPrompt, setOpenPrompt] = useState<OpenPanel | null>(null)
  /** Version dont le prompt COMPLET (parties protégées) est déplié : masqué par défaut, une seule à la fois. */
  const [fullPromptVersion, setFullPromptVersion] = useState<string | null>(null)
  const [openAnalysis, setOpenAnalysis] = useState<number | null>(null)
  const [openDiff, setOpenDiff] = useState<OpenPanel | null>(null)
  const [openSnapshot, setOpenSnapshot] = useState(false)
  const [feedbackText, setFeedbackText] = useState('')
  const [showFeedback, setShowFeedback] = useState(false)
  const [promotionVersion, setPromotionVersion] = useState<OpenPanel | null>(null)
  const [labelTables, setLabelTables] = useState<Record<string, Record<string, string>> | null>(null)
  const [stopping, setStopping] = useState(false)
  /** FIL DE CONVERSATION : la mémoire de l'atelier (tours validés par promotion, rejoués au cycle suivant). */
  const [thread, setThread] = useState<PromptThread | null>(null)
  /** Question suivante saisie dans le bloc de conversation (repart avec tout l'historique). */
  const [nextQuestion, setNextQuestion] = useState('')
  /** Tour en cours de correction (un seul à la fois). */
  const [editingTurn, setEditingTurn] = useState<{ index: number; content: string } | null>(null)
  /** AGENT C — client simulé : mode activé par une case à cocher (désactivé par défaut). */
  const [clientMode, setClientMode] = useState(false)
  /** Brief du client : ce qu'il est et ce qu'il veut (remplace la question de test quand le mode est actif). */
  const [clientBrief, setClientBrief] = useState('')
  /**
   * Briefs DÉJÀ proposés par l'Agent C (« Générer projet ») : transmis au modèle au clic suivant pour qu'il
   * cherche un projet franchement différent, jamais une variante du même scénario.
   */
  const [clientBriefs, setClientBriefs] = useState<string[]>([])
  /** Un projet est en cours de rédaction par l'agent C (le bouton indique l'attente). */
  const [briefGenerating, setBriefGenerating] = useState(false)
  /** PROFONDEUR : nombre maximum de questions que le client simulé posera au cours du scénario. */
  const [clientDepth, setClientDepth] = useState(3)
  const [clientProvider, setClientProvider] = useState<AIProvider>('DEEPSEEK')
  /**
   * Qui promeut ? Décoché (défaut) : c'est VOUS qui validez chaque cycle. Coché : la dernière version du
   * cycle est promue automatiquement et le client enchaîne tout seul sa question suivante.
   */
  const [autoPromote, setAutoPromote] = useState(false)
  /** Question du client EN ATTENTE : affichée dans la conversation, corrigeable avant de lancer le cycle. */
  const [clientQuestion, setClientQuestion] = useState<string | null>(null)
  const [clientRunning, setClientRunning] = useState(false)
  const stopRef = useRef(false)
  const runningRef = useRef(false)
  /** Arrêt du scénario Agent C (la boucle s'arrête après le cycle en cours, rien n'est perdu). */
  const clientStopRef = useRef(false)
  /** Agents dont la conversation a déjà été restaurée : « Nouvelle conversation » reste respecté. */
  const restoredAgentsRef = useRef<string[]>([])
  const threadScrollRef = useRef<HTMLDivElement | null>(null)

  /**
   * Libellé lisible d'un code technique : la table du BACKEND fait foi (source unique, mêmes libellés
   * que côté serveur) ; un repli local évite tout affichage brut avant le chargement.
   */
  const label = useCallback(
    (table: string, code: string, fallback?: string) => labelTables?.[table]?.[code] ?? fallback ?? humanize(code),
    [labelTables],
  )

  /** Ouvre/ferme un panneau de détail : il s'affiche LÀ OÙ il a été demandé (sous l'itération concernée). */
  function togglePanel(
    current: OpenPanel | null,
    setter: (value: OpenPanel | null) => void,
    version: string,
    iteration: number | null,
  ) {
    if (current && current.version === version && current.iteration === iteration) {
      setter(null)
      return
    }
    setter({ version, iteration })
    setFullPromptVersion(null)
  }

  const campaign = detail?.campaign ?? null
  const remaining = campaign ? Math.max(0, campaign.requestedIterations - campaign.completedIterations) : 0

  useEffect(() => {
    let active = true
    fetchPromptOptimizationAgents()
      .then((zoneInfo) => {
        if (!active) return
        setAgents(zoneInfo)
        setLabelTables(zoneInfo.labels ?? null)
        const selectable = selectableZones(zoneInfo)
        const first = selectable.find((zone) => zone.optimizable) ?? selectable[0]
        if (first) {
          setAgentId(first.agentId)
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : 'Erreur de chargement.')
      })
    return () => {
      active = false
    }
  }, [])

  /**
   * CONVERSATION REPRISE au chargement de la page : le fil le plus récent de l'agent sélectionné. Une page
   * rechargée ne perd donc plus la conversation — seul le DÉTAIL de la campagne précédente n'est plus
   * affiché (l'IHM ne propose pas de reprendre une campagne).
   * <p>
   * Un fil ABANDONNÉ (« Nouvelle conversation ») n'est jamais repris, même après un F5 : les fils modifiés
   * avant l'abandon sont ignorés ; un fil créé depuis l'abandon l'est normalement.
   */
  useEffect(() => {
    if (!agentId || restoredAgentsRef.current.includes(agentId)) return
    restoredAgentsRef.current.push(agentId)
    let active = true
    fetchPromptThreads()
      .then((threads) => {
        if (!active) return
        const clearedAt = memoryClearedAt(agentId)
        const mine = threads.find((item) => item.agentId === agentId && item.turns.length > 0
          && Date.parse(item.updatedAt) > clearedAt)
        if (mine) {
          setThread(mine)
        }
      })
      .catch(() => {
        // Aucun fil lisible : la page reste parfaitement utilisable (démarrage sans mémoire).
      })
    return () => {
      active = false
    }
  }, [agentId])

  /** La conversation défile en bas : le dernier tour est toujours visible. */
  useEffect(() => {
    const node = threadScrollRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [thread?.turns.length, campaign?.campaignId])

  /**
   * Le fil courant est aussi gardé dans une REF : la fin d'un scénario automatique doit ouvrir le bilan du fil
   * qui vient d'être créé, alors que l'état `thread` capturé au clic sur GO est encore l'ancien.
   */
  useEffect(() => {
    threadRef.current = thread
  }, [thread])

  /**
   * L'IHM ne propose PAS de reprendre une campagne passée (l'historique n'est pas consulté) : seul l'état de
   * la campagne courante est rafraîchi. Le backend conserve ses fichiers — une nouvelle campagne clôt
   * automatiquement les précédentes (`CANCELLED`). Le FIL de conversation, lui, est repris sous l'agent.
   */
  const refresh = useCallback(async (campaignId: string) => {
    try {
      const current = await fetchPromptCampaign(campaignId)
      setDetail(current)
      if (current.thread) setThread(current.thread)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur de rafraîchissement.')
    }
  }, [])

  /** Boucle d'itérations pilotée par l'IHM : une requête = une itération complète. */
  const drive = useCallback(
    async (campaignId: string) => {
      if (runningRef.current) return
      runningRef.current = true
      stopRef.current = false
      setStopping(false)
      setBusy(true)
      setError(null)
      try {
        for (;;) {
          const current = await fetchPromptCampaign(campaignId)
          setDetail(current)
          const state = current.campaign
          if (stopRef.current) break
          if (state.status !== 'RUNNING' && state.status !== 'CREATED') break
          if (state.completedIterations >= state.requestedIterations) break
          if (state.completedIterations >= state.maxIterations) break
          await iteratePromptCampaign(campaignId)
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Erreur pendant l'itération.")
      } finally {
        runningRef.current = false
        setBusy(false)
        await refresh(campaignId)
      }
    },
    [refresh],
  )

  async function guard(action: () => Promise<void>) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Action impossible.')
    } finally {
      setBusy(false)
    }
  }

  const selectedZone = selectableZones(agents).find((zone) => zone.agentId === agentId) ?? null
  /** Nombre d'ÉCHANGES déjà figés dans la conversation (question + réponse d'une version promue). */
  const threadExchanges = thread ? thread.turns.filter((turn) => turn.role === 'assistant').length : 0
  /** Une conversation est EN COURS : elle s'enchaîne depuis son propre bloc, pas depuis « Question de test ». */
  const threadActive = threadExchanges > 0
  const canStart = Boolean(agents?.enabled && selectedZone?.optimizable && question.trim() && iterations >= 1)
  const zonesShown = selectableZones(agents)

  /**
   * Décisions humaines : possibles dès que la campagne ne tourne pas (une promotion refuse d'écraser un
   * traitement en cours).
   */
  const canDecide = Boolean(campaign && campaign.status !== 'RUNNING' && campaign.status !== 'STOP_REQUESTED')
  /**
   * Itérations ENCORE disponibles sur le plafond CUMULÉ (demandées + ajoutées) : une reprise ne peut jamais
   * dépasser `maxIterations`. Valeur réellement appliquée = saisie bornée à ce reste.
   */
  const maxExtraIterations = campaign ? Math.max(0, campaign.maxIterations - campaign.requestedIterations) : 0
  const extraToAdd = Math.min(extraIterations, maxExtraIterations)

  /**
   * Une itération ne produit une NOUVELLE version que si l'Agent A a réellement modifié la zone.
   * Sans nouvelle version : pas de diff (Vn → Vn n'aurait aucun sens).
   */
  const producedNewVersion = (iteration: PromptIteration) =>
    iteration.resultingVersion !== iteration.promptVersion

  /**
   * Une version est PROMOUVABLE si elle n'est pas DÉJÀ celle du fichier de production. Depuis que le mode
   * automatique de l'Agent C ACCEPTE sans écrire, la promotion reste possible après l'acceptation : c'est
   * l'état réel du fichier qui décide, plus le statut de la campagne.
   */
  const promotableVersion = (version: string): boolean => Boolean(detail
    && detail.versions.some((item) => item.version === version && !item.production && !item.applied))

  /**
   * Itération qui a RÉELLEMENT répondu avec cette version ({@code promptVersion}, réponse non vide).
   * <p>
   * C'est la clé du modèle « je lis une réponse, je valide le prompt qui l'a produite » : une version ne
   * peut être jugée que par la réponse qu'elle a effectivement donnée.
   */
  const answeringIteration = (version: string): PromptIteration | null =>
    detail?.iterations.find((iteration) => iteration.promptVersion === version && Boolean(iteration.coachResponse))
      ?? null

  /** Campagne courante clôturée par une décision humaine : la conversation peut alors s'enchaîner. */
  const campaignDecided = !campaign
    || campaign.status === 'ACCEPTED' || campaign.status === 'REJECTED' || campaign.status === 'CANCELLED'
  /**
   * L'Agent A n'a proposé AUCUNE modification : la seule version connue est celle du snapshot (production).
   * Il n'y a donc rien à promouvoir — mais il faut pouvoir ACCEPTER la campagne, sinon la conversation reste
   * bloquée (aucune décision, donc aucune réponse de l'IA dans le fil).
   */
  const noVersionProduced = Boolean(detail && detail.versions.length <= 1)
  /**
   * Action DIRECTE, sans confirmation : accepter une campagne sans modification n'écrit RIEN (le backend le
   * vérifie : ni sauvegarde, ni réécriture du prompt). Il n'y a donc rien à écraser, donc rien à confirmer.
   */
  const canAcceptUnchanged = Boolean(campaign && canDecide && noVersionProduced && !campaign.promotedVersion)
  /** La campagne courante a-t-elle déjà son échange dans la conversation (promotion faite) ? */
  const campaignInThread = Boolean(campaign && thread?.turns.some((turn) => turn.campaignId === campaign.campaignId))
  const canContinue = Boolean(threadActive && nextQuestion.trim() && !busy && campaignDecided)

  // --- Agent C : le CLIENT simulé conduit la conversation ----------------------------------------------

  /** Nombre de questions du client DÉJÀ traitées (une réponse de l'IA par question). */
  const clientAsked = threadExchanges
  const clientDepthReached = clientAsked >= clientDepth
  /**
   * Numéro de la question AFFICHÉE (celle du bloc « Question du client (Agent C) ») : les questions déjà traitées
   * plus une. Le `+ 1` supplémentaire qui figurait ici comptait la question en cours DEUX fois (la première
   * question s'affichait « n°2 »).
   */
  const clientTurnNumber = clientAsked + 1

  /** Question suivante du client : il reçoit le brief, les TROIS chiffres du dossier et la conversation. */
  async function askClient(turnNumber: number, threadId: string | null): Promise<string | null> {
    const turn = await fetchClientQuestion({
      threadId,
      brief: clientBrief.trim(),
      turnNumber,
      depth: clientDepth,
      provider: clientProvider,
    })
    const texte = turn.question.trim()
    if (turn.endConversation || !texte) {
      setNotice(texte
        ? `Le client met fin au scénario : « ${texte} »`
        : `Le client n'a plus de question — scénario terminé${turn.reason ? ` (${turn.reason})` : ''}.`)
      return null
    }
    return texte
  }

  /**
   * UN cycle d'optimisation sur la question du client. Le fil est transmis pour que le cycle reparte avec TOUT
   * l'historique ; le prompt testé est celui qui vient d'être retenu — celui en production, ou celui hérité du
   * cycle précédent quand `base` est fourni (chaînage du mode automatique : les cycles s'accumulent sans
   * qu'aucune écriture n'ait eu lieu).
   */
  async function startCycle(questionText: string, threadId: string | null, base: VersionRef | null) {
    const started = await startPromptCampaign({
      agentId,
      question: questionText,
      iterations,
      zoneKey: ZONE_KEY,
      provider,
      controllerProvider,
      editorProvider,
      threadId,
      fromCampaignId: base?.campaignId ?? null,
      fromVersion: base?.version ?? null,
    })
    setThread(started.thread)
    setEditingTurn(null)
    return {
      campaignId: started.campaign.campaignId,
      threadId: started.thread?.threadId ?? threadId,
    }
  }

  /**
   * ACCEPTATION AUTOMATIQUE (mode Agent C) : la dernière version du cycle est acceptée POUR LA CONVERSATION —
   * sa réponse entre dans le fil, le client garde sa mémoire — mais le PROMPT DE PRODUCTION N'EST PAS ÉCRIT.
   * L'écriture reste une décision humaine, prise à la fin du scénario : bouton « PROMOUVOIR » du bilan
   * « Comparaison de la conversation — début ↔ fin ». Aucune modification à accepter : la version de référence
   * est acceptée telle quelle (aucune écriture non plus).
   */
  async function acceptCycle(campaignId: string): Promise<VersionRef | null> {
    const current = await fetchPromptCampaign(campaignId)
    setDetail(current)
    const state = current.campaign
    if (state.promotedVersion) {
      return { campaignId, version: state.promotedVersion }
    }
    if (state.status === 'REJECTED' || state.status === 'CANCELLED') return null
    const version = state.currentCandidateVersion || state.basePromptVersion
    const result = await acceptPromptVersion(campaignId, version)
    setNotice(`Cycle accepté pour la conversation : ${result.message}`)
    await refresh(campaignId)
    return { campaignId, version }
  }

  /**
   * SCÉNARIO DU CLIENT (Agent C) : le client pose une question → un cycle l'optimise → la version est promue
   * (par vous, ou automatiquement) → sa réponse entre dans la conversation → le client lit cette réponse et
   * pose la question suivante… et ainsi de suite jusqu'à la PROFONDEUR choisie (ou l'arrêt par STOP).
   */
  async function clientStep(pending?: string) {
    let question = pending?.trim() ?? ''
    let asked = clientAsked
    let currentThreadId = thread?.threadId ?? null
    // CHAÎNAGE DES CYCLES (mode automatique) : le premier cycle hérite la zone de la dernière version RETENUE du
    // fil qui n'est pas en production, et chaque cycle suivant hérite de celle qu'il vient de retenir — le bilan
    // « début ↔ fin » est donc réellement CUMULATIF. En mode manuel, l'humain promeut : le cycle repart du
    // prompt de production, qui contient déjà sa décision (aucun chaînage).
    let base: VersionRef | null = autoPromote ? await promotionTargetOf() : null
    for (;;) {
      if (clientStopRef.current) break
      if (!question) {
        if (asked >= clientDepth) {
          setNotice(`Profondeur atteinte : ${asked} question(s) du client — scénario terminé.`)
          break
        }
        setClientQuestion('le client rédige sa question…')
        const posee = await askClient(asked + 1, currentThreadId)
        if (!posee) {
          setClientQuestion(null)
          break
        }
        setClientQuestion(posee)
        if (!autoPromote) break
        question = posee
      }
      setClientQuestion(null)
      const started = await startCycle(question, currentThreadId, base)
      question = ''
      if (!started) break
      currentThreadId = started.threadId
      setNotice(`Cycle ${started.campaignId} : ${iterations} itération(s) sur la question du client.`)
      await drive(started.campaignId)
      asked += 1
      if (!autoPromote) {
        setNotice(`Question ${asked}/${clientDepth} traitée. Validez une version (promouvoir, ou « accepter sans changement »), puis « QUESTION SUIVANTE ».`)
        break
      }
      if (clientStopRef.current) break
      const retenue = await acceptCycle(started.campaignId)
      // Le cycle suivant part de la version qui vient d'être retenue : les améliorations s'ACCUMULENT.
      if (retenue) base = retenue
    }
  }

  /** Entrée du scénario Agent C (GO, « poser cette question », QUESTION SUIVANTE) : une seule exécution. */
  async function startClientScenario(pending?: string) {
    if (clientRunning || busy) return
    if (!clientBrief.trim()) {
      setError('Décrivez le client (son profil et son projet) avant de lancer le scénario.')
      return
    }
    clientStopRef.current = false
    setClientRunning(true)
    setError(null)
    try {
      await clientStep(pending)
      // FIN DU SCÉNARIO EN MODE AUTOMATIQUE : rien n'a été écrit dans le prompt de production (chaque cycle a
      // seulement été ACCEPTÉ pour la conversation). On affiche donc le bilan début ↔ fin, avec le bouton
      // « PROMOUVOIR » : c'est l'humain qui décide, en connaissance de cause, à la toute fin.
      if (autoPromote && threadRef.current) {
        await showThreadComparison(threadRef.current.threadId)
        setNotice('Scénario terminé : le prompt de production n\'a PAS été modifié. Comparez le prompt initial et le '
          + 'prompt final ci-dessous, puis « PROMOUVOIR » la version retenue — ou non.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Scénario interrompu.')
    } finally {
      setClientRunning(false)
    }
  }

  /** STOP du scénario : le cycle en cours se termine proprement (campagne en pause), puis la boucle s'arrête. */
  function handleClientStop() {
    clientStopRef.current = true
    setNotice("Arrêt demandé : l'itération en cours se termine, sa réponse est sauvegardée, puis le scénario s'arrête.")
    if (campaign && (campaign.status === 'RUNNING' || campaign.status === 'CREATED')) handleStop()
  }

  async function handleStart() {
    await guard(async () => {
      setThreadComparison(null)
      setThreadTarget(null)
      const started = await startPromptCampaign({
        agentId,
        question: question.trim(),
        iterations,
        zoneKey: ZONE_KEY,
        provider,
        controllerProvider,
        editorProvider,
      })
      setThread(started.thread)
      setNotice(`Campagne ${started.campaign.campaignId} créée : snapshot de référence figé.`)
      await drive(started.campaign.campaignId)
    })
  }

  /**
   * QUESTION SUIVANTE : un NOUVEAU cycle démarre avec TOUT l'historique de la conversation. Le prompt utilisé
   * est celui qui vient d'être promu (il est en production) : la conversation teste donc la version acceptée
   * sur une question de suivi, exactement comme dans le chat.
   */
  async function handleContinue() {
    const text = nextQuestion.trim()
    if (!text || !thread) return
    await guard(async () => {
      const started = await startPromptCampaign({
        agentId,
        question: text,
        iterations,
        zoneKey: ZONE_KEY,
        provider,
        controllerProvider,
        editorProvider,
        threadId: thread.threadId,
      })
      setThread(started.thread)
      setNextQuestion('')
      setEditingTurn(null)
      setThreadComparison(null)
      setThreadTarget(null)
      setNotice(`Nouveau cycle ${started.campaign.campaignId} démarré avec ${threadExchanges} échange(s) d'historique.`)
      await drive(started.campaign.campaignId)
    })
  }

  /**
   * Nouvelle conversation : le prochain cycle repart SANS mémoire (les fils précédents restent sur disque).
   * <p>
   * Si l'une des deux cases est cochée, le scénario qui se termine est d'abord CLÔTURÉ comme une conversation
   * de la page coach : même pipeline (agent de suivi → dossier → mail conseiller → score de sens commercial),
   * et le dossier est archivé (ou non) pour la page « Centre d'appels ».
   */
  async function handleNewConversation() {
    if ((closeMail || closeArchive) && thread !== null && thread.turns.length > 0) {
      setClosing(true)
      try {
        const result = await closePromptThread(thread.threadId, {
          sendMail: closeMail,
          archive: closeArchive,
          provider,
        })
        setNotice(closureNotice(result.response.status, closeMail, closeArchive, result.response.warnings ?? []))
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Clôture impossible')
      } finally {
        setClosing(false)
      }
    }
    setThread(null)
    setNextQuestion('')
    setEditingTurn(null)
    setThreadComparison(null)
    setThreadTarget(null)
    // Nouveau scénario : le projet précédent n'a plus à être évité, on repart d'une page blanche.
    setClientBriefs([])
    // La remise à zéro est MÉMORISÉE : sans cela, un simple F5 faisait réapparaître le fil abandonné
    // (il est repris depuis le serveur au chargement). Un fil créé après cet instant sera, lui, repris.
    markMemoryCleared(agentId)
    if (!closeMail && !closeArchive) {
      setNotice("Nouvelle conversation : le prochain cycle démarrera sans historique (aucune mémoire). La conversation abandonnée ne sera plus rechargée, même après un rafraîchissement de la page.")
    }
  }

  /**
   * Cases à cocher de clôture + bouton « Nouvelle conversation » : quand un test s'arrête, on peut vouloir
   * envoyer le mail conseiller et/ou retrouver la conversation dans la page « Centre d'appels ».
   */
  function newConversationGroup(disabled: boolean) {
    return (
      <>
        <label
          className="plab-switch"
          title="Envoie le mail au conseiller à la fin du test (même dossier, même score commercial que depuis la page coach)."
        >
          <input
            type="checkbox"
            checked={closeMail}
            disabled={busy || closing}
            onChange={(event) => setCloseMail(event.target.checked)}
          />
          email
        </label>
        <label
          className="plab-switch"
          title="Archive le dossier du test : la conversation devient consultable dans la page Centre d'appels."
        >
          <input
            type="checkbox"
            checked={closeArchive}
            disabled={busy || closing}
            onChange={(event) => setCloseArchive(event.target.checked)}
          />
          centre d'appel
        </label>
        <button type="button" onClick={() => void handleNewConversation()} disabled={disabled || closing}>
          <Plus size={15} /> {closing ? 'Clôture…' : 'Nouvelle conversation'}
        </button>
      </>
    )
  }

  /**
   * « GÉNÉRER PROJET » (Agent C) : l'agent cherche lui-même un client et un projet correspondant à l'agent de
   * coach sélectionné (crédit à la consommation, épargne, assurance…) et l'écrit dans le champ « Brief du
   * client ». Un nouvel appui propose un projet DIFFÉRENT : les propositions précédentes sont transmises au
   * modèle. Rien n'est écrit côté backend : le texte reste modifiable, et le brief reste FIGÉ ensuite pour tout
   * le scénario (il n'est relu qu'au démarrage du scénario).
   */
  async function handleGenerateProject() {
    if (briefGenerating || clientRunning || !agentId) return
    setBriefGenerating(true)
    setError(null)
    try {
      const proposal = await fetchGeneratedClientBrief({
        agentId,
        provider: clientProvider,
        previousBriefs: [...clientBriefs, ...(clientBrief.trim() ? [clientBrief.trim()] : [])],
      })
      setClientBrief(proposal.brief)
      setClientBriefs((previous) => [...previous, proposal.brief])
      const montant = typeof proposal.montantProjet === 'number'
        ? `${proposal.montantProjet.toLocaleString('fr-FR')} € à financer — `
        : ''
      setNotice(`Projet proposé par l’agent C (${providerLabel(clientProvider)}) : ${montant}${proposal.reason || 'relisez-le et modifiez-le si besoin, puis lancez le scénario.'}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Projet impossible à générer.')
    } finally {
      setBriefGenerating(false)
    }
  }

  /**
   * Correction d'un tour : l'humain garde la main sur la réponse qui sera rejouée au cycle suivant (une
   * réponse mal attribuée viciérait l'optimisation du prompt).
   */
  async function handleSaveTurn() {
    if (!thread || !editingTurn) return
    const { index, content } = editingTurn
    await guard(async () => {
      setThread(await updatePromptTurn(thread.threadId, index, content.trim()))
      setEditingTurn(null)
      setNotice("Réponse corrigée : c'est ce texte qui sera rejoué dans les prochains cycles.")
    })
  }

  /**
   * Arrêt GRACIEUX (§11) : il reste possible PENDANT la campagne (la boucle est pilotée par l'IHM),
   * l'appel IA en cours va au bout, sa réponse est sauvegardée, puis la campagne passe en pause.
   */
  function handleStop() {
    if (!campaign || stopping) return
    stopRef.current = true
    setStopping(true)
    setError(null)
    setNotice("Arrêt demandé : l'itération en cours se termine, sa réponse est sauvegardée, puis la campagne passe en pause.")
    stopPromptCampaign(campaign.campaignId)
      .then(() => setStopping(false))
      .catch((err) => {
        setStopping(false)
        setError(err instanceof Error ? err.message : 'Arrêt impossible.')
      })
  }

  /**
   * Reprise (PAUSED / COMPLETED) : le nombre d'itérations ajoutées est RÉGLABLE. Un avis saisi mais non
   * enregistré est transmis AVANT la reprise — sinon le texte serait perdu (l'avis est prioritaire).
   */
  async function handleResume(withExtra: boolean) {
    if (!campaign) return
    const extra = withExtra ? extraToAdd : 0
    const feedback = feedbackText.trim()
    await guard(async () => {
      if (feedback) {
        await sendPromptHumanFeedback(campaign.campaignId, feedback)
        setFeedbackText('')
        setShowFeedback(false)
      }
      await resumePromptCampaign(campaign.campaignId, extra)
      setNotice([
        feedback ? "Avis enregistré et appliqué par l'éditeur" : '',
        extra > 0 ? `${extra} itération(s) ajoutée(s) au cycle cumulé.` : '',
      ].filter(Boolean).join(' · ') || 'Campagne reprise.')
      await refresh(campaign.campaignId)
      await drive(campaign.campaignId)
    })
  }

  async function handleFeedback() {
    if (!campaign || !feedbackText.trim()) return
    await guard(async () => {
      await sendPromptHumanFeedback(campaign.campaignId, feedbackText.trim())
      setFeedbackText('')
      setShowFeedback(false)
      setNotice("Avis enregistré : il sera appliqué par l'éditeur avant le prochain appel au Coach.")
      await refresh(campaign.campaignId)
    })
  }

  /**
   * « Enregistrer et reprendre » : l'avis est ENREGISTRÉ d'abord (sinon il serait perdu), puis le cycle est
   * prolongé du nombre d'itérations choisi et la boucle repart.
   */
  async function handleFeedbackAndResume() {
    if (!campaign || !feedbackText.trim()) return
    const extra = extraToAdd
    await guard(async () => {
      await sendPromptHumanFeedback(campaign.campaignId, feedbackText.trim())
      setFeedbackText('')
      setShowFeedback(false)
      await resumePromptCampaign(campaign.campaignId, extra)
      setNotice(extra > 0
        ? `Avis enregistré et appliqué par l'éditeur · ${extra} itération(s) ajoutée(s) au cycle cumulé.`
        : "Avis enregistré et appliqué par l'éditeur.")
      await refresh(campaign.campaignId)
      await drive(campaign.campaignId)
    })
  }

  async function handlePromote(version: string) {
    if (!campaign) return
    await guard(async () => {
      const result = await promotePromptVersion(campaign.campaignId, version)
      setPromotionVersion(null)
      setNotice(result.message)
      await refresh(campaign.campaignId)
    })
  }

  /**
   * ACCEPTER SANS CHANGEMENT : l'Agent A n'a proposé aucune modification, donc accepter la campagne ne réécrit
   * RIEN (le backend le vérifie : ni sauvegarde, ni prompt). AUCUNE confirmation n'est demandée — il n'y a rien
   * à écraser. C'est ce qui débloque l'enchaînement : la réponse de l'IA entre dans la conversation.
   */
  async function handleAcceptUnchanged() {
    if (!campaign) return
    await guard(async () => {
      const result = await promotePromptVersion(campaign.campaignId, campaign.basePromptVersion)
      setNotice(result.message)
      await refresh(campaign.campaignId)
    })
  }

  async function handleReject() {
    if (!campaign) return
    await guard(async () => {
      await rejectPromptCampaign(campaign.campaignId)
      setNotice('Campagne refusée : aucune version promue, tout reste consultable.')
      await refresh(campaign.campaignId)
    })
  }

  async function handleCompare() {
    if (!campaign) return
    await guard(async () => {
      setComparison(await fetchPromptComparison(campaign.campaignId))
    })
  }

  /**
   * BILAN de la CONVERSATION : le prompt du premier cycle face au prompt retenu à la fin. La comparaison d'une
   * campagne ne montre qu'une question ; celle-ci montre tout le scénario — c'est la lecture « qu'est-ce que
   * cette conversation a changé au prompt ? » demandée à la fin, et le point de décision depuis que le mode
   * automatique n'écrit plus rien : on regarde, puis on promeut (ou pas).
   */
  async function showThreadComparison(threadId: string) {
    await guard(async () => {
      const bilan = await fetchPromptThreadComparison(threadId)
      setThreadComparison(bilan)
      setThreadTarget(await promotionTargetOf())
      setThreadComparisonApplied(await versionApplied(bilan.currentCampaignId, bilan.currentVersion))
    })
  }

  /**
   * Dernière version RETENUE par la conversation qui n'est PAS encore celle du prompt de production, du cycle le
   * plus récent au plus ancien.
   * <p>
   * C'est la version à promouvoir à la fin d'un scénario : une version acceptée sans écriture reste promouvable
   * (le fil a gardé sa réponse), même si un cycle ULTÉRIEUR n'a rien proposé — sans cette recherche, le bilan
   * affichait « déjà appliquée » et ne proposait plus aucun bouton alors qu'une amélioration attendait.
   */
  async function promotionTargetOf(): Promise<PromotionTarget | null> {
    const current = threadRef.current
    if (!current) return null
    for (const campaignId of [...current.campaignIds].reverse()) {
      try {
        const state = await fetchPromptCampaign(campaignId)
        const version = state.campaign.promotedVersion
        if (!version) continue
        const view = state.versions.find((item) => item.version === version)
        if (!view || view.applied) continue
        return { campaignId, version, editableSection: view.editableSection, prompt: view.prompt }
      } catch {
        // Campagne illisible : on essaie la précédente (au pire, aucun bouton n'est proposé).
      }
    }
    return null
  }

  /** La version d'une campagne est-elle DÉJÀ appliquée au prompt de production ? */
  async function versionApplied(campaignId: string, version: string): Promise<boolean> {
    try {
      const state = await fetchPromptCampaign(campaignId)
      return state.versions.find((item) => item.version === version)?.applied ?? false
    } catch {
      return false
    }
  }

  /**
   * Version visée par le bilan : la version RETENUE à promouvoir si elle existe, sinon la version finale (déjà en
   * production → simple accusé de réception).
   */
  function threadPanelTarget(): PromotionTarget | null {
    if (threadTarget) return threadTarget
    if (!threadComparison) return null
    return {
      campaignId: threadComparison.currentCampaignId,
      version: threadComparison.currentVersion,
      editableSection: threadComparison.currentEditableSection,
      prompt: threadComparison.currentPrompt,
    }
  }

  /** DÉCISION FINALE : l'humain promeut la version du bilan (seule action qui écrit le prompt de production). */
  async function handlePromoteThreadVersion() {
    const cible = threadPanelTarget()
    if (!cible) return
    await guard(async () => {
      const result = await promotePromptVersion(cible.campaignId, cible.version)
      setNotice(`Promotion : ${result.message}`)
      setThreadTarget(null)
      setThreadComparisonApplied(true)
      await refresh(cible.campaignId)
    })
  }

  async function handleThreadCompare() {
    const current = threadRef.current
    if (!current) return
    await showThreadComparison(current.threadId)
  }

  /** Bouton unique du bilan de conversation (mode manuel et mode Agent C). */
  function threadCompareButton() {
    return (
      <button
        type="button"
        onClick={handleThreadCompare}
        disabled={busy || !thread || threadExchanges === 0}
        title={threadExchanges === 0
          ? 'Aucun échange validé : il n\'y a rien à comparer pour le moment.'
          : 'Compare le prompt du début de la conversation avec celui en vigueur à la fin.'}
      >
        <Braces size={15} /> COMPARER LE PROMPT INITIAL ET LE PROMPT FINAL
      </button>
    )
  }

  /**
   * BILAN « Comparaison de la conversation — début ↔ fin » : c'est le POINT DE DÉCISION du mode automatique,
   * puisqu'aucun cycle n'a rien écrit dans le prompt de production.
   * <p>
   * La « fin » affichée est la dernière version RETENUE par la conversation qui n'est PAS encore dans le prompt
   * de production (voir `threadPanelTarget`) : quand les derniers cycles n'ont rien proposé, c'est une version
   * d'un cycle ANTÉRIEUR — celle qu'on juge utile d'adopter — au lieu d'un « rien à promouvoir » trompeur.
   */
  function threadComparisonPanel() {
    const bilan = threadComparison
    const cible = threadPanelTarget()
    if (!bilan || !cible) return null
    const debut = bilan.baseEditableSection
    const fin = cible.editableSection
    const estVersionFinale = cible.campaignId === bilan.currentCampaignId
      && cible.version === bilan.currentVersion
    const rienAPromouvoir = !threadTarget
    // Les deux prompts complets peuvent venir de la MÊME campagne (dernière version retenue) : on ne répète
    // alors pas le second bloc.
    const finalDiffere = bilan.currentVersion !== cible.version
      || bilan.currentCampaignId !== cible.campaignId
    return (
      <section className="mkt-card">
        <h2><Braces size={16} /> Comparaison de la conversation — début ↔ fin</h2>
        <p className="plab-hint">
          Prompt <b>initial {bilan.baseVersion}</b> (au premier échange) → version <b>{cible.version}</b> retenue par
          le cycle <span className="plab-mono">{cible.campaignId}</span>.{' '}
          {estVersionFinale
            ? bilan.summary
            : "Les derniers cycles n'ont proposé aucune modification : la zone retenue à la fin est donc déjà "
              + "celle du prompt de production, et la version à promouvoir est celle du cycle ci-dessus."}
        </p>
        <p className="plab-hint">
          Agent <b>{bilan.agentLibelle}</b> ·{' '}
          {bilan.zoneKey === 'principal'
            ? <>zone transverse <code>agent/principal.txt</code></>
            : <>zone de l'agent <code>agent/{bilan.zoneFile}</code></>}
          {' · '}{bilan.cycleCount} cycle(s) · {bilan.iterationCount} itération(s) ·
          {' '}{bilan.promotionCount} version(s) retenue(s)
        </p>
        {/* DÉCISION HUMAINE, À LA FIN DU SCÉNARIO : comparer puis promouvoir (ou pas). */}
        <div className="plab-actions">
          {rienAPromouvoir && threadComparisonApplied ? (
            <span className="plab-tag ok">
              ✓ Rien à promouvoir : le prompt de production contient déjà la version retenue par la conversation
            </span>
          ) : (
            <button
              type="button"
              className="plab-primary"
              disabled={busy}
              onClick={() => handlePromoteThreadVersion()}
              title="Écrit cette version dans le prompt de production (le prompt précédent est sauvegardé)."
            >
              <ThumbsUp size={15} /> PROMOUVOIR {cible.version} EN PRODUCTION
            </button>
          )}
          <small className="plab-snippet">
            Rien n'est écrit avant ce clic : les cycles enchaînés ont seulement été acceptés pour la conversation,
            le prompt de production est resté celui qu'on compare ci-dessous.
          </small>
        </div>
        <div className="plab-two">
          <div className="plab-box">
            <strong>VERSION INITIALE {bilan.baseVersion} — début de la conversation</strong>
            <p className="plab-hint">Cycle <span className="plab-mono">{bilan.baseCampaignId}</span></p>
            <pre className="plab-pre">{debut || '(zone vide)'}</pre>
          </div>
          <div className="plab-box">
            <strong>
              VERSION RETENUE {cible.version} — {rienAPromouvoir ? 'déjà en production' : 'à promouvoir'}
              {estVersionFinale ? '' : ' (choisie par un cycle antérieur)'}
            </strong>
            <p className="plab-hint">Cycle <span className="plab-mono">{cible.campaignId}</span></p>
            <pre className="plab-pre">{fin || '(zone vide)'}</pre>
          </div>
        </div>
        {debut === fin ? (
          <p className="plab-hint">
            Aucune ligne de la zone n'a changé pendant cette conversation : le bilan est donc un prompt identique.
          </p>
        ) : (
          <>
            <h3>Changements de la zone {bilan.baseVersion} → {cible.version}</h3>
            <div className="plab-diff">
              {compactDiff(diffLines(debut, fin)).map((line, index) => (
                <div key={index} className={`plab-diff-line ${line.type}`}>
                  <span className="plab-diff-sign">
                    {line.type === 'add' ? '+' : line.type === 'remove' ? '−' : ' '}
                  </span>
                  <span>{line.text}</span>
                </div>
              ))}
            </div>
          </>
        )}
        <details className="plab-help">
          <summary>Voir les prompts complets (parties protégées incluses)</summary>
          <strong>PROMPT INITIAL {bilan.baseVersion}</strong>
          <pre className="plab-pre">{bilan.basePrompt}</pre>
          <strong>PROMPT RETENU {cible.version} (cycle {cible.campaignId})</strong>
          <pre className="plab-pre">{cible.prompt}</pre>
          {finalDiffere && (
            <>
              <strong>DERNIÈRE VERSION {bilan.currentVersion} (cycle {bilan.currentCampaignId})</strong>
              <pre className="plab-pre">{bilan.currentPrompt}</pre>
            </>
          )}
        </details>
      </section>
    )
  }

  /**
   * Revient à la configuration pour démarrer une NOUVELLE campagne (l'IHM ne conserve pas d'historique de
   * CAMPAGNES : les fichiers de la campagne précédente restent sur disque, mais ne sont plus proposés).
   * La CONVERSATION, elle, n'est pas perdue : c'est elle qui porte la mémoire des cycles suivants.
   */
  function newCampaign() {
    stopRef.current = true
    setDetail(null)
    setComparison(null)
    setOpenPrompt(null)
    setFullPromptVersion(null)
    setOpenDiff(null)
    setOpenAnalysis(null)
    setOpenSnapshot(false)
    setShowFeedback(false)
    setFeedbackText('')
    setPromotionVersion(null)
    setEditingTurn(null)
    setThreadComparison(null)
    setThreadTarget(null)
    setNotice(null)
    setError(null)
  }

  const previousSectionFor = (version: string): string => {
    if (!detail) return ''
    const versions = detail.versions
    const index = versions.findIndex((item) => item.version === version)
    return index > 0 ? versions[index - 1].editableSection : ''
  }

  /** Panneau « prompt d'une version » : zone modifiable + prompt complet replié. */
  function promptPanel(panel: OpenPanel | null) {
    if (!panel || !detail) return null
    const version = detail.versions.find((item) => item.version === panel.version)
    if (!version) return null
    const fullPromptShown = fullPromptVersion === version.version
    const parts = version.prompt.split(version.editableSection)
    return (
      <div className="plab-panel">
        <h3>Zone modifiable {version.version} — la seule partie que l'Agent A peut réécrire</h3>
        <pre className="plab-pre">{version.editableSection}</pre>
        <div className="plab-actions">
          <button type="button" onClick={() => setFullPromptVersion(fullPromptShown ? null : version.version)}>
            {fullPromptShown ? <X size={14} /> : <Eye size={14} />}
            {fullPromptShown ? 'Masquer' : 'Afficher'} le prompt complet ({version.version})
          </button>
        </div>
        {fullPromptShown && (
          <>
            <h3>Prompt complet {version.version} — partie protégée / ZONE MODIFIABLE / partie protégée</h3>
            <pre className="plab-pre">{parts.length > 1
              ? parts.map((part, index) => (
                <span key={index}>
                  {part}
                  {index < parts.length - 1 && <mark className="plab-zone">{version.editableSection}</mark>}
                </span>
              ))
              : version.prompt}</pre>
          </>
        )}
      </div>
    )
  }

  /** Panneau « changements » : diff de la seule zone éditable vers la version demandée. */
  function diffPanel(panel: OpenPanel | null) {
    if (!panel || !detail) return null
    const version = detail.versions.find((item) => item.version === panel.version)
    if (!version) return null
    return (
      <div className="plab-panel">
        <h3>Changements de la zone éditable vers {version.version}</h3>
        <div className="plab-diff">
          {compactDiff(diffLines(previousSectionFor(version.version), version.editableSection)).map((line, index) => (
            <div key={index} className={`plab-diff-line ${line.type}`}>
              <span className="plab-diff-sign">{line.type === 'add' ? '+' : line.type === 'remove' ? '−' : ' '}</span>
              <span>{line.text}</span>
            </div>
          ))}
        </div>
      </div>
    )
  }

  /** Contenu de la confirmation de promotion (le cadre est fourni par l'appelant). */
  function promotionPanel(panel: OpenPanel | null) {
    if (!panel || !campaign) return null
    const answered = answeringIteration(panel.version)
    return (
      <>
        <h2><ThumbsUp size={16} /> Confirmer la promotion</h2>
        <p>
          Vous êtes sur le point de remplacer la zone du prompt de l'agent <b>{campaign.agentLibelle}</b> par
          la version <b>{panel.version}</b> issue de cette campagne.
        </p>
        <p>Le prompt actuel sera conservé dans l'historique (retour arrière possible).</p>
        {answered ? (
          <p className="plab-ok">
            La <b>réponse de l'itération {answered.iterationNumber}</b> — celle qui a été produite par cette
            version — rejoindra la conversation de l'atelier : la mémoire contiendra donc exactement la réponse
            du prompt promu.
          </p>
        ) : (
          <p className="plab-hint">
            Cette version <b>n'a pas encore répondu</b> : sa réponse sera <b>générée avec ce prompt</b> (un appel IA
            supplémentaire) puis rejoindra la conversation de l'atelier.
          </p>
        )}
        <div className="plab-actions">
          <button type="button" onClick={() => setPromotionVersion(null)} disabled={busy}>ANNULER</button>
          <button type="button" className="plab-primary" onClick={() => handlePromote(panel.version)} disabled={busy}>
            <Check size={15} /> CONFIRMER
          </button>
        </div>
      </>
    )
  }

  return (
    <div className="logs-shell plab-page">
      <header className="logs-header">
        <a href="#/" className="logs-back"><ArrowLeft size={18} /> Retour au chat</a>
        <h1><Wand2 size={22} /> Atelier d'amélioration des prompts</h1>
        <span className={`logs-status ${agents?.enabled ? 'ok' : 'ko'}`}>
          <span className="status-dot" />
          {agents?.enabled
            ? `Plafond cumulé : ${agents.maxIterations} itérations (limite technique ${agents.hardMaxIterations})`
            : 'Module désactivé'}
        </span>
      </header>

      {error && <div className="logs-error">{error}</div>}
      {notice && <div className="plab-notice">{notice}</div>}

      {/* 1) Configuration : agent, zone, question, itérations */}
      <section className="mkt-card">
        <h2><Bot size={16} /> Campagne d'optimisation</h2>
        <label className="plab-switch">
          <input
            type="checkbox"
            checked={clientMode}
            disabled={busy || clientRunning}
            onChange={(event) => {
              setClientMode(event.target.checked)
              setClientQuestion(null)
            }}
          />
          <span>
            <b>Agent C — client simulé</b> <Users size={13} /> : l'IA joue le client, pose ses questions et
            conduit la conversation (vous gardez la main sur chaque cycle)
          </span>
        </label>
        <div className="plab-config">
          <label className="plab-field">
            <span>Agent à optimiser</span>
            <select
              value={agentId}
              disabled={busy || Boolean(campaign)}
              onChange={(event) => setAgentId(event.target.value)}
            >
              {zonesShown.map((zone) => (
                <option key={zone.agentId} value={zone.agentId}>
                  {zone.agentLibelle}{zone.optimizable ? '' : ' — non optimisable'}
                </option>
              ))}
            </select>
          </label>
          <label className="plab-field">
            <span>Zone optimisée</span>
            <div className="plab-static">
              Prompt de l'agent spécialisé
              {selectedZone && selectedZone.zoneFile && <> — <code>agent/{selectedZone.zoneFile}</code></>}
            </div>
            <small>Zone FIGÉE : la zone transverse « agent principal » n'est plus proposée par l'atelier.</small>
          </label>
          <label className="plab-field">
            <span>Fournisseur — IA coach</span>
            <select value={provider} disabled={busy || Boolean(campaign)} onChange={(event) => setProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
              <option value="LOCAL">Local (LM Studio)</option>
            </select>
            <small>Modèle qui répond au client (la question de test)</small>
          </label>
          <label className="plab-field">
            <span>Fournisseur — Agent B (contrôleur)</span>
            <select value={controllerProvider} disabled={busy || Boolean(campaign)} onChange={(event) => setControllerProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
              <option value="LOCAL">Local (LM Studio)</option>
            </select>
            <small>Modèle qui diagnostique la réponse</small>
          </label>
          <label className="plab-field">
            <span>Fournisseur — Agent A (éditeur)</span>
            <select value={editorProvider} disabled={busy || Boolean(campaign)} onChange={(event) => setEditorProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
              <option value="LOCAL">Local (LM Studio)</option>
            </select>
            <small>Modèle qui réécrit la zone du prompt</small>
          </label>
          <label className="plab-field">
            <span>Nombre d'itérations</span>
            <input
              type="number"
              min={1}
              max={agents?.maxIterations ?? 10}
              value={iterations}
              disabled={busy || Boolean(campaign)}
              onChange={(event) => setIterations(Number(event.target.value))}
            />
            <small>Plafond : {agents?.maxIterations ?? 10} itération(s) cumulée(s) (limite technique {agents?.hardMaxIterations ?? 50})</small>
          </label>
          {clientMode && (
            <>
              <label className="plab-field">
                <span>Fournisseur — Agent C (client simulé)</span>
                <select
                  value={clientProvider}
                  disabled={busy || clientRunning}
                  onChange={(event) => setClientProvider(event.target.value as AIProvider)}
                >
                  <option value="DEEPSEEK">DeepSeek</option>
                  <option value="GPT">OpenAI</option>
                  <option value="LOCAL">Local (LM Studio)</option>
                </select>
                <small>Modèle qui joue le client (il ne conseille jamais)</small>
              </label>
              <label className="plab-field">
                <span>Profondeur du scénario</span>
                <input
                  type="number"
                  min={1}
                  max={20}
                  value={clientDepth}
                  disabled={busy || clientRunning}
                  onChange={(event) => setClientDepth(Math.max(1, Math.min(20, Number(event.target.value) || 1)))}
                />
                <small>Nombre maximum de questions posées par le client (ex. 10)</small>
              </label>
            </>
          )}
        </div>
        {clientMode && (
          <label className="plab-switch">
            <input
              type="checkbox"
              checked={autoPromote}
              disabled={busy || clientRunning}
              onChange={(event) => setAutoPromote(event.target.checked)}
            />
            <span>
              <b>Enchaînement automatique</b> — décoché : <b>vous</b> validez chaque cycle. Coché : la dernière
              version du cycle est <b>acceptée pour la conversation</b> (sa réponse entre dans la mémoire du
              client) et le client enchaîne sa question suivante — <b>sans écrire le prompt de production</b>.
              À la fin, le bilan « Comparaison de la conversation — début ↔ fin » s'affiche avec le bouton
              <b> PROMOUVOIR</b> : c'est vous qui décidez ce qui part en production.
            </span>
          </label>
        )}
        {clientMode && (
          <div className="plab-actions">
            <button
              type="button"
              disabled={briefGenerating || clientRunning || busy || !agentId || !agents?.enabled}
              onClick={handleGenerateProject}
              title="L'agent C cherche lui-même un client et un projet correspondant à l'agent sélectionné. Un nouvel appui en propose un différent."
            >
              <Sparkles size={15} />
              {briefGenerating
                ? 'RECHERCHE D’UN PROJET…'
                : clientBriefs.length > 0
                  ? 'GÉNÉRER UN AUTRE PROJET'
                  : 'GÉNÉRER PROJET'}
            </button>
            <small className="plab-snippet">
              L’agent C invente le client et la raison de sa visite dans le périmètre de l’agent choisi
              {clientBriefs.length > 0 ? ` — ${clientBriefs.length} projet(s) déjà proposé(s), il en cherchera un autre` : ''}.
            </small>
          </div>
        )}
        <label className="plab-field">
          <span>
            {clientMode
              ? 'Brief du client (qui il est, son projet — figé pour tout le scénario)'
              : 'Question de test (figée pour toute la campagne)'}
          </span>
          <textarea
            rows={3}
            value={clientMode ? clientBrief : question}
            disabled={busy || clientRunning || (!clientMode && Boolean(campaign))}
            placeholder={clientMode
              ? "Tu as un projet de rénovation de la cuisine : les travaux coûtent environ 15 000 €. Tu veux savoir si ta situation financière le permet et quelle solution est la plus adaptée."
              : "Je souhaite financer une voiture d'occasion à 15 000 €. Quelles solutions pourraient être adaptées à ma situation ?"}
            onChange={(event) => (clientMode ? setClientBrief(event.target.value) : setQuestion(event.target.value))}
          />
        </label>
        {clientMode && (
          <p className="plab-hint">
            Le client ne reçoit que <b>trois chiffres</b> (solde du compte courant, solde de l'épargne, crédit en
            cours à rembourser), ce <b>brief</b> et la <b>conversation</b> : il pose une question à la fois, ne
            donne jamais de conseil et n'invente aucun chiffre. Il s'arrête de lui-même quand il a tout compris.
          </p>
        )}
        {selectedZone && (
          <>
            <p className="plab-hint">
              {selectedZone.optimizable
                ? <>Prompt actuellement en production : <code>agent/{selectedZone.zoneFile}</code> · zone modifiable détectée <Braces size={13} /> (mêmes parties protégées pour toutes les versions)</>
                : <>Agent non optimisable : {selectedZone.error}</>}
            </p>
            {/* CHAÎNAGE : la zone testée peut venir de la version RETENUE d'un cycle précédent (mode automatique) —
                le prompt de production, lui, n'a pas été écrit. Sans ce repère, la version V0 du cycle semble
                étrangement différente de la production. */}
            {detail?.snapshot?.baseZoneSource && (
              <p className="plab-hint">
                <span className="plab-tag warn">zone héritée</span> Zone de départ de ce cycle : <b>Celle de la version
                retenue</b> <span className="plab-mono">{detail.snapshot.baseZoneSource.split(':')[1]}</span> du cycle{' '}
                <span className="plab-mono">{detail.snapshot.baseZoneSource.split(':')[0]}</span> — le prompt de
                production <b>n'a pas été modifié</b> (il diffère donc de la version de référence V0 de ce cycle).
              </p>
            )}
            <details className="plab-help">
              <summary>Qu'est-ce que la « zone optimisée » ?</summary>
              <p className="plab-hint">
                Le prompt d'un agent est un texte long : les règles communes, la personnalité, les garde-fous…
                L'atelier n'en autorise la réécriture que dans <b>une seule zone</b> délimitée par des marqueurs
                (ceux-ci ne sont <b>jamais</b> envoyés au modèle).
              </p>
              <ul className="plab-locks">
                <li><b>Prompt de l'agent spécialisé</b> — LA zone optimisée : le fichier de l'agent choisi (ex. <code>credit-conso.txt</code>)</li>
                <li>La zone de <code>principal.txt</code> (transverse à tous les agents) n'est <b>plus proposée</b> par l'atelier : il n'y a donc aucun risque de réécrire les règles communes</li>
              </ul>
              <p className="plab-hint">
                Tout ce qui est <b>hors</b> de la zone est figé : le backend recompose lui-même le prompt
                (<code>parties protégées + zone</code>) et refuse toute proposition qui tenterait d'en sortir.
                C'est ce qui rend la comparaison entre versions honnête.
              </p>
            </details>
          </>
        )}
        {threadActive && !clientMode && (
          <p className="plab-hint">
            Une <b>conversation</b> est ouverte avec cet agent : enchaînez la question suivante depuis le bloc
            <b> « Conversation de l'atelier »</b> ci-dessous (tout l'historique sera transmis au Coach), ou
            cliquez <b>« Nouvelle conversation »</b> pour repartir sans mémoire.
          </p>
        )}
        <div className="plab-actions">
          <button
            type="button"
            className="plab-primary"
            disabled={clientMode
              ? busy || clientRunning || !clientBrief.trim() || !agents?.enabled
              : !canStart || busy || threadActive}
            onClick={clientMode ? () => startClientScenario() : handleStart}
            title={!clientMode && threadActive ? "Une conversation est en cours : utilisez « Conversation de l'atelier »." : undefined}
          >
            <Wand2 size={16} /> GO — {clientMode ? 'le client démarre le scénario' : "démarrer l'optimisation"}
          </button>
          {campaign && (
            <button type="button" onClick={newCampaign} disabled={busy}>
              <Plus size={15} /> Nouvelle campagne
            </button>
          )}
        </div>
      </section>

      {/* 1bis) Conversation de l'atelier : la MÉMOIRE de l'atelier. Une version promue fait entrer la réponse
          de l'IA dans la conversation ; la question suivante repart avec tout l'historique. */}
      {(thread || campaign || clientQuestion !== null) && (
        <section className="mkt-card plab-thread">
          <h2>
            <MessageSquare size={16} /> Conversation de l'atelier
            {threadExchanges > 0 && ` (${threadExchanges} échange(s))`}
          </h2>
          <p className="plab-hint">
            Comme dans la page coach : chaque cycle d'itérations porte sur <b>une question</b>. Dès qu'une version
            est promue, la <b>réponse de l'IA pour cette version</b> entre dans la conversation, et la question
            suivante repart avec <b>tout l'historique</b> — comme un client qui poursuit l'échange.
            {clientMode && <> Ici, c'est le <b>client simulé (Agent C)</b> qui lit cette réponse et pose lui-même la question suivante.</>}
          </p>

          {!thread && (
            <p className="plab-hint">
              Aucune conversation en mémoire : le prochain cycle démarre <b>sans historique</b> (les fils
              précédents restent sur disque, mais ne sont pas rejoués).
            </p>
          )}

          {(thread || clientQuestion !== null) && (
            <>
              <div className="plab-thread-scroll" ref={threadScrollRef}>
                {thread?.turns.map((turn: PromptTurn, index: number) => (
                  turn.role === 'user' ? (
                    <div key={`${turn.campaignId}-${index}`} className="message-row user">
                      <div className="message-bubble user">
                        <div className="message-meta">
                          <span>{clientMode ? 'Question du client (Agent C)' : 'Question de test'}</span>
                        </div>
                        <div className="message-text">
                          {renderMessageContent(`plab-q-${turn.campaignId}-${index}`, turn.content)}
                        </div>
                      </div>
                      <div className="avatar user-avatar">V</div>
                    </div>
                  ) : (
                    <div key={`${turn.campaignId}-${index}`} className="message-row assistant">
                      <div className="avatar assistant-avatar"><Sparkles size={17} /></div>
                      <div className="message-bubble assistant">
                        <div className="message-meta">
                          <span>Réponse de l'IA — version {turn.version || '—'} acceptée</span>
                        </div>
                        {editingTurn?.index === index ? (
                          <>
                            <textarea
                              className="plab-thread-edit"
                              rows={6}
                              value={editingTurn.content}
                              onChange={(event) => setEditingTurn({ index, content: event.target.value })}
                            />
                            <div className="plab-actions">
                              <button type="button" onClick={() => setEditingTurn(null)} disabled={busy}>ANNULER</button>
                              <button
                                type="button"
                                className="plab-primary"
                                onClick={handleSaveTurn}
                                disabled={busy || !editingTurn.content.trim()}
                              >
                                <Check size={14} /> ENREGISTRER
                              </button>
                            </div>
                          </>
                        ) : (
                          <>
                            <div className="message-text">
                              {turn.content
                                ? renderMessageContent(`plab-a-${turn.campaignId}-${index}`, turn.content)
                                : '(réponse vide)'}
                            </div>
                            <div className="plab-thread-meta">
                              <span className="plab-tag ok">★ {turn.version || 'version'} — version acceptée</span>
                              <span className="plab-mono">cycle {turn.campaignId}</span>
                              <button
                                type="button"
                                className="plab-thread-edit-btn"
                                onClick={() => setEditingTurn({ index, content: turn.content })}
                                disabled={busy}
                              >
                                <Pencil size={13} /> Corriger la réponse
                              </button>
                            </div>
                          </>
                        )}
                      </div>
                    </div>
                  )
                ))}

                {clientQuestion !== null && (
                  <div className="message-row user">
                    <div className="message-bubble user pending">
                      <div className="message-meta">
                        <span>
                          Question du client (Agent C) — n°{clientTurnNumber} / profondeur {clientDepth}
                          {clientRunning ? ' · cycle en cours…' : ' · en attente de votre validation'}
                        </span>
                      </div>
                      <textarea
                        className="plab-thread-edit"
                        rows={3}
                        value={clientQuestion}
                        disabled={clientRunning}
                        onChange={(event) => setClientQuestion(event.target.value)}
                      />
                      <div className="plab-actions">
                        <button
                          type="button"
                          className="plab-primary"
                          disabled={clientRunning || busy || !clientQuestion.trim()}
                          onClick={() => startClientScenario(clientQuestion)}
                        >
                          <Play size={14} /> GO — poser cette question
                        </button>
                      </div>
                    </div>
                    <div className="avatar user-avatar"><Users size={15} /></div>
                  </div>
                )}

                {campaign && !campaignInThread && (
                  <div className="message-row user">
                    <div className="message-bubble user pending">
                      <div className="message-meta">
                        <span>{clientMode ? 'Question du client (Agent C) — en cours' : 'Question de test — en cours'}</span>
                      </div>
                      <div className="message-text">
                        {renderMessageContent(`plab-pending-${campaign.campaignId}`, campaign.question)}
                      </div>
                    </div>
                    <div className="avatar user-avatar">V</div>
                  </div>
                )}
              </div>

              {campaign && !campaignInThread && (
                <p className="plab-hint">
                  ⏳ La réponse de l'IA n'est pas encore figée : <b>promouvoir une version</b> ajoute à la
                  conversation la réponse produite par l'itération qui l'a créée (c'est elle qui deviendra la
                  mémoire des cycles suivants). Si l'Agent A n'a proposé aucune modification, utilisez
                  <b> ACCEPTER SANS CHANGEMENT</b> : le prompt reste identique et la réponse entre quand même
                  dans la conversation.
                </p>
              )}

              {!clientMode && (
                <div className="plab-thread-composer">
                  <label className="plab-field">
                    <span>Question suivante — le cycle repart avec l'historique complet</span>
                    <textarea
                      rows={3}
                      value={nextQuestion}
                      disabled={busy}
                      onChange={(event) => setNextQuestion(event.target.value)}
                      placeholder="Et si j'allongeais la durée à 60 mois ? Qu'est-ce que cela change pour ma mensualité ?"
                    />
                  </label>
                  <div className="plab-actions">
                    <button type="button" className="plab-primary" disabled={!canContinue} onClick={handleContinue}>
                      <Send size={16} /> GO — enchaîner ({threadExchanges} échange(s) de mémoire)
                    </button>
                    {threadCompareButton()}
                    {newConversationGroup(busy)}
                  </div>
                  {!campaignDecided && (
                    <p className="plab-hint">
                      Pour enchaîner : <b>promouvoir une version</b>, <b>accepter la campagne sans changement</b> (si
                      l'éditeur n'a rien proposé) ou refuser la campagne — la réponse de l'IA doit d'abord être figée
                      dans la conversation.
                    </p>
                  )}
                </div>
              )}

              {clientMode && (
                <div className="plab-thread-composer plab-client-bar">
                  <div className="plab-client-head">
                    <span className="plab-tag ok"><Users size={13} /> Agent C — client simulé</span>
                    <span className="plab-hint">
                      Question {Math.min(clientAsked + 1, clientDepth)} / profondeur {clientDepth} ·
                      {' '}{autoPromote ? 'enchaînement automatique, acceptation sans écriture' : 'validation par vous'} ·
                      {' '}fournisseur {providerLabel(clientProvider)}
                    </span>
                  </div>
                  <div className="plab-actions">
                    <button
                      type="button"
                      onClick={handleClientStop}
                      disabled={!clientRunning}
                      title="Le cycle en cours se termine proprement, puis le scénario s'arrête."
                    >
                      <Square size={14} /> STOP
                    </button>
                    <button
                      type="button"
                      className="plab-primary"
                      disabled={clientRunning || busy || !campaignDecided || clientDepthReached || clientQuestion !== null}
                      onClick={() => startClientScenario()}
                      title={clientDepthReached ? 'Profondeur atteinte : relancez le scénario avec une profondeur plus grande.' : undefined}
                    >
                      <Play size={14} /> CONTINUER — question suivante du client
                    </button>
                    {threadCompareButton()}
                    {newConversationGroup(busy || clientRunning)}
                  </div>
                  <p className="plab-hint">
                    {clientRunning
                      ? 'Scénario en cours : le client pose une question, le cycle l\'optimise, la version est promue et la réponse entre dans la conversation.'
                      : clientDepthReached
                        ? `Profondeur atteinte (${clientDepth} question(s)) : augmentez la profondeur pour continuer.`
                        : !campaignDecided
                          ? "Le cycle en cours doit d'abord être validé (promouvoir une version, ou « accepter sans changement »)."
                          : 'CONTINUER : le client lit la dernière réponse et pose sa question suivante (tout l\'historique est rejoué).'}
                  </p>
                </div>
              )}
            </>
          )}
        </section>
      )}

      {/* 1ter) BILAN de la conversation : le prompt du DÉBUT face au prompt RETENU à la fin. La comparaison
          d'une campagne ne montre qu'une question ; celle-ci montre tout le scénario, cycle après cycle. */}
      {threadComparison && threadComparisonPanel()}

      {campaign && detail && (
        <>
          {/* 2) Snapshot de référence */}
          <section className="mkt-card">
            <h2><Braces size={16} /> Snapshot de référence créé</h2>
            <ul className="plab-locks">
              <li>🔒 Question — figée</li>
              <li>🔒 Données financières — figées ({detail.snapshot.frozenData.length} fichier(s), catalogue {detail.snapshot.catalogSize} entrée(s))</li>
              <li>🔒 Classification d'intention et projet — figés</li>
              <li>🔒 Prompt hors zone — figé (gabarit + agent principal)</li>
              <li>Seule la zone <code>[[[ … ]]]</code> évolue.</li>
            </ul>
            <div className="plab-actions">
              <button type="button" onClick={() => setOpenSnapshot((open) => !open)}>
                <Eye size={15} /> {openSnapshot ? 'Masquer le snapshot' : 'Voir le snapshot'}
              </button>
              <span className="plab-mono">hash {detail.snapshot.snapshotHash.slice(0, 12)}…</span>
            </div>
            {openSnapshot && (
              <pre className="plab-pre">{detail.snapshot.debug || 'Aucun détail de contexte.'}</pre>
            )}
          </section>

          {/* 3) Production vs candidat (§49) */}
          <section className="mkt-card">
            <h2>Prompt de production et candidat de campagne</h2>
            <div className="plab-two">
              <div className="plab-box">
                <strong>PROMPT ACTUEL / PRODUCTION</strong>
                <p>Fichier <code>agent/{campaign.zoneFile}</code></p>
                <p className="plab-mono">{campaign.zoneKey === 'principal'
                  ? 'agent principal (transverse à tous les agents)'
                  : campaign.agentLibelle}</p>
                <p className="plab-hint">Aucune version candidate n'est utilisée par les conversations tant qu'un humain ne l'a pas promue.</p>
              </div>
              <div className="plab-box">
                <strong>CAMPAGNE {campaign.campaignId}</strong>
                <p>Base : <b>{campaign.basePromptVersion}</b> · Candidat courant : <b>{campaign.currentCandidateVersion}</b></p>
                <p className="plab-hint">
                  Fournisseurs : IA coach <b>{providerLabel(campaign.provider)}</b> · Agent B <b>{providerLabel(campaign.controllerProvider)}</b>
                  {' '}· Agent A <b>{providerLabel(campaign.editorProvider)}</b>
                </p>
                {campaign.promotedVersion && (
                  <p className="plab-ok">
                    ★ Version acceptée pour la conversation : {campaign.promotedVersion}
                    {detail.versions.find((item) => item.version === campaign.promotedVersion)?.applied
                      ? ' — présente dans le prompt de production.'
                      : ' — le prompt de production n\'a PAS été modifié (utilisez « Promouvoir », ou le bilan de la conversation).'}
                  </p>
                )}
              </div>
            </div>
          </section>

          {/* 4) Progression + coûts */}
          <section className="mkt-card">
            <h2>Progression</h2>
            <p>
              État : <b>{label('campaignStatusLabels', campaign.status, STATUS_LABELS[campaign.status])}</b> · Itération {campaign.completedIterations} / {campaign.requestedIterations}
              {' '}(réalisées {campaign.completedIterations}, restantes {remaining})
            </p>
            <div className="plab-progress">
              <div
                className="plab-progress-bar"
                style={{ width: `${campaign.requestedIterations > 0 ? Math.min(100, (campaign.completedIterations / campaign.requestedIterations) * 100) : 0}%` }}
              />
            </div>
            <p className="plab-hint">
              <Type size={13} /> Appels IA : {campaign.aiCalls} · Caractères envoyés : {campaign.totalPromptChars.toLocaleString('fr-FR')} · Durée IA : {(campaign.totalDurationMs / 1000).toFixed(1)} s
            </p>
            {campaign.error && (campaign.errorStep
              ? <p className="plab-error">Étape {campaign.errorStep} : {campaign.error}</p>
              : <p className="plab-hint">⏸ {campaign.error}</p>)}

            {/* Nombre d'itérations ajoutées à la reprise : visible DÈS que la campagne ne tourne plus
                (une seule occurrence à l'écran, utilisée par « Ajouter mon avis et continuer » ET
                « Continuer sans avis »). */}
            {(campaign.status === 'PAUSED' || campaign.status === 'COMPLETED' || campaign.status === 'ERROR')
              && maxExtraIterations > 0 && (
              <label className="plab-field">
                <span>Itérations supplémentaires à ajouter au cycle</span>
                <input
                  type="number"
                  min={0}
                  max={maxExtraIterations}
                  value={extraToAdd}
                  disabled={busy}
                  onChange={(event) => setExtraIterations(Math.max(0,
                    Math.min(maxExtraIterations, Number(event.target.value) || 0)))}
                />
                <small>
                  {`Plafond cumulé : ${campaign.maxIterations} itérations (déjà demandées : ${campaign.requestedIterations}, encore possible : ${maxExtraIterations}).`}
                </small>
              </label>
            )}

            <div className="plab-actions">
              {campaign.status === 'RUNNING' && (
                <button type="button" className={stopping ? 'plab-danger' : undefined} onClick={handleStop} disabled={stopping}>
                  <X size={15} /> {stopping ? 'ARRÊT DEMANDÉ…' : 'STOP'}
                </button>
              )}
              {campaign.status === 'STOP_REQUESTED' && <span className="plab-hint">Arrêt demandé : la réponse en cours se termine…</span>}
              {/* Aucune modification proposée : promouvoir serait identique ⇒ action DIRECTE, sans confirmation. */}
              {canAcceptUnchanged && (
                <button
                  type="button"
                  className="plab-primary"
                  onClick={handleAcceptUnchanged}
                  disabled={busy}
                  title="L'Agent A n'a proposé aucune modification : accepter enregistre la réponse de l'IA dans la conversation de l'atelier. Le prompt n'est pas réécrit — il n'y a donc rien à confirmer."
                >
                  <ThumbsUp size={15} /> ACCEPTER SANS CHANGEMENT
                </button>
              )}
              {(campaign.status === 'PAUSED' || campaign.status === 'STOP_REQUESTED') && (
                <>
                  <button type="button" onClick={() => setShowFeedback((open) => !open)} disabled={busy}>
                    <MessageSquare size={15} /> AJOUTER MON AVIS
                  </button>
                  <button type="button" className="plab-primary" onClick={() => handleResume(false)} disabled={busy}>
                    <RefreshCw size={15} /> REPRENDRE
                  </button>
                  <button type="button" onClick={() => handleResume(true)} disabled={busy || maxExtraIterations === 0}>
                    <RefreshCw size={15} /> Ajouter mon avis et continuer (+{extraToAdd})
                  </button>
                </>
              )}
              {campaign.status === 'COMPLETED' && (
                <>
                  <button type="button" onClick={handleCompare} disabled={busy}><Braces size={15} /> COMPARER</button>
                  <button type="button" className="plab-primary" onClick={() => handleResume(true)}
                          disabled={busy || maxExtraIterations === 0 || extraToAdd === 0}>
                    <RefreshCw size={15} /> CONTINUER SANS AVIS (+{extraToAdd})
                  </button>
                  <button type="button" onClick={() => setShowFeedback((open) => !open)} disabled={busy}>
                    <MessageSquare size={15} /> AJOUTER MON AVIS ET CONTINUER
                  </button>
                  <button type="button" className="plab-danger" onClick={handleReject} disabled={busy}>
                    <ThumbsDown size={15} /> REFUSER LA CAMPAGNE
                  </button>
                </>
              )}
            </div>

            {showFeedback && (
              <div className="plab-feedback">
                <label className="plab-field">
                  <span>Votre avis (prioritaire sur le contrôleur automatique)</span>
                  <textarea
                    rows={3}
                    value={feedbackText}
                    onChange={(event) => setFeedbackText(event.target.value)}
                    placeholder="Le résultat est meilleur mais le Coach insiste trop sur les risques. Je souhaite conserver l'avertissement mais en une seule phrase."
                  />
                </label>
                <div className="plab-actions">
                  <button type="button" className="plab-primary" onClick={handleFeedback} disabled={busy || !feedbackText.trim()}>
                    <Check size={15} /> Enregistrer mon avis
                  </button>
                  {campaign.status === 'COMPLETED' && (
                    <button type="button" onClick={handleFeedbackAndResume} disabled={busy || !feedbackText.trim() || maxExtraIterations === 0}>
                      <RefreshCw size={15} /> Enregistrer et reprendre (+{extraToAdd})
                    </button>
                  )}
                </div>
              </div>
            )}
          </section>

          {/* 5) Versions */}
          <section className="mkt-card">
            <h2><Braces size={16} /> Versions du prompt ({detail.versions.length})</h2>            <p className="plab-hint">
              <b>Promouvoir</b> installe une version en production (action confirmée, avec sauvegarde du prompt
              actuel). Deux points d'entrée : la <b>version qui a répondu</b> — bouton sur l'itération concernée, à
              côté de la réponse que vous venez de juger — et <b>cette table</b>, pour n'importe quelle version,
              dont la <b>dernière proposée par l'Agent A</b> qui n'a pas encore répondu (sa réponse sera alors
              <b>générée avec ce prompt</b>). Quand l'Agent A n'a proposé <b>aucune</b> modification, la campagne
              s'accepte <b>sans changement</b> (barre d'actions de « Progression ») : le prompt n'est pas réécrit et
              <b>aucune confirmation</b> n'est demandée.
            </p>            <div className="mkt-table-scroll">
              <table className="mkt-table">
                <thead>
                  <tr>
                    <th>Version</th><th>Empreinte</th><th>Zone (début)</th><th>Statut</th><th></th>
                  </tr>
                </thead>
                <tbody>
                  {detail.versions.map((version: PromptVersionView) => (
                    <tr key={version.version}>
                      <td><b>{version.version}</b></td>
                      <td className="plab-mono">{version.promptHash.slice(0, 10)}…</td>
                      <td className="plab-snippet">{version.editableSection.slice(0, 90)}…</td>
                      <td>
                        {version.applied && <span className="plab-tag ok">★ en production</span>}
                        {version.production && !version.applied && <span className="plab-tag">référence</span>}
                        {version.promoted && !version.applied && (
                          <span className="plab-tag warn">★ acceptée (à promouvoir)</span>
                        )}
                      </td>
                      <td>
                        <button type="button" onClick={() => togglePanel(openPrompt, setOpenPrompt, version.version, null)}>
                          <Eye size={14} /> Voir le prompt
                        </button>
                        {version.version !== campaign.basePromptVersion && (
                          <button type="button" onClick={() => togglePanel(openDiff, setOpenDiff, version.version, null)}>
                            <Braces size={14} /> Changements
                          </button>
                        )}
                        {!version.production && !version.applied && canDecide && (
                          <button type="button" onClick={() => setPromotionVersion({ version: version.version, iteration: null })} disabled={busy}>
                            <ThumbsUp size={14} /> Promouvoir
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {openPrompt?.iteration === null && promptPanel(openPrompt)}
            {openDiff?.iteration === null && diffPanel(openDiff)}

            {detail.versions.length === 1 && (
              <p className="plab-hint">
                Aucune nouvelle version pour l'instant : <b>{campaign.basePromptVersion}</b> est le prompt de
                production et l'Agent A n'a proposé aucun changement. Le numéro de version n'avance QUE quand la
                zone est réellement réécrite — les itérations « sans modification » réutilisent donc la même
                version (d'où « Voir le prompt utilisé (V0) » sur plusieurs itérations). Pour enchaîner la
                conversation malgré tout, utilisez <b>ACCEPTER SANS CHANGEMENT</b> : le prompt reste identique et
                aucune confirmation n'est demandée.
              </p>
            )}
          </section>

          {/* 6) Comparaison initial / final */}
          {comparison && (
            <section className="mkt-card">
              <h2><Braces size={16} /> Comparaison {comparison.baseVersion} → {comparison.currentVersion}</h2>
              <div className="plab-two">
                <div className="plab-box">
                  <strong>VERSION INITIALE {comparison.baseVersion}</strong>
                  <pre className="plab-pre">{comparison.baseEditableSection}</pre>
                  <h3>Réponse</h3>
                  <pre className="plab-pre">{comparison.baseResponse}</pre>
                </div>
                <div className="plab-box">
                  <strong>VERSION COURANTE {comparison.currentVersion}</strong>
                  <pre className="plab-pre">{comparison.currentEditableSection}</pre>
                  <h3>Réponse</h3>
                  <pre className="plab-pre">{comparison.currentResponse}</pre>
                </div>
              </div>
              <p className="plab-hint">
                {comparison.iterationCount} itération(s) menée(s) sur cette campagne.
              </p>
            </section>
          )}

          {/* 7) Confirmation de promotion (§17) — demandée depuis le tableau des versions */}
          {promotionVersion?.iteration === null && (
            <section className="mkt-card plab-confirm">
              {promotionPanel(promotionVersion)}
            </section>
          )}

          {/* 8) Itérations */}
          <section className="mkt-card">
            <h2><HistoryIcon size={16} /> Itérations ({detail.iterations.length})</h2>
            {detail.iterations.length === 0 && <p className="plab-hint">Aucune itération pour l'instant.</p>}
            {[...detail.iterations].reverse().map((iteration: PromptIteration) => (
              <article key={iteration.iterationId} className="plab-iteration">
                <header>
                  <b>ITÉRATION {iteration.iterationNumber}</b>
                  <span>Prompt {iteration.promptVersion} → {iteration.resultingVersion}</span>
                  <span>{new Date(iteration.completedAt || iteration.startedAt).toLocaleTimeString('fr-FR')}</span>
                  {iteration.noChange && (
                    <span className="plab-tag"
                          title="L'Agent A n'a proposé aucun changement : cette itération ne produit donc aucune nouvelle version (le prompt utilisé reste la version courante).">
                      sans modification → aucune nouvelle version
                    </span>
                  )}
                  {iteration.humanFeedbackApplied && <span className="plab-tag ok">avis humain appliqué</span>}
                  {(iteration.contextAddedData?.length ?? 0) > 0 && (
                    <span
                      className="plab-tag"
                      title={`Le Coach a demandé ces données : ${(iteration.contextAddedData ?? []).join(', ')}`}
                    >
                      contexte complété (+{iteration.contextAddedData?.length})
                    </span>
                  )}
                  {iteration.status === 'ERROR' && <span className="plab-tag ko">erreur</span>}
                  {iteration.error.includes('NEED_DATA') && (
                    <span
                      className="plab-tag ko"
                      title="Le prompt a demandé des données que le contexte ne contient pas : l'itération est dégradée exactement comme en production (la réponse de repli est enregistrée, la campagne continue)."
                    >
                      données indisponibles → réponse dégradée
                    </span>
                  )}
                </header>
                <h3>Réponse du Coach</h3>
                <pre className="plab-pre response">{iteration.coachResponse || '(aucune réponse enregistrée)'}</pre>
                {iteration.error && <p className="plab-error">{iteration.error}</p>}
                <p className="plab-hint">
                  Agent B : {iteration.controllerFeedback
                    ? `${iteration.controllerFeedback.issues.length} point(s) d'amélioration — ${label('controllerStatusLabels', iteration.controllerFeedback.status)}`
                    : '—'}
                  {' · '}Agent A : {iteration.editorResult ? label('editorStatusLabels', iteration.editorResult.status) : '—'}
                  {iteration.changeSummary.length > 0 && ` — ${iteration.changeSummary.join(' ; ')}`}
                </p>
                <div className="plab-actions">
                  <button type="button" onClick={() => setOpenAnalysis(openAnalysis === iteration.iterationNumber ? null : iteration.iterationNumber)}>
                    <Bot size={14} /> Voir l'analyse Agent B
                  </button>
                  <button type="button" onClick={() => togglePanel(openPrompt, setOpenPrompt, iteration.promptVersion, iteration.iterationNumber)}>
                    <Eye size={14} /> Voir le prompt utilisé ({iteration.promptVersion})
                  </button>
                  {producedNewVersion(iteration) && (
                    <>
                      <button type="button" onClick={() => togglePanel(openPrompt, setOpenPrompt, iteration.resultingVersion, iteration.iterationNumber)}>
                        <Eye size={14} /> Voir le prompt produit ({iteration.resultingVersion})
                      </button>
                      <button type="button" onClick={() => togglePanel(openDiff, setOpenDiff, iteration.resultingVersion, iteration.iterationNumber)}>
                        <Braces size={14} /> Changements {iteration.promptVersion} → {iteration.resultingVersion}
                      </button>
                    </>
                  )}
                  {canDecide && promotableVersion(iteration.promptVersion) && (
                    <button
                      type="button"
                      onClick={() => setPromotionVersion({ version: iteration.promptVersion, iteration: iteration.iterationNumber })}
                      disabled={busy}
                      title={`Promouvoir ${iteration.promptVersion} : c'est le prompt qui a produit la réponse ci-dessus — vous validez donc le prompt dont vous venez de juger la réponse.`}
                    >
                      <ThumbsUp size={14} /> Promouvoir
                    </button>
                  )}
                  {!producedNewVersion(iteration) && (
                    <span className="plab-hint">
                      Aucune version produite : le prompt n'a pas été modifié, il n'y a donc ni
                      « prompt produit » ni diff à afficher pour cette itération.
                    </span>
                  )}
                </div>
                {/* Détails affichés SOUS cette itération (comme l'analyse Agent B) : pas de remontée en haut de page. */}
                {openPrompt?.iteration === iteration.iterationNumber && promptPanel(openPrompt)}
                {openDiff?.iteration === iteration.iterationNumber && diffPanel(openDiff)}
                {promotionVersion?.iteration === iteration.iterationNumber && (
                  <div className="plab-panel plab-confirm">{promotionPanel(promotionVersion)}</div>
                )}
                {openAnalysis === iteration.iterationNumber && iteration.controllerFeedback && (
                  <div className="plab-panel">
                    <p>{iteration.controllerFeedback.summary}</p>
                    {iteration.controllerFeedback.positivePoints.length > 0 && (
                      <>
                        <h3>Points satisfaisants</h3>
                        <ul>{iteration.controllerFeedback.positivePoints.map((point, index) => <li key={index}>✓ {point}</li>)}</ul>
                      </>
                    )}
                    {iteration.controllerFeedback.issues.length > 0 && (
                      <>
                        <h3>Points à améliorer</h3>
                        <ul>
                          {iteration.controllerFeedback.issues.map((issue, index) => (
                            <li key={index}>
                              ⚠ <b>{label('issueTypeLabels', issue.type)}</b> — {label('severityLabels', issue.severity, SEVERITY_LABELS[issue.severity])}
                              {' · '}origine : {label('sourceLabels', issue.source, SOURCE_LABELS[issue.source])}
                              <div>{issue.observation}</div>
                              <div className="plab-hint">Attendu : {issue.expectedBehavior}</div>
                            </li>
                          ))}
                        </ul>
                      </>
                    )}
                    {iteration.controllerFeedback.mustPreserve.length > 0 && (
                      <>
                        <h3>À préserver</h3>
                        <ul>{iteration.controllerFeedback.mustPreserve.map((item, index) => <li key={index}>✓ {item}</li>)}</ul>
                      </>
                    )}
                    <h3>Recommandation transmise à l'éditeur</h3>
                    <p>{iteration.controllerFeedback.recommendationForPromptEditor}</p>
                    {iteration.controllerFeedback.requiresHumanOrBusinessReview && (
                      <p className="plab-error">Le contrôleur signale une décision humaine ou métier nécessaire.</p>
                    )}
                    {iteration.editorResult && iteration.editorResult.unresolvedPoints.length > 0 && (
                      <>
                        <h3>Points non résolus (revue humaine)</h3>
                        <ul>{iteration.editorResult.unresolvedPoints.map((item, index) => <li key={index}>{item}</li>)}</ul>
                      </>
                    )}
                  </div>
                )}
              </article>
            ))}
          </section>

          {/* 9) Avis humains */}
          {detail.feedbacks.length > 0 && (
            <section className="mkt-card">
              <h2><MessageSquare size={16} /> Avis humains ({detail.feedbacks.length})</h2>
              <ul className="plab-feedbacks">
                {detail.feedbacks.map((feedback) => (
                  <li key={feedback.feedbackId} className={feedback.applied ? '' : 'pending'}>
                    <span className="plab-tag">AVIS HUMAIN</span> après l'itération {feedback.iterationNumber}
                    {' · '}{new Date(feedback.createdAt).toLocaleString('fr-FR')}
                    {' · '}{feedback.applied ? 'appliqué' : 'en attente'}
                    <div>{feedback.content}</div>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  )
}
