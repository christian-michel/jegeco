#!/usr/bin/env bash
#
# run.sh — Lance Geconomicus Helper.
#
# Par défaut, lance la NOUVELLE interface web (module geco-server) et ouvre
# automatiquement votre navigateur. Ce script vérifie automatiquement que Java 21+
# et Maven sont installés (et les installe si besoin via le gestionnaire de paquets
# du système), compile le projet s'il ne l'est pas déjà, puis lance le jeu.
# L'utilisateur n'a besoin de taper aucune commande : lancer ce script suffit.
#
# Options :
#   --classic   lance l'ancienne interface Swing (module geco-app) à la place
#   --rebuild   force une recompilation même si le jar existe déjà
#               (à utiliser après avoir mis à jour le code source)
#   (les deux options peuvent se combiner : ./run.sh --classic --rebuild)

# Garde-fou : sur certaines distributions (Ubuntu, Zorin, Debian...), /bin/sh pointe
# vers "dash" et non "bash". Si un gestionnaire de fichiers ou un lanceur exécute ce
# script avec "sh script.sh" au lieu de respecter le "#!/usr/bin/env bash" ci-dessus,
# les instructions propres à bash utilisées plus bas (${BASH_SOURCE[0]}, [[ ]], local,
# etc.) échouent silencieusement sous dash - le script part alors dans le mauvais
# dossier et s'arrête sans message clair (symptôme : le terminal s'ouvre puis se
# referme aussitôt). Ce garde-fou détecte ce cas et relance explicitement le script
# avec bash.
if [ -z "${BASH_VERSION:-}" ]; then
	exec bash "$0" "$@"
fi

set -euo pipefail

# --- Petits utilitaires d'affichage (définis avant le trap ci-dessous : le trap peut
# se déclencher très tôt en cas d'erreur précoce, error() doit donc déjà exister) ---
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}==>${NC} $1"; }
warn()  { echo -e "${YELLOW}==>${NC} $1"; }
error() { echo -e "${RED}==>${NC} $1"; }

# Garantit que la fenêtre de terminal reste ouverte à la fin de l'exécution, que le
# script se termine normalement, en erreur, ou soit interrompu - pour que les
# messages (succès, erreur, instructions) restent lisibles au lieu de disparaître
# avec la fermeture immédiate du terminal (c'est ce "trap" qui corrige le symptôme
# "le terminal s'ouvre puis se ferme sans rien montrer").
pause_before_exit()
{
	local code=$?
	echo ""
	if [ $code -ne 0 ]; then
		error "Le script s'est arrêté avec une erreur (code $code) - voir les messages ci-dessus."
	fi
	read -n 1 -s -r -p "Appuyez sur une touche pour fermer cette fenêtre..."
	echo ""
}
trap pause_before_exit EXIT

# On se place systématiquement à la racine du projet (le dossier contenant ce
# script), pour que ça fonctionne quel que soit l'endroit d'où il est lancé
# (double-clic dans un gestionnaire de fichiers, raccourci, terminal...).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR_PATH="geco-server/target/geco-server.jar"
MODULE="geco-server"
INTERFACE_LABEL="l'interface web (nouvelle version)"
FORCE_REBUILD=false
for arg in "$@"; do
	case "$arg" in
		--classic|--swing)
			JAR_PATH="geco-app/target/gecohelper.jar"
			MODULE="geco-app"
			INTERFACE_LABEL="l'interface Swing (ancienne version)"
			;;
		--rebuild)
			FORCE_REBUILD=true
			;;
	esac
done

# --- 1. Détection du système : Linux (apt/dnf/pacman) ou macOS (brew) ---
OS_TYPE="$(uname -s)"
PKG_MANAGER=""
if [[ "$OS_TYPE" == "Darwin" ]]; then
	PKG_MANAGER="brew"
elif command -v apt-get >/dev/null 2>&1; then
	PKG_MANAGER="apt"
elif command -v dnf >/dev/null 2>&1; then
	PKG_MANAGER="dnf"
elif command -v pacman >/dev/null 2>&1; then
	PKG_MANAGER="pacman"
fi

# Installe un paquet en tentant la commande adaptée au gestionnaire détecté.
# Les 3 arguments sont les noms du paquet pour apt / dnf / pacman (brew utilise
# généralement le même nom que apt, passé en 1er argument).
install_package()
{
	local apt_pkg="$1" dnf_pkg="$2" pacman_pkg="$3"
	case "$PKG_MANAGER" in
		brew)   brew install "$apt_pkg" ;;
		apt)    sudo apt-get update -qq && sudo apt-get install -y "$apt_pkg" ;;
		dnf)    sudo dnf install -y "$dnf_pkg" ;;
		pacman) sudo pacman -Sy --noconfirm "$pacman_pkg" ;;
		*)
			error "Impossible de détecter votre gestionnaire de paquets (apt/dnf/pacman/brew)."
			error "Merci d'installer Java 21 et Maven manuellement (voir docs/02-installation.md), puis relancez ce script."
			exit 1
			;;
	esac
}

# --- 2. Vérification / installation de Java 21+ ---
java_ok()
{
	command -v java >/dev/null 2>&1 || return 1
	local ver
	ver="$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
	[ -n "$ver" ] && [ "$ver" -ge 21 ]
}

if java_ok; then
	info "Java détecté : $(java -version 2>&1 | head -1)"
else
	warn "Java 21 (ou supérieur) non détecté. Installation en cours..."
	if [[ "$PKG_MANAGER" == "brew" ]]; then
		install_package openjdk@21 "" ""
		# Sur macOS, openjdk installé par brew n'est pas toujours lié automatiquement
		# au système : on crée le lien symbolique attendu par /usr/libexec/java_home.
		sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" \
			/Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
	else
		install_package openjdk-21-jdk java-21-openjdk jdk21-openjdk
	fi
	if ! java_ok; then
		error "L'installation automatique de Java a échoué."
		error "Installez-le manuellement (voir docs/02-installation.md), puis relancez ce script."
		exit 1
	fi
	info "Java installé avec succès."
fi

# --- 3. Vérification / installation de Maven ---
if command -v mvn >/dev/null 2>&1; then
	info "Maven détecté."
else
	warn "Maven non détecté. Installation en cours..."
	install_package maven maven maven
	if ! command -v mvn >/dev/null 2>&1; then
		error "L'installation automatique de Maven a échoué."
		error "Installez-le manuellement (voir docs/02-installation.md), puis relancez ce script."
		exit 1
	fi
	info "Maven installé avec succès."
fi

# --- 3bis. Correctif Ubuntu/Debian : pont d'accessibilité GNOME manquant (Swing uniquement) ---
# Sur ces distributions, le paquet OpenJDK peut activer automatiquement le chargement
# d'un pont d'accessibilité (org.GNOME.Accessibility.AtkWrapper) dès que l'"Accès
# universel" est activé dans les réglages système - sans que le paquet fournissant
# réellement cette classe ne soit installé, ce qui fait planter immédiatement toute
# application Swing (AWTError). Sans objet pour geco-server (pas d'interface Swing),
# on ne l'installe donc que si l'interface classique est demandée.
if [ "$MODULE" = "geco-app" ] && [[ "$PKG_MANAGER" == "apt" ]] && ! dpkg -s libatk-wrapper-java >/dev/null 2>&1; then
	warn "Installation du pont d'accessibilité Java (corrige un bug connu Ubuntu/Debian/Zorin avec Swing)..."
	sudo apt-get install -y libatk-wrapper-java libatk-wrapper-java-jni \
		|| warn "Installation du pont d'accessibilité échouée (non bloquant : l'option -D au lancement du jeu devrait suffire à compenser)."
fi

# --- 4. Compilation (uniquement si nécessaire) ---
# On ne construit que le module choisi et sa dépendance geco-engine (-pl ... -am) :
# c'est le strict nécessaire pour lancer le jeu, ça évite de télécharger inutilement
# les dépendances de l'autre interface si vous n'en avez pas besoin.
if [ ! -f "$JAR_PATH" ] || [ "$FORCE_REBUILD" = true ]; then
	info "Compilation de $INTERFACE_LABEL (peut prendre quelques minutes la première fois, connexion internet requise)..."
	mvn -q -pl "$MODULE" -am clean package
	info "Compilation terminée."
else
	info "Le projet est déjà compilé (fichier $JAR_PATH trouvé)."
	warn "Pour recompiler après une mise à jour du code : ./run.sh --rebuild (ajoutez --classic si besoin)"
fi

# --- 5. Lancement ---
if [ "$MODULE" = "geco-server" ]; then
	info "Lancement de $INTERFACE_LABEL..."
	info "Ouverture automatique de http://localhost:7000 dans votre navigateur..."
	# Ouvre le navigateur par défaut une fois le serveur prêt (Javalin démarre en
	# général en moins d'une seconde ; 2s de marge). Exécuté en tâche de fond pour ne
	# pas bloquer le démarrage du serveur lui-même, qui doit rester au premier plan
	# (Ctrl+C dans ce terminal l'arrête proprement).
	(
		sleep 2
		URL="http://localhost:7000"
		if command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1
		elif command -v open >/dev/null 2>&1; then open "$URL" >/dev/null 2>&1
		fi
	) &
	java -jar "$JAR_PATH"
else
	info "Lancement de $INTERFACE_LABEL..."
	# -Djavax.accessibility.assistive_technologies= : contourne un bug connu sur
	# Ubuntu/Debian/Zorin (et dérivés) où le paquet OpenJDK active automatiquement le
	# chargement d'un pont d'accessibilité GNOME (org.GNOME.Accessibility.AtkWrapper)
	# dès que l'"Accès universel" est activé dans les réglages système, sans que le
	# paquet fournissant réellement cette classe (java-atk-wrapper) ne soit installé.
	# Résultat : toute application Swing plante immédiatement au démarrage avec une
	# AWTError. Cette option force explicitement le JDK à ne charger aucune techno
	# d'assistance, indépendamment de la configuration système (uniquement pertinent
	# pour geco-app/Swing, sans effet sur geco-server).
	java -Djavax.accessibility.assistive_technologies= -jar "$JAR_PATH"
fi
