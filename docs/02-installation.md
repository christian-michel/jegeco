# Installation et lancement

Ce guide vous permet d'installer et de lancer le logiciel, que vous soyez
développeur ou simplement animateur souhaitant utiliser l'outil pour une partie.

Il y a **deux interfaces disponibles** :

- **`geco-server`** : la nouvelle interface web (navigateur), design refondu selon
  la maquette du projet. **C'est l'interface lancée par défaut** par les scripts
  ci-dessous, et celle activement développée.
- **`geco-app`** : l'interface bureau (Swing) historique, identique dans son
  fonctionnement à l'outil original de jytou. Toujours disponible via l'option
  `--classic` (voir plus bas), pour ceux qui la préfèrent ou veulent comparer.

Il n'existe pas encore d'installeur "tout-en-un" en un clic (c'est justement l'objet
de l'étape 3 du projet, avec Docker — voir `03-architecture-technique.md`). En
attendant, un **script de lancement automatisé** est fourni à la racine du projet :
il détecte et installe Java/Maven si nécessaire, compile le projet si besoin, ouvre
automatiquement votre navigateur, et lance le jeu — **sans qu'aucune commande ne
soit à taper**.

## ⚠️ Si vous ne voyez que l'ancienne interface Swing

Si en lançant le script vous voyez une fenêtre Java classique (menus "Partie /
Joueur / Vue / Aide") plutôt qu'une page web, c'est que vous avez utilisé l'option
`--classic`, ou une version du script antérieure à cette mise à jour (le
comportement par défaut a changé : **c'est désormais l'interface web qui se lance
en premier**, plus l'ancienne interface Swing). Relancez simplement `./run.sh` (ou
`run.bat` / `run.command`) **sans option** pour obtenir la nouvelle interface, votre
navigateur s'ouvrira automatiquement sur `http://localhost:7000`.

`geco-app` contient par ailleurs **deux points d'entrée possibles** en interne :
`HelperUI` (l'interface graphique Swing, celle décrite ci-dessus) et
`GeconomicusHelper`, un outil en ligne de commande non-interactif (une commande =
une action) qui sert à scripter des opérations ponctuelles, **pas à animer une
partie**. Si vous utilisez un script personnalisé qui propose un choix entre
plusieurs modes, assurez-vous de sélectionner `HelperUI` et non `GeconomicusHelper`.

## Méthode recommandée : le script de lancement automatisé

À la racine du projet se trouvent trois scripts, un par système d'exploitation :

| Fichier | Système | Comment le lancer |
|---|---|---|
| `run.sh` | Linux | Double-clic (si votre gestionnaire de fichiers l'autorise) ou clic droit → « Exécuter dans un terminal » |
| `run.command` | macOS | Double-clic depuis le Finder |
| `run.bat` | Windows | Double-clic depuis l'Explorateur |

Ce script :
1. vérifie si Java 21 est installé, et l'installe automatiquement sinon (via le
   gestionnaire de paquets de votre système : `apt`/`dnf`/`pacman` sur Linux,
   Homebrew sur macOS, `winget` sur Windows) ;
2. fait de même pour Maven ;
3. compile le jeu s'il ne l'est pas déjà (uniquement au premier lancement, ou après
   une mise à jour du code) ;
4. **lance l'interface web et ouvre automatiquement votre navigateur** sur
   `http://localhost:7000`.

**Pour utiliser l'ancienne interface Swing à la place**, ajoutez l'option
`--classic` : `./run.sh --classic`, `run.bat --classic`, ou `./run.command --classic`.

**Premier lancement sur Linux** : si le double-clic ouvre le script dans un éditeur
de texte au lieu de l'exécuter, faites un clic droit sur `run.sh` → « Exécuter dans
un terminal » (ou « Run in Terminal »), ou ouvrez un terminal et tapez `./run.sh`
une seule fois.

**Premier lancement sur macOS** : macOS peut afficher un avertissement « développeur
non identifié ». Faites alors un clic droit sur `run.command` puis « Ouvrir » (au
lieu d'un double-clic classique) pour autoriser l'exécution — ce n'est nécessaire
qu'une seule fois.

**Sur Windows**, si Java ou Maven viennent d'être installés automatiquement, le
script vous demandera de le relancer une fois (le temps que Windows prenne en
compte la mise à jour) : double-cliquez simplement à nouveau sur `run.bat`.

**Pour relancer le jeu après une mise à jour du code**, utilisez l'option
`--rebuild` (dans un terminal : `./run.sh --rebuild`, `run.bat --rebuild`, ou
`./run.command --rebuild`) pour forcer une recompilation. Les options se combinent :
`./run.sh --classic --rebuild` recompile et relance l'interface Swing.

Si le script échoue pour une raison quelconque (pas de connexion internet, droits
administrateur refusés, gestionnaire de paquets non reconnu...), la méthode manuelle
détaillée ci-dessous fonctionne toujours.

## Méthode manuelle, clic par clic (si le script automatisé ne convient pas)

Choisissez votre système d'exploitation ci-dessous.

### 🐧 Linux

1. **Ouvrir un terminal.** Sur la plupart des distributions : clic droit sur le
   bureau ou dans un dossier vide, puis « Ouvrir un terminal ici ». Sinon, cherchez
   « Terminal » dans le menu des applications, ou essayez le raccourci `Ctrl+Alt+T`
   (fonctionne sur beaucoup de distributions, dont Ubuntu et Mint).

2. **Vérifier / installer Java 21.** Tapez `java -version` et Entrée. Si vous voyez
   « 21 » quelque part, c'est bon. Sinon, installez-le selon votre distribution :
   - Ubuntu / Debian : `sudo apt install openjdk-21-jdk`
   - Fedora : `sudo dnf install java-21-openjdk`
   - Arch : `sudo pacman -S jdk21-openjdk`

   Le système vous demandera votre mot de passe (rien ne s'affiche pendant la
   saisie, c'est normal).

3. **Vérifier / installer Maven.** Tapez `mvn -version`. Si absent :
   - Ubuntu / Debian : `sudo apt install maven`
   - Fedora : `sudo dnf install maven`
   - Arch : `sudo pacman -S maven`

4. **Récupérer le projet.** Si vous avez le fichier `.zip` du projet : clic droit
   dessus dans votre gestionnaire de fichiers puis « Extraire ici » (« Extract
   Here »). Dans le terminal, déplacez-vous ensuite dans ce dossier avec `cd` suivi
   du chemin, par exemple :
   ```bash
   cd ~/Téléchargements/geco-multimodule
   ```
   Astuce : tapez `cd `, laissez un espace, puis glissez-déposez le dossier extrait
   dans la fenêtre du terminal — le chemin se remplit tout seul.

5. **Compiler le projet (une seule fois).** Toujours dans le terminal, à
   l'intérieur du dossier du projet :
   ```bash
   mvn clean package
   ```
   La première fois, ça télécharge des fichiers nécessaires (connexion internet
   requise) et ça peut prendre quelques minutes. À la fin, vous devez voir écrit
   « BUILD SUCCESS ».

6. **Lancer le jeu.** Toujours dans le même terminal, pour la nouvelle interface web
   (recommandée) :
   ```bash
   java -jar geco-server/target/geco-server.jar
   ```
   puis ouvrez votre navigateur sur `http://localhost:7000`. Pour l'ancienne
   interface Swing à la place :
   ```bash
   java -jar geco-app/target/gecohelper.jar
   ```

Les étapes 2 à 5 ne sont à faire qu'une seule fois (ou après une mise à jour du
code). Pour rejouer plus tard, seule l'étape 6 est nécessaire — voir aussi
« Créer un raccourci de lancement » plus bas pour éviter de repasser par le
terminal à chaque fois.

### 🪟 Windows

1. **Ouvrir un terminal.** Appuyez sur la touche Windows, tapez `PowerShell`, puis
   Entrée.

2. **Vérifier / installer Java 21.** Tapez `java -version` et Entrée. Si absent, le
   plus simple est d'utiliser `winget` (déjà installé sur Windows 10/11) :
   ```powershell
   winget install EclipseAdoptium.Temurin.21.JDK
   ```
   Sinon, téléchargez et lancez l'installeur depuis https://adoptium.net/
   (choisissez la version 21, cliquez sur `.msi`, puis « Suivant » jusqu'au bout).

3. **Vérifier / installer Maven.** Tapez `mvn -version`. Si absent :
   ```powershell
   winget install Apache.Maven
   ```
   Fermez puis rouvrez le terminal après l'installation pour que la commande soit
   reconnue.

4. **Récupérer le projet.** Clic droit sur le fichier `.zip` téléchargé, puis
   « Extraire tout... », et choisissez un dossier de destination. Dans le terminal,
   placez-vous dans ce dossier, par exemple :
   ```powershell
   cd C:\Users\VotreNom\Downloads\geco-multimodule
   ```

5. **Compiler le projet (une seule fois).**
   ```powershell
   mvn clean package
   ```
   Attendez « BUILD SUCCESS » à la fin (la première fois peut prendre quelques
   minutes, connexion internet requise).

6. **Lancer le jeu.** Pour la nouvelle interface web (recommandée) :
   ```powershell
   java -jar geco-server\target\geco-server.jar
   ```
   puis ouvrez votre navigateur sur `http://localhost:7000`. Pour l'ancienne
   interface Swing à la place :
   ```powershell
   java -jar geco-app\target\gecohelper.jar
   ```

### 🍎 macOS

1. **Ouvrir un terminal.** `Cmd + Espace`, tapez `Terminal`, puis Entrée.

2. **Vérifier / installer Java 21.** Tapez `java -version` et Entrée. Si absent, le
   plus simple est d'utiliser [Homebrew](https://brew.sh/) :
   ```bash
   brew install openjdk@21
   ```
   Sinon, téléchargez l'installeur `.dmg` (version 21) depuis
   https://adoptium.net/ et suivez les instructions à l'écran.

3. **Vérifier / installer Maven.** Tapez `mvn -version`. Si absent :
   ```bash
   brew install maven
   ```

4. **Récupérer le projet.** Double-cliquez sur le fichier `.zip` téléchargé (macOS
   l'extrait automatiquement dans le même dossier). Dans le terminal :
   ```bash
   cd ~/Downloads/geco-multimodule
   ```

5. **Compiler le projet (une seule fois).**
   ```bash
   mvn clean package
   ```
   Attendez « BUILD SUCCESS ».

6. **Lancer le jeu.** Pour la nouvelle interface web (recommandée) :
   ```bash
   java -jar geco-server/target/geco-server.jar
   ```
   puis ouvrez votre navigateur sur `http://localhost:7000`. Pour l'ancienne
   interface Swing à la place :
   ```bash
   java -jar geco-app/target/gecohelper.jar
   ```

## Créer un raccourci de lancement (éviter de retaper la commande)

Le plus simple est de créer un raccourci vers `run.sh` (ou `run.bat`/`run.command`)
lui-même plutôt que vers un jar précis : il se charge de tout (compilation si
besoin, ouverture du navigateur) en un double-clic.

**Sur Linux**, créez un fichier `Geconomicus.desktop` sur votre bureau avec ce
contenu (adaptez le chemin `Exec` à l'emplacement réel de votre projet) :

```ini
[Desktop Entry]
Type=Application
Name=Ğeconomicus Helper
Exec=/chemin/vers/geco-multimodule/run.sh
Icon=/chemin/vers/geco-multimodule/geco-app/src/main/resources/geconomicus.png
Terminal=true
```

Rendez-le exécutable (clic droit → Propriétés → « Autoriser l'exécution comme un
programme », ou en terminal : `chmod +x ~/Bureau/Geconomicus.desktop`). Vous pourrez
ensuite le lancer d'un double-clic.

**Sur Windows**, un raccourci vers `run.bat` suffit : clic droit sur `run.bat` →
« Créer un raccourci », puis déplacez ce raccourci sur le Bureau.

**Sur macOS**, un alias vers `run.command` (clic droit → « Créer un alias ») fait
la même chose.

## Prérequis (résumé)

- **Java 21** (LTS, "Long Term Support" — version pérenne)
- **Maven** (pour compiler le projet et télécharger ses dépendances)

## Astuce langue

Pour forcer l'affichage en français ou en anglais :

```bash
# Français
java -Duser.country=FR -Duser.language=fr -jar geco-app/target/gecohelper.jar

# Anglais
java -Duser.country=US -Duser.language=en -jar geco-app/target/gecohelper.jar
```

## Sauvegarde des parties

Toutes les données de la partie sont sauvegardées **automatiquement et en temps
réel** dans une base de données locale (fichier `geco.h2` dans votre dossier
utilisateur) : vous pouvez fermer et rouvrir le logiciel sans perdre vos données.

## Précision sur le port de l'interface web

L'interface web (lancée par défaut par `run.sh`/`run.bat`/`run.command`, ou
manuellement via `java -jar geco-server/target/geco-server.jar`) écoute sur le port
7000 par défaut (`http://localhost:7000`). Pour utiliser un autre port :
```bash
java -jar geco-server/target/geco-server.jar 8080
```

## En cas de problème

- **`Database may be already in use: "~/geco.h2.mv.db"` pendant la compilation**
  (erreur de test `CreateGameTestCase`) : une autre instance de l'application
  (`geco-app` ou `geco-server`) est probablement déjà ouverte quelque part —
  H2 en mode fichier n'autorise qu'une seule connexion à la fois. **Fermez toute
  fenêtre/onglet de terminal où le jeu tournerait encore**, puis relancez
  `./run.sh`. Si vous ne savez plus où elle tourne : sur macOS, ouvrez le
  Moniteur d'activité et cherchez un processus `java` ; sur Linux,
  `ps aux | grep java` puis `kill <PID>` ; sur Windows, l'onglet Détails du
  Gestionnaire des tâches.
  **Corrigé à la racine** : les tests automatiques utilisaient par erreur la même
  base de données que le jeu réel (`~/geco.h2`), ce qui provoquait ce conflit à
  chaque fois qu'une instance tournait déjà — et polluait vos vraies parties
  avec une fausse partie de test à chaque compilation. Depuis la dernière mise à
  jour, les tests utilisent une base isolée, en mémoire, indépendante de vos
  données. Ce cas de figure ne devrait donc plus se reproduire pour cette raison
  précise (une autre instance déjà lancée peut toujours bloquer le **lancement du
  jeu lui-même**, ce qui reste normal et attendu — un seul processus peut ouvrir
  `~/geco.h2` à la fois).

- **`Exception in thread "main" java.awt.AWTError: Assistive Technology not found:
  org.GNOME.Accessibility.AtkWrapper`** : bug connu sur Ubuntu/Debian/Zorin (et
  dérivés). Le paquet OpenJDK de ces distributions active automatiquement le
  chargement d'un pont d'accessibilité GNOME dès que l'« Accès universel » /
  « Assistive Technologies » est activé dans les réglages système — sans que le
  paquet fournissant réellement cette classe (`java-atk-wrapper`) ne soit installé.
  Toute application Swing plante alors immédiatement au démarrage.
  **Corrigé** dans `run.sh` : le paquet `libatk-wrapper-java` (qui fournit la classe
  manquante) est désormais installé automatiquement sur Debian/Ubuntu si besoin,
  avec en complément l'option `-Djavax.accessibility.assistive_technologies=` au
  lancement.
  Si l'erreur persiste malgré tout, vérifiez que votre copie de `run.sh` contient
  bien la ligne suivante (elle doit apparaître deux fois si le fichier est à jour) :
  ```bash
  grep -n "assistive_technologies\|libatk-wrapper" run.sh
  ```
  et sinon, corrigez-le vous-même en une commande, sans attendre une nouvelle
  archive :
  ```bash
  sudo apt install libatk-wrapper-java libatk-wrapper-java-jni
  java -Djavax.accessibility.assistive_technologies= -jar geco-app/target/gecohelper.jar
  ```
- **Le terminal s'ouvre puis se ferme immédiatement, sans rien afficher** : ce
  symptôme touchait certaines distributions Linux (Ubuntu, Zorin, Debian...) où
  `/bin/sh` pointe vers `dash` et non `bash`. Si le gestionnaire de fichiers lance le
  script avec `sh` plutôt que `bash`, des instructions propres à bash échouaient
  silencieusement et le terminal se refermait avant que l'erreur soit visible.
  **Corrigé** dans `run.sh` : le script se relance désormais automatiquement sous
  `bash` si nécessaire, et une pause garantit que la fenêtre reste ouverte jusqu'à
  ce que vous appuyiez sur une touche, succès ou erreur. Si le problème persiste
  malgré la mise à jour du script, ouvrez un terminal manuellement et lancez
  `./run.sh` directement pour voir le message d'erreur complet.
- **`mvn: command not found`** (ou « n'est pas reconnu... » sur Windows) : Maven
  n'est pas installé ou pas reconnu par le terminal — revoyez l'étape « Vérifier /
  installer Maven » ci-dessus, et pensez à fermer/rouvrir le terminal après
  l'installation.
- **Erreur de compilation liée à une dépendance introuvable** : vérifiez votre
  connexion internet (Maven a besoin d'accéder à Maven Central lors du premier
  lancement pour télécharger les bibliothèques).
- **La fenêtre Swing ne s'affiche pas / erreur liée à l'environnement graphique**
  (Linux uniquement, machines sans interface graphique) : assurez-vous de lancer la
  commande sur une machine avec un environnement graphique, ou utilisez un export
  d'affichage type X11 forwarding si vous êtes connecté à distance.

## Pour aller plus loin

- `01-le-jeu-et-ses-regles.md` : comprendre le jeu et les écrans de l'application.
- `03-architecture-technique.md` : le détail des choix techniques de cette mise à
  jour, si vous souhaitez contribuer au code.
