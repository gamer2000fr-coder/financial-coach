# Guide IA — Crédit à la consommation

## Structure

Le fichier contient `catalogue` puis une liste `offres`. Chaque offre est identifiée par `id` et `nom`.

## Champs de classification

- `famille` : famille fonctionnelle (prêt personnel, auto, étudiant, regroupement, BFM, etc.).
- `type_credit` : fonctionnement du crédit, par exemple amortissable, renouvelable ou regroupement.
- `cible` : profils auxquels l'offre est destinée.
- `distributeur`, `preteur` : distinguent la distribution SG du prêteur lorsque la Banque Française Mutualiste intervient.

## Adéquation au besoin

- `projets_adaptes` : usages pour lesquels l'offre est pertinente.
- `projets_exclus` : projets explicitement exclus.
- `credits_exclus` : catégories de crédits ne pouvant pas être intégrées à une opération de regroupement.
- `besoin_complementaire_possible` : possibilité indiquée d'intégrer un besoin supplémentaire.

`projets_adaptes` n'implique pas à lui seul que le crédit soit juridiquement affecté à l'achat.

## Éligibilité

`eligibilite` contient les critères connus : âge, statut, inscription dans l'enseignement supérieur, appartenance à la fonction publique/BFM, compte SG, etc.

`cible` est un positionnement ; `eligibilite` doit être considérée comme plus forte pour déterminer l'accès à l'offre.

Une valeur telle que `"limite_age": null` signifie que la limite n'est pas renseignée dans le catalogue.

## Montant

Dans `montant` :
- `minimum` / `maximum` : bornes indiquées ;
- `maximum_public` : maximum publié ; `null` = non renseigné ;
- `reponse_principe_immediate_jusqua` : seuil lié à la réponse de principe, pas plafond du crédit ;
- `minimum_virement` : montant minimal d'une utilisation/virement ;
- `montant_unique` : minimum et maximum identiques correspondent à un montant unique ;
- `determination` : règle textuelle de détermination du montant ;
- `devise` : unité monétaire.

## Durée

- `minimum_mois`, `maximum_mois` : plage de durée.
- `duree_unique` : indique une durée fixe lorsque les deux bornes sont identiques.

## Taux et coût

Dans `taux` :
- `type` : fixe ou autre fonctionnement ;
- `taeg` / `taeg_fixe_pct` : TAEG renseigné dans le catalogue ;
- `depend_du_dossier` : le taux affiché ne doit pas être présenté comme garanti à tous les clients ;
- `depend_de_la_duree` : le taux dépend de la durée ;
- `conditions_preferentielles` : signale un positionnement préférentiel.

`frais_dossier: 0` signifie que le catalogue indique zéro frais de dossier pour l'offre concernée.

## Crédit renouvelable

Pour Alterna, `fonctionnement` décrit la réserve :
- `reserve_reutilisable` ;
- intérêts sur les sommes utilisées ;
- coût lorsque la réserve n'est pas utilisée.

Ne pas comparer un crédit renouvelable et un prêt amortissable uniquement sur la mensualité. La règle `vigilance` demande explicitement une comparaison du coût total.

## Souplesse et remboursement

`flexibilite` peut contenir :
- mensualités modulables ;
- report d'échéances ;
- différé ;
- remboursement anticipé ;
- nombre maximal de reports.

`remboursement_anticipe` peut aussi être un objet avec `possible` et `sans_frais`.

## Assurance et caution

- `assurance_emprunteur.disponible` : assurance proposée.
- `assurance_emprunteur.obligatoire` : obligation indiquée dans le catalogue.
- `caution.generalement_requise` : ne pas transformer « généralement » en « toujours ».
- `alternative_sans_caution_personnelle` : solution alternative décrite pour le prêt étudiant.

## Règles de recommandation

Appliquer d'abord l'éligibilité, puis :
- `forte_priorite_si` / `priorite_tres_elevee_si` ;
- `priorite_si` ;
- `comparaison_obligatoire_avec` ;
- `eviter_si` ;
- `vigilance`.

Cas importants :
- un étudiant éligible doit faire comparer le prêt étudiant avec un prêt personnel générique ;
- un client éligible BFM doit faire examiner les offres BFM correspondantes ;
- un regroupement de crédits doit être évalué sur le coût total, pas uniquement sur la baisse de mensualité ;
- un besoin récurrent de petite trésorerie peut correspondre au renouvelable, mais la vigilance coût reste prioritaire.

## Valeurs absentes

Ne pas inventer un maximum, un TAEG ou une durée lorsque le champ est absent ou `null`.
