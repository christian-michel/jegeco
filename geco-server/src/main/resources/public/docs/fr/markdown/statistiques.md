# Comprendre les statistiques de fin de partie

Cette page explique les indicateurs affichés sur le rapport de fin de partie
(moyenne, médiane, écart type, indice de Gini, seuils de pauvreté et de
condition modeste), avec leurs définitions officielles et des sources pour
aller plus loin.

## Seuil de pauvreté et seuil de condition modeste

Ğeconomicus utilise les **définitions officielles d'Eurostat et de l'INSEE**,
utilisées dans toutes les comparaisons de pauvreté en Europe :

- **Seuil de pauvreté** = 60 % du niveau de vie **médian** (pas la moyenne).
  Un joueur est considéré "pauvre" si sa richesse en fin de partie est
  inférieure à ce seuil.
- **Seuil de condition modeste** = 75 % du niveau de vie médian. Entre les
  deux seuils (60 % et 75 % de la médiane), on parle de personnes "modestes"
  plutôt que "pauvres" : leurs conditions de vie sont proches de celles des
  personnes pauvres, sans être comptées dans le taux de pauvreté au sens
  strict.

Pourquoi la **médiane** plutôt que la moyenne ? La médiane est la valeur qui
sépare la population en deux moitiés égales (autant de joueurs au-dessus qu'en
dessous). Elle est moins sensible qu'une moyenne aux valeurs extrêmes : un
seul joueur extrêmement riche ferait fortement remonter une moyenne, sans
refléter la situation du joueur "typique" - la médiane, elle, ne bouge pas
pour autant.

**Pour aller plus loin :**
- [Base de données Eurostat sur les revenus et conditions de
  vie](https://ec.europa.eu/eurostat/fr/web/income-and-living-conditions/database)
- [La pauvreté en Europe — Observatoire des inégalités](https://www.inegalites.fr/La-pauvrete-en-Europe)
- Étude DREES, *Personnes pauvres et modestes en Europe : qui sont-elles ?*,
  Études et Résultats n°1349, septembre 2025 (Théodore Bérut) - la source qui a
  servi à préciser ces définitions dans l'application. Disponible auprès de la
  DREES (drees.solidarites-sante.gouv.fr).

## Indice de Gini

L'indice de Gini mesure les **inégalités de richesse** au sein d'un groupe, sur
une échelle de 0 à 1 (affiché ici multiplié par 100, donc de 0 à 100) :

- **0** = égalité parfaite (tous les joueurs ont exactement la même richesse).
- **100** (proche de 1) = inégalité extrême (un seul joueur possède toute la
  richesse, tous les autres n'ont rien).

Concrètement, l'indice compare la répartition réelle des richesses entre
joueurs à une répartition parfaitement égalitaire : plus l'écart entre les
deux est grand, plus l'indice est élevé. C'est l'indicateur le plus utilisé au
monde pour comparer les inégalités entre pays, régions, ou - ici - entre deux
systèmes monétaires au sein d'une même partie de Ğeconomicus.

**Pour aller plus loin :**
- [Coefficient de Gini — Wikipédia](https://fr.wikipedia.org/wiki/Coefficient_de_Gini)
- [Définition de l'indice de Gini — INSEE](https://www.insee.fr/fr/metadonnees/definition/c1551)
- [Indice de Gini, données et statistiques — INSEE](https://www.insee.fr/fr/statistiques/2491918)
