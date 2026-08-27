# Étape 2 — Aide contextuelle : écarts avec le manuel original

Ce document consigne, pour référence future, les fonctionnalités décrites dans
le manuel de la version originale (Swing, jytou —
https://gitlab.com/jytou/geconomicus_helper) qui **n'existent pas encore** dans
la version web actuelle. Volontairement **non exposé aux utilisateurs** : le
but est d'éviter de documenter dans l'aide en jeu des boutons ou écrans qui ne
sont pas réellement là, ce qui serait trompeur pour l'animateur en pleine
partie.

## Méthode suivie

Avant d'écrire le contenu d'aide, le code réel de l'application web a été
inspecté (formulaire de création de partie, types d'événements disponibles,
affichage de la liste des joueurs, présence ou non d'un système d'annulation)
pour s'assurer que chaque section d'aide décrit une fonctionnalité qui existe
vraiment, plutôt que de traduire le manuel original tel quel.

## Ce qui existe déjà (bonne surprise à la vérification)

- La plupart des types d'événements du manuel original sont bien disponibles
  via le bouton "+ Événement" : crédit, remboursement (intérêt seul ou
  capital+intérêt), défaut de paiement (faillite/prison), investissement de la
  banque, changement de masse monétaire, rupture technologique, fin de partie.
- Le mécanisme de mort/renaissance est **manuel**, comme dans l'original ("il
  reste de la responsabilité de l'animateur de provoquer ces morts
  lui-même") — pas d'écart ici, contrairement à ce qu'on aurait pu craindre.

## Ce qui manque encore (non documenté dans l'aide en jeu)

- **Raccourcis clavier à une touche** ([j], [c], [t], [r], [d], [z]...) :
  aucun raccourci de ce type n'existe dans la version web aujourd'hui.
- **Liste des joueurs enrichie** : pas de code couleur par statut (rouge/gris/
  vert), pas de chaîne de caractères codée résumant l'historique d'un joueur
  (M, +3, D, F, P, R...), pas de suggestion automatique de qui doit mourir à
  un tour donné (calculée dans l'original pour garantir que chaque joueur
  connaisse une renaissance avant la fin de la partie).
- **Annulation (undo)** de la dernière action, **suppression** ou **édition**
  d'un événement déjà enregistré (date, valeurs) : aucune de ces trois
  fonctionnalités n'existe dans la version web.
- **Suppression ou renommage d'un joueur** après sa création.
- **Écran "valeurs en cours" pour vidéoprojecteur** (monnaie libre, rotation
  des couleurs faible/moyenne/forte) et **fenêtre compte à rebours** séparée
  pour écran externe : notre minuteur existe mais reste intégré au tableau de
  bord, pas de fenêtre dédiée plein écran pour un second moniteur.
- **Anti-triche** (saisie du nombre de pièces présentées par chaque joueur à
  chaque tour) et **calculateur pour jetons de valeur 3** (aide au rendu de
  monnaie) : non implémentés.
- **Utilisation à deux ordinateurs** (animateur + banquier séparés, avec
  export/import XML et fusion par horodatage) : non pertinent avec
  l'architecture actuelle (un seul serveur web partagé, accessible par
  n'importe quel appareil du réseau) — cette limitation du logiciel original
  n'existe donc plus, mais le mécanisme de sauvegarde/fusion à deux lui-même
  n'a pas d'équivalent construit.
- **Dialogue de comparaison de deux parties** (onglets dette sans banque/avec
  banque/masse monétaire, monnaie libre, agrégat, statistiques "corrigées"
  soustrayant les cartes de départ et le DU) : notre rapport de fin de partie
  couvre une seule partie à la fois, pas de comparaison côte-à-côte entre deux
  parties.
- **Recalcul des événements** (menu Partie/Recalcul) et **ajustement manuel de
  la masse monétaire** via une interface dédiée (le type d'événement existe
  côté données, mais pas mis en avant comme une action distincte dans
  l'interface).
- **Bouton "Mise à jour ?"** sur l'écran d'accueil : lié au chantier des mises
  à jour automatiques, pas encore construit (voir échange précédent sur ce
  sujet, en attente d'un point de publication des versions).
- **Sauvegarde/export XML** d'une partie : la sauvegarde repose aujourd'hui
  uniquement sur le fichier de base de données (`~/geco.h2`), pas d'export
  par partie individuelle.

## Aide contextuelle ajoutée dans cette itération

Contenu enrichi dans `js/tutorial.js` (parcours `game` et nouveau parcours
`report`), adapté du manuel original mais limité à ce qui existe réellement :
panneau joueurs, historique des événements, bouton "+ Événement" (avec le
détail des types disponibles), assistant de fin de tour, et l'ensemble des
éléments du rapport de fin de partie (indicateurs, histogramme, masse
monétaire, activité par joueur, graphique de convergence). Le bouton "?" est
maintenant visible sur les écrans Nouvelle partie, Connexion joueurs, Partie en
cours, et Rapport de fin de partie.
