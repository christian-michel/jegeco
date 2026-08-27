# Étape 3 — Prompts d'illustration pour les cartes

Ce document donne un gabarit de prompt réutilisable, cohérent avec le style de la
maquette de référence, plus un fichier CSV contenant un prompt prêt à l'emploi pour
chacune des ~450 illustrations nécessaires (104 produits transformés + ~350
constituants uniques, tous secteurs confondus).

## Recommandation : illustrer séparément l'icône et composer la carte ensuite

Plutôt que de demander à l'IA de générer la carte complète avec le texte intégré
(nom du produit, nom du constituant), qui donne souvent un texte mal orthographié
ou mal positionné, je recommande de :

1. Générer uniquement **l'illustration** (le produit en grand, ou le constituant en
   icône), **sans aucun texte incrusté dans l'image**.
2. Composer la carte finale (fond coloré, cercle, coins arrondis, textes) par-dessus
   ces illustrations avec un gabarit — un simple template HTML/CSS ou SVG suffit,
   et donne un rendu bien plus net et cohérent d'une carte à l'autre que du texte
   généré par IA. Je peux m'en charger via un script une fois les illustrations
   prêtes (elles doivent juste garder un nom de fichier prévisible, voir le CSV).

## Gabarit de prompt — illustration du produit transformé (grande image centrale)

```
Digital illustration of {PRODUIT}, centered composition, small children's card game
style, semi-realistic and colorful, warm inviting lighting, clean bold outlines,
simple uncluttered background, vibrant but harmonious colors, no text, no letters,
no watermark, no signature, square format, leaves breathing room around the edges
for a circular crop.
```

## Gabarit de prompt — icône de constituant (petite illustration de coin)

```
Digital illustration icon of {CONSTITUANT}, centered on a plain light background,
small children's card game style, semi-realistic and colorful, soft shading, clean
bold outlines, simple and instantly recognizable silhouette even at small size, no
text, no letters, no watermark, no signature, square format.
```

## Palette par secteur (à réutiliser pour le fond de carte, hors prompt d'illustration)

| Secteur | Couleur | Hex |
|---|---|---|
| Jaune — Primaire | 🟡 | `#F5C542` |
| Bleu — Secondaire | 🔵 | `#4A90E2` |
| Vert — Électronique | 🟢 | `#43B581` |
| Rouge — Informatique | 🔴 | `#E94F4F` |

## Fichier des prompts

👉 **`prompts-illustrations.csv`** (dans ce même dossier `docs/`) contient une ligne
par illustration à produire, avec :
- `secteur` : Primaire / Secondaire / Électronique / Informatique
- `type` : "produit" ou "constituant"
- `nom` : le nom exact (français) à illustrer
- `nom_fichier` : nom de fichier suggéré (normalisé, sans accents/espaces), pour
  garder une correspondance fiable entre le CSV et vos fichiers générés
- `prompt` : le prompt complet prêt à copier-coller

Exemple (secteur Primaire, produit "Pizza") :

| secteur | type | nom | nom_fichier | prompt |
|---|---|---|---|---|
| Primaire | produit | Pizza | primaire_produit_pizza.png | Digital illustration of Pizza, centered composition... |
| Primaire | constituant | Blé | primaire_constituant_ble.png | Digital illustration icon of Blé, centered on a plain light background... |

Les constituants qui reviennent d'un produit à l'autre au sein d'un même secteur
(ex. "Blé" apparaît dans Pizza, Pain, Bière...) **n'apparaissent qu'une seule fois**
dans le CSV : une illustration par constituant suffit, réutilisable sur toutes les
cartes qui le mentionnent.
