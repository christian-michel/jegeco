// tutorial.js — Assistant/tutoriel optionnel, sous forme d'infobulles guidées.
//
// ⚠️ Fichier totalement autonome et détachable, par conception :
//   - il ne modifie AUCUN autre fichier du projet ;
//   - il ne fait que LIRE le DOM existant, en ciblant les éléments par les ID déjà
//     présents dans index.html (aucun attribut supplémentaire à ajouter au HTML) ;
//   - ses propres styles sont injectés par lui-même (voir injectStyles ci-dessous),
//     pas besoin de toucher à style.css ;
//   - app.js n'a aucune dépendance vers ce fichier.
//
// Pour retirer complètement cette fonctionnalité : supprimez la ligne
// <script src="/js/tutorial.js"></script> dans index.html. Rien d'autre à faire.
//
// Si un sélecteur ci-dessous ne correspond plus à rien (l'interface a changé),
// l'étape est simplement ignorée sans erreur - le tutoriel reste "best effort" et
// ne doit jamais bloquer l'utilisation normale de l'application.

(function () {
	"use strict";

	const STORAGE_KEY_DISABLED = "geco_tutorial_disabled";
	const STORAGE_KEY_SEEN_PREFIX = "geco_tutorial_seen_";

	// Définition des parcours. Chaque étape cible un élément du DOM par sélecteur
	// CSS. Facile à étendre : ajoutez un parcours (nouvelle clé) ou des étapes sans
	// toucher au reste du fichier.
	const TOURS = {
		home: [
			{ selector: ".new-game-card h2", title: "Bienvenue !",
				text: "Commencez ici : choisissez un type de monnaie et créez votre première partie." },
			{ selector: ".money-type-choice", title: "Type de monnaie",
				text: "Monnaie dette (crédits avec intérêts, comme aujourd'hui) ou monnaie libre (June, avec Dividende Universel, sans dette) : les règles du jeu changent selon votre choix." },
			{ selector: "#btnNewGame", title: "Créer la partie",
				text: "Une fois les paramètres réglés (nombre de joueurs, durée des tours, nombre de tours), cliquez ici pour démarrer." },
		],
		game: [
			{ selector: ".stat-cards", title: "Vue d'ensemble",
				text: "Ces cartes résument l'état de la partie en temps réel : joueurs actifs, âge moyen, masse monétaire, crédits en cours." },
			{ selector: "#turnTimer", title: "Minuteur de tour",
				text: "Le temps restant pour ce tour, partagé par tous les écrans connectés à la partie. Utile pour que les joueurs sachent combien de temps il leur reste." },
			{ selector: "#btnAddPlayer", title: "Ajouter un joueur",
				text: "En début de partie (ou en cours, un joueur peut toujours rejoindre) : donnez-lui un nom unique. Deux joueurs de même nom seraient impossibles à distinguer dans l'historique." },
			{ selector: "#playersList", title: "Liste des joueurs",
				text: "Pour chaque joueur : son âge (en tours depuis sa dernière naissance), sa dette et ses intérêts en cours s'il a un crédit actif." },
			{ selector: "#eventsList", title: "Historique des événements",
				text: "Chaque action enregistrée apparaît ici, la plus récente en premier - crédits, remboursements, morts, tours... Cet historique fait foi en cas de doute pendant la partie." },
			{ selector: ".general-actions", title: "Actions générales",
				text: "Les actions qui concernent toute la partie (masse monétaire, rupture technologique, fin de partie...) sont ici, directement accessibles." },
			{ selector: "#playersList", title: "Actions par joueur",
				text: "Trois icônes sur chaque ligne : ✎ pour renommer, ✕ pour supprimer, + pour enregistrer un événement propre à ce joueur (crédit, remboursement...)." },
			{ selector: "#btnEndTurn", title: "Fin de tour",
				text: "Ouvre le bilan de fin de tour : remboursements des joueurs endettés, sélection des morts (c'est vous qui décidez, comme dans le jeu physique), nouveaux-nés, puis nouveaux crédits. Une fois ce bilan terminé, le bouton vert \"Nouveau tour\" apparaît pour démarrer le tour suivant." },
			{ selector: "#navStats", title: "Statistiques",
				text: "Graphiques et rapport de fin de partie, disponibles à tout moment - pas besoin d'attendre la fin pour y jeter un œil." },
		],
		report: [
			{ selector: ".report-metrics, #reportMetrics", title: "Indicateurs statistiques",
				text: "Moyenne, médiane, écart-type et indice de Gini (inégalité de répartition, de 0 = parfaitement égalitaire à 100 = un seul joueur possède tout) des richesses en fin de partie." },
			{ selector: "#chartHistogram", title: "Répartition des richesses",
				text: "Histogramme du nombre de joueurs par tranche de richesse - une bonne façon de voir visuellement les inégalités générées par cette partie." },
			{ selector: "#chartReportMoneyMass", title: "Masse monétaire dans le temps",
				text: "L'évolution de la monnaie en circulation au fil des tours. En monnaie dette, on voit souvent des dents de scie liées aux moments d'assèchement monétaire ; en monnaie libre, les variations sont généralement plus douces." },
			{ selector: "#activityTableBody, .activity-table", title: "Activité par joueur",
				text: "Qui a fait le plus de transactions, qui a le plus emprunté, qui a fait circuler le plus de monnaie - pour répondre aux questions du type \"mais je n'ai pas autant échangé que ça\"." },
			{ selector: "#chartGalilee", title: "Convergence vers la moyenne",
				text: "Ce graphique illustre un principe clé de la Théorie Relative de la Monnaie : en monnaie libre, la richesse de chaque joueur (exprimée relativement à la moyenne) tend à converger vers 1, quel que soit son point de départ." },
			{ selector: "#btnExportReport", title: "Exporter le rapport",
				text: "Télécharge un fichier JSON avec toutes les données du rapport - utile pour comparer plusieurs parties ensuite, ou pour en garder une trace." },
		],
		connect: [
			{ selector: ".connect-intro", title: "Même réseau requis",
				text: "Les smartphones des joueurs doivent être sur le même réseau que cet ordinateur - partage de connexion recommandé, voir la page Documentation pour le détail." },
			{ selector: ".connect-card.likely, .connect-card", title: "Choisissez la bonne adresse",
				text: "Plusieurs adresses réseau peuvent apparaître : celle marquée \"Probable\" correspond généralement au bon réseau (Wifi local ou partage de connexion)." },
			{ selector: ".connect-qr", title: "Faites scanner le QR",
				text: "Les joueurs scannent ce code avec l'appareil photo natif de leur téléphone - aucune application à installer." },
		],
	};

	function isDisabled() {
		return localStorage.getItem(STORAGE_KEY_DISABLED) === "true";
	}
	function setDisabled(value) {
		localStorage.setItem(STORAGE_KEY_DISABLED, value ? "true" : "false");
	}
	function hasSeenTour(name) {
		return localStorage.getItem(STORAGE_KEY_SEEN_PREFIX + name) === "true";
	}
	function markTourSeen(name) {
		localStorage.setItem(STORAGE_KEY_SEEN_PREFIX + name, "true");
	}

	// --- Styles injectés par ce fichier lui-même (voir note en tête de fichier) ---
	function injectStyles() {
		if (document.getElementById("geco-tutorial-styles")) return;
		const style = document.createElement("style");
		style.id = "geco-tutorial-styles";
		style.textContent = `
			.geco-tuto-highlight {
				position: relative; z-index: 10000; border-radius: 10px;
				box-shadow: 0 0 0 4px rgba(37,99,235,0.45), 0 0 0 9999px rgba(15,20,35,0.55);
			}
			.geco-tuto-popover {
				position: absolute; z-index: 10001; max-width: 280px; background: #fff; color: #111827;
				border-radius: 12px; padding: 1rem 1.1rem; box-shadow: 0 12px 30px rgba(0,0,0,0.25);
				font-family: -apple-system, "Segoe UI", Roboto, sans-serif; font-size: 0.85rem; line-height: 1.45;
			}
			.geco-tuto-popover h4 { margin: 0 0 0.4rem; font-size: 0.9rem; }
			.geco-tuto-popover p { margin: 0 0 0.85rem; color: #4b5563; }
			.geco-tuto-actions { display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; }
			.geco-tuto-progress { font-size: 0.72rem; color: #9ca3af; }
			.geco-tuto-btn { border: none; border-radius: 7px; padding: 0.4rem 0.75rem; font-size: 0.78rem; cursor: pointer; font-weight: 600; font-family: inherit; }
			.geco-tuto-btn-primary { background: #2563eb; color: #fff; }
			.geco-tuto-btn-ghost { background: transparent; color: #6b7280; }
			.geco-tuto-fab {
				position: fixed; bottom: 1.25rem; right: 1.25rem; z-index: 9999; width: 44px; height: 44px;
				border-radius: 50%; background: #2563eb; color: #fff; border: none; cursor: pointer;
				font-size: 1.1rem; font-weight: 700; box-shadow: 0 6px 16px rgba(37,99,235,0.4);
			}
		`;
		document.head.appendChild(style);
	}

	// Détermine l'écran actuellement affiché, parmi ceux gérés par le tutoriel.
	// Retourne null si aucun des écrans concernés n'est actif - c'est ce qui permet
	// de masquer le bouton "?" partout ailleurs.
	// Détermine l'écran actuellement affiché, parmi ceux gérés par le tutoriel.
	// Retourne null si aucun des écrans concernés n'est actif - c'est ce qui permet
	// de masquer le bouton "?" partout ailleurs (ex: écran Documentation, qui a son
	// propre contenu explicatif et n'a pas besoin d'un tour superposé).
	function getActiveScreen() {
		const isVisible = (id) => {
			const el = document.getElementById(id);
			return el && !el.classList.contains("hidden");
		};
		if (isVisible("view-games")) return "home";
		if (isVisible("view-connect")) return "connect";
		if (isVisible("view-report")) return "report";
		// view-game affiche plusieurs onglets (Partie en cours/Joueurs/Événements) sur
		// le même écran physique : le tour "game" couvre l'ensemble, peu importe
		// l'onglet actif.
		if (isVisible("view-game")) return "game";
		return null;
	}

	// --- Bouton flottant "?" pour activer/relancer le tutoriel à tout moment ---
	// Visible et interactif sur les écrans couverts par un parcours (voir TOURS et
	// getActiveScreen ci-dessus) - masqué ailleurs, plutôt que d'afficher un bouton
	// y compris sur l'écran de partie, plutôt que d'afficher un bouton qui ne
	// réagirait pas.
	function injectFab() {
		if (document.getElementById("geco-tuto-fab")) return;
		const fab = document.createElement("button");
		fab.id = "geco-tuto-fab";
		fab.className = "geco-tuto-fab";
		fab.type = "button";
		fab.title = "Revoir le tutoriel";
		fab.textContent = "?";
		fab.addEventListener("click", () => {
			const screen = getActiveScreen();
			if (!screen) return;
			setDisabled(false);
			runTour(screen, true);
		});
		document.body.appendChild(fab);
		updateFabVisibility();
	}

	function updateFabVisibility() {
		const fab = document.getElementById("geco-tuto-fab");
		if (!fab) return;
		fab.style.display = getActiveScreen() ? "block" : "none";
	}

	let currentPopover = null;
	function clearPopover() {
		if (currentPopover) {
			currentPopover.remove();
			currentPopover = null;
		}
		document.querySelectorAll(".geco-tuto-highlight").forEach((el) => el.classList.remove("geco-tuto-highlight"));
	}

	function showStep(steps, index, tourName) {
		clearPopover();
		if (index >= steps.length) {
			markTourSeen(tourName);
			return;
		}
		const step = steps[index];
		const target = document.querySelector(step.selector);
		// Cible absente ou invisible (mauvais écran, élément pas encore chargé...) :
		// on passe à l'étape suivante sans bloquer le parcours ni afficher d'erreur.
		if (!target || target.offsetParent === null) {
			showStep(steps, index + 1, tourName);
			return;
		}
		target.classList.add("geco-tuto-highlight");
		target.scrollIntoView({ behavior: "smooth", block: "center" });

		const popover = document.createElement("div");
		popover.className = "geco-tuto-popover";
		popover.innerHTML = `
			<h4></h4>
			<p></p>
			<div class="geco-tuto-actions">
				<span class="geco-tuto-progress">${index + 1} / ${steps.length}</span>
				<span>
					<button type="button" class="geco-tuto-btn geco-tuto-btn-ghost" id="geco-tuto-skip">Ignorer</button>
					<button type="button" class="geco-tuto-btn geco-tuto-btn-primary" id="geco-tuto-next">${index === steps.length - 1 ? "Terminer" : "Suivant"}</button>
				</span>
			</div>`;
		// textContent plutôt qu'innerHTML pour le titre/texte : ce sont des chaînes
		// codées en dur dans ce fichier (pas de données utilisateur), mais autant
		// garder le réflexe.
		popover.querySelector("h4").textContent = step.title;
		popover.querySelector("p").textContent = step.text;
		document.body.appendChild(popover);
		currentPopover = popover;

		// Positionnement simple : juste sous la cible (ajusté si ça déborde à droite).
		const rect = target.getBoundingClientRect();
		const top = window.scrollY + rect.bottom + 10;
		let left = window.scrollX + rect.left;
		if (left + 300 > window.innerWidth) left = window.innerWidth - 300;
		popover.style.top = top + "px";
		popover.style.left = Math.max(10, left) + "px";

		document.getElementById("geco-tuto-next").onclick = () => showStep(steps, index + 1, tourName);
		document.getElementById("geco-tuto-skip").onclick = () => {
			setDisabled(true);
			markTourSeen(tourName);
			clearPopover();
		};
	}

	function runTour(name, force) {
		if (!force && (isDisabled() || hasSeenTour(name))) return;
		const steps = TOURS[name];
		if (!steps) return;
		injectStyles();
		showStep(steps, 0, name);
	}

	// API minimale exposée au cas où on voudrait s'y brancher depuis app.js plus
	// tard (ex: bouton "revoir le tutoriel" dans un futur écran Paramètres) - mais
	// app.js n'a aujourd'hui AUCUNE dépendance vers ce fichier, il fonctionne très
	// bien sans (c'est le principe même de cette séparation).
	window.GecoTutorial = { run: runTour, isDisabled, setDisabled };

	document.addEventListener("DOMContentLoaded", () => {
		injectStyles();
		injectFab();
		// Petite temporisation pour laisser le reste de la page (app.js) s'initialiser
		// (chargement de la liste des parties, etc.) avant d'afficher la 1re infobulle.
		setTimeout(() => runTour("home", false), 600);

		// Déclenche le tour correspondant la première fois que chaque écran devient
		// visible, et met à jour la visibilité du bouton "?" en conséquence - sans
		// dépendre d'un appel explicite depuis app.js : on observe simplement les
		// changements de classe des vues existantes.
		document.querySelectorAll(".view").forEach((v) => {
			new MutationObserver(() => {
				updateFabVisibility();
				if (v.classList.contains("hidden")) return;
				if (v.id === "view-game") setTimeout(() => runTour("game", false), 500);
				else if (v.id === "view-connect") setTimeout(() => runTour("connect", false), 500);
				else if (v.id === "view-report") setTimeout(() => runTour("report", false), 500);
			}).observe(v, { attributes: true, attributeFilter: ["class"] });
		});
	});
})();
