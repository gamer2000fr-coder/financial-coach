import { useCallback, useEffect, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  BadgeCheck,
  Bot,
  Download,
  Gauge,
  RefreshCw,
  Star,
  Wand2,
} from 'lucide-react'

import {
  fetchQualityOverview,
  fetchQualityReport,
  generateQualityDemoData,
  qualitySatisfactionCsvUrl,
  runQualityBatch,
} from './api'
import type { QualityAggregates, QualityPeriod, QualityReport } from './types.quality'

const PERIODS: { id: QualityPeriod; label: string }[] = [
  { id: 'today', label: "Aujourd'hui" },
  { id: 'yesterday', label: 'Hier' },
  { id: '7d', label: '7 jours' },
  { id: '30d', label: '30 jours' },
  { id: 'custom', label: 'Personnalisé' },
]

function formatPercent(value: number | null | undefined, decimals = 0): string {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(decimals)} %`
}

function formatNumber(value: number | null | undefined, decimals = 2): string {
  return value === null || value === undefined ? '—' : value.toFixed(decimals)
}

function evolutionLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'n/a'
  return `${value > 0 ? '+' : ''}${value.toFixed(1)} %`
}

function evolutionClass(value: number | null | undefined, inverse = false): string {
  if (value === null || value === undefined) return 'mkt-evo flat'
  if (value === 0) return 'mkt-evo flat'
  const positive = inverse ? value < 0 : value > 0
  return positive ? 'mkt-evo up' : 'mkt-evo down'
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
 * Page <b>Qualité &amp; Satisfaction du Coach IA</b> (`#/quality`).
 * <p>
 * Deux familles d'indicateurs STRICTEMENT séparées : satisfaction client (avis) et conformité du
 * Coach (contrôles automatiques), puis leur croisement. La page n'affiche jamais un indicateur non
 * calculable : un contrôle non implémenté est Listé à part, jamais compté comme un « 0 ».
 */
export default function Quality() {
  const [period, setPeriod] = useState<QualityPeriod>('7d')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [ratingFilter, setRatingFilter] = useState<number | ''>('')
  const [severityFilter, setSeverityFilter] = useState('')
  const [data, setData] = useState<QualityAggregates | null>(null)
  const [report, setReport] = useState<QualityReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const overview = await fetchQualityOverview(period, from || undefined, to || undefined, {
        rating: ratingFilter === '' ? undefined : Number(ratingFilter),
        severity: severityFilter || undefined,
      })
      setData(overview)
      setReport(await fetchQualityReport(overview.dateTo))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    } finally {
      setLoading(false)
    }
  }, [period, from, to, ratingFilter, severityFilter])

  useEffect(() => {
    void load()
  }, [load])

  async function regenerate() {
    if (!data) return
    setNotice(null)
    try {
      await runQualityBatch(data.dateTo)
      setNotice(`Agrégats et rapport IA régénérés pour le ${data.dateTo}.`)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  async function seedDemo() {
    setNotice(null)
    try {
      const result = await generateQualityDemoData(14, 5)
      setNotice(
        `Données de démonstration générées (${result.days} jours, ${result.feedbackWritten} avis, ${result.checksWritten} contrôles).`,
      )
      setPeriod('30d')
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  const satisfaction = data?.satisfaction
  const conformity = data?.conformity
  const matrix = data?.satisfactionVsCompliance
  const hasFeedback = (satisfaction?.feedbackCount ?? 0) > 0
  const hasChecks = (conformity?.checksRun ?? 0) > 0

  return (
    <div className="logs-shell marketing-page">
      <header className="logs-header">
          <a className="logs-back" href="#/">
            <ArrowLeft size={16} /> Retour au chat
          </a>
          <h1>
            <BadgeCheck size={20} /> Qualité &amp; Satisfaction du Coach IA
          </h1>
        </header>
        <p className="mkt-subtitle">
          Suivi de l&rsquo;expérience client <strong>et</strong> du respect des règles du Coach. Les deux
          dimensions restent séparées : une mauvaise note n&rsquo;est jamais considérée comme une erreur du Coach.
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
            aria-label="Filtrer par note"
            value={ratingFilter}
            onChange={(event) => setRatingFilter(event.target.value === '' ? '' : Number(event.target.value))}
          >
            <option value="">Toutes les notes</option>
            {[5, 4, 3, 2, 1].map((value) => (
              <option key={value} value={value}>
                {value} étoile{value > 1 ? 's' : ''}
              </option>
            ))}
          </select>
          <select
            aria-label="Filtrer par sévérité"
            value={severityFilter}
            onChange={(event) => setSeverityFilter(event.target.value)}
          >
            <option value="">Toutes les sévérités</option>
            <option value="HIGH">HIGH</option>
            <option value="MEDIUM">MEDIUM</option>
            <option value="LOW">LOW</option>
          </select>
          <button type="button" className="mkt-action" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={14} /> {loading ? 'Chargement…' : 'Actualiser'}
          </button>
          <button type="button" className="mkt-action" onClick={() => void regenerate()} disabled={!data}>
            <Bot size={14} /> Régénérer le rapport IA
          </button>
          <button type="button" className="mkt-action" onClick={() => void seedDemo()}>
            <Wand2 size={14} /> Données de démo
          </button>
          <a className="mkt-action" href={qualitySatisfactionCsvUrl(period, from || undefined, to || undefined)}>
            <Download size={14} /> Export CSV
          </a>
        </div>

        {notice && <div className="mkt-notice">{notice}</div>}
        {error && <div className="mkt-notice warn">Erreur : {error}</div>}
        {data?.demo && (
          <div className="mkt-demo-banner">
            Des données de DÉMONSTRATION (source=DEMO) sont présentes sur cette période : elles ne
            représentent pas de vrais avis clients.
          </div>
        )}
        {data && data.invalidLines > 0 && (
          <div className="mkt-notice warn">
            {data.invalidLines} ligne(s) illisible(s) ignorée(s) dans les fichiers de qualité.
          </div>
        )}

        {data && !hasFeedback && !hasChecks && (
          <div className="mkt-card">
            <h2>Aucune donnée sur la période</h2>
            <p className="mkt-empty">
              Aucun avis ni contrôle enregistré entre le {data.dateFrom} et le {data.dateTo}. Aucune
              statistique n&rsquo;est estimée : lancez une conversation puis « Terminer la conversation », ou
              générez des données de démonstration.
            </p>
          </div>
        )}

        {data && (hasFeedback || hasChecks) && (
          <>
            <div className="mkt-kpis">
              <Kpi label="Note moyenne" value={formatNumber(satisfaction?.averageRating, 2)} suffix="/ 5" />
              <Kpi label="Taux de participation" value={formatPercent(satisfaction?.participationRate)} />
              <Kpi label="Avis positifs (4-5)" value={formatPercent(satisfaction?.positiveRate)} />
              <Kpi label="Avis négatifs (1-2)" value={formatPercent(satisfaction?.negativeRate)} />
              <Kpi label="Anomalies Coach" value={String(conformity?.anomalies ?? 0)} />
              <Kpi label="dont anomalies HIGH" value={String(conformity?.highAnomalies ?? 0)} />
              <Kpi
                label="Évolution de la note"
                value={evolutionLabel(satisfaction?.averageRatingEvolutionPercent)}
              />
              <Kpi label="Contrôles exécutés" value={String(conformity?.checksRun ?? 0)} />
            </div>

            <p className="mkt-hint">
              {satisfaction?.feedbackCount ?? 0} avis sur {satisfaction?.conversationsClosed ?? 0}{' '}
              conversation(s) terminée(s) —{' '}
              {satisfaction?.sufficientSample
                ? 'échantillon suffisant pour interpréter les tendances.'
                : 'échantillon encore limité : observations préliminaires (le rapport IA le signale).'}
            </p>

            <div className="mkt-grid">
              <section className="mkt-card">
                <h2>
                  <Star size={15} /> Distribution des notes
                </h2>
                {data.ratingDistribution.every((bucket) => bucket.count === 0) ? (
                  <p className="mkt-empty">Aucun avis sur la période.</p>
                ) : (
                  data.ratingDistribution.map((bucket) => (
                    <div key={bucket.rating} className="mkt-row column">
                      <div className="mkt-ranking-row">
                        <span className="mkt-ranking-name">
                          {'★'.repeat(bucket.rating)}
                          <span className="mkt-muted">{'★'.repeat(5 - bucket.rating)}</span>
                        </span>
                        <span className="mkt-ranking-value">{formatPercent(bucket.share)}</span>
                      </div>
                      <div className="mkt-ranking-bar" style={{ width: `${Math.max(bucket.share * 100, 1)}%` }} />
                      <span className="mkt-muted">{bucket.count} avis</span>
                    </div>
                  ))
                )}
              </section>

              <section className="mkt-card">
                <h2>Principaux motifs d&rsquo;insatisfaction</h2>
                {data.feedbackCategories.length === 0 ? (
                  <p className="mkt-empty">
                    Aucun motif sélectionné par les clients sur la période (les motifs ne sont proposés
                    qu&rsquo;à partir de 3 étoiles ou moins).
                  </p>
                ) : (
                  data.feedbackCategories.slice(0, 6).map((reason) => (
                    <div key={reason.reason} className="mkt-row column">
                      <div className="mkt-ranking-row">
                        <span className="mkt-ranking-name">{reason.label}</span>
                        <span className="mkt-ranking-value">{reason.count}</span>
                      </div>
                      <div className="mkt-ranking-bar" style={{ width: `${Math.max(reason.share * 100, 1)}%` }} />
                      <span className={evolutionClass(reason.evolutionPercent, true)}>
                        <AlertTriangle size={12} /> {evolutionLabel(reason.evolutionPercent)} vs période
                        précédente
                      </span>
                    </div>
                  ))
                )}
              </section>

              <section className="mkt-card">
                <h2>Thèmes détectés dans les commentaires</h2>
                {data.commentThemes.length === 0 ? (
                  <p className="mkt-empty">Aucun commentaire exploitable sur la période.</p>
                ) : (
                  data.commentThemes.slice(0, 6).map((theme) => (
                    <div key={theme.theme} className="mkt-row column">
                      <div className="mkt-ranking-row">
                        <span className="mkt-ranking-name">{theme.label}</span>
                        <span className="mkt-ranking-value">{theme.count}</span>
                      </div>
                      <div className="mkt-ranking-bar" style={{ width: `${Math.max(theme.share * 100, 1)}%` }} />
                    </div>
                  ))
                )}
                <p className="mkt-hint">
                  Thèmes issus d&rsquo;une heuristique locale déterministe sur les commentaires anonymisés
                  (l&rsquo;analyse IA des commentaires n&rsquo;est pas activée dans cette version).
                </p>
              </section>

              <section className="mkt-card">
                <h2>
                  <Gauge size={15} /> Contrôles du Coach
                </h2>
                <table className="mkt-table compact">
                  <thead>
                    <tr>
                      <th>Contrôle</th>
                      <th>Sévérité</th>
                      <th>Exécutés</th>
                      <th>Anomalies</th>
                      <th>Évolution</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.qualityChecks.map((check) => (
                      <tr key={check.checkType}>
                        <td className="mkt-strong">{check.label}</td>
                        <td>{check.severity}</td>
                        <td>{check.checksRun}</td>
                        <td className={check.detected > 0 ? 'mkt-low' : ''}>{check.detected}</td>
                        <td>
                          <span className={evolutionClass(check.evolutionPercent, true)}>
                            {evolutionLabel(check.evolutionPercent)}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {data.notImplementedChecks.length > 0 && (
                  <p className="mkt-hint">
                    Contrôles décrits mais NON implémentés dans cette version (donc jamais affichés comme un
                    zéro) : {data.notImplementedChecks.map((type) => type).join(', ')}.
                  </p>
                )}
              </section>
            </div>

            <section className="mkt-card wide">
              <h2>Satisfaction client vs conformité du Coach</h2>
              <div className="qlt-matrix">
                <div className="qlt-cell ok">
                  <span className="mkt-muted">Client satisfait + Coach conforme</span>
                  <strong>{matrix?.satisfiedCompliant ?? 0}</strong>
                  <span className="mkt-muted">Fonctionnement attendu</span>
                </div>
                <div className="qlt-cell watch">
                  <span className="mkt-muted">Client satisfait + anomalie</span>
                  <strong>{matrix?.satisfiedAnomaly ?? 0}</strong>
                  <span className="mkt-muted">
                    Problème invisible pour le client, à corriger malgré tout
                  </span>
                </div>
                <div className="qlt-cell friction">
                  <span className="mkt-muted">Client insatisfait + Coach conforme</span>
                  <strong>{matrix?.unsatisfiedCompliant ?? 0}</strong>
                  <span className="mkt-muted">
                    Règle correctement appliquée : travailler la pédagogie, jamais supprimer le garde-fou
                  </span>
                </div>
                <div className="qlt-cell critical">
                  <span className="mkt-muted">Client insatisfait + anomalie</span>
                  <strong>{matrix?.unsatisfiedAnomaly ?? 0}</strong>
                  <span className="mkt-muted">Cas prioritaire : expérience ET conformité en jeu</span>
                </div>
              </div>
              <p className="mkt-hint">
                Les notes neutres (3 étoiles) sont exclues du croisement. {(matrix?.unratedWithAnomaly ?? 0)}{' '}
                session(s) présentent une anomalie sans avis client.
              </p>
            </section>

            <section className="mkt-card wide">
              <h2>
                <Bot size={15} /> Analyse IA de la qualité
              </h2>
              <p className="mkt-hint">
                Analyse générée à partir des statistiques calculées par le backend et des feedbacks
                anonymisés. L&rsquo;IA n&rsquo;a calculé aucun chiffre et ne modifie rien automatiquement :
                elle propose, l&rsquo;équipe décide.
              </p>
              {!report ? (
                <p className="mkt-empty">
                  Aucun rapport IA disponible pour cette période. Utilisez « Régénérer le rapport IA ».
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
                      <h3>Satisfaction</h3>
                      <p className="mkt-empty">{report.satisfactionAnalysis?.summary || '—'}</p>
                      {report.satisfactionAnalysis?.positivePoints?.length ? (
                        <ul className="mkt-report-list">
                          {report.satisfactionAnalysis.positivePoints.map((item) => (
                            <li key={item}>{item}</li>
                          ))}
                        </ul>
                      ) : null}
                      {report.satisfactionAnalysis?.mainIrritants?.length ? (
                        <>
                          <h3>Irritants</h3>
                          <ul className="mkt-report-list">
                            {report.satisfactionAnalysis.mainIrritants.map((item) => (
                              <li key={item}>{item}</li>
                            ))}
                          </ul>
                        </>
                      ) : null}
                    </div>
                    <div>
                      <h3>Qualité / conformité</h3>
                      <p className="mkt-empty">{report.qualityAndCompliance?.summary || '—'}</p>
                      {report.qualityAndCompliance?.mainIssues?.length ? (
                        <ul className="mkt-report-list">
                          {report.qualityAndCompliance.mainIssues.map((item) => (
                            <li key={item}>{item}</li>
                          ))}
                        </ul>
                      ) : null}
                      {report.qualityAndCompliance?.criticalIssues?.length ? (
                        <>
                          <h3>Anomalies critiques</h3>
                          <ul className="mkt-report-list">
                            {report.qualityAndCompliance.criticalIssues.map((item) => (
                              <li key={item}>{item}</li>
                            ))}
                          </ul>
                        </>
                      ) : null}
                    </div>
                    <div>
                      <h3>Satisfaction × conformité</h3>
                      <p className="mkt-empty">{report.satisfactionVsCompliance?.summary || '—'}</p>
                      {report.satisfactionVsCompliance?.notableCases?.length ? (
                        <ul className="mkt-report-list">
                          {report.satisfactionVsCompliance.notableCases.map((item) => (
                            <li key={item}>{item}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                    <div>
                      <h3>Règles conformes mais frustrantes</h3>
                      {report.ruleFriction.length === 0 ? (
                        <p className="mkt-empty">Aucune règle conforme identifiée comme frustrante.</p>
                      ) : (
                        <ul className="mkt-report-list">
                          {report.ruleFriction.map((item) => (
                            <li key={`${item.rule}-${item.observation}`}>
                              <strong>{item.rule}</strong> — {item.observation}
                              {item.recommendation ? ` → ${item.recommendation}` : ''}
                              {item.coachCompliant === true ? ' (Coach conforme)' : ''}
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>

                  {report.priorityImprovements.length > 0 && (
                    <>
                      <h3>Améliorations prioritaires</h3>
                      <table className="mkt-table compact">
                        <thead>
                          <tr>
                            <th>Priorité</th>
                            <th>Titre</th>
                            <th>Observation</th>
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
                      <h3>Tendances identifiées</h3>
                      <ul className="mkt-report-list">
                        {report.trends.map((trend) => (
                          <li key={`${trend.type}-${trend.topic}`}>
                            <strong>{trend.type}</strong> — {trend.topic} : {trend.observation}
                          </li>
                        ))}
                      </ul>
                    </>
                  )}

                  {report.alerts.length > 0 && (
                    <div className="mkt-alerts">
                      {report.alerts.map((alert) => (
                        <div
                          key={`${alert.level}-${alert.title}`}
                          className={
                            alert.level === 'IMPORTANT'
                              ? 'mkt-alert important'
                              : alert.level === 'WATCH'
                                ? 'mkt-alert watch'
                                : 'mkt-alert'
                          }
                        >
                          <div className="mkt-strong">{alert.title}</div>
                          <div className="mkt-muted">{alert.description}</div>
                        </div>
                      ))}
                    </div>
                  )}

                  {report.finalAssessment && <p className="mkt-summary">{report.finalAssessment}</p>}
                  <p className="mkt-hint">
                    Rapport du {report.reportDate} · modèle {report.model ?? '—'} ·{' '}
                    {report.aiGenerated ? 'généré par IA' : 'analyse IA indisponible'}
                  </p>
                </>
              )}
            </section>
          </>
        )}
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
