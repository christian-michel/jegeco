# Étape 2 — Internationalisation (i18n)

## Ce qui est fait (preuve de concept)

- **Mécanisme complet et testé** : `js/i18n.js` (autonome, même esprit que
  `tutorial.js`), avec un lecteur de fichiers `.po` écrit en JavaScript pur (pas
  d'étape de build). Testé avec des cas réels : guillemets échappés, chaînes
  multi-lignes, en-tête ignorée, et les deux vrais fichiers du projet
  (`lang/fr.po`, `lang/en.po`, 14 clés chacun) — validation faite via `node`,
  hors du navigateur.
- **Détection automatique** : préférence sauvegardée > langue du navigateur
  (`navigator.language`) > français par défaut.
- **Sélecteur de drapeaux** en haut à droite de l'écran (comme demandé), injecté
  par le module lui-même — aucune modification du reste du HTML nécessaire pour
  l'ajouter.
- **Couverture actuelle** : la barre latérale de navigation (tous les libellés,
  écran "hors partie" et "en partie") + l'écran "Connexion joueurs" en entier,
  en français et anglais.
- **Correction incluse** : le lien vers `docs/05-etape3-connectivite.md` dans
  l'écran "Connexion joueurs" (inaccessible depuis le navigateur, repéré par
  l'utilisateur) a été remplacé par un vrai lien fonctionnel vers la page
  Documentation intégrée à l'application.

## Comment ça marche, en bref

1. Un élément à traduire porte `data-i18n="cle"` (remplace son texte) ou
   `data-i18n-html="cle"` (remplace son HTML interne, pour les textes contenant
   une balise comme un lien — pratique standard en i18n, le traducteur conserve
   la balise sans y toucher).
2. `lang/fr.po` sert de **référence canonique** : toutes les clés existantes, en
   français. `lang/en.po` en est la traduction. Une clé absente d'un fichier de
   langue laisse simplement le texte français d'origine (jamais d'écran vide).
3. Le sélecteur de drapeaux appelle `setLang(code)`, qui recharge le fichier
   `.po` correspondant et ré-applique toutes les traductions sans recharger la
   page.

## Ce qui reste à faire pour une couverture complète

C'est un chantier volontairement non traité d'un coup, vu son ampleur :

1. **Étendre la couverture à tout le reste du web** : écran "Nouvelle partie",
   tableau de bord de partie, assistant de fin de tour, rapport de fin de
   partie, page Documentation, et l'application mobile (`join.html`/`player.js`,
   pas encore touchée du tout). Mécaniquement répétitif (ajouter des
   `data-i18n`, compléter les `.po`) mais représente un nombre de chaînes de
   texte important.
2. **4 langues supplémentaires** (espagnol, italien, portugais, allemand) : je
   peux fournir un premier jet de traduction, mais je recommande une relecture
   par un locuteur natif avant publication, en particulier pour le vocabulaire
   spécifique au jeu (monnaie libre, dividende universel, carré...).
3. **Côté `geco-app` (Swing)** : le mécanisme `ResourceBundle`/`.properties`
   existe déjà (français + anglais). L'étendre aux 4 langues supplémentaires
   suit la même logique que pour le web, mais avec le format Java natif plutôt
   que `.po` (pas de raison de dupliquer un mécanisme qui fonctionne déjà bien
   pour l'app Swing).
4. **Application mobile** (`join.html`) : le même module `i18n.js` peut y être
   réutilisé tel quel (il est autonome), il suffit de l'y charger et d'ajouter
   les `data-i18n` sur les textes de l'écran d'inscription/avatar.

## Mises à jour automatiques (sujet lié, en attente d'un point à trancher)

Le mécanisme de vérification de version (comparer la version installée à la
dernière disponible, proposer une mise à jour) est réalisable, mais nécessite un
**point de publication** des versions du logiciel (par exemple des releases
taguées sur un dépôt GitLab ou GitHub) pour avoir quelque chose à vérifier. Ce
point n'existe pas encore dans le projet — **question à trancher avec
l'utilisateur avant de construire ce mécanisme** : où seront publiées les
futures versions ? Une fois cette réponse connue, la vérification (et
éventuellement l'application automatique, avec un réglage pour l'activer ou
non) pourra être construite dans le même esprit que le reste du projet :
mécanisme testé, avec un repli sûr si la vérification échoue (pas de blocage du
lancement du jeu en cas d'absence de connexion internet, par exemple).
