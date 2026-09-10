# Guide IA — Épargne et placements

## Objectif

Le fichier couvre des produits très différents : livrets réglementés/non réglementés, épargne logement, compte à terme, assurance-vie, PER et enveloppes boursières. Ils ne doivent pas être classés sur le seul rendement.

## Critères principaux de comparaison

Pour recommander un produit, analyser :
1. objectif du client ;
2. besoin de liquidité ;
3. horizon ;
4. tolérance au risque/perte en capital ;
5. éligibilité ;
6. fiscalité décrite ;
7. versement minimum/plafond ;
8. rendement lorsqu'il est renseigné ;
9. règles de recommandation.

## Risque et garantie

Le schéma n'est pas uniforme :
- `risque: "nul"` est une valeur simple ;
- `risque: "perte_capital_possible"` indique un risque de perte ;
- pour l'assurance-vie/PER, `risque` peut être un objet distinguant fonds euros et unités de compte ;
- `capital_garanti` est un booléen lorsqu'il est fourni.

Ne jamais appliquer la garantie d'un fonds euros aux unités de compte.

## Liquidité et disponibilité

- `liquidite: immediate` / `disponible` : fonds présentés comme disponibles.
- l'assurance-vie décrit `rachat_partiel` et `rachat_total`.
- le PER utilise `disponibilite` et indique un blocage de principe jusqu'à la retraite avec cas de déblocage.
- le compte à terme indique un `retrait_anticipe` avec pénalités possibles.

Une épargne de précaution doit privilégier la disponibilité avant la recherche de rendement.

## Versements et plafonds

`versement` varie selon le produit :
- `minimum`, `minimum_ouverture`, `initial_minimum` ;
- versements périodiques ;
- `plafond` / `plafond_reglementaire` ;
- versement `unique` pour le compte à terme.

`plafond: null` signifie plafond non renseigné dans le catalogue, pas absence certaine de plafond.

## Rendement

Les champs peuvent être :
- `taux_net_annuel_pct` ;
- `taux_standard_brut_pct` ;
- `prime_fidelite_brute_pct` ;
- taux par tranche ;
- `taux: "taux_SG_en_vigueur"` ;
- `taux_public: false`.

Respecter `brut` versus `net`. Ne pas comparer directement un taux brut à un taux net sans tenir compte de cette différence.

`date_taux` indique la date associée au taux renseigné et doit être prise en compte pour les données susceptibles d'évoluer.

## Fiscalité

Le fichier décrit uniquement certains aspects :
- exonération d'impôt/prélèvements pour certains livrets ;
- caractère imposable pour certains livrets ;
- avantage après cinq ans pour le PEA ;
- déductibilité sous conditions pour le PER.

Ne pas compléter automatiquement avec des règles fiscales non présentes si la réponse doit rester strictement fondée sur le catalogue.

## Épargne réglementée

Pour Livret A, LDDS, LEP et Livret Jeune, `eligibilite` et `nombre_maximum_par_personne` sont des contraintes structurantes.

Le LEP comporte une priorité très élevée sous réserve d'éligibilité. Ne pas le recommander comme accessible sans vérifier la condition de revenu.

## Épargne logement

CEL et PEL comportent `droits_a_pret_immobilier`. Il s'agit d'un avantage lié au produit, pas d'une garantie d'obtention d'un prêt.

Le PEL possède un `horizon` avec durée maximale de versements et clôture automatique indiquées dans le catalogue.

## Assurance-vie

Érable Essentiel et Séquoia indiquent fonds euros et unités de compte. La présence d'unités de compte implique le risque de perte indiqué.

`gestion` décrit les modes de gestion proposés.

Ébène est décrit principalement par son `positionnement` et ses règles de recommandation ; ne pas inventer des seuils ou supports absents.

## PER

`objectif`, `disponibilite`, `sortie`, `fiscalite` et `risque` sont centraux. Le besoin de liquidité court terme et l'absence d'épargne de précaution sont explicitement des motifs pour éviter le produit.

## PEA, PEA-PME et CTO

Ces produits comportent un risque de perte en capital.

- `horizon_recommande.minimum_annees` est une recommandation d'horizon.
- `supports` décrit les instruments accessibles.
- le CTO est notamment positionné lorsque le PEA ne correspond pas aux actifs recherchés ou lorsque l'accès aux marchés internationaux est souhaité.

Ne pas recommander ces produits comme épargne de précaution lorsque les règles du catalogue indiquent le contraire.

## Priorités

`priorite_avant` et `prioritaire_par_rapport_a` expriment une préférence explicite. Les utiliser après vérification de l'éligibilité.

Le rendement ne doit jamais être l'unique critère : risque, liquidité, horizon et fiscalité doivent être pris en compte.
