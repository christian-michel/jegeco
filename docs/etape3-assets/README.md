# etape3-assets — Gabarit d'assemblage des cartes

## Utilisation

1. Recevez un lot d'illustrations de ChatGPT (ex. la famille "Pizza" : 1 produit +
   5 constituants).
2. Enregistrez chaque image dans `illustrations/`, avec le nom de fichier **exact**
   indiqué dans `../prompts-illustrations.csv` (colonne `nom_fichier`), par exemple :
   - `illustrations/primaire_produit_pizza.png`
   - `illustrations/primaire_constituant_ble.png`
3. Ouvrez `card-render.html` dans un navigateur (double-clic suffit, pas besoin de
   serveur). Les cartes dont l'illustration est présente s'assemblent
   automatiquement ; celles qui n'ont pas encore d'image affichent un repli
   textuel (📦/🔸 + nom) sans bloquer l'affichage des autres.
4. Utilisez la barre de recherche ou le filtre par secteur pour retrouver
   rapidement une famille de cartes précise parmi les 520.

## Fichiers

- `card-render.html` — le gabarit lui-même (HTML/CSS/JS, aucune dépendance
  externe, aucun outil de build requis).
- `cards-data.js` — les 520 cartes physiques (104 produits × 5 constituants),
  généré automatiquement depuis `geconomicus_26_cartes_4_secteurs.xlsx`. Ne pas
  éditer à la main : si le catalogue change dans le classeur source, régénérer ce
  fichier plutôt que de le corriger directement.
- `illustrations/` — dossier où déposer les images reçues (vide au départ).
- `card-render-preview.png` — capture d'exemple (famille "Pizza", avec repli
  automatique puisqu'aucune illustration n'est encore fournie à ce stade).

## Point de vigilance corrigé pendant les tests

Le premier jet du gabarit utilisait un gestionnaire `onerror` écrit comme une
chaîne JavaScript directement dans l'attribut HTML. Un nom de produit contenant
une apostrophe (`Jus d'orange`, présent dans le catalogue) cassait cette chaîne —
un vrai bug, reproductible dans n'importe quel navigateur, pas une simple
limite d'outil de test. Corrigé en attachant les gestionnaires d'erreur en
JavaScript après coup plutôt qu'en attribut inline, avec échappement systématique
des noms affichés.
