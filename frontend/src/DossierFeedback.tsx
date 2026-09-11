import { useEffect, useState } from 'react'
import { ArrowLeft, BadgeCheck, Check, Send, ThumbsDown, ThumbsUp } from 'lucide-react'

import {
  ADVISOR_AREAS,
  ADVISOR_EMAIL_ASSESSMENTS,
  ADVISOR_INTEREST_LEVELS,
  ADVISOR_PRODUCT_REASONS,
  ADVISOR_REASONS,
  fetchAdvisorCandidates,
  fetchAdvisorDossier,
  submitAdvisorFeedback,
} from './api'
import type {
  AdvisorAssessment,
  AdvisorCatalogueProduct,
  AdvisorDossierView,
  AdvisorIssueInput,
  AdvisorProductFeedbackInput,
} from './types.advisor'

const ASSESSMENTS: { code: AdvisorAssessment; label: string; icon: typeof ThumbsUp }[] = [
  { code: 'RELEVANT', label: 'Pertinente', icon: ThumbsUp },
  { code: 'NEEDS_IMPROVEMENT', label: 'À améliorer', icon: ThumbsUp },
  { code: 'INCORRECT', label: 'Incorrecte', icon: ThumbsDown },
]

/**
 * VUE « DOSSIER » ciblée par le lien du mail conseiller (§42/§43/§44) : le conseiller arrive
 * directement sur le dossier de la conversation (projet, résumé, produits, suivi, email préparé)
 * puis donne son avis en quelques secondes. Aucun dashboard statistique n'est affiché ici.
 */
export default function DossierFeedback({ sessionId }: { sessionId: string }) {
  const [view, setView] = useState<AdvisorDossierView | null>(null)
  const [loading, setLoading] = useState(true)
  const [available, setAvailable] = useState(true)
  const [assessment, setAssessment] = useState<AdvisorAssessment | null>(null)
  const [comment, setComment] = useState('')
  const [areaReasons, setAreaReasons] = useState<Record<string, string>>({})
  const [products, setProducts] = useState<Record<string, AdvisorProductFeedbackInput>>({})
  const [missing, setMissing] = useState<string[]>([])
  const [catalogue, setCatalogue] = useState<AdvisorCatalogueProduct[]>([])
  const [nextAction, setNextAction] = useState('')
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const dossier = await fetchAdvisorDossier(sessionId)
        setView(dossier)
        setAvailable(dossier !== null)
      } catch {
        setAvailable(false)
      } finally {
        setLoading(false)
      }
    })()
  }, [sessionId])

  // Catalogue réel (produit oublié, §8) : chargé une fois à l'ouverture du dossier.
  useEffect(() => {
    void fetchAdvisorCandidates(30, true)
      .then((result) => setCatalogue(result.catalogue))
      .catch(() => undefined)
  }, [])

  function toggleArea(code: string, checked: boolean) {
    const updated = { ...areaReasons }
    if (checked) updated[code] = ''
    else delete updated[code]
    setAreaReasons(updated)
  }

  async function submit() {
    if (!assessment) return
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
      if (response.status === 'SAVED') {
        setSent(true)
        setMessage(null)
      } else {
        setMessage(response.message ?? `Feedback non enregistré (${response.status}).`)
      }
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Erreur inconnue')
    }
  }

  if (loading) {
    return (
      <div className="logs-shell marketing-page">
        <p className="mkt-empty">Chargement du dossier…</p>
      </div>
    )
  }

  if (!available || !view) {
    return (
      <div className="logs-shell marketing-page">
        <header className="logs-header">
          <a className="logs-back" href="#/advisor-feedback">
            <ArrowLeft size={16} /> Tableau de suivi
          </a>
          <h1>
            <BadgeCheck size={20} /> Feedback conseiller
          </h1>
        </header>
        <div className="mkt-card">
          <h2>Ce dossier n&rsquo;est plus disponible.</h2>
          <p className="mkt-empty">
            La conversation correspondante n&rsquo;est plus accessible (session inconnue, backend redémarré ou
            dossier purgé). Aucune donnée n&rsquo;est perdue : le tableau de suivi reste consultable.
          </p>
        </div>
      </div>
    )
  }

  const dossier = view.dossier

  return (
    <div className="logs-shell marketing-page">
      <header className="logs-header">
        <a className="logs-back" href="#/advisor-feedback">
          <ArrowLeft size={16} /> Tableau de suivi
        </a>
        <h1>
          <BadgeCheck size={20} /> Feedback conseiller
        </h1>
        <span className="mkt-muted">
          {view.feedbackStatus === 'COMPLETED'
            ? `Feedback déjà donné (version ${view.feedback?.version ?? 1})`
            : 'En attente de votre avis'}
        </span>
      </header>

      <div className="mkt-card wide">
        <h2>Conversation du {dossier.timestamp.slice(0, 10)}</h2>
        <div className="mkt-report-cols">
          <div>
            <h3>Projet détecté</h3>
            <p className="mkt-empty">{dossier.mainProject || 'Non identifié'}</p>
            {dossier.otherProjects.length > 0 && (
              <p className="mkt-muted">Autres sujets : {dossier.otherProjects.join(' · ')}</p>
            )}
            {dossier.preferences.length > 0 && (
              <>
                <h3>Préférences relevées</h3>
                <ul className="mkt-report-list">
                  {dossier.preferences.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </>
            )}
          </div>
          <div>
            <h3>Suivi conseillé</h3>
            {dossier.nextActions.length === 0 ? (
              <p className="mkt-empty">Aucune action de suivi extraite du mail conseiller.</p>
            ) : (
              <ul className="mkt-report-list">
                {dossier.nextActions.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}
          </div>
        </div>
        <h3>Synthèse préparée par le Coach</h3>
        <pre className="afb-summary">{dossier.advisorEmail.body || '(vide)'}</pre>
      </div>

      {sent ? (
        <div className="mkt-card wide">
          <h2>
            <Check size={16} /> Merci, votre retour a bien été enregistré.
          </h2>
          <p className="mkt-empty">
            Il alimente les indicateurs de pertinence et l&rsquo;analyse IA. Aucune modification du Coach
            n&rsquo;est appliquée automatiquement : l&rsquo;équipe décide des évolutions.
          </p>
          <a className="mkt-action" href="#/advisor-feedback">
            Retour au tableau de suivi
          </a>
        </div>
      ) : (
        <div className="mkt-card wide">
          <h2>Votre avis sur cette analyse</h2>
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
                        onChange={(event) => toggleArea(area.code, event.target.checked)}
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

          <h3>Produits identifiés</h3>
          {dossier.productsOfInterest.length === 0 ? (
            <p className="mkt-empty">Aucun produit d&rsquo;intérêt dans ce dossier.</p>
          ) : (
            <table className="mkt-table compact">
              <thead>
                <tr>
                  <th>Produit</th>
                  <th>Intérêt IA</th>
                  <th>Motif du Coach</th>
                  <th>Votre jugement</th>
                  <th>Intérêt corrigé</th>
                  <th>Motif</th>
                </tr>
              </thead>
              <tbody>
                {dossier.productsOfInterest.map((product) => {
                  const entry = products[product.productId] ?? { productId: product.productId }
                  const update = (patch: Partial<AdvisorProductFeedbackInput>) =>
                    setProducts({
                      ...products,
                      [product.productId]: {
                        ...entry,
                        productId: product.productId,
                        productName: product.name ?? undefined,
                        aiInterestLevel: product.interestLevel ?? undefined,
                        ...patch,
                      },
                    })
                  return (
                    <tr key={product.productId}>
                      <td className="mkt-strong">{product.name ?? product.productId}</td>
                      <td>{product.interestLevel ?? '—'}</td>
                      <td className="mkt-muted">{product.interestReason ?? '—'}</td>
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
                  {catalogue.find((product) => product.productId === id)?.productName ?? id}
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

          <h3>Commentaire facultatif</h3>
          <textarea
            className="qlt-comment"
            rows={3}
            maxLength={1000}
            placeholder="Commentaire (facultatif)"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />

          {message && <div className="mkt-notice warn">{message}</div>}
          <div className="qtl-send-row">
            <button type="button" className="qlt-send" onClick={() => void submit()} disabled={!assessment}>
              <Send size={14} /> Envoyer mon feedback
            </button>
            <p className="mkt-hint">
              Un avis déjà enregistré peut être révisé : une nouvelle version est créée, l&rsquo;historique est
              conservé. Aucune donnée personnelle n&rsquo;est stockée.
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
