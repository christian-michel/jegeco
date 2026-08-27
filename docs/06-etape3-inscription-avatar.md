# Étape 3 — Inscription et avatar (Phase B)

## Ce qui a été fait

**Backend (`geco-engine`)** : `Player.java` reçoit trois nouveaux champs,
volontairement optionnels (n'affectent pas les joueurs ajoutés par l'animateur) :
- `avatarConfigJson` : petit objet JSON (couleur de peau, style/couleur de
  cheveux, accessoire) plutôt que des colonnes dédiées par attribut — le jeu
  d'options d'avatar va probablement évoluer, une colonne JSON évite une
  migration de schéma à chaque ajustement.
- `favoriteColor` : couleur d'identité choisie par le joueur (code hex).
- `declaredAge` : âge "de personnage" choisi à l'inscription — **à ne pas
  confondre** avec l'âge en tours de jeu déjà calculé ailleurs
  (`Dtos.GameDetailDto`, nombre de tours depuis la dernière naissance).

**API (`geco-server`)** : nouvelle route `POST /api/games/{id}/join`, distincte de
`POST /api/games/{id}/players` (utilisée par l'animateur) : vérifie que le prénom
n'est pas déjà pris dans la partie (deux téléphones pourraient saisir le même
prénom sans le savoir, contrairement à l'animateur qui voit la liste à l'écran),
puis crée le joueur avec ses informations.

**Front mobile** (`join.html` + `css/player.css` + `js/player.js`) : page **séparée**
du dashboard animateur (`index.html`), pensée mobile-first, reprenant le parcours
de la maquette de référence :
1. Identité (prénom, âge, couleur) ;
2. Avatar : peau / cheveux (style + couleur) / accessoire, avec **aperçu en temps
   réel** ;
3. Confirmation d'inscription.

**Avatar généré en SVG, sans illustration externe.** Plutôt que d'attendre les
~350 illustrations du système de cartes (Phase C, en cours de production séparée),
l'avatar est composé de formes SVG simples (cercle de peau, formes de cheveux,
accessoires) générées entièrement côté client. Avantage : aucune dépendance à la
production d'images, personnalisation immédiatement fonctionnelle. Limite connue :
rendu stylisé simple plutôt que les illustrations réalistes de la maquette — à
enrichir plus tard si souhaité, sans remettre en cause l'architecture (le JSON de
configuration resterait le même, seul le rendu graphique changerait).

**Intégration dans le dashboard animateur** : nouveau bouton "📱 Inviter" dans le
panneau Joueurs, qui affiche un QR code pointant vers
`join.html?gameId={id_de_la_partie}` — réutilise la détection d'adresses réseau de
la Phase A, mais cette fois avec le contexte d'une partie précise (contrairement à
l'écran général "Connexion joueurs" de la Phase A, qui ne pointait que vers la
page d'accueil).

## Vérifications effectuées

- Compilation complète des 3 modules (`geco-engine`, `geco-server`, `geco-app`)
  validée, y compris la non-régression de l'app Swing après l'ajout des nouveaux
  champs `Player`.
- Syntaxe JS de `player.js` validée (`node -c`).

## Point de vigilance non vérifié visuellement

Comme pour Chart.js précédemment, je n'ai pas pu produire de capture d'écran
fiable de cet écran mobile : mon outil de capture (moteur JavaScript ancien) bute
sur une déclaration de fonction imbriquée pourtant parfaitement valide (revérifié
avec `node -c`, qui la valide sans erreur). Ce n'est pas un problème dans un vrai
navigateur mobile (Safari iOS, Chrome Android), mais je n'ai donc pas de capture à
vous montrer cette fois. **Merci de tester réellement sur un téléphone** une fois
`geco-server` lancé chez vous : ouvrez `http://<ip-de-votre-pc>:7000/join.html?gameId=1`
(en remplaçant `1` par l'ID d'une vraie partie) depuis le navigateur de votre
téléphone, ou scannez le QR du bouton "📱 Inviter" du dashboard.

## Ce qui n'est pas encore fait

- Le flux se termine sur un simple écran de confirmation ("Attendez que
  l'animateur démarre la partie"). L'écran suivant du parcours joueur (inventaire
  de cartes, échanges) est la Phase C, qui dépend aussi du système de cartes en
  cours de conception séparément (voir `04-etape3-catalogue-cartes.md`).
- Pas de mécanisme de reconnexion si le joueur ferme son navigateur : l'ID joueur
  est stocké dans le `localStorage` du téléphone (`geco_player_<gameId>`), mais
  rien ne l'utilise encore pour permettre de "revenir" dans la partie après une
  fermeture accidentelle de l'onglet.
- Le dashboard animateur n'affiche pas encore les couleurs/avatars des joueurs
  inscrits via ce nouveau flux (juste leur nom, comme avant) — amélioration
  visuelle possible mais non bloquante.
