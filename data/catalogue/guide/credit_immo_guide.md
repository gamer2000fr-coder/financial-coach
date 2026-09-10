# Guide IA — Crédit immobilier

## Structure

Le fichier contient une liste `offres` couvrant prêt immobilier à taux fixe, prêts réglementés, crédit relais, in fine, épargne logement et avantages BFM.

## Classification et projet

- `type_credit` : nature du financement.
- `cible` : profil principalement visé.
- `projets_adaptes` : opérations compatibles d'après le catalogue.
- les suffixes `_sous_conditions` signifient qu'une vérification supplémentaire est nécessaire.

## Éligibilité et conditions

Le fichier utilise à la fois `eligibilite` et `conditions`.

`eligibilite` regroupe notamment primo-accession, ressources, PEL/CEL et conditions réglementaires.

`conditions` apparaît notamment sur le prêt à taux fixe et le crédit relais. Interpréter les clés concrètes (`majeur`, `compte_sg`, `bien_actuel_en_vente`, `acceptation_dossier`) comme des prérequis indiqués, sans extrapoler au-delà.

## Taux

- `type: fixe` : taux fixe.
- `taux_personnalise: true` : aucun taux universel ne doit être inventé.
- `taux_interet_pct: 0` : taux d'intérêt indiqué à zéro pour le prêt concerné.
- `type: plafonne` : le catalogue indique un taux plafonné, sans fournir nécessairement le taux client.
- `determination` : explique comment le taux est déterminé.

Ne pas confondre taux d'intérêt et TAEG lorsqu'un TAEG n'est pas fourni.

## Financement

- `jusqua_pct_operation` : part maximale de l'opération indiquée.
- `finance_totalite_operation: false` : financement complémentaire requis.
- `quotite_indicative` : proportions indicatives ; ne pas les transformer en droit automatique.
- `montant.maximum_pct_valeur_bien_a_vendre` : plafond relatif au bien à vendre pour le crédit relais.

## Durée

Les prêts peuvent utiliser `minimum_annees`/`maximum_annees` ou `maximum_mois`.

`maximum_mois_vefa_ou_construction` est une durée maximale spécifique au cas indiqué.

`duree_indicative` signifie que la durée est présentée comme indicative.

## Remboursement et flexibilité

- `mensualites_modulables`, `report_echeances_possible`, `paliers_remboursement` : souplesses indiquées.
- pour un crédit in fine, `capital: fin_du_pret` signifie que le capital est remboursé à l'échéance selon le catalogue.
- pour le crédit relais, différé partiel/total et remboursement du capital en fin de prêt sont décrits séparément.

## Garanties

`garanties_possibles` liste des formes de garantie envisageables. « Possible » ne signifie pas qu'elles sont toutes disponibles ou retenues pour chaque dossier.

## Prêts réglementés

PTZ, PAS, Prêt Conventionné, Prêt Épargne Logement et Éco-PTZ comportent des règles d'éligibilité réglementaires. L'IA doit :
1. vérifier l'adéquation du projet ;
2. vérifier les conditions connues ;
3. signaler les conditions réglementaires non déterminables ;
4. ne jamais déclarer l'éligibilité certaine à partir d'informations client insuffisantes.

`doit_etre_associe_a` est une contrainte d'association explicite du catalogue.

## BFM

`sg_bfm_avantages_immo` est décrit comme un ensemble d'avantages pour certains clients de la fonction publique, et non comme un prêt immobilier autonome classique.

`produits_concernes` référence des identifiants de produits. Si une référence n'est pas définie dans le fichier, ne pas inventer ses caractéristiques.

## Recommandation

Priorité de raisonnement :
1. projet immobilier ;
2. éligibilité aux dispositifs réglementés ;
3. situation particulière (bien à vendre, investissement locatif, PEL/CEL, fonction publique) ;
4. financement principal ;
5. comparaison des solutions explicitement demandée par `comparaison_obligatoire_avec` ;
6. vigilances, notamment pour l'in fine.

Pour l'in fine, les éléments `cout_total`, `risque_capital_a_rembourser_a_echeance` et `necessite_epargne_ou_garantie_associee` doivent être considérés comme des points de vigilance importants.
