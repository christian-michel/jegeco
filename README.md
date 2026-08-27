# jegeco

📍 **Nouveau sur ce projet ? Commencez par `docs/00-vue-ensemble.md`** — structure
du dépôt, comment lancer l'app, où trouver quoi.

Outil d'aide à l'animation des parties de **Ğeconomicus**, le jeu de société qui fait
vivre à ses joueurs, en quelques dizaines de minutes, l'équivalent d'une vie
économique entière — une fois en monnaie dette, une fois en monnaie libre — afin
d'en comparer concrètement les effets.

📖 **Règles officielles du jeu** : https://geconomicus.glibre.org/rules.html
📖 **Théorie Relative de la Monnaie (TRM)** : https://trm.creationmonetaire.info/

## Fonctionnalités

- **Trois systèmes d'échange jouables** : monnaie dette, monnaie libre (avec un
  mode "strict TRM" optionnel), et troc — via une architecture à plugins,
  conçue pour en accueillir d'autres (une monnaie digitale de banque centrale
  est envisagée pour l'étape 3).
- **Assistant d'entre-deux-tours** qui guide l'animateur pas à pas (gestion des
  crédits, morts/renaissances, distribution du DU, contrôles de cohérence en
  temps réel).
- **Statistiques complètes** : histogrammes de richesse, historique de la masse
  monétaire, comparaison entre parties, export JSON.
- **Multilingue** (français/anglais intégrés), avec import/export de langues
  personnalisées depuis l'interface.
- **Sauvegarde et export** : sauvegarde complète de la base, export d'une
  partie individuelle.
- **Protection optionnelle par code** : code PIN par partie pour l'animateur,
  jeton d'accès individuel par joueur — désactivée par défaut.
- **Suite de tests automatisés** sur la logique métier des trois systèmes.

## Origine de ce projet

Ce projet **reprend le code de jytou dans l'état où il se trouvait**, et le fait
évoluer depuis :

🔗 **Projet original : https://gitlab.com/jytou/geconomicus_helper**

La base — moteur de jeu, calculs de la monnaie dette et de la monnaie libre, gestion
des tours, des morts/renaissances — vient de ce projet original. Depuis, l'étape 2
a fait évoluer une partie de cette logique (voir ci-dessous : ajout d'un troisième
système d'échange — le troc —, corrections de plusieurs formules après vérification
avec l'auteur du projet actuel, nouveau mode "strict TRM" en monnaie libre), en plus
de la modernisation technique et de l'interface détaillée plus bas.

⚠️ **Licence** : à vérifier sur le dépôt d'origine avant publication — je n'ai pas pu
confirmer avec certitude la licence exacte utilisée par jytou pour ce projet.

## Démarrage rapide

**Le plus simple** : lancez le script correspondant à votre système, à la racine du
projet — il installe automatiquement ce qui manque (Java, Maven), compile, puis
lance le jeu, sans commande à taper :

- Linux → double-clic sur `run.sh` (ou clic droit → « Exécuter dans un terminal »)
- macOS → double-clic sur `run.command`
- Windows → double-clic sur `run.bat`

Ou, si vous préférez la ligne de commande :

```bash
mvn clean package
java -jar geco-app/target/gecohelper.jar
```

👉 Voir **[docs/02-installation.md](docs/02-installation.md)** pour le guide complet
(prérequis, détail des scripts, dépannage).

## Documentation

| Document | Contenu |
|---|---|
| **[docs/01-le-jeu-et-ses-regles.md](docs/01-le-jeu-et-ses-regles.md)** | Comprendre le jeu, ses règles, et les écrans de l'application (avec captures) |
| **[docs/02-installation.md](docs/02-installation.md)** | Installer et lancer le logiciel, étape par étape |
| **[docs/03-architecture-technique.md](docs/03-architecture-technique.md)** | Les choix techniques de cette mise à jour, pour les contributeurs |
| **[docs/10-etape-plugins-troc.md](docs/10-etape-plugins-troc.md)** | Le troc comme premier plugin tiers : conception et règles |
| **[docs/11-plugin-api-contrat.md](docs/11-plugin-api-contrat.md)** | Le contrat d'API que doit respecter un plugin de système d'échange |
| **[docs/12-guide-creer-systeme-echange.md](docs/12-guide-creer-systeme-echange.md)** | Guide pas à pas pour créer un nouveau système d'échange |
| **[CAHIER_DES_CHARGES_ETAPE3.md](CAHIER_DES_CHARGES_ETAPE3.md)** | Périmètre et décisions de conception pour l'étape 3 (jeu sur smartphone) |

## Organisation du code

```
geco-parent/            (pom.xml racine, multi-module)
├── run.sh / run.command / run.bat   Scripts de lancement automatisé (voir ci-dessus)
├── geco-engine/         Moteur métier pur (Game/Player/Event, persistance JPA/H2)
├── geco-app/             Interface Swing historique + CLI
├── geco-server/          Serveur web (API REST + WebSocket) et nouveau front HTML/CSS/JS
└── docs/                Documentation (ce que vous lisez actuellement)
```

## État d'avancement

- ✅ **Étape 1** — Migration technique vers Java 21 / Jakarta EE.
- ✅ **Étape 2** — Interface web moderne, trois systèmes d'échange jouables
  (dette, libre, troc), assistant complet, statistiques, i18n, sauvegarde/
  export, protection par code optionnelle, tests automatisés.
- 🚧 **Étape 3** (planification en cours) — jeu sur smartphone en complément du
  mode classique (cartes/jetons physiques), au choix de l'animateur : cartes
  numériques identifiables, profils et avatars persistants, transactions
  individuelles entre joueurs, statistiques plus fines (module Galilée).

Voir **[CAHIER_DES_CHARGES_ETAPE3.md](CAHIER_DES_CHARGES_ETAPE3.md)** pour le
détail complet de cette feuille de route.
