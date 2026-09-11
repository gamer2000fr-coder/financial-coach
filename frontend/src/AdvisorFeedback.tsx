import { useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  BadgeCheck,
  Bot,
  Download,
  MessageSquareQuote,
  RefreshCw,
  ThumbsDown,
  ThumbsUp,
  UserCheck,
  Wand2,
} from 'lucide-react'

import {
  ADVISOR_AREAS,
  ADVISOR_EMAIL_ASSESSMENTS,
  ADVISOR_INTEREST_LEVELS,
  ADVISOR_PRODUCT_REASONS,
  ADVISOR_REASONS,
  advisorSummaryCsvUrl,
  fetchAdvisorCandidates,
  fetchAdvisorOverview,
  fetchAdvisorReport,
  generateAdvisorDemoData,
  generateAdvisorReport,
  submitAdvisorFeedback,
  type AdvisorFilters,
} from './api'
import type {
  AdvisorAggregates,
  AdvisorAssessment,
  AdvisorCandidateSession,
  AdvisorCatalogueProduct,
  AdvisorIssueInput,
  AdvisorPeriod,
  AdvisorProductFeedbackInput,
  AdvisorReport,
} from './types.advisor'

const PERIODS: { id: AdvisorPeriod; label: string }[] = [
  { id: 'today', label: "Aujourd'hui" },
  { id: 'yesterday', label: 'Hier' },
  { id: '7d', label: '7 jours' },
  { id: '30d', label: '30 jours' },
  { id: 'custom', label: 'Personnalisé' },
]

const ASSESSMENTS: { code: AdvisorAssessment; label: string; icon: typeof ThumbsUp }[] = [
  { code: 'RELEVANT', label: 'Pertinente', icon: ThumbsUp },
  { code: 'NEEDS_IMPROVEMENT', label: 'À améliorer', icon: MessageSquareQuote },
  { code: 'INCORRECT', label: 'Incorrecte', icon: ThumbsDown },
]

function percent(value: number | null | undefined, decimals = 0): string {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(decimals)} %`
}

function evolution(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'n/a'
  return `${value > 0 ? '+' : ''}${value.toFixed(1)} %`
}

function evolutionClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return 'mkt-evo flat'
  return value > 0 ? 'mkt-evo up' : 'mkt-evo down'
}

function statusClass(status: string | undefined): string {
  switch ((status ?? '').toUpperCase()) {
    case 'GOOD':
      return 'mkt-importance low'
    case 'WATCH':
      return 'mkt-importance medium'
    case 'ATTENTION':
      return 'mkt-importance high'
    default:
      return 'mkt-importance'
  }
}

/**
 * Page <b>Feedback Conseillers</b> (`#/advisor-feedback`) : mesure la pertinence du travail du Coach
 * à partir de l'expertise des conseillers (saisie rapide) et restitue les KPI, les corrections
 * récurrentes, la pertinence produit, la qualité des emails et l'analyse IA de la période.
 */
export default function AdvisorFeedback() {
  const [period, setPeriod] = useState<AdvisorPeriod>('7d')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [filters, setFilters] = useState<AdvisorFilters>({})
  const [data, setData] = useState<AdvisorAggregates | null>(null)
  const [report, setReport] = useState<AdvisorReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const overview = await fetchAdvisorOverview(period, from || undefined, to || undefined, filters)
      setData(overview)
      setReport(await fetchAdvisorReport(overview.dateTo))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    } finally {
      setLoading(false)
    }
  }, [period, from, to, filters])

  useEffect(() => {
    void load()
  }, [load])

  async function generate() {
    setNotice(null)
    try {
      setReport(await generateAdvisorReport(period, from || undefined, to || undefined))
      setNotice('Analyse IA générée pour la période sélectionnée.')
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  async function seedDemo() {
    setNotice(null)
    try {
      const result = await generateAdvisorDemoData(14, 4)
      setNotice(`Données de démonstration générées (${result.days} jours, ${result.written} feedbacks).`)
      setPeriod('30d')
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  const kpis = data?.kpis
  const hasFeedback = (kpis?.feedbackCount ?? 0) > 0

  return (
    <div className="logs-shell marketing-page">
      <header className="logs-header">
        <a className="logs-back" href="#/">
          <ArrowLeft size={16} /> Retour au chat
        </a>
        <h1>
          <UserCheck size={20} /> Feedback Conseillers
        </h1>
      </header>
      <p className="mkt-subtitle">
        Mesurer la pertinence du Coach à partir de l&rsquo;expertise des conseillers. Le conseiller donne son
        avis, le système mesure, l&rsquo;IA analyse les tendances : <strong>aucune modification du Coach
        n&rsquo;est appliquée automatiquement</strong>.
      </p>

      <div className="mkt-toolbar">
        <div className="mkt-periods">
          {PERIODS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={period === item.id ? 'mkt-period active' : 'mkt-period'}
              onClick={() => setPeriod(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>
        {period === 'custom' && (
          <div className="mkt-dates">
            <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
            <span>→</span>
            <input type="date" value={to} onChange={(event) => setTo(event.target.value)} />
          </div>
        )}
        <select
          aria-label="Filtrer par évaluation"
          value={filters.assessment ?? ''}
          onChange={(event) => setFilters({ ...filters, assessment: event.target.value || undefined })}
        >
          <option value="">Toutes les évaluations</option>
          <option value="RELEVANT">Pertinente</option>
          <option value="NEEDS_IMPROVEMENT">À améliorer</option>
          <option value="INCORRECT">Incorrecte</option>
        </select>
        <select
          aria-label="Filtrer par exploitabilité email"
          value={filters.emailAssessment ?? ''}
          onChange={(event) => setFilters({ ...filters, emailAssessment: event.target.value || undefined })}
        >
          <option value="">Tous les emails</option>
          {ADVISOR_EMAIL_ASSESSMENTS.map((item) => (
            <option key={item.code} value={item.code}>
              {item.label}
            </option>
          ))}
        </select>
        <button type="button" className="mkt-action" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={14} /> {loading ? 'Chargement…' : 'Actualiser'}
        </button>
        <button type="button" className="mkt-action" onClick={() => void generate()}>
          <Bot size={14} /> Générer l&rsquo;analyse IA
        </button>
        <button type="button" className="mkt-action" onClick={() => void seedDemo()}>
          <Wand2 size={14} /> Données de démo
        </button>
        <a className="mkt-action" href={advisorSummaryCsvUrl(period, from || undefined, to || undefined)}>
          <Download size={14} /> Export CSV
        </a>
      </div>

      {notice && <div className="mkt-notice">{notice}</div>}
      {error && <div className="mkt-notice warn">Erreur : {error}</div>}
      {data?.demo && (
        <div className="mkt-demo-banner">
          Des feedbacks de DÉMONSTRATION (source=DEMO) sont présents : ils ne représentent pas de vrais
          retours de conseillers.
        </div>
      )}
      {data && data.invalidLines > 0 && (
        <div className="mkt-notice warn">
          {data.invalidLines} ligne(s) illisible(s) ignorée(s) dans les fichiers de feedback.
        </div>
      )}

      {data && !hasFeedback && (
        <div className="mkt-card">
          <h2>Aucun feedback conseiller sur la période</h2>
          <p className="mkt-empty">
            Aucun dossier évalué entre le {data.dateFrom} et le {data.dateTo}. Aucune statistique n&rsquo;est
            estimée : utilisez le bloc « Évaluer un dossier » ci-dessous ou générez des données de démonstration.
          </p>
        </div>
      )}

      {data && hasFeedback && (
        <>
          <div className="mkt-kpis">
            <Kpi label="Dossiers évalués" value={String(kpis?.sessionsEvaluated ?? 0)} />
            <Kpi label="Analyses pertinentes" value={percent(kpis?.relevantRate)} />
            <Kpi label="À améliorer" value={percent(kpis?.needsImprovementRate)} />
            <Kpi label="Incorrectes" value={percent(kpis?.incorrectRate)} />
            <Kpi label="Produits jugés pertinents" value={percent(kpis?.productRelevanceRate)} />
            <Kpi label="Emails prêts ou mineurs" value={percent(kpis?.emailReadyOrMinorRate)} />
            <Kpi label="Corrections d'intérêt" value={String(kpis?.interestCorrections ?? 0)} />
            <Kpi label="Évolution « pertinent »" value={evolution(kpis?.relevantRateEvolutionPercent)} />
          </div>

          <p className="mkt-hint">
            {kpis?.feedbackCount} feedback(s) sur {kpis?.sessionsEvaluated} dossier(s)
            {kpis?.participationRate === null || kpis?.participationRate === undefined
              ? ' — taux de feedback non calculable (aucune conversation clôturée sur la période).'
              : ` — taux de feedback ${percent(kpis.participationRate)} des conversations clôturées.`}{' '}
            {kpis?.sufficientSample
              ? 'Échantillon suffisant pour interpréter.'
              : 'Échantillon encore limité : conclusions préliminaires.'}
          </p>

          <div className="mkt-grid">
            <section className="mkt-card">
              <h2>Évaluations globales</h2>
              {data.assessments.map((item) => (
                <div key={item.code} className="mkt-row column">
                  <div className="mkt-ranking-row">
                    <span className="mkt-ranking-name">{item.label}</span>
                    <span className="mkt-ranking-value">{item.count}</span>
                  </div>
                  <div className="mkt-ranking-bar" style={{ width: `${Math.max(item.share * 100, 1)}%` }} />
                  <span className={evolutionClass(item.evolutionPercent)}>
                    {evolution(item.evolutionPercent)} vs période précédente
                  </span>
                </div>
              ))}
            </section>

            <section className="mkt-card">
              <h2>À améliorer — zones les plus corrigées</h2>
              {data.areas.length === 0 ? (
                <p className="mkt-empty">Aucune correction signalée sur la période.</p>
              ) : (
                data.areas.map((area) => (
                  <div key={area.area} className="mkt-row column">
                    <div className="mkt-ranking-row">
                      <span className="mkt-ranking-name">{area.label}</span>
                      <span className="mkt-ranking-value">{area.count}</span>
                    </div>
                    <div className="mkt-ranking-bar" style={{ width: `${Math.max(area.share * 100, 1)}%` }} />
                    <span className={evolutionClass(area.evolutionPercent)}>
                      {evolution(area.evolutionPercent)} — part {percent(area.share)}
                    </span>
                  </div>
                ))
              )}
              {data.reasons.length > 0 && (
                <>
                  <h3>Motifs structurés</h3>
                  <ul className="mkt-report-list">
                    {data.reasons.slice(0, 6).map((reason) => (
                      <li key={reason.reason}>
                        {reason.label} : {reason.count} ({evolution(reason.evolutionPercent)})
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>

            <section className="mkt-card">
              <h2>Pertinence des produits</h2>
              {data.products.length === 0 ? (
                <p className="mkt-empty">Aucun produit évalué sur la période.</p>
              ) : (
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Produit</th>
                      <th>Éval.</th>
                      <th>Pertinent</th>
                      <th>Non pertinent</th>
                      <th>Taux</th>
                      <th>Corr.</th>
                      <th>Ajouté</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.products.slice(0, 12).map((product) => (
                      <tr key={product.productId}>
                        <td className="mkt-strong">{product.productName ?? product.productId}</td>
                        <td>{product.assessments}</td>
                        <td>{product.relevant}</td>
                        <td className={product.notRelevant > 0 ? 'mkt-low' : ''}>{product.notRelevant}</td>
                        <td>{percent(product.relevanceRate)}</td>
                        <td>{product.interestCorrections}</td>
                        <td>{product.addedByAdvisor}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            <section className="mkt-card">
              <h2>Niveaux d&rsquo;intérêt corrigés</h2>
              {data.interestCorrections.length === 0 ? (
                <p className="mkt-empty">
                  Aucune correction de niveau d&rsquo;intérêt : la valeur IA et la valeur conseiller sont
                  conservées séparément lorsqu&rsquo;il y en a une.
                </p>
              ) : (
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Produit</th>
                      <th>IA</th>
                      <th>Conseiller</th>
                      <th>Cas</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.interestCorrections.slice(0, 10).map((correction) => (
                      <tr key={`${correction.productId}-${correction.fromLevel}-${correction.toLevel}`}>
                        <td className="mkt-strong">{correction.productName ?? correction.productId}</td>
                        <td>{correction.fromLevel}</td>
                        <td>{correction.toLevel}</td>
                        <td>{correction.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <h3>Qualité des emails préparés</h3>
              {data.emailQuality.length === 0 ? (
                <p className="mkt-empty">Aucun email évalué sur la période.</p>
              ) : (
                data.emailQuality.map((email) => (
                  <div key={email.code} className="mkt-row column">
                    <div className="mkt-ranking-row">
                      <span className="mkt-ranking-name">{email.label}</span>
                      <span className="mkt-ranking-value">{email.count}</span>
                    </div>
                    <div className="mkt-ranking-bar" style={{ width: `${Math.max(email.share * 100, 1)}%` }} />
                    <span className={evolutionClass(email.evolutionPercent)}>
                      {percent(email.share)} — {evolution(email.evolutionPercent)}
                    </span>
                  </div>
                ))
              )}
            </section>
          </div>
        </>
      )}

      <CaptureForm
        candidatesPeriodDays={period === '30d' ? 30 : period === 'custom' ? 30 : 7}
        onSubmitted={(message) => {
          setNotice(message)
          void load()
        }}
      />

      <section className="mkt-card wide">
        <h2>
          <Bot size={15} /> Analyse IA des retours conseillers
        </h2>
        <p className="mkt-hint">
          Analyse générée à partir des KPI calculés par le backend et de commentaires anonymisés. L&rsquo;IA
          n&rsquo;a calculé aucun chiffre et ne modifie rien : elle propose, l&rsquo;équipe décide.
        </p>
        {!report ? (
          <p className="mkt-empty">
            Aucune analyse disponible : utilisez « Générer l&rsquo;analyse IA » (la génération est manuelle
            dans le POC).
          </p>
        ) : (
          <>
            {report.executiveSummary && (
              <p className="mkt-summary">
                <span className={statusClass(report.executiveSummary.status)}>
                  {report.executiveSummary.status}
                </span>{' '}
                {report.executiveSummary.summary}
              </p>
            )}
            {report.error && <div className="mkt-notice warn">Analyse IA indisponible : {report.error}</div>}
            <div className="mkt-report-cols">
              <div>
                <h3>Ce qui fonctionne</h3>
                {report.strengths.length === 0 ? (
                  <p className="mkt-empty">Aucun point fort identifié dans les données fournies.</p>
                ) : (
                  <ul className="mkt-report-list">
                    {report.strengths.map((item) => (
                      <li key={item.title}>
                        <strong>{item.title}</strong> — {item.observation}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3>Problèmes principaux</h3>
                {report.mainIssues.length === 0 ? (
                  <p className="mkt-empty">Aucun problème récurrent identifié.</p>
                ) : (
                  <ul className="mkt-report-list">
                    {report.mainIssues.map((item) => (
                      <li key={`${item.area}-${item.observation}`}>
                        <strong>{item.severity}</strong> — {item.observation}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3>Niveaux d&rsquo;intérêt</h3>
                <p className="mkt-empty">{report.interestLevelAnalysis?.summary || '—'}</p>
                {report.interestLevelAnalysis?.overestimationSignals?.length ? (
                  <ul className="mkt-report-list">
                    {report.interestLevelAnalysis.overestimationSignals.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                ) : null}
              </div>
              <div>
                <h3>Email client &amp; suivi</h3>
                <p className="mkt-empty">{report.clientEmailAnalysis?.summary || '—'}</p>
                <p className="mkt-empty">{report.nextActionAnalysis?.summary || '—'}</p>
              </div>
            </div>

            {report.productAnalysis.length > 0 && (
              <>
                <h3>Analyse par produit</h3>
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Produit</th>
                      <th>Signal</th>
                      <th>Observation</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.productAnalysis.map((item) => (
                      <tr key={item.productId}>
                        <td className="mkt-strong">{item.productName || item.productId}</td>
                        <td>{item.signal}</td>
                        <td>{item.observation}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}

            {report.priorityImprovements.length > 0 && (
              <>
                <h3>Priorités d&rsquo;amélioration</h3>
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Priorité</th>
                      <th>Titre</th>
                      <th>Constat</th>
                      <th>Recommandation</th>
                      <th>Bénéfice attendu</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.priorityImprovements.map((item) => (
                      <tr key={`${item.priority}-${item.title}`}>
                        <td>
                          <span
                            className={
                              item.priority === 'HIGH'
                                ? 'mkt-importance high'
                                : item.priority === 'LOW'
                                  ? 'mkt-importance low'
                                  : 'mkt-importance medium'
                            }
                          >
                            {item.priority}
                          </span>
                        </td>
                        <td className="mkt-strong">{item.title}</td>
                        <td>{item.observation}</td>
                        <td>{item.recommendation}</td>
                        <td>{item.expectedBenefit}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}

            {report.trends.length > 0 && (
              <>
                <h3>Tendances</h3>
                <ul className="mkt-report-list">
                  {report.trends.map((trend) => (
                    <li key={`${trend.type}-${trend.topic}`}>
                      <strong>{trend.type}</strong> — {trend.topic} : {trend.observation}
                    </li>
                  ))}
                </ul>
              </>
            )}

            {report.watchPoints.length > 0 && (
              <>
                <h3>Points de vigilance</h3>
                <ul className="mkt-report-list">
                  {report.watchPoints.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </>
            )}

            {report.finalAssessment && <p className="mkt-summary">{report.finalAssessment}</p>}
            <p className="mkt-hint">
              Rapport du {report.reportDate} · modèle {report.model ?? '—'} ·{' '}
              {report.aiGenerated ? 'généré par IA' : 'analyse IA indisponible'} — le module ne modifie jamais
              automatiquement le Coach (prompt, règles, seuils, catalogue, code).
            </p>
          </>
        )}
      </section>
    </div>
  )
}

function Kpi({ label, value, suffix }: { label: string; value: string; suffix?: string }) {
  return (
    <div className="mkt-kpi">
      <span className="mkt-kpi-label">{label}</span>
      <span className="mkt-kpi-value">
        {value}
        {suffix ? <span className="mkt-muted"> {suffix}</span> : null}
      </span>
    </div>
  )
}

/**
 * SAISIE RAPIDE du feedback conseiller (§3/§4/§5/§7/§8/§9/§10/§11) : l'évaluation globale suffit,
 * tout le reste est facultatif et n'apparaît que si nécessaire.
 */
function CaptureForm({ candidatesPeriodDays, onSubmitted }: {
  candidatesPeriodDays: number
  onSubmitted: (message: string) => void
}) {
  const [candidates, setCandidates] = useState<AdvisorCandidateSession[]>([])
  const [catalogue, setCatalogue] = useState<AdvisorCatalogueProduct[]>([])
  const [sessionId, setSessionId] = useState('')
  const [assessment, setAssessment] = useState<AdvisorAssessment | null>(null)
  const [comment, setComment] = useState('')
  const [areaReasons, setAreaReasons] = useState<Record<string, string>>({})
  const [products, setProducts] = useState<Record<string, AdvisorProductFeedbackInput>>({})
  const [missing, setMissing] = useState<string[]>([])
  const [nextAction, setNextAction] = useState('')
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (!open) return
    void fetchAdvisorCandidates(candidatesPeriodDays, true)
      .then((result) => {
        setCandidates(result.candidates)
        setCatalogue(result.catalogue)
      })
      .catch(() => setCandidates([]))
  }, [open, candidatesPeriodDays])

  const selected = candidates.find((item) => item.sessionId === sessionId) ?? null

  function reset() {
    setAssessment(null)
    setComment('')
    setAreaReasons({})
    setProducts({})
    setMissing([])
    setNextAction('')
    setEmail('')
  }

  async function submit() {
    if (!sessionId || !assessment) return
    const issues: AdvisorIssueInput[] = Object.entries(areaReasons)
      .filter(([, reason]) => reason !== '')
      .map(([area, reason]) => ({ area, reason: reason || undefined }))
    const productFeedback: AdvisorProductFeedbackInput[] = Object.entries(products)
      .filter(([, value]) => Boolean(value.advisorAssessment) || Boolean(value.advisorInterestLevel))
      .map(([productId, value]) => ({ ...value, productId }))
    try {
      const response = await submitAdvisorFeedback({
        sessionId,
        overallAssessment: assessment,
        issues,
        productFeedback,
        missingProductIds: missing,
        nextActionAssessment: nextAction || undefined,
        clientEmailAssessment: email || undefined,
        comment: comment || undefined,
      })
      setMessage(
        response.status === 'SAVED'
          ? `Feedback enregistré (version ${response.version})${response.event === 'UPDATED' ? ' — révision' : ''}.`
          : `Feedback non enregistré (${response.status})${response.message ? ` : ${response.message}` : ''}`,
      )
      onSubmitted(response.status === 'SAVED'
        ? `Feedback conseiller enregistré pour ${sessionId}.`
        : `Feedback conseiller : ${response.status}.`)
      reset()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  return (
    <section className="mkt-card wide">
      <h2>
        <BadgeCheck size={15} /> Évaluer un dossier
      </h2>
      <p className="mkt-hint">
        Quelques secondes suffisent : l&rsquo;évaluation globale est le seul champ requis. Un feedback négatif
        ouvre les détails (zones, motifs, produits, intérêts, suivi, email).
      </p>
      {!open ? (
        <button type="button" className="mkt-action" onClick={() => setOpen(true)}>
          Ouvrir la saisie rapide
        </button>
      ) : (
        <>
          <div className="qlt-popup-actions" style={{ justifyContent: 'flex-start' }}>
            <select
              aria-label="Dossier à évaluer"
              value={sessionId}
              onChange={(event) => {
                setSessionId(event.target.value)
                setProducts({})
                setMissing([])
              }}
            >
              <option value="">— choisir un dossier —</option>
              {candidates.map((candidate) => (
                <option key={candidate.sessionId} value={candidate.sessionId}>
                  {candidate.sessionId}
                  {candidate.projectType ? ` · ${candidate.projectType}` : ''}
                  {candidate.evaluated ? ` · déjà évalué (v${candidate.currentVersion})` : ''}
                </option>
              ))}
            </select>
            <button type="button" className="mkt-action" onClick={() => void submit()} disabled={!sessionId || !assessment}>
              Envoyer le feedback
            </button>
            <button type="button" className="mkt-action" onClick={() => setOpen(false)}>
              Fermer
            </button>
          </div>

          <h3>Cette analyse vous paraît-elle pertinente&nbsp;?</h3>
          <div className="mkt-periods" style={{ width: 'fit-content' }}>
            {ASSESSMENTS.map((item) => {
              const Icon = item.icon
              return (
                <button
                  key={item.code}
                  type="button"
                  className={assessment === item.code ? 'mkt-period active' : 'mkt-period'}
                  onClick={() => setAssessment(item.code)}
                >
                  <Icon size={13} /> {item.label}
                </button>
              )
            })}
          </div>

          {(assessment === 'NEEDS_IMPROVEMENT' || assessment === 'INCORRECT') && (
            <>
              <h3>Qu&rsquo;est-ce qui doit être amélioré&nbsp;?</h3>
              <div className="afb-areas">
                {ADVISOR_AREAS.map((area) => (
                  <div key={area.code} className="afb-area">
                    <label className="qlt-reason">
                      <input
                        type="checkbox"
                        checked={area.code in areaReasons}
                        onChange={(event) => {
                          const updated = { ...areaReasons }
                          if (event.target.checked) updated[area.code] = ''
                          else delete updated[area.code]
                          setAreaReasons(updated)
                        }}
                      />
                      <span>{area.label}</span>
                    </label>
                    {area.code in areaReasons && (
                      <select
                        aria-label={`Motif pour ${area.label}`}
                        value={areaReasons[area.code]}
                        onChange={(event) =>
                          setAreaReasons({ ...areaReasons, [area.code]: event.target.value })
                        }
                      >
                        <option value="">Motif (facultatif)</option>
                        {ADVISOR_REASONS.map((reason) => (
                          <option key={reason.code} value={reason.code}>
                            {reason.label}
                          </option>
                        ))}
                      </select>
                    )}
                  </div>
                ))}
              </div>
            </>
          )}

          {selected && selected.products.length > 0 && (
            <>
              <h3>Produits détectés — pertinence et niveau d&rsquo;intérêt</h3>
              <table className="mkt-table compact">
                <thead>
                  <tr>
                    <th>Produit</th>
                    <th>Intérêt IA</th>
                    <th>Jugement</th>
                    <th>Intérêt corrigé</th>
                    <th>Motif</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.products.map((product) => {
                    const entry = products[product.productId] ?? { productId: product.productId }
                    const update = (patch: Partial<AdvisorProductFeedbackInput>) =>
                      setProducts({
                        ...products,
                        [product.productId]: {
                          ...entry,
                          productId: product.productId,
                          productName: product.productName ?? undefined,
                          aiInterestLevel: product.aiInterestLevel ?? undefined,
                          ...patch,
                        },
                      })
                    return (
                      <tr key={product.productId}>
                        <td className="mkt-strong">{product.productName ?? product.productId}</td>
                        <td>{product.aiInterestLevel ?? '—'}</td>
                        <td>
                          <button
                            type="button"
                            className={entry.advisorAssessment === 'RELEVANT_PRODUCT' ? 'mkt-period active' : 'mkt-period'}
                            onClick={() => update({ advisorAssessment: 'RELEVANT_PRODUCT' })}
                          >
                            <ThumbsUp size={12} /> Pertinent
                          </button>{' '}
                          <button
                            type="button"
                            className={entry.advisorAssessment === 'NOT_RELEVANT' ? 'mkt-period active' : 'mkt-period'}
                            onClick={() => update({ advisorAssessment: 'NOT_RELEVANT' })}
                          >
                            <ThumbsDown size={12} /> Non pertinent
                          </button>
                        </td>
                        <td>
                          <select
                            aria-label={`Niveau d'intérêt corrigé pour ${product.productId}`}
                            value={entry.advisorInterestLevel ?? ''}
                            onChange={(event) => update({ advisorInterestLevel: event.target.value || undefined })}
                          >
                            <option value="">(inchangé)</option>
                            {ADVISOR_INTEREST_LEVELS.map((level) => (
                              <option key={level} value={level}>
                                {level}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td>
                          {entry.advisorAssessment === 'NOT_RELEVANT' ? (
                            <select
                              aria-label={`Motif pour ${product.productId}`}
                              value={entry.reason ?? ''}
                              onChange={(event) => update({ reason: event.target.value || undefined })}
                            >
                              <option value="">Motif (facultatif)</option>
                              {ADVISOR_PRODUCT_REASONS.map((reason) => (
                                <option key={reason.code} value={reason.code}>
                                  {reason.label}
                                </option>
                              ))}
                            </select>
                          ) : (
                            <span className="mkt-muted">—</span>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </>
          )}

          <h3>Un produit pertinent a-t-il été oublié&nbsp;?</h3>
          <select
            aria-label="Produit manquant (catalogue)"
            value=""
            onChange={(event) => {
              if (event.target.value && !missing.includes(event.target.value)) {
                setMissing([...missing, event.target.value])
              }
            }}
          >
            <option value="">— ajouter un produit du catalogue —</option>
            {catalogue.map((product) => (
              <option key={product.productId} value={product.productId}>
                {product.productName}
              </option>
            ))}
          </select>
          {missing.length > 0 && (
            <ul className="mkt-report-list">
              {missing.map((id) => (
                <li key={id}>
                  {catalogue.find((product) => product.productId === id)?.productName ?? id}{' '}
                  <button type="button" className="mkt-action" onClick={() => setMissing(missing.filter((item) => item !== id))}>
                    retirer
                  </button>
                </li>
              ))}
            </ul>
          )}

          <h3>Suivi conseillé et email préparé</h3>
          <div className="mkt-report-cols">
            <div>
              <select
                aria-label="Évaluation du suivi conseillé"
                value={nextAction}
                onChange={(event) => setNextAction(event.target.value)}
              >
                <option value="">Suivi conseillé : non évalué</option>
                <option value="RELEVANT">Pertinent</option>
                <option value="NEEDS_IMPROVEMENT">À améliorer</option>
                <option value="INCORRECT">Incorrect</option>
              </select>
            </div>
            <div>
              <select
                aria-label="Évaluation de l'email préparé"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              >
                <option value="">Email client : non évalué</option>
                {ADVISOR_EMAIL_ASSESSMENTS.map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <textarea
            className="qlt-comment"
            rows={3}
            maxLength={1000}
            placeholder="Commentaire (facultatif)"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
          {message && <p className="mkt-hint">{message}</p>}
          <p className="mkt-hint">
            Aucun feedback ne modifie automatiquement le Coach : il alimente les KPI et l&rsquo;analyse IA.
            Votre nom n&rsquo;est pas stocké (identifiant technique uniquement).
          </p>
        </>
      )}
    </section>
  )
}
