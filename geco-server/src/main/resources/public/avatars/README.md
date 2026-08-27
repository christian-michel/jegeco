# avatars/ — Galerie d'avatars filtrable

## Comment ajouter vos images

1. Déposez vos fichiers image ici (`public/avatars/`).
2. Renseignez `avatars-catalog.js` avec une entrée par image :
   ```js
   { id: "identifiant_unique", filename: "nom_du_fichier.png",
     genre: "homme" | "femme" | "neutre",
     ageCategory: "enfant" | "adulte" | "senior",
     skinTone: "#codehex", skinToneLabel: "claire" | "mate" | "foncée" }
   ```
3. Recompilez (`./run.sh --rebuild`).

Si vous préférez, envoyez-moi vos images + un fichier Excel/CSV avec ces mêmes
colonnes (comme pour le catalogue de cartes) : je génère `avatars-catalog.js`
automatiquement, sans que vous ayez à toucher au format JS vous-même.

## Comportement

- La galerie se filtre par genre / âge / teint (mise à jour instantanée).
- Une image manquante ou pas encore ajoutée affiche un repli automatique
  (avatar généré en SVG) plutôt que de casser l'affichage.
- Le nombre d'avatars n'est pas limité : ajoutez-en autant que vous voulez, à
  tout moment, sans changement de code.
