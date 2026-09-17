import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowLeft,
  Bot,
  Braces,
  Check,
  Eye,
  History as HistoryIcon,
  MessageSquare,
  Plus,
  RefreshCw,
  ThumbsDown,
  ThumbsUp,
  Type,
  Wand2,
  X,
} from 'lucide-react'
import {
  fetchPromptCampaign,
  fetchPromptComparison,
  fetchPromptOptimizationAgents,
  iteratePromptCampaign,
  promotePromptVersion,
  rejectPromptCampaign,
  resumePromptCampaign,
  sendPromptHumanFeedback,
  startPromptCampaign,
  stopPromptCampaign,
} from './api'
import type { AIProvider } from './types'
import type {
  CampaignStatus,
  PromptCampaign,
  PromptCampaignDetail,
  PromptComparison,
  PromptIteration,
  PromptOptimizationAgents,
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
  if (code === 'MOCK') return 'Mode démo'
  return code || '—'
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
  const [agentId, setAgentId] = useState('')
  const [zoneKey, setZoneKey] = useState<PromptZoneKey | ''>('')
  const [question, setQuestion] = useState('')
  const [iterations, setIterations] = useState(3)
  const [provider, setProvider] = useState<AIProvider>('DEEPSEEK')
  const [controllerProvider, setControllerProvider] = useState<AIProvider>('DEEPSEEK')
  const [editorProvider, setEditorProvider] = useState<AIProvider>('DEEPSEEK')
  const [extraIterations, setExtraIterations] = useState(3)
  const [busy, setBusy] = useState(false)
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
  const stopRef = useRef(false)
  const runningRef = useRef(false)

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
          setZoneKey(first.zoneKey)
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
   * L'IHM ne propose PAS de reprendre une campagne passée (l'historique n'est pas consulté) : seul l'état de
   * la campagne courante est rafraîchi. Le backend conserve ses fichiers — une nouvelle campagne clôt
   * automatiquement les précédentes (`CANCELLED`).
   */
  const refresh = useCallback(async (campaignId: string) => {
    try {
      setDetail(await fetchPromptCampaign(campaignId))
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

  async function handleStart() {
    await guard(async () => {
      const created = await startPromptCampaign({
        agentId,
        question: question.trim(),
        iterations,
        zoneKey: zoneKey || null,
        provider,
        controllerProvider,
        editorProvider,
      })
      setNotice(`Campagne ${created.campaignId} créée : snapshot de référence figé.`)
      await drive(created.campaignId)
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
   * Revient à la configuration pour démarrer une NOUVELLE campagne (l'IHM ne conserve pas d'historique :
   * les fichiers de la campagne précédente restent sur disque, mais ne sont plus proposés).
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
    return (
      <>
        <h2><ThumbsUp size={16} /> Confirmer la promotion</h2>
        <p>
          Vous êtes sur le point de remplacer la zone du prompt de l'agent <b>{campaign.agentLibelle}</b> par
          la version <b>{panel.version}</b> issue de cette campagne.
        </p>
        <p>Le prompt actuel sera conservé dans l'historique (retour arrière possible).</p>
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
        <div className="plab-config">
          <label className="plab-field">
            <span>Agent à optimiser</span>
            <select
              value={agentId}
              disabled={busy || Boolean(campaign)}
              onChange={(event) => {
                const next = event.target.value
                setAgentId(next)
                const zone = zonesShown.find((item) => item.agentId === next)
                if (zone) setZoneKey(zone.zoneKey)
              }}
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
            <select
              value={zoneKey}
              disabled={busy || Boolean(campaign)}
              onChange={(event) => setZoneKey(event.target.value as PromptZoneKey)}
            >
              <option value="agent">Prompt de l'agent spécialisé</option>
              <option value="principal">Agent principal (transverse à tous les agents)</option>
            </select>
          </label>
          <label className="plab-field">
            <span>Fournisseur — IA coach</span>
            <select value={provider} disabled={busy || Boolean(campaign)} onChange={(event) => setProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
            </select>
            <small>Modèle qui répond au client (la question de test)</small>
          </label>
          <label className="plab-field">
            <span>Fournisseur — Agent B (contrôleur)</span>
            <select value={controllerProvider} disabled={busy || Boolean(campaign)} onChange={(event) => setControllerProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
            </select>
            <small>Modèle qui diagnostique la réponse</small>
          </label>
          <label className="plab-field">
            <span>Fournisseur — Agent A (éditeur)</span>
            <select value={editorProvider} disabled={busy || Boolean(campaign)} onChange={(event) => setEditorProvider(event.target.value as AIProvider)}>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="GPT">OpenAI</option>
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
        </div>
        <label className="plab-field">
          <span>Question de test (figée pour toute la campagne)</span>
          <textarea
            rows={3}
            value={question}
            disabled={busy || Boolean(campaign)}
            placeholder="Je souhaite financer une voiture d'occasion à 15 000 €. Quelles solutions pourraient être adaptées à ma situation ?"
            onChange={(event) => setQuestion(event.target.value)}
          />
        </label>
        {selectedZone && (
          <>
            <p className="plab-hint">
              {selectedZone.optimizable
                ? <>Prompt actuellement en production : <code>agent/{selectedZone.zoneFile}</code> · zone modifiable détectée <Braces size={13} /> (mêmes parties protégées pour toutes les versions)</>
                : <>Agent non optimisable : {selectedZone.error}</>}
            </p>
            <details className="plab-help">
              <summary>Qu'est-ce que la « zone optimisée » ?</summary>
              <p className="plab-hint">
                Le prompt d'un agent est un texte long : les règles communes, la personnalité, les garde-fous…
                L'atelier n'en autorise la réécriture que dans <b>une seule zone</b> délimitée par des marqueurs
                (ceux-ci ne sont <b>jamais</b> envoyés au modèle).
              </p>
              <ul className="plab-locks">
                <li><b>Prompt de l'agent spécialisé</b> — la zone du fichier de l'agent choisi (ex. <code>credit-conso.txt</code>)</li>
                <li><b>Agent principal</b> — la zone de <code>principal.txt</code>, <b>transverse</b> : elle s'applique à tous les agents</li>
              </ul>
              <p className="plab-hint">
                Tout ce qui est <b>hors</b> de la zone est figé : le backend recompose lui-même le prompt
                (<code>parties protégées + zone</code>) et refuse toute proposition qui tenterait d'en sortir.
                C'est ce qui rend la comparaison entre versions honnête.
              </p>
            </details>
          </>
        )}
        <div className="plab-actions">
          <button type="button" className="plab-primary" disabled={!canStart || busy} onClick={handleStart}>
            <Wand2 size={16} /> GO — démarrer l'optimisation
          </button>
          {campaign && (
            <button type="button" onClick={newCampaign} disabled={busy}>
              <Plus size={15} /> Nouvelle campagne
            </button>
          )}
        </div>
      </section>

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
                {campaign.promotedVersion && <p className="plab-ok">★ Promue en production : {campaign.promotedVersion}</p>}
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
            {campaign.error && <p className="plab-error">Étape {campaign.errorStep || '—'} : {campaign.error}</p>}

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
              <b>Promouvoir</b> installe une version en production — action depuis la ligne de la version
              (ou depuis l'itération qui l'a produite), confirmée, avec sauvegarde du prompt actuel.
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
                        {version.production && <span className="plab-tag">production</span>}
                        {version.promoted && <span className="plab-tag ok">★ promue</span>}
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
                        {!version.production && !version.promoted && canDecide && (
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
                  {iteration.noChange && <span className="plab-tag">sans modification</span>}
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
                  {producedNewVersion(iteration) && canDecide && (
                    <button type="button" onClick={() => setPromotionVersion({ version: iteration.resultingVersion, iteration: iteration.iterationNumber })} disabled={busy}>
                      <ThumbsUp size={14} /> Promouvoir {iteration.resultingVersion}
                    </button>
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
