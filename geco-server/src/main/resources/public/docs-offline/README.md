# Archive locale — PDF consultables hors connexion

Ce dossier est servi tel quel par `geco-server` (accessible depuis l'app à
`/docs-offline/...`), pour que l'animateur puisse consulter certains documents
de référence **même sans connexion internet** pendant une partie (cas typique :
PC en partage de connexion Wifi local, sans accès internet).

## Théorie Relative de la Monnaie (PDF)

## Comment l'ajouter

1. Téléchargez le PDF depuis :
   https://www.trm.creationmonetaire.info/TheorieRelativedelaMonnaie.pdf
   (ce site bloque le téléchargement automatisé, un téléchargement manuel depuis
   votre navigateur est nécessaire).
2. Déposez le fichier ici, **sous ce nom exact** :
   ```
   geco-server/src/main/resources/public/docs-offline/TheorieRelativedelaMonnaie.pdf
   ```
3. Recompilez (`./run.sh --rebuild`) : le fichier sera alors embarqué dans le jar
   et servi par l'application.

La page "Documentation" du logiciel détecte automatiquement la présence de ce
fichier : un bouton "Ouvrir le PDF (archive locale)" apparaît s'il est présent,
sinon un simple lien vers la version en ligne s'affiche à la place.

## DREES (2025) — Personnes pauvres et modestes en Europe

Fichier `DREES_2025_pauvres_et_modestes_en_Europe.pdf` : étude qui a servi à
préciser les définitions du "seuil de pauvreté" et du "seuil de condition
modeste" utilisées par l'application (voir `docs/fr/markdown/statistiques.md`).
Fourni par l'utilisateur, déjà présent dans ce dossier - contrairement au PDF
de la TRM, aucune étape manuelle n'est nécessaire pour celui-ci.
