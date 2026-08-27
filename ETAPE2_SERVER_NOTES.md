# Mise en place de geco-server (préparation étape 2 → étape 3)

## Ce qui a été fait

Le projet est passé d'un module unique à **3 modules Maven** :

- **`geco-engine`** : le moteur métier pur (`Game`, `Player`, `Event`,
  `EventTypeConverter`, persistance JPA/H2). Zéro dépendance UI. C'est strictement le
  même code qu'à l'étape 1, juste déplacé dans son propre module.
- **`geco-app`** : l'interface Swing historique + CLI, inchangée, dépend de
  `geco-engine`. C'est l'équivalent exact du livrable de l'étape 1.
- **`geco-server`** *(nouveau)* : serveur web local embarqué avec **Javalin**, qui
  expose `geco-engine` via une **API REST** + un canal **WebSocket**, et sert un
  premier front **HTML5/CSS3/JS** moderne et responsive.

## Contenu de geco-server

- `GecoServer.java` : point d'entrée. Démarre Javalin sur le port 7000, sert les
  fichiers statiques (`src/main/resources/public`), enregistre les routes REST et le
  WebSocket.
- `GameService.java` : couche de service qui encapsule les opérations `EntityManager`
  (créer une partie, ajouter un joueur, enregistrer un événement). C'est l'équivalent,
  côté web, de ce que fait `HelperUI` directement avec Swing.
- `Dtos.java` : objets de transfert JSON (on ne sérialise jamais les entités JPA
  directement, pour éviter les soucis de lazy-loading et les références cycliques
  Game ↔ Player ↔ Event).
- `public/index.html` + `public/css/style.css` + `public/js/app.js` : premier front,
  volontairement en JS natif (aucun outil de build requis pour lancer l'app, dans le
  même esprit de simplicité que le code Java d'origine). Thème sombre, responsive,
  liste des parties en cartes, détail d'une partie (joueurs/événements), et connexion
  WebSocket qui rafraîchit la vue en temps réel dès qu'un autre client modifie l'état
  du jeu.

## Routes REST disponibles

| Méthode | Route                         | Usage                                   |
|---------|--------------------------------|------------------------------------------|
| GET     | `/api/games`                   | Liste des parties                        |
| POST    | `/api/games`                   | Créer une partie                         |
| GET     | `/api/games/{id}`               | Détail d'une partie (joueurs + événements) |
| POST    | `/api/games/{id}/players`       | Ajouter un joueur                        |
| POST    | `/api/games/{id}/events`        | Enregistrer un événement (crédit, remboursement, mort, nouveau tour...) |
| WS      | `/ws`                           | Diffusion temps réel des changements     |

## Pourquoi ce découpage prépare directement l'étape 3

- Le canal WebSocket (`/ws`) diffuse déjà les mises à jour à **tous les clients
  connectés**. Pour l'étape 3, il suffira que chaque smartphone se connecte au même
  canal : le mécanisme de synchronisation temps réel entre plusieurs participants
  n'est pas à réinventer.
- Les DTOs et routes REST sont conçus indépendamment du nombre de clients : que ce
  soit le navigateur de l'animateur (étape 2) ou 15 smartphones de joueurs (étape 3),
  l'API ne change pas de nature — on ajoutera surtout des routes d'inscription joueur
  via QR code et de gestion de session multi-clients.
- `geco-engine` reste totalement indépendant de la couche de présentation : aucune
  réécriture de la logique TRM/monnaie dette n'est nécessaire pour la suite.

## ⚠️ Non vérifié dans cet environnement

Contrairement à `geco-engine`/`geco-app` (recompilés avec succès localement via des
stubs), **`geco-server` n'a pas pu être compilé ici** : Javalin, Jackson et leurs
dépendances ne sont disponibles ni via Maven Central (bloqué) ni via les registres
accessibles (npm/PyPI/GitHub releases, sans résultat utilisable dans le temps
imparti). Le code a été écrit et relu avec soin en s'appuyant sur l'API Javalin 6.x
documentée, mais merci de :

1. Lancer `mvn clean package` à la racine et me remonter toute erreur de compilation.
2. Lancer `java -jar geco-server/target/geco-server.jar` et vérifier que
   `http://localhost:7000` affiche bien la liste des parties.
3. Tester la création d'une partie, l'ajout d'un joueur, et l'ouverture de la même
   page dans deux onglets pour vérifier la synchronisation WebSocket en temps réel.

---

**Mise à jour** : une documentation complète a depuis été ajoutée dans le dossier
`docs/` (règles du jeu, guide d'installation, architecture technique détaillée avec
captures d'écran). Ce fichier reste conservé comme note de suivi ponctuelle de la
mise en place initiale de geco-server ; se référer à `docs/03-architecture-technique.md`
pour la version consolidée et à jour.
