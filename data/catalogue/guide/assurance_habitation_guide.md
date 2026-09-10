# Guide IA — Assurance Habitation SG

## Structure

Le JSON utilise `produits` pour contenir un **objet produit unique**, malgré le nom au pluriel. Cet objet contient `cibles`, `formules`, les garanties possibles, les protections complémentaires, un avantage lié au crédit SG et des règles de recommandation.

## Cibles

Au niveau produit, `cibles` liste les grands profils : locataire, propriétaire occupant, étudiant et propriétaire non occupant.

Au niveau formule, le champ est `cible` au singulier et précise les profils associés.

Ne pas confondre `cibles` avec une condition d'éligibilité.

## Formules

- `Initiale` : `niveau_protection: 1`, positionnement essentiel.
- `Confort` : niveau 2, intermédiaire.
- `Optimale` : niveau 3, renforcé.
- `Étudiante` : positionnement logement étudiant.
- `Propriétaire non occupant` : cible bailleur/investisseur locatif et informations spécifiques.

`positionnement` décrit le rôle commercial de la formule ; il ne fournit pas à lui seul la liste détaillée de ses garanties.

## Garanties possibles

`garanties_possibles` est une liste au niveau du produit. Elle ne précise pas, dans ce JSON, quelles garanties appartiennent exactement à Initiale, Confort ou Optimale.

L'IA ne doit donc **pas inventer une matrice de garanties par formule**.

Même règle pour `protections_complementaires` : elles sont décrites comme possibles au niveau produit, pas nécessairement incluses dans toutes les formules.

## Formule PNO

La formule propriétaire non occupant contient :
- tarif `a_partir_de_mensuel` ;
- `eligibilite` ;
- `contrat`.

Le tarif est indicatif.

`eligibilite` doit être vérifiée : personne physique, majorité, titulaire d'un compte SG et nombre maximal de pièces selon les données du catalogue.

## Contrat

`duree_initiale_mois` et `renouvellement_tacite` décrivent le fonctionnement contractuel indiqué pour la formule où ces champs apparaissent. Ne pas les généraliser automatiquement aux autres formules si le JSON ne le dit pas.

## Plus Tranquillité Bancaire

`avantage_credit_sg` décrit une prise en charge **possible**, sous conditions, de mensualités du prêt immobilier SG en cas de sinistre grave.

`maximum_mensualites` est un maximum indiqué, pas une prise en charge automatique.

## Règles de recommandation

Les champs `si_*` sont des règles conditionnelles :
- locataire → tenir compte de l'obligation indiquée ;
- propriétaire en copropriété → responsabilité civile indiquée comme obligatoire ;
- propriétaire de maison individuelle → assurance indiquée comme non obligatoire légalement mais fortement recommandée ;
- investissement locatif → proposer la formule PNO.

Ces règles orientent le choix du type/formule. Elles ne permettent pas de déduire les garanties détaillées absentes du JSON.

## Point important pour l'IA

Ce fichier est moins détaillé par formule que le fichier assurance auto. L'IA doit reconnaître cette limite et dire qu'une caractéristique précise n'est pas renseignée plutôt que d'attribuer arbitrairement une garantie à Initiale, Confort ou Optimale.
