# avatars/ — Galerie d'avatars filtrable

## Comment ajouter vos images

1. Déposez vos fichiers image ici (`public/avatars/`), sous la forme
   `avatar_001.png`, `avatar_002.png`, etc.
2. Renseignez leurs métadonnées (genre, tranche d'âge, teint) depuis l'écran
   **Paramètres > mode smartphone > onglet Avatars** de l'application - un
   clic sur une ligne ouvre la zoombox d'édition.

Il n'y a plus de fichier `avatars-catalog.js` à éditer à la main : le
catalogue vit désormais uniquement côté serveur (voir `CatalogService`/
`CatalogSeeds`, fichier `catalogs/avatars.json` de l'installation), et c'est
la même donnée qui alimente à la fois l'écran d'administration et la galerie
du joueur (`GET /api/catalogs/avatars`, appelé par `player.js`). Éditer une
entrée dans l'écran Paramètres suffit : la galerie du joueur reflète le
changement immédiatement, sans recompilation.

## Comportement

- La galerie se filtre par genre / âge / teint (mise à jour instantanée).
- Une image manquante ou pas encore ajoutée affiche un repli automatique
  (avatar généré en SVG) plutôt que de casser l'affichage.
- Le nombre d'avatars n'est pas limité côté catalogue - mais la CRÉATION de
  nouvelles entrées ne se fait pas encore depuis l'écran Paramètres (seule
  l'édition des entrées déjà présentes dans le catalogue est possible pour
  l'instant) : à construire si vous ajoutez des avatars au-delà des 76
  actuels.
