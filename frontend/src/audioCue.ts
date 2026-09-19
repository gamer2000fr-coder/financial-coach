/**
 * Repères SONORES du mode audio (mains libres), façon Siri.
 * <p>
 * « En veille — dites “Chloé” puis votre question » : sans repère sonore, l'utilisateur ne sait pas si le
 * mot-clé a été entendu, ni quand l'application a commencé à écouter. Un **carillon court et montant**
 * joué au moment de la reconnaissance marque ce début d'écoute — comme le « ding » de Siri sur iPhone.
 * <p>
 * Le son est **synthétisé** (Web Audio API) plutôt qu'embarqué en fichier : aucun binaire à versionner,
 * aucun chargement réseau, aucun échec possible si l'asset manque, et la durée/le volume restent réglables
 * ici. Deux notes suffisent : A5 puis E6 (quinte montante = signal « je t'écoute »), très courtes et
 * discrètes pour ne pas polluer la reconnaissance vocale en cours (le micro est ouvert).
 */

/** Notes du carillon : fréquence (Hz), décalage (s) et durée (s) — montée franche, sans traîne. */
const WAKE_CUE_TONES: ReadonlyArray<{ frequency: number; delay: number; duration: number }> = [
  { frequency: 880.0, delay: 0.0, duration: 0.085 }, // A5
  { frequency: 1318.5, delay: 0.07, duration: 0.12 }, // E6
]

/** Volume de crête volontairement bas : le micro est ouvert, le carillon ne doit pas être capté. */
const WAKE_CUE_PEAK_GAIN = 0.06

/** Attaque courte mais NON nulle : sans montée, le son commence par un « clic ». */
const WAKE_CUE_ATTACK_SECONDS = 0.012

/** Anti-rebond : le mot-clé peut être reconnu deux fois en quelques millisecondes (résultats partiels). */
const WAKE_CUE_MIN_INTERVAL_MS = 250

let audioContext: AudioContext | null = null
let lastPlayedAt = 0

/**
 * Contexte audio PARTAGÉ (un seul par onglet) : en créer un à chaque réveil épuiserait la limite des
 * navigateurs (« too many AudioContexts ») et le carillon finirait par ne plus jouer du tout.
 */
function audioContextFor(): AudioContext | null {
  if (typeof window === 'undefined') return null
  const Ctor = (window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext })
    .webkitAudioContext) as typeof AudioContext | undefined
  if (!Ctor) return null
  if (!audioContext) {
    try {
      audioContext = new Ctor()
    } catch {
      return null
    }
  }
  return audioContext
}

/**
 * Joue le carillon de RÉVEIL : à appeler dès que le mot-clé est reconnu, avant d'écouter la question.
 * <p>
 * Ne lève jamais d'exception et n'attend rien du navigateur : un repère sonore indisponible (navigateur
 * sans Web Audio, onglet sans sortie audio, contexte refusé avant toute interaction) ne doit JAMAIS
 * empêcher l'écoute de la question.
 */
export function playWakeCue(): void {
  const now = Date.now()
  if (now - lastPlayedAt < WAKE_CUE_MIN_INTERVAL_MS) return
  const context = audioContextFor()
  if (!context) return
  try {
    lastPlayedAt = now
    // Un contexte « suspended » (onglet en arrière-plan, première utilisation) doit être relancé ;
    // speechSynthesis, lui, n'a pas d'horloge : on part de currentTime dans tous les cas.
    if (context.state === 'suspended') {
      void context.resume().catch(() => undefined)
    }
    const start = context.currentTime + 0.01
    WAKE_CUE_TONES.forEach((tone) => {
      const oscillator = context.createOscillator()
      const gain = context.createGain()
      const from = start + tone.delay
      oscillator.type = 'sine'
      oscillator.frequency.setValueAtTime(tone.frequency, from)
      // Enveloppe : montée douce puis extinction exponentielle (aucun clic début/fin).
      gain.gain.setValueAtTime(0.0001, from)
      gain.gain.exponentialRampToValueAtTime(WAKE_CUE_PEAK_GAIN, from + WAKE_CUE_ATTACK_SECONDS)
      gain.gain.exponentialRampToValueAtTime(0.0001, from + tone.duration)
      oscillator.connect(gain).connect(context.destination)
      oscillator.start(from)
      oscillator.stop(from + tone.duration + 0.02)
    })
  } catch {
    // Repère purement informatif : jamais bloquant.
  }
}
