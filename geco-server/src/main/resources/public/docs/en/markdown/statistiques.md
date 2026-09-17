# Understanding end-of-game statistics

This page explains the indicators shown on the end-of-game report (mean,
median, standard deviation, Gini index, poverty and modest-living
thresholds), with their official definitions and sources to learn more.

## Poverty threshold and modest-living threshold

Ğeconomicus uses the **official Eurostat and INSEE definitions**, used in
every poverty comparison across Europe:

- **Poverty threshold** = 60% of the **median** standard of living (not the
  mean). A player is considered "poor" if their end-of-game wealth falls
  below this threshold.
- **Modest-living threshold** = 75% of the median standard of living.
  Between the two thresholds (60% and 75% of the median), people are
  referred to as "modest" rather than "poor": their living conditions are
  close to those of poor people, without being counted in the poverty rate
  in the strict sense.

Why the **median** rather than the mean? The median is the value that
splits the population into two equal halves (as many players above as
below). It is less sensitive than a mean to extreme values: a single
extremely wealthy player would sharply raise a mean, without reflecting the
situation of the "typical" player — the median, by contrast, does not move.

**To go further:**
- [Eurostat database on income and living
  conditions](https://ec.europa.eu/eurostat/en/web/income-and-living-conditions/database)
- [Poverty in Europe — Observatoire des inégalités (French)](https://www.inegalites.fr/La-pauvrete-en-Europe)
- DREES study, *Personnes pauvres et modestes en Europe : qui sont-elles ?*,
  Études et Résultats n°1349, September 2025 (Théodore Bérut) — the source
  used to refine these definitions in the application. Available from DREES
  (drees.solidarites-sante.gouv.fr), French only.

## Gini index

The Gini index measures **wealth inequality** within a group, on a scale
from 0 to 1 (shown here multiplied by 100, so from 0 to 100):

- **0** = perfect equality (every player has exactly the same wealth).
- **100** (close to 1) = extreme inequality (a single player holds all the
  wealth, everyone else has nothing).

Concretely, the index compares the actual distribution of wealth among
players to a perfectly equal distribution: the larger the gap between the
two, the higher the index. It is the most widely used indicator in the
world for comparing inequality between countries, regions, or — here —
between two monetary systems within the same Ğeconomicus game.

**To go further:**
- [Gini coefficient — Wikipedia](https://en.wikipedia.org/wiki/Gini_coefficient)
- [Definition of the Gini index — INSEE (French)](https://www.insee.fr/fr/metadonnees/definition/c1551)
- [Gini index, data and statistics — INSEE (French)](https://www.insee.fr/fr/statistiques/2491918)
