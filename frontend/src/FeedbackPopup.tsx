import { useState } from 'react'
import { Star } from 'lucide-react'

import { QUALITY_REASONS } from './api'
import type { QualityFeedbackRequest } from './types.quality'

interface FeedbackPopupProps {
  /** Envoie l'avis (au mieux) puis clôture la conversation. */
  onSubmit: (payload: QualityFeedbackRequest) => void
  /** Ferme la pop-in sans avis : la conversation se clôture normalement. */
  onSkip: () => void
  submitting?: boolean
}

/**
 * POP-IN de fin de conversation (§3/§4/§6/§7) : quelques secondes suffisent.
 * <ul>
 *   <li>note 1 à 5 (envoi possible dès la sélection) ;</li>
 *   <li>motifs d'insatisfaction proposés uniquement à partir de 3 étoiles ou moins ;</li>
 *   <li>commentaire toujours facultatif ;</li>
 *   <li>« Passer » clôture sans rien enregistrer.</li>
 * </ul>
 */
export default function FeedbackPopup({ onSubmit, onSkip, submitting }: FeedbackPopupProps) {
  const [rating, setRating] = useState<number | null>(null)
  const [reasons, setReasons] = useState<string[]>([])
  const [comment, setComment] = useState('')

  const showReasons = rating !== null && rating <= 3

  function toggleReason(code: string) {
    setReasons((current) =>
      current.includes(code) ? current.filter((item) => item !== code) : [...current, code],
    )
  }

  function send() {
    if (rating === null) return
    onSubmit({ rating, selectedReasons: reasons, comment: comment.trim() || undefined })
  }

  return (
    <div className="qlt-popup-backdrop" role="dialog" aria-modal="true" aria-labelledby="qlt-popup-title">
      <div className="qlt-popup">
        <h2 id="qlt-popup-title">Comment s&rsquo;est passée votre conversation avec le Coach&nbsp;?</h2>
        <p className="mkt-subtitle">Votre avis nous aide à améliorer le Coach.</p>

        <div className="qlt-stars" role="radiogroup" aria-label="Note de 1 à 5 étoiles">
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={rating === value}
              aria-label={`${value} étoile${value > 1 ? 's' : ''}`}
              className={rating !== null && value <= rating ? 'qlt-star active' : 'qlt-star'}
              onClick={() => setRating(value)}
            >
              <Star size={28} fill={rating !== null && value <= rating ? 'currentColor' : 'none'} />
            </button>
          ))}
        </div>

        {rating !== null && rating >= 4 && <p className="qlt-thanks">Merci pour votre retour.</p>}

        {showReasons && (
          <div className="qlt-reasons">
            <p className="qlt-reasons-title">Qu&rsquo;est-ce qui pourrait être amélioré&nbsp;?</p>
            {QUALITY_REASONS.map((reason) => (
              <label key={reason.code} className="qlt-reason">
                <input
                  type="checkbox"
                  checked={reasons.includes(reason.code)}
                  onChange={() => toggleReason(reason.code)}
                />
                <span>{reason.label}</span>
              </label>
            ))}
          </div>
        )}

        <textarea
          className="qlt-comment"
          rows={3}
          maxLength={1000}
          placeholder="Commentaire (facultatif)"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
        />

        <div className="qlt-popup-actions">
          <button type="button" className="qlt-send" onClick={send} disabled={rating === null || submitting}>
            Envoyer mon avis
          </button>
          <button type="button" className="mkt-action" onClick={onSkip} disabled={submitting}>
            Passer
          </button>
        </div>
        <p className="mkt-hint">
          Avis facultatif et anonyme : aucune donnée personnelle n&rsquo;est enregistrée. Si l&rsquo;enregistrement
          échoue, la conversation est clôturée normalement.
        </p>
      </div>
    </div>
  )
}
