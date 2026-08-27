# Vue d'ensemble — Geconomicus Helper

Ce document est le point d'entrée pour toute personne qui découvre le projet
(vous-même dans 6 mois, un·e autre développeur·euse, une IA à qui vous
délégueriez une tâche). Il répond à "comment est-ce que ça marche aujourd'hui
et où est-ce que je dois regarder", **pas** à "qu'est-ce qui a changé et
pourquoi" — pour ça, voir `docs/03-architecture-technique.md`, qui est un
journal chronologique de toutes les décisions techniques prises au fil du
développement (utile pour comprendre le RAISONNEMENT derrière un choix, mais
long à lire d'une traite).

## En une phrase

Refonte Java 21 + interface web (Javalin) d'un jeu pédagogique comparant
monnaie dette et monnaie libre (théorie relative de la monnaie), à partir du
projet original de jytou (https://gitlab.com/jytou/geconomicus_helper).

## Structure du dépôt

```
geco-multimodule/
├── geco-engine/    module Maven : le moteur du jeu (Game/Player/Event, JPA/H2)
│                   -> logique métier PURE, partagée par le web ET par l'app Swing
│                   -> c'est ici qu'il faut chercher/modifier les RÈGLES DU JEU
├── geco-app/       module Maven : l'ancienne interface Swing (conservée, "--classic")
├── geco-server/    module Maven : le serveur web (Javalin) + le front (public/)
│   └── src/main/resources/public/
│       ├── index.html   écran animateur (le plus gros fichier)
│       ├── js/app.js     toute la logique côté client (voir son en-tête pour
│       │                 une carte des sections - c'est LE fichier à connaître)
│       ├── js/i18n.js    système de traduction (.po), autonome
│       ├── js/tutorial.js  bouton d'aide "?", autonome
│       ├── js/vendor/    bibliothèques tierces hébergées localement (pas de
│       │                 CDN externe - fonctionne sans connexion internet)
│       └── docs/         documentation utilisateur multilingue (distincte du
│                         dossier docs/ à la racine, voir docs/README.md)
├── docs/           documentation DE TRAVAIL (specs, historique, notes) -
│                   jamais servie aux utilisateurs finaux
├── run.sh / .bat / .command   scripts de lancement (web par défaut, --classic
│                              pour Swing, --rebuild pour forcer la recompilation)
└── pom.xml         build Maven racine
```

## Comment lancer le projet

```bash
./run.sh              # interface web, port 7000 par défaut
./run.sh --classic    # ancienne interface Swing
./run.sh --rebuild     # force une recompilation complète avant de lancer
```

Base de données : H2 fichier unique dans le dossier personnel de l'utilisateur
(`~/geco.h2`), partagée entre web et Swing - pas de serveur de base de données
séparé à installer.

## Où trouver quoi (questions fréquentes)

- **"Comment sont enregistrés les événements de jeu (crédit, remboursement,
  mort...) ?"** → `geco-engine/.../Event.java`, méthode `applyEvent()` - un
  gros `switch` sur le type d'événement, une seule source de vérité pour le
  web ET le Swing.
- **"Comment ajouter un nouveau type d'action animateur ?"** → 3 endroits :
  `Event.java` (si un nouveau `EventType` est nécessaire, rare), `GameService.
  java` (méthode `recordEvent` ou une méthode dédiée si la logique est plus
  riche - voir `deletePlayer`/`renamePlayer` comme exemples), puis `GecoServer.
  java` (une nouvelle route qui appelle le service), enfin `app.js` (le
  formulaire/bouton qui appelle la route).
- **"Comment fonctionne l'assistant de fin de tour ?"** → `app.js`, fonction
  `openEndOfTurnWizard()` - plusieurs étapes séquentielles (`renderStep0`,
  `renderStep1`...), qui se branchent différemment selon le système monétaire
  (dette/libre) et selon qu'on est au dernier tour de la partie ou non.
- **"Comment fonctionne le système de traduction ?"** → `js/i18n.js` (module
  autonome, lecteur de fichiers `.po`), voir `docs/08-etape2-i18n.md` pour le
  détail et le périmètre couvert à ce jour.
- **"Où sont les règles du jeu (pour un humain, pas pour le code) ?"** →
  `docs/01-le-jeu-et-ses-regles.md` (règles générales) et la documentation
  utilisateur intégrée à l'app (`public/docs/`, distincte de ce dossier-ci).

## Philosophie du projet (pourquoi certains choix peuvent surprendre)

- **Aucun outil de build côté web** (pas de npm/webpack/etc.) : un seul
  `app.js` en JavaScript natif, des bibliothèques tierces (Chart.js, QRCode.js)
  téléchargées une fois et versionnées dans `js/vendor/` plutôt que chargées
  depuis un CDN - l'app doit fonctionner même sans connexion internet et sans
  étape de compilation.
- **Le moteur (`geco-engine`) ne change jamais de comportement entre web et
  Swing** : toute correction de bug ou nouvelle règle de jeu s'y fait une
  seule fois, profite aux deux interfaces.
- **Beaucoup de mécaniques ont été retrouvées en lisant le code source
  original** (algorithme de suggestion des morts, comportement de la banque
  pour l'investissement/bilan final...) plutôt que réinventées - en cas de
  doute sur "comment ça devrait marcher", le dépôt original
  (https://gitlab.com/jytou/geconomicus_helper) reste la référence.

## Pour aller plus loin

- `docs/03-architecture-technique.md` : historique détaillé de toutes les
  décisions techniques, dans l'ordre chronologique.
- `docs/02-installation.md` : instructions d'installation détaillées.
- `docs/09-etape2-aide-contextuelle.md` : ce qui manque encore par rapport au
  manuel du programme original (fonctionnalités non portées à ce jour).
