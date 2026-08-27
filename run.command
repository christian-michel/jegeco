#!/usr/bin/env bash
#
# run.command — Double-cliquable depuis le Finder sur macOS.
# macOS exécute automatiquement les fichiers .command dans une fenêtre Terminal
# lorsqu'on double-clique dessus (contrairement aux .sh, ouverts en éditeur de texte
# par défaut). Ce fichier ne fait qu'appeler run.sh, qui contient toute la logique
# (détection/installation de Java et Maven, compilation, lancement).
#
# Remarque macOS : lors du tout premier double-clic, Gatekeeper peut afficher un
# avertissement "développeur non identifié". Faites alors un clic droit sur ce
# fichier puis "Ouvrir" (au lieu d'un double-clic) pour autoriser l'exécution.
#
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# run.sh gère lui-même la pause de fin (succès ou erreur) via un trap interne :
# pas besoin d'en ajouter une autre ici.
bash "$SCRIPT_DIR/run.sh" "$@"
