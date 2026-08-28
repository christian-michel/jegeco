// ============================================================================
// Client web pour Ğeconomicus Helper (interface animateur, geco-server).
// ============================================================================
// Vanilla JS volontairement : pas de build tool nécessaire pour lancer l'app,
// dans le même esprit de simplicité que le code Java d'origine. Un seul
// fichier plutôt que des modules ES6 séparés - un choix délibéré, cohérent
// avec la philosophie "aucune étape de compilation" de tout le projet web
// (voir docs/00-vue-ensemble.md à la racine du dépôt pour le contexte complet).
//
// Plan du fichier (chercher les bannières "---------- Nom ----------") :
//   state, el()          - état global côté client + raccourci getElementById
//   API                  - fetch() vers /api/... (une fonction par route)
//   WebSocket             - canal temps réel (rafraîchissement multi-écrans)
//   Thème dynamique        - couleurs bleu (dette) / vert (libre)
//   Vue liste des parties   - écran "Nouvelle partie" + "Parties récentes"
//   Formulaires d'événement  - dialogues génériques + par joueur (voir
//                              openPlayerEventDialog, openBankForm,
//                              openPlayerQuitDialog) et la logique de saisie
//                              automatique "Ne peut pas payer" (voir
//                              computeAutoSeizure/classifyCannotPay)
//   Vue détail d'une partie   - tableau de bord animateur (le plus gros bloc)
//   Graphiques              - Chart.js (masse monétaire, répartition richesses)
//   Connexion joueurs        - détection réseau + QR code (étape 3, Phase A)
//   Documentation            - page d'aide intégrée
//   Minuteur de tour          - chrono synchronisé entre écrans
//   Assistant de fin de tour   - LE plus gros morceau : plusieurs étapes
//                                séquentielles (renderStep0, renderStep2...),
//                                voir openEndOfTurnWizard() pour le fil
//                                conducteur et le branchement dette/libre/
//                                dernier-tour
//   Navigation entre vues     - showView()
//   Dialogues                - openDialog() : la boîte de dialogue générique
//                              réutilisée partout (voir son commentaire pour
//                              les pièges déjà rencontrés - boutons qui
//                              restent cachés, soumission implicite...)
//   Actions (bindActions())  - tous les gestionnaires de clic/changement,
//                              appelée une seule fois au chargement de la page
//   Init                    - séquence de démarrage, tout en bas du fichier


const state = {
	currentGameId: null,
	currentGame: null,
	ws: null,
	newGame: { moneySystem: 1, players: 12, turns: 10, turnDuration: 5 },
	charts: { moneyMass: null, wealth: null },
	timer: { intervalId: null, lastTurnStartedAt: null },
	wizardOpen: false,
	turnEnded: false,
};

const el = (id) => document.getElementById(id);

// ---------- API ----------
// Remonté par un utilisateur : mémorisation du PIN d'une partie protégée, par
// appareil/navigateur (localStorage, pas sessionStorage - on veut que
// l'animateur n'ait à le saisir qu'une fois, même après avoir fermé l'onglet).
// Volontairement séparé de la logique métier ci-dessous : deux petites
// fonctions, faciles à vérifier indépendamment.
function getStoredGamePin(pGameId) {
	try { return localStorage.getItem(`gecoGamePin_${pGameId}`); } catch (err) { return null; }
}
function storeGamePin(pGameId, pPin) {
	try { localStorage.setItem(`gecoGamePin_${pGameId}`, pPin); } catch (err) { /* pas grave si indisponible (navigation privée, etc.) */ }
}

async function api(path, options = {}) {
	// Remonté par un utilisateur : si un PIN est déjà mémorisé pour cette partie
	// (voir storeGamePin), on l'inclut systématiquement - sans effet si la
	// partie n'est pas protégée (le serveur ignore l'en-tête dans ce cas).
	const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
	const gameIdMatch = path.match(/^\/api\/games\/(\d+)/);
	if (gameIdMatch) {
		const pin = getStoredGamePin(gameIdMatch[1]);
		if (pin) headers["X-Game-Pin"] = pin;
	}
	let res = await fetch(path, { ...options, headers });
	if ((res.status === 403) && gameIdMatch && (mBackgroundRefreshDepth === 0)) {
		// Partie protégée, PIN manquant ou incorrect : le demande une fois via une
		// simple invite navigateur (choix pragmatique - c'est une interaction rare,
		// une seule fois par appareil, qui ne justifie pas une boîte de dialogue
		// dédiée), puis réessaie automatiquement la requête d'origine.
		const gameId = gameIdMatch[1];
		const pin = window.prompt(window.GecoI18n.t("game.pin_prompt"));
		if (pin) {
			const unlockRes = await fetch(`/api/games/${gameId}/unlock`, {
				method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ pin }),
			});
			if (unlockRes.ok) {
				storeGamePin(gameId, pin);
				headers["X-Game-Pin"] = pin;
				res = await fetch(path, { ...options, headers });
			} else {
				alert(window.GecoI18n.t("game.pin_incorrect"));
			}
		}
	}
	if (!res.ok) throw new Error(`API error ${res.status} on ${path}`);
	if (res.status === 204) return null;
	return res.json();
}

const Api = {
	listGames: () => api("/api/games"),
	createGame: (body) => api("/api/games", { method: "POST", body: JSON.stringify(body) }),
	getGame: (id) => api(`/api/games/${id}`),
	getStats: (id) => api(`/api/games/${id}/stats`),
	getReport: (id, includeBank) => api(`/api/games/${id}/report?includeBank=${includeBank ? "true" : "false"}`),
	getActivity: (id) => api(`/api/games/${id}/activity`),
	deleteEvent: (gameId, eventId) => api(`/api/games/${gameId}/events/${eventId}`, { method: "DELETE" }),
	editEvent: (gameId, eventId, body) => api(`/api/games/${gameId}/events/${eventId}`, { method: "PUT", body: JSON.stringify(body) }),
	undo: (gameId) => api(`/api/games/${gameId}/undo`, { method: "POST" }),
	getSuggestedDeaths: (gameId) => api(`/api/games/${gameId}/suggested-deaths`),
	getWealthOverTime: (id) => api(`/api/games/${id}/wealth-over-time`),
	getNetworkInfo: () => api(`/api/network-info`),
	deleteGame: (id) => api(`/api/games/${id}`, { method: "DELETE" }),
	addPlayer: (gameId, name) => api(`/api/games/${gameId}/players`, { method: "POST", body: JSON.stringify({ name }) }),
	renamePlayer: (gameId, playerId, name) => api(`/api/games/${gameId}/players/${playerId}`, { method: "PUT", body: JSON.stringify({ name }) }),
	deletePlayer: (gameId, playerId) => api(`/api/games/${gameId}/players/${playerId}`, { method: "DELETE" }),
	startGame: (gameId) => api(`/api/games/${gameId}/start`, { method: "POST" }),
	recordEvent: (gameId, body) => api(`/api/games/${gameId}/events`, { method: "POST", body: JSON.stringify(body) }),
	extendTurn: (gameId, seconds) => api(`/api/games/${gameId}/turn/extend?seconds=${seconds}`, { method: "POST" }),
	pauseTurn: (gameId) => api(`/api/games/${gameId}/turn/pause`, { method: "POST" }),
	resumeTurn: (gameId) => api(`/api/games/${gameId}/turn/resume`, { method: "POST" }),
	getComparison: (ids) => api(`/api/games/compare?ids=${ids.join(",")}`),
	listPlugins: () => api("/api/plugins"),
	setPluginEnabled: (id, enabled) => api(`/api/plugins/${id}/enabled`, { method: "PUT", body: JSON.stringify({ enabled }) }),
	// Remonté par un utilisateur : écran Paramètres (langue par défaut, son,
	// langues personnalisées) - voir AppSettings/LanguageService côté serveur.
	getSettings: () => api("/api/settings"),
	updateSettings: (body) => api("/api/settings", { method: "PUT", body: JSON.stringify(body) }),
	// Étape 3, mode smartphone (écran Paramètres) : les trois tableaux de
	// gestion des visuels - voir CatalogService côté serveur. kind vaut
	// "cartes", "visuels" ou "avatars". Patch partiel des métadonnées d'une
	// entrée (jamais l'image elle-même) - voir openCatalogZoombox().
	getCatalog: (kind) => api(`/api/catalogs/${kind}`),
	patchCatalogEntry: (kind, id, fields) => api(`/api/catalogs/${kind}/${id}`, { method: "PUT", body: JSON.stringify({ fields }) }),
	// Étape 3, monnaie libre : historique des transactions smartphone d'une
	// partie (voir GameService.listTransactions côté serveur) - utilisé par
	// l'assistant de fin de tour pour pré-remplir automatiquement le bilan de
	// chaque joueur (voir renderStepAllPlayersMoney dans openEndOfTurnWizard).
	getTransactions: (gameId) => api(`/api/games/${gameId}/transactions`),
	checkForUpdates: () => api("/api/updates/check"),
	listLanguages: () => api("/api/languages"),
	// Le corps envoyé est le contenu BRUT du fichier .po (pas du JSON) - on
	// contourne donc api() (qui force toujours Content-Type: application/json)
	// pour utiliser fetch() directement avec le bon type de contenu.
	uploadLanguage: async (code, poContent) => {
		const res = await fetch(`/api/languages/${code}`, {
			method: "POST",
			headers: { "Content-Type": "text/plain; charset=utf-8" },
			body: poContent,
		});
		if (!res.ok) {
			let message = `HTTP ${res.status}`;
			try { message = (await res.json()).error || message; } catch (err) { /* corps non-JSON, on garde le code HTTP */ }
			throw new Error(message);
		}
	},
	deleteLanguage: (code) => api(`/api/languages/${code}`, { method: "DELETE" }),
};

// Types d'événements exposés dans l'interface (code à 1 lettre attendu par l'API,
// voir EventTypeConverter côté Java : le code est la 1ère lettre du nom de l'enum).
// Remonté par un utilisateur : les joueurs doivent toujours être listés par
// ordre alphabétique, que ce soit sur le tableau de bord ou dans les listes/
// menus déroulants de l'assistant de fin de tour.
function sortByName(players) {
	return [...players].sort((a, b) => a.name.localeCompare(b.name, "fr"));
}

// Remonté par un utilisateur (audit i18n) : EventDto.typeLabel vient du serveur
// (EventType.getDescription(), voir Event.java) et est TOUJOURS en français, quelle
// que soit la langue choisie côté web - le mécanisme i18n.js ne peut évidemment pas
// le traduire puisqu'il ne passe jamais par le HTML. Plutôt que de rendre le
// backend Java sensible à la langue (gros chantier à part, hors périmètre de
// l'écran "Partie en cours"), la liste d'événements traduit donc elle-même le code
// de type (e.type, le nom complet de l'enum còté serveur, ex. "DEATH",
// "NEW_CREDIT") côté client, en ignorant purposefully le typeLabel du serveur.
const EVENT_TYPE_LABEL_KEYS = {
	JOIN: "event.type.JOIN",
	TURN: "event.type.TURN",
	NEW_CREDIT: "event.type.NEW_CREDIT",
	INTEREST_ONLY: "event.type.INTEREST_ONLY",
	REIMB_CREDIT: "event.type.REIMB_CREDIT",
	CANNOT_PAY: "event.type.CANNOT_PAY",
	BANKRUPT: "event.type.BANKRUPT",
	PRISON: "event.type.PRISON",
	QUIT: "event.type.QUIT",
	MM_CHANGE: "event.type.MM_CHANGE",
	END: "event.type.END",
	DEATH: "event.type.DEATH",
	XTECHNOLOGICAL_BREAKTHROUGH: "event.type.XTECHNOLOGICAL_BREAKTHROUGH",
	SIDE_INVESTMENT: "event.type.SIDE_INVESTMENT",
	ASSESSMENT_FINAL: "event.type.ASSESSMENT_FINAL",
	GOODS_TRADE: "event.type.GOODS_TRADE",
};
function eventTypeLabel(fullEnumName) {
	const key = EVENT_TYPE_LABEL_KEYS[fullEnumName];
	return key ? window.GecoI18n.t(key) : fullEnumName;
}

const EVENT_TYPES = [
	{ code: "D", i18nKey: "event.type.DEATH" },
	{ code: "N", i18nKey: "event.type.NEW_CREDIT" },
	{ code: "I", i18nKey: "event.type.INTEREST_ONLY" },
	{ code: "R", i18nKey: "event.type.REIMB_CREDIT" },
	{ code: "C", i18nKey: "event.type.CANNOT_PAY" },
	{ code: "S", i18nKey: "event.type.SIDE_INVESTMENT" },
	{ code: "A", i18nKey: "event.type.ASSESSMENT_FINAL" },
	{ code: "M", i18nKey: "event.type.MM_CHANGE" },
	{ code: "Q", i18nKey: "event.type.QUIT" },
	{ code: "X", i18nKey: "event.type.XTECHNOLOGICAL_BREAKTHROUGH" },
	{ code: "E", i18nKey: "event.type.END" },
];
// "Banqueroute" (B) et "Prison" (P) existent toujours côté moteur, mais ne sont
// plus sélectionnables manuellement : remonté par un utilisateur, c'est désormais
// le programme qui tranche automatiquement entre Saisie simple/Banqueroute/Prison
// en fonction de ce qui est récupéré par rapport au crédit du joueur (voir
// classifyCannotPay ci-dessous).

// Remonté par un utilisateur : les actions propres à un joueur (accessibles via la
// 3e icône de chaque ligne de la liste des joueurs) et les actions générales de la
// partie (accessibles directement sur la page) doivent être séparées, plutôt que
// mélangées dans un unique formulaire générique. Cette liste vaut pour la monnaie
// dette ; la variante monnaie libre reste à préciser (retour utilisateur à venir).
const PLAYER_EVENT_TYPES = ["D", "N", "I", "R", "C"];

// Remonté par un utilisateur (partie protégée par PIN) : un joueur inscrit
// depuis son smartphone n'apparaissait jamais dans le tableau de bord de
// l'animateur. Piste retenue : le rafraîchissement automatique déclenché par
// le WebSocket (ci-dessous) appelle Api.getGame(), qui - sur une partie
// protégée - peut ouvrir une popup NATIVE BLOQUANTE (window.prompt(), voir
// api() plus bas) si le PIN mémorisé est absent ou invalide. Une popup
// native bloque TOUT le JavaScript de la page (minuteries, autres
// événements...) jusqu'à ce qu'un humain la voie et y réponde - si personne
// ne regarde l'écran du PC à ce moment précis (le cas typique : l'animateur
// est en train de regarder le téléphone du joueur), la page semble
// simplement figée, sans aucun signe visible de ce qui bloque. Ce compteur
// désactive cette popup PENDANT un rafraîchissement en arrière-plan : on
// laisse simplement l'appel échouer silencieusement (voir le catch
// ci-dessous) plutôt que de risquer de geler toute la page sans prévenir.
//
// Un COMPTEUR plutôt qu'un simple booléen (remonté par un utilisateur -
// deuxième bug trouvé le 28/08/2026, en creusant un compte à rebours de fin
// de tour qui ne redémarrait pas sur une partie protégée) : plusieurs
// rafraîchissements en arrière-plan peuvent se chevaucher (plusieurs
// notifications WebSocket arrivant coup sur coup, ex. en toute fin de tour)
// - avec un simple booléen, le PREMIER à se terminer réactivait la popup
// alors qu'un SECOND rafraîchissement était encore en cours, recréant
// exactement le risque de gel qu'on voulait éviter. Le compteur ne redevient
// 0 que quand TOUS les rafraîchissements en cours sont terminés.
let mBackgroundRefreshDepth = 0;

// ---------- WebSocket temps réel ----------
function connectWs() {
	const proto = location.protocol === "https:" ? "wss" : "ws";
	const ws = new WebSocket(`${proto}://${location.host}/ws`);
	state.ws = ws;

	ws.onopen = () => setConnStatus(true);
	ws.onclose = () => { setConnStatus(false); setTimeout(connectWs, 2000); };
	ws.onerror = () => ws.close();

	ws.onmessage = async (evt) => {
		const msg = JSON.parse(evt.data);
		// Comparaison en chaîne plutôt qu'en nombre : élimine par précaution
		// tout risque de faux négatif si l'un des deux côtés véhiculait un
		// identifiant sous forme de texte plutôt que de nombre (jamais observé
		// ici, mais un rafraîchissement manqué est un bug silencieux difficile
		// à repérer - autant s'en prémunir).
		if (String(msg.gameId) === String(state.currentGameId)) {
			mBackgroundRefreshDepth++;
			try {
				await renderGameDetail(state.currentGameId);
			} catch (err) {
				// Échec silencieux volontaire (voir le commentaire au-dessus de
				// mBackgroundRefreshDepth) : pas de popup, juste une trace en
				// console pour le diagnostic. Le prochain rafraîchissement
				// (message WS suivant, ou action explicite de l'animateur) réessaiera.
				console.warn("Rafraîchissement automatique impossible (partie protégée ? PIN pas encore mémorisé) :", err);
			} finally {
				mBackgroundRefreshDepth--;
			}
		}
	};
}

function setConnStatus(online) {
	const badge = el("connStatus");
	badge.textContent = online ? window.GecoI18n.t("conn.online") : window.GecoI18n.t("conn.offline");
	badge.className = "conn-status " + (online ? "conn-online" : "conn-offline");
}

// ---------- Thème dynamique (bleu = monnaie dette, vert = monnaie libre) ----------
function setMoneyTheme(moneySystem) {
	document.body.classList.toggle("money-libre", moneySystem === 0);
}

// ---------- Écran "Nouvelle partie" : mise à jour du résumé ----------
function updateNewGameSummary() {
	const { turns, turnDuration } = state.newGame;
	el("sumTurns").textContent = window.GecoI18n.t("newgame.summary_turns_value", { n: turns });
	el("sumDuration").textContent = window.GecoI18n.t("newgame.summary_duration_value", { n: turnDuration });
	el("sumYears").textContent = window.GecoI18n.t("newgame.summary_years_value", { n: turns * 8 }); // convention du jeu : 1 tour = 8 ans (80 ans / 10 tours)
}

// Pioche dans un objet {fr:"...", en:"..."} (fourni par un manifeste de plugin,
// voir docs/11-plugin-api-contrat.md) le texte de la langue active, avec repli
// sur le français si la langue active n'est pas couverte par ce plugin précis.
function localize(pI18nObject) {
	if (!pI18nObject) return "";
	const lang = window.GecoI18n.getActiveLang();
	return pI18nObject[lang] || pI18nObject.fr || "";
}

// Remonté par un utilisateur : les choix de l'écran "Nouvelle partie" doivent
// dépendre des systèmes activés dans les Paramètres, pas de deux boutons figés
// en dur - construits ici depuis les manifestes de plugins (voir
// docs/11-plugin-api-contrat.md), un par plugin activé.
// Remonté par un utilisateur (capture d'écran + logs à l'appui) : "troc" avait
// été marqué engineReady=true côté serveur, mais oublié ici - le bouton se
// réactivait bien, mais state.newGame.moneySystem restait undefined au moment
// de créer la partie, ce qui déclenchait à tort le message "pas encore
// jouable" configuré comme garde-fou. Voir Game.MONEY_TROC côté moteur (= 2).
const PLUGIN_ID_TO_MONEY_SYSTEM = { dette: 1, libre: 0, troc: 2 };
let mNewGamePlugins = [];

async function renderMoneyTypeChoices() {
	const container = el("moneyTypeChoice");
	const allPlugins = await Api.listPlugins();
	mNewGamePlugins = allPlugins.filter((p) => p.enabled);
	// Filet de sécurité : si l'animateur a désactivé tous les systèmes par
	// erreur dans les Paramètres, on retombe sur la liste complète plutôt que
	// de laisser l'écran "Nouvelle partie" sans aucun choix possible.
	if (mNewGamePlugins.length === 0) mNewGamePlugins = allPlugins;

	container.innerHTML = mNewGamePlugins.map((p, i) => {
		// hasBank -> bleu/banque (monnaie dette) ; hasMoneyMass sans banque ->
		// vert/feuille (monnaie libre) ; ni l'un ni l'autre -> icône neutre
		// (ex. le troc, échange direct sans monnaie).
		const icon = p.hasBank ? "bank" : (p.hasMoneyMass ? "leaf" : "users");
		const colorClass = p.hasBank ? "money-debt-color" : (p.hasMoneyMass ? "money-libre-color" : "money-neutral-color");
		const notReadyNote = p.engineReady ? "" : `<small class="money-choice-not-ready">${escapeHtml(window.GecoI18n.t("newgame.not_playable_yet"))}</small>`;
		return `
			<button type="button" class="money-choice ${i === 0 ? "active" : ""} ${p.engineReady ? "" : "money-choice-disabled"}" data-plugin-id="${p.id}">
				<span class="money-choice-icon ${colorClass}" data-icon="${icon}"></span>
				<span class="money-choice-text">
					<strong>${escapeHtml(localize(p.displayName))}</strong>
					<small>${escapeHtml(localize(p.shortDescription))}</small>
					${notReadyNote}
				</span>
			</button>`;
	}).join("");
	renderIcons(container);
	wireMoneyChoiceClicks();
	if (mNewGamePlugins.length > 0) selectMoneyChoice(mNewGamePlugins[0]);
}

function wireMoneyChoiceClicks() {
	document.querySelectorAll(".money-choice").forEach((btn) => {
		btn.addEventListener("click", () => {
			const plugin = mNewGamePlugins.find((p) => p.id === btn.dataset.pluginId);
			if (!plugin) return;
			document.querySelectorAll(".money-choice").forEach((b) => b.classList.remove("active"));
			btn.classList.add("active");
			selectMoneyChoice(plugin);
		});
	});
}

function findConfigFieldDefault(pPlugin, pKey) {
	if (!Array.isArray(pPlugin.configFields)) return null;
	const field = pPlugin.configFields.find((f) => f.key === pKey);
	return field ? field.default : null;
}

function selectMoneyChoice(pPlugin) {
	state.newGame.pluginId = pPlugin.id;
	// undefined pour un plugin pas encore "engineReady" (ex. le troc) : le
	// bouton "Créer la partie" reste désactivé dans ce cas, voir plus bas.
	state.newGame.moneySystem = PLUGIN_ID_TO_MONEY_SYSTEM[pPlugin.id];

	// Remonté par un utilisateur : la case "Pénalité d'un jeton" (règles
	// officielles de la monnaie libre) n'a de sens qu'en monnaie libre - pas
	// une propriété générique de "hasBank=false" (le troc n'en a pas besoin
	// non plus).
	el("fLibreOptions").classList.toggle("hidden", pPlugin.id !== "libre");

	// Remonté par un utilisateur : le troc n'a ni facteur carte/monnaie ni
	// valeur de pièce - aucune valeur n'est imposée dans ce système (voir
	// plugins/troc/manifest.json, qui ne déclare d'ailleurs pas ces champs).
	el("fMoneyValueRow").classList.toggle("hidden", pPlugin.id === "troc");

	el("btnNewGame").disabled = !pPlugin.engineReady;
	el("btnNewGame").title = pPlugin.engineReady ? "" : window.GecoI18n.t("newgame.not_playable_yet_title");

	// Remonté par un utilisateur, avec un document de spécification détaillé :
	// facteur carte/monnaie pré-rempli selon la valeur par défaut déclarée par
	// le plugin lui-même (voir configFields dans le manifeste), modifiable par
	// l'animateur - on ne touche donc au champ que s'il est encore à une
	// valeur par défaut connue, pour ne jamais écraser une valeur déjà
	// personnalisée.
	const factorField = el("fMoneyCardsFactor");
	const configFactor = findConfigFieldDefault(pPlugin, "moneyCardsFactor");
	if (configFactor != null && ["1", "2", "3"].includes(factorField.value)) {
		factorField.value = String(configFactor);
	}
	// Même principe pour la valeur d'une pièce faible : le troc n'a pas ce
	// champ (masqué juste au-dessus), mais dette/libre peuvent chacun déclarer
	// leur propre valeur par défaut dans leur manifeste.
	const coinField = el("fWeakCoinValue");
	const configCoinValue = findConfigFieldDefault(pPlugin, "weakCoinValue");
	if (configCoinValue != null && ["1", "0.5"].includes(coinField.value)) {
		coinField.value = String(configCoinValue);
	}
}

// Remonté par un utilisateur : écran "Paramètres", pour choisir quels systèmes
// d'échange (plugins) proposer sur l'écran "Nouvelle partie" - voir
// docs/11-plugin-api-contrat.md. Un plugin peut être chargé (visible ici) sans
// être encore réellement jouable (badge "pas encore jouable" à côté, mais
// activable/désactivable quand même : ça permet de vérifier que la sélection
// fonctionne de bout en bout côté interface avant même que le moteur ne sache
// le faire tourner).
async function renderSettingsView() {
	stopTurnTimer();
	showView("view-settings");
	el("navHome").classList.remove("hidden");
	el("navGame").classList.add("hidden");
	document.querySelectorAll("#navHome .active").forEach((b) => b.classList.remove("active"));
	el("navSettings").classList.add("active");
	state.currentGameId = null;
	const t = window.GecoI18n.t;

	// --- Langue ---
	// Remonté par un utilisateur : réutilise directement la liste déjà connue de
	// i18n.js (intégrées + personnalisées, avec drapeau/libellé) plutôt que de
	// la reconstruire à part - une seule source de vérité pour "quelles langues
	// existent", cohérente avec le sélecteur de drapeaux en haut à droite.
	const settings = await Api.getSettings();
	mAppSettings = settings; // reflète immédiatement les réglages actuels (son inclus)

	// --- Mode de jeu (étape 3) ---
	// Remonté par un utilisateur : bouton radio exclusif, pas une case à
	// cocher indépendante - voir AppSettings.gameMode côté serveur. Le bloc
	// des trois tableaux (Cartes/Visuels/Avatars) n'a de sens qu'en mode
	// smartphone, il est donc masqué/affiché en fonction du choix courant.
	function applyGameModeVisibility(mode) {
		el("settingsCatalogsPanel").classList.toggle("hidden", mode !== "smartphone");
		if (mode === "smartphone") renderCatalogsPanel();
	}
	el("settingsGameModeClassic").checked = settings.gameMode !== "smartphone";
	el("settingsGameModeSmartphone").checked = settings.gameMode === "smartphone";
	applyGameModeVisibility(settings.gameMode);
	document.querySelectorAll("input[name=settingsGameMode]").forEach((radio) => {
		radio.onchange = async () => {
			const mode = document.querySelector("input[name=settingsGameMode]:checked").value;
			mAppSettings.gameMode = mode;
			await Api.updateSettings({
				defaultLanguage: mAppSettings.defaultLanguage, soundMuted: mAppSettings.soundMuted,
				soundVolume: mAppSettings.soundVolume, updateCheckUrl: mAppSettings.updateCheckUrl,
				protectionEnabled: mAppSettings.protectionEnabled, gameMode: mode,
			});
			applyGameModeVisibility(mode);
		};
	});

	// Remonté par un utilisateur : après un import réussi, le sélecteur de
	// drapeaux (haut à droite) proposait bien la nouvelle langue, mais pas
	// "Langue par défaut" ici - cause réelle : la liste des langues était lue
	// UNE SEULE FOIS au chargement de cet écran (variable figée), pas relue à
	// chaque appel. renderLangOptions() relit donc maintenant
	// getSupportedLangs() à chaque fois qu'elle est appelée.
	function renderLangOptions(selectEl, selectedCode) {
		const langs = window.GecoI18n.getSupportedLangs();
		selectEl.innerHTML = langs.map((l) => `<option value="${l.code}" ${l.code === selectedCode ? "selected" : ""}>${l.flag} ${escapeHtml(l.label)}</option>`).join("");
	}
	renderLangOptions(el("settingsDefaultLanguage"), settings.defaultLanguage);
	renderLangOptions(el("settingsExportLanguage"), window.GecoI18n.getActiveLang());

	// Remonté par un utilisateur : changer le menu déroulant ne faisait rien de
	// visible - un bouton "Valider" explicite est nécessaire pour (1) enregistrer
	// le choix comme langue par défaut de l'installation (utilisée à la
	// prochaine ouverture, sur un navigateur qui n'a encore fait aucun choix),
	// ET (2) appliquer immédiatement cette langue à l'écran en cours. Réutilise
	// directement window.GecoI18n.setLang() plutôt que de dupliquer sa logique :
	// c'est la même fonction qui gère déjà le sélecteur de drapeaux en haut à
	// droite, donc les deux restent automatiquement synchronisés ("les deux
	// boutons gèrent la même fonction", comme demandé).
	el("btnValidateDefaultLanguage").onclick = async () => {
		const code = el("settingsDefaultLanguage").value;
		// Remonté par un utilisateur : l'API attend les quatre réglages à chaque
		// mise à jour (voir Dtos.UpdateSettingsRequest, aucun n'est optionnel côté
		// serveur) - on renvoie donc toujours l'état courant complet, en ne
		// changeant que le champ concerné. Oublier un champ ici l'écraserait
		// silencieusement (ex. désactiverait la protection par PIN sans le
		// vouloir) - piège déjà rencontré une fois, d'où ce commentaire.
		await Api.updateSettings({
			defaultLanguage: code,
			soundMuted: mAppSettings.soundMuted, soundVolume: mAppSettings.soundVolume,
			updateCheckUrl: mAppSettings.updateCheckUrl, protectionEnabled: mAppSettings.protectionEnabled,
		});
		mAppSettings.defaultLanguage = code;
		window.GecoI18n.setLang(code);
	};

	el("btnExportLanguage").onclick = async () => {
		// Remonté par un utilisateur : "télécharger un fichier de langue (modèle
		// ou à modifier)" - on récupère le .po tel que servi (intégré ou
		// personnalisé, i18n.js gère déjà ce repli côté chargement, on fait
		// pareil ici) et on déclenche un téléchargement classique du navigateur.
		const code = el("settingsExportLanguage").value;
		let res = await fetch(`/lang/${code}.po`);
		if (!res.ok) res = await fetch(`/lang-custom/${code}.po`);
		if (!res.ok) { alert(t("settings.export_error")); return; }
		const blob = await res.blob();
		const url = URL.createObjectURL(blob);
		const a = document.createElement("a");
		a.href = url;
		a.download = `${code}.po`;
		a.click();
		URL.revokeObjectURL(url);
	};

	// Remonté par un utilisateur : en choisissant un fichier nommé "es.po" sans
	// avoir rempli le champ "code de langue" (pas évident qu'il faille le
	// renseigner à la main), le clic sur "Importer" échouait avec un message
	// qui ne laissait pas deviner que le champ était simplement resté vide -
	// le code est donc maintenant pré-rempli automatiquement depuis le nom du
	// fichier choisi, tant que l'animateur n'a rien tapé lui-même.
	el("settingsImportLanguageFile").addEventListener("change", () => {
		const codeField = el("settingsImportLanguageCode");
		const file = el("settingsImportLanguageFile").files[0];
		if (!file || codeField.value.trim() !== "") return;
		const guessedCode = file.name.replace(/\.po$/i, "").toLowerCase().replace(/[^a-z0-9-]/g, "");
		if (guessedCode) codeField.value = guessedCode;
	});

	el("btnImportLanguage").onclick = async () => {
		const code = el("settingsImportLanguageCode").value.trim().toLowerCase();
		const fileInput = el("settingsImportLanguageFile");
		const msgEl = el("settingsImportLanguageMessage");
		msgEl.style.display = "none";
		if (!/^[a-z0-9-]{2,10}$/.test(code)) {
			// Message distinct pour le cas "champ vide" - c'est le cas qui a
			// concrètement induit un utilisateur en erreur (voir ci-dessus).
			msgEl.textContent = code === "" ? t("settings.import_empty_code") : t("settings.import_invalid_code");
			msgEl.style.color = "var(--danger)";
			msgEl.style.display = "block";
			return;
		}
		if (!fileInput.files || fileInput.files.length === 0) {
			msgEl.textContent = t("settings.import_no_file");
			msgEl.style.color = "var(--danger)";
			msgEl.style.display = "block";
			return;
		}
		try {
			const text = await fileInput.files[0].text();
			await Api.uploadLanguage(code, text);
			// Rafraîchit la liste connue de i18n.js (sélecteur de drapeaux inclus)
			// sans recharger la page, puis les deux menus déroulants de cet écran.
			await window.GecoI18n.refreshLanguages();
			renderLangOptions(el("settingsDefaultLanguage"), settings.defaultLanguage);
			renderLangOptions(el("settingsExportLanguage"), code);
			renderLanguagesList();
			msgEl.textContent = t("settings.import_success", { code });
			msgEl.style.color = "var(--accent-libre)";
			msgEl.style.display = "block";
			fileInput.value = "";
		} catch (err) {
			msgEl.textContent = t("settings.import_error", { error: err.message });
			msgEl.style.color = "var(--danger)";
			msgEl.style.display = "block";
		}
	};

	// Remonté par un utilisateur : liste de toutes les langues installées
	// (intégrées ET personnalisées), avec un bouton de suppression uniquement
	// pour les personnalisées - le français et l'anglais, fournis avec
	// l'application, ne peuvent jamais être supprimés (refusé aussi côté
	// serveur si jamais cette route était appelée directement).
	function renderLanguagesList() {
		const langs = window.GecoI18n.getSupportedLangs();
		const container = el("settingsLanguagesList");
		container.innerHTML = langs.map((l) => {
			const isBuiltin = l.code === "fr" || l.code === "en";
			return `
			<div class="compare-game-item">
				<span>${l.flag} <strong>${escapeHtml(l.label)}</strong> <span class="compare-game-meta">(${escapeHtml(l.code)})</span>${isBuiltin ? ` <span class="compare-game-meta">· ${escapeHtml(t("settings.language_builtin"))}</span>` : ""}</span>
				<span class="event-row-actions">
					<button type="button" class="event-action-btn settingsLanguageEdit" data-code="${escapeHtml(l.code)}" data-flag="${escapeHtml(l.flag)}" data-label="${escapeHtml(l.label)}" title="${escapeHtml(t("settings.language_edit_title"))}">✎</button>
					${isBuiltin ? "" : `<button type="button" class="event-action-btn event-action-delete settingsLanguageDelete" data-code="${escapeHtml(l.code)}" title="${escapeHtml(t("settings.language_delete_title"))}">✕</button>`}
				</span>
			</div>`;
		}).join("");
		container.querySelectorAll(".settingsLanguageDelete").forEach((btn) => {
			btn.addEventListener("click", async () => {
				if (!confirm(t("settings.language_delete_confirm", { code: btn.dataset.code }))) return;
				try {
					await Api.deleteLanguage(btn.dataset.code);
					await window.GecoI18n.refreshLanguages();
					renderLangOptions(el("settingsDefaultLanguage"), settings.defaultLanguage);
					renderLangOptions(el("settingsExportLanguage"), window.GecoI18n.getActiveLang());
					renderLanguagesList();
				} catch (err) {
					alert(t("settings.language_delete_error", { error: err.message }));
				}
			});
		});
		// Remonté par un utilisateur : le drapeau/libellé d'une langue est deviné
		// automatiquement (une même langue pouvant être parlée dans plusieurs
		// pays, la déduction peut se tromper) - ce réglage manuel permet de
		// corriger au cas par cas, pour n'importe quelle langue y compris fr/en.
		container.querySelectorAll(".settingsLanguageEdit").forEach((btn) => {
			btn.addEventListener("click", () => {
				const { code, flag, label } = btn.dataset;
				openDialog(t("settings.language_edit_dialog_title", { code }), `
					<label>${t("settings.language_edit_flag_label")}</label>
					<input id="fLanguageFlag" type="text" value="${escapeHtml(flag)}" maxlength="8">
					<label>${t("settings.language_edit_name_label")}</label>
					<input id="fLanguageName" type="text" value="${escapeHtml(label)}" maxlength="60">
					<p style="font-size:0.78rem;color:var(--text-dim);margin-top:0.4rem;">${t("settings.language_edit_reset_hint")}</p>
				`, async () => {
					await api(`/api/languages/${code}/display`, {
						method: "PUT",
						body: JSON.stringify({ flag: el("fLanguageFlag").value.trim(), label: el("fLanguageName").value.trim() }),
					});
					await window.GecoI18n.refreshLanguages();
					renderLangOptions(el("settingsDefaultLanguage"), settings.defaultLanguage);
					renderLangOptions(el("settingsExportLanguage"), window.GecoI18n.getActiveLang());
					renderLanguagesList();
				});
			});
		});
	}
	renderLanguagesList();

	// --- Son ---
	el("settingsSoundMuted").checked = settings.soundMuted;
	el("settingsSoundVolume").value = settings.soundVolume;
	el("settingsSoundVolume").disabled = settings.soundMuted;
	el("settingsSoundVolumeValue").textContent = `${settings.soundVolume}%`;

	async function saveSoundSettings() {
		mAppSettings.soundMuted = el("settingsSoundMuted").checked;
		mAppSettings.soundVolume = parseInt(el("settingsSoundVolume").value, 10);
		await Api.updateSettings({
			defaultLanguage: mAppSettings.defaultLanguage,
			soundMuted: mAppSettings.soundMuted, soundVolume: mAppSettings.soundVolume,
			updateCheckUrl: mAppSettings.updateCheckUrl, protectionEnabled: mAppSettings.protectionEnabled,
		});
	}
	el("settingsSoundMuted").onchange = () => {
		el("settingsSoundVolume").disabled = el("settingsSoundMuted").checked;
		saveSoundSettings();
	};
	el("settingsSoundVolume").oninput = () => {
		el("settingsSoundVolumeValue").textContent = `${el("settingsSoundVolume").value}%`;
	};
	el("settingsSoundVolume").onchange = () => saveSoundSettings();
	el("btnTestSound").onclick = () => playWhistle("start");

	// --- Mises à jour ---
	// Remonté par un utilisateur : vérification en lecture seule uniquement -
	// jamais de téléchargement ni d'installation automatique, voir
	// UpdateCheckService côté serveur. L'animateur fournit sa propre adresse
	// (encore inconnue au moment de l'écriture de ce code) et garde la main sur
	// la suite.
	el("settingsCurrentVersion").textContent = settings.currentVersion || "—";
	el("settingsUpdateCheckUrl").value = settings.updateCheckUrl || "";

	el("btnSaveUpdateUrl").onclick = async () => {
		const url = el("settingsUpdateCheckUrl").value.trim();
		await Api.updateSettings({
			defaultLanguage: mAppSettings.defaultLanguage, soundMuted: mAppSettings.soundMuted,
			soundVolume: mAppSettings.soundVolume, updateCheckUrl: url, protectionEnabled: mAppSettings.protectionEnabled,
		});
		mAppSettings.updateCheckUrl = url;
		settings.updateCheckUrl = url;
	};

	el("btnCheckUpdate").onclick = async () => {
		const resultEl = el("settingsUpdateResult");
		resultEl.className = "";
		resultEl.textContent = t("settings.update_checking");
		resultEl.classList.remove("hidden");
		const result = await Api.checkForUpdates();
		if (!result.checkConfigured) {
			resultEl.textContent = t("settings.update_not_configured");
			resultEl.style.color = "var(--text-dim)";
		} else if (!result.success) {
			resultEl.textContent = t("settings.update_check_error", { error: result.error || "" });
			resultEl.style.color = "var(--danger)";
		} else if (result.updateAvailable) {
			resultEl.innerHTML = `
				<p style="color:var(--accent-libre);font-weight:600;">${escapeHtml(t("settings.update_available", { version: result.latestVersion }))}</p>
				${result.releaseNotes ? `<p style="font-size:0.85rem;color:var(--text-dim);">${escapeHtml(result.releaseNotes)}</p>` : ""}
				${result.downloadUrl ? `<a href="${escapeHtml(result.downloadUrl)}" target="_blank" rel="noopener" class="btn btn-small btn-accent">${escapeHtml(t("settings.update_download_btn"))}</a>` : ""}`;
		} else {
			resultEl.textContent = t("settings.update_up_to_date", { version: result.currentVersion });
			resultEl.style.color = "var(--accent-libre)";
		}
	};

	// --- Sauvegarde ---
	// Remonté par un utilisateur, pour finaliser l'étape 2 : simple navigation
	// (pas de fetch+blob nécessaire, comme pour l'export de plugin) - le
	// serveur renvoie déjà les bons en-têtes Content-Disposition pour
	// déclencher le téléchargement du navigateur.
	el("btnDownloadBackup").onclick = () => {
		window.location.href = "/api/backup";
	};

	// --- Protection par code ---
	el("settingsProtectionEnabled").checked = settings.protectionEnabled;
	el("settingsProtectionEnabled").onchange = async () => {
		mAppSettings.protectionEnabled = el("settingsProtectionEnabled").checked;
		await Api.updateSettings({
			defaultLanguage: mAppSettings.defaultLanguage, soundMuted: mAppSettings.soundMuted,
			soundVolume: mAppSettings.soundVolume, updateCheckUrl: mAppSettings.updateCheckUrl,
			protectionEnabled: mAppSettings.protectionEnabled,
		});
	};

	// --- Plugins (systèmes d'échange) ---
	const plugins = await Api.listPlugins();
	const container = el("settingsPluginsList");
	container.innerHTML = plugins.map((p) => {
		// Remonté par un utilisateur : même oubli que PLUGIN_ID_TO_MONEY_SYSTEM/
		// buildGameCard/renderCompareView ci-dessus.
		const badgeClass = p.hasBank ? "debt" : (p.hasMoneyMass ? "libre" : "troc");
		// Remonté par un utilisateur : icônes télécharger/supprimer en bout de
		// ligne - la suppression est masquée pour les deux systèmes fournis par
		// défaut (dette/libre), jamais supprimables (voir PluginRegistry.isBuiltin
		// côté serveur, qui refuse aussi la requête si elle est tout de même
		// envoyée). Les boutons sont volontairement en dehors du <label> de la
		// case à cocher, pour ne jamais déclencher son activation/désactivation
		// en cliquant dessus.
		return `
		<div class="compare-game-item" data-plugin-id="${p.id}">
			<label style="display:flex;align-items:center;gap:0.6rem;flex:1;min-width:0;cursor:pointer;">
				<input type="checkbox" class="settingsPluginToggle" data-plugin-id="${p.id}" ${p.enabled ? "checked" : ""}>
				<span class="compare-game-badge ${badgeClass}">${escapeHtml(p.id)}</span>
				<span><strong>${escapeHtml(localize(p.displayName))}</strong>
				<span class="compare-game-meta"> — ${escapeHtml(localize(p.shortDescription))}${p.engineReady ? "" : ` · ${escapeHtml(t("settings.not_playable_yet"))}`}</span></span>
			</label>
			<span class="event-row-actions">
				<button type="button" class="event-action-btn settingsPluginDownload" data-plugin-id="${p.id}" title="${escapeHtml(t("settings.plugin_download_title"))}">⬇</button>
				${p.isBuiltin ? "" : `<button type="button" class="event-action-btn event-action-delete settingsPluginDelete" data-plugin-id="${p.id}" title="${escapeHtml(t("settings.plugin_delete_title"))}">✕</button>`}
			</span>
		</div>`;
	}).join("");
	container.querySelectorAll(".settingsPluginToggle").forEach((cb) => {
		cb.addEventListener("change", () => Api.setPluginEnabled(cb.dataset.pluginId, cb.checked));
	});
	container.querySelectorAll(".settingsPluginDownload").forEach((btn) => {
		btn.addEventListener("click", () => {
			// Téléchargement direct via une navigation (pas de fetch+blob
			// nécessaire ici, contrairement à l'export de langue) : le serveur
			// renvoie déjà les bons en-têtes Content-Disposition pour déclencher
			// le téléchargement du navigateur.
			window.location.href = `/api/plugins/${btn.dataset.pluginId}/download`;
		});
	});
	container.querySelectorAll(".settingsPluginDelete").forEach((btn) => {
		btn.addEventListener("click", async () => {
			if (!confirm(t("settings.plugin_delete_confirm", { id: btn.dataset.pluginId }))) return;
			try {
				await api(`/api/plugins/${btn.dataset.pluginId}`, { method: "DELETE" });
				renderSettingsView();
			} catch (err) {
				alert(t("settings.plugin_delete_error", { error: err.message }));
			}
		});
	});
}

// ---------- Étape 3, mode smartphone : tableaux Cartes/Visuels/Avatars ----------
// Voir §5.3 du cahier des charges : trois catalogues distincts (le catalogue
// "cartes" ne fait qu'assigner un visuel par id, jamais dupliquer sa
// description), chaque ligne cliquable ouvre une zoombox d'édition des
// métadonnées (jamais l'image elle-même - pas de recadrage/retouche ici).

// Valeurs fixes (niveau/secteur/règle/genre/tranche d'âge) : un ensemble
// connu à l'avance, traduit via des clés .po dédiées - si une valeur ne s'y
// trouve pas encore (ex. un secteur inventé plus tard par l'animateur), on
// retombe simplement sur le code brut plutôt que d'afficher la clé technique.
function catalogEnumLabel(pPrefix, pCode) {
	if (!pCode) return "";
	const key = `catalog.${pPrefix}.${pCode}`;
	const translated = window.GecoI18n.t(key);
	return translated === key ? pCode : translated;
}

// Champs de texte libre affichés au joueur (nom de carte, étiquette de
// visuel) : une table {code langue -> texte} stockée DANS le catalogue lui-
// même (pas dans les .po de l'application, voir CatalogSeeds côté serveur) -
// l'animateur les édite directement depuis la zoombox, dans toutes les
// langues actuellement installées. On affiche la langue active, puis le
// français en repli, puis la première valeur trouvée - jamais une clé vide.
function catalogTextValue(pValue) {
	if (!pValue || typeof pValue !== "object") return pValue || "";
	const activeLang = window.GecoI18n.getActiveLang ? window.GecoI18n.getActiveLang() : "fr";
	if (pValue[activeLang]) return pValue[activeLang];
	if (pValue.fr) return pValue.fr;
	const firstValue = Object.values(pValue).find((v) => v);
	return firstValue || "";
}

// Construit un input texte par langue installée pour un champ multilingue de
// catalogue (voir catalogTextValue ci-dessus) - utilisé dans la zoombox.
function catalogTextFieldsHtml(pFieldName, pValue) {
	const langs = window.GecoI18n.getSupportedLangs();
	const current = (pValue && typeof pValue === "object") ? pValue : {};
	return langs.map((l) => `
		<label class="field-label" style="margin-top:0.6rem;">${l.flag} ${escapeHtml(l.label)}</label>
		<input type="text" class="field-input catalogTextField" data-field="${pFieldName}" data-lang="${l.code}" value="${escapeHtml(current[l.code] || "")}">
	`).join("");
}

// Relit les champs construits par catalogTextFieldsHtml() pour reconstituer
// la table {code langue -> texte} à envoyer au serveur (voir Api.patchCatalogEntry).
function readCatalogTextFields(pFieldName) {
	const result = {};
	document.querySelectorAll(`.catalogTextField[data-field="${pFieldName}"]`).forEach((input) => {
		result[input.dataset.lang] = input.value;
	});
	return result;
}

// Vignette d'une entrée (carte/visuel/avatar) - un simple <img>, avec repli
// visuel (icône générique) si le fichier n'existe pas encore sur le disque :
// c'est le cas attendu tant que les vraies images n'ont pas été déposées (voir
// CatalogSeeds, catalogues de démonstration).
function catalogThumbHtml(pUrl, pClass) {
	if (!pUrl) return `<div class="${pClass || "catalog-row-thumb"}-fallback">🖼️</div>`;
	return `<img src="${escapeHtml(pUrl)}" class="${pClass || "catalog-row-thumb"}" `
		+ `onerror="this.outerHTML='<div class=&quot;${pClass || "catalog-row-thumb"}-fallback&quot;>🖼️</div>'">`;
}

// Étape 3, mode smartphone : groupes repliables (accordéon) par table - vu le
// volume réel (104 cartes/visuels, 76 avatars), tout afficher à plat rendait
// l'écran interminable. Regroupement par le champ le plus naturel de chaque
// catalogue (niveau pour cartes/visuels, genre pour avatars) ; conservé PAR
// onglet (changer d'onglet et revenir garde les groupes ouverts tels quels).
// "fonds" n'a que 4 entrées fixes : pas de regroupement, affichage à plat.
const mSettingsCatalogExpanded = { cartes: new Set(), visuels: new Set(), avatars: new Set(), fonds: new Set() };

// Clé de regroupement d'une entrée pour un type de catalogue donné, ou null
// si ce catalogue ne se regroupe pas (fonds).
function catalogGroupKey(pKind, pEntry) {
	if ((pKind === "cartes") || (pKind === "visuels")) return pEntry.niveau || "";
	if (pKind === "avatars") return pEntry.genre || "";
	return null;
}

// Libellé + ordre d'affichage des groupes pour un type de catalogue donné.
function catalogGroupOrder(pKind) {
	if ((pKind === "cartes") || (pKind === "visuels")) return ["faible", "moyenne", "forte", "tresforte"];
	if (pKind === "avatars") return ["homme", "femme", "neutre"];
	return [];
}

function catalogGroupLabel(pKind, pGroupKey) {
	if ((pKind === "cartes") || (pKind === "visuels")) return catalogEnumLabel("level", pGroupKey);
	if (pKind === "avatars") return catalogEnumLabel("avatar_genre", pGroupKey);
	return pGroupKey;
}

async function renderCatalogsPanel() {
	const t = window.GecoI18n.t;
	document.querySelectorAll(".settings-catalog-tab").forEach((btn) => {
		btn.classList.toggle("active", btn.dataset.catalogKind === mSettingsCatalogKind);
		btn.onclick = () => { mSettingsCatalogKind = btn.dataset.catalogKind; renderCatalogsPanel(); };
	});

	const container = el("settingsCatalogTable");
	container.textContent = t("settings.catalog_loading");

	const entries = await Api.getCatalog(mSettingsCatalogKind);
	// Le tableau "Cartes" affiche la vignette du visuel qui lui est assigné
	// (voir "visualId") : il faut donc aussi le catalogue "Visuels" pour
	// résoudre le nom de fichier correspondant.
	const visuals = (mSettingsCatalogKind === "cartes") ? await Api.getCatalog("visuels") : null;

	if (entries.length === 0) {
		container.innerHTML = `<p class="galilee-explainer">${escapeHtml(t("settings.catalog_empty"))}</p>`;
		return;
	}

	const expanded = mSettingsCatalogExpanded[mSettingsCatalogKind];

	if (mSettingsCatalogKind === "fonds") {
		// Pas de regroupement : 4 entrées fixes, affichage à plat comme avant.
		container.innerHTML = entries.map((entry) => renderCatalogRowHtml(mSettingsCatalogKind, entry, visuals)).join("");
	} else {
		const order = catalogGroupOrder(mSettingsCatalogKind);
		container.innerHTML = order
			.map((groupKey) => {
				const groupEntries = entries.filter((e) => catalogGroupKey(mSettingsCatalogKind, e) === groupKey);
				if (groupEntries.length === 0) return ""; // groupe vide (ex. "neutre" sans avatar) : pas affiché du tout
				const isOpen = expanded.has(groupKey);
				return `
				<div class="catalog-group">
					<button type="button" class="catalog-group-header" data-group="${escapeHtml(groupKey)}">
						<span class="catalog-group-chevron">${isOpen ? "▾" : "▸"}</span>
						<span class="catalog-group-title">${escapeHtml(catalogGroupLabel(mSettingsCatalogKind, groupKey))}</span>
						<span class="catalog-group-count">${groupEntries.length}</span>
					</button>
					<div class="catalog-group-body ${isOpen ? "" : "hidden"}">
						${groupEntries.map((entry) => renderCatalogRowHtml(mSettingsCatalogKind, entry, visuals)).join("")}
					</div>
				</div>`;
			})
			.join("");

		container.querySelectorAll(".catalog-group-header").forEach((header) => {
			header.addEventListener("click", () => {
				const key = header.dataset.group;
				if (expanded.has(key)) expanded.delete(key); else expanded.add(key);
				renderCatalogsPanel();
			});
		});
	}

	container.querySelectorAll(".catalog-row").forEach((row) => {
		row.addEventListener("click", () => {
			const entry = entries.find((e) => e.id === row.dataset.id);
			openCatalogZoombox(mSettingsCatalogKind, entry, visuals);
		});
	});
}

function renderCatalogRowHtml(pKind, pEntry, pVisuals) {
	const t = window.GecoI18n.t;
	if (pKind === "cartes") {
		const visual = pVisuals ? pVisuals.find((v) => v.id === pEntry.visualId) : null;
		const thumbUrl = visual ? `/cartes/${visual.filename}` : null;
		const title = catalogTextValue(pEntry.nom) || pEntry.id;
		const meta = [catalogEnumLabel("level", pEntry.niveau), catalogEnumLabel("sector", pEntry.secteur)]
			.filter(Boolean).join(" · ");
		return `
		<button type="button" class="catalog-row" data-id="${escapeHtml(pEntry.id)}">
			${catalogThumbHtml(thumbUrl)}
			<span class="catalog-row-main">
				<span class="catalog-row-title">${escapeHtml(title)}</span>
				<span class="catalog-row-meta">${escapeHtml(meta)}</span>
			</span>
		</button>`;
	}
	if (pKind === "visuels") {
		const title = catalogTextValue(pEntry.etiquette) || pEntry.filename;
		const meta = [catalogEnumLabel("level", pEntry.niveau), pEntry.filename].filter(Boolean).join(" · ");
		return `
		<button type="button" class="catalog-row" data-id="${escapeHtml(pEntry.id)}">
			${catalogThumbHtml(`/cartes/${pEntry.filename}`)}
			<span class="catalog-row-main">
				<span class="catalog-row-title">${escapeHtml(title)}</span>
				<span class="catalog-row-meta">${escapeHtml(meta)}</span>
			</span>
		</button>`;
	}
	if (pKind === "fonds") {
		return `
		<button type="button" class="catalog-row" data-id="${escapeHtml(pEntry.id)}">
			${catalogThumbHtml(`/cartes/${pEntry.filename}`)}
			<span class="catalog-row-main">
				<span class="catalog-row-title">${escapeHtml(catalogEnumLabel("level", pEntry.niveau))}</span>
				<span class="catalog-row-meta">${escapeHtml(pEntry.filename)}</span>
			</span>
		</button>`;
	}
	// pKind === "avatars"
	const meta = [catalogEnumLabel("avatar_genre", pEntry.genre), catalogEnumLabel("avatar_age", pEntry.ageCategory)]
		.filter(Boolean).join(" · ");
	return `
	<button type="button" class="catalog-row" data-id="${escapeHtml(pEntry.id)}">
		${catalogThumbHtml(`/avatars/${pEntry.filename}`)}
		<span class="catalog-row-main">
			<span class="catalog-row-title">${escapeHtml(pEntry.id)}</span>
			<span class="catalog-row-meta">${escapeHtml(meta)}</span>
		</span>
	</button>`;
}

// Zoombox (agrandissement + édition des métadonnées, jamais l'image) - voir
// §5.3 du cahier des charges. Réutilise openDialog(), comme toutes les autres
// éditions de l'écran Paramètres (langue, plugins...).
function openCatalogZoombox(pKind, pEntry, pVisuals) {
	const t = window.GecoI18n.t;
	let previewUrl = null;
	let bodyHtml = "";

	if (pKind === "cartes") {
		const visual = pVisuals ? pVisuals.find((v) => v.id === pEntry.visualId) : null;
		previewUrl = visual ? `/cartes/${visual.filename}` : null;
		const levelOptions = ["faible", "moyenne", "forte", "tresforte"]
			.map((lvl) => `<option value="${lvl}" ${pEntry.niveau === lvl ? "selected" : ""}>${escapeHtml(catalogEnumLabel("level", lvl))}</option>`).join("");
		const visualOptions = [`<option value="">${escapeHtml(t("settings.catalog_no_visual"))}</option>`]
			.concat((pVisuals || []).map((v) => `<option value="${escapeHtml(v.id)}" ${pEntry.visualId === v.id ? "selected" : ""}>${escapeHtml(catalogTextValue(v.etiquette) || v.filename)}</option>`))
			.join("");
		bodyHtml = `
			<label class="field-label">${escapeHtml(t("settings.catalog_field_level"))}</label>
			<select id="catalogFieldNiveau" class="field-input">${levelOptions}</select>
			<label class="field-label" style="margin-top:0.6rem;">${escapeHtml(t("settings.catalog_field_sector"))}</label>
			<input type="text" id="catalogFieldSecteur" class="field-input" value="${escapeHtml(pEntry.secteur || "")}">
			<label class="field-label" style="margin-top:0.6rem;">${escapeHtml(t("settings.catalog_field_visual"))}</label>
			<select id="catalogFieldVisualId" class="field-input">${visualOptions}</select>
			<p class="galilee-explainer" style="margin-top:1rem;">${escapeHtml(t("settings.catalog_field_name_intro"))}</p>
			${catalogTextFieldsHtml("nom", pEntry.nom)}
		`;
	} else if (pKind === "visuels") {
		previewUrl = `/cartes/${pEntry.filename}`;
		const levelOptions = ["faible", "moyenne", "forte", "tresforte"]
			.map((lvl) => `<option value="${lvl}" ${pEntry.niveau === lvl ? "selected" : ""}>${escapeHtml(catalogEnumLabel("level", lvl))}</option>`).join("");
		bodyHtml = `
			<p class="galilee-explainer">${escapeHtml(t("settings.catalog_field_filename_label"))} : <strong>${escapeHtml(pEntry.filename)}</strong></p>
			<label class="field-label">${escapeHtml(t("settings.catalog_field_level"))}</label>
			<select id="catalogFieldNiveau" class="field-input">${levelOptions}</select>
			<p class="galilee-explainer" style="margin-top:1rem;">${escapeHtml(t("settings.catalog_field_label_intro"))}</p>
			${catalogTextFieldsHtml("etiquette", pEntry.etiquette)}
		`;
	} else if (pKind === "fonds") {
		// Un fond de carte est structurellement lié à son niveau (id = niveau,
		// voir CatalogSeeds.seedBackgrounds côté serveur) : rien à réassigner
		// ni à renommer ici, uniquement un agrandissement pour vérifier que le
		// bon fichier a bien été déposé au bon endroit.
		previewUrl = `/cartes/${pEntry.filename}`;
		bodyHtml = `
			<p class="galilee-explainer">${escapeHtml(t("settings.catalog_field_level"))} : <strong>${escapeHtml(catalogEnumLabel("level", pEntry.niveau))}</strong></p>
			<p class="galilee-explainer">${escapeHtml(t("settings.catalog_field_filename_label"))} : <strong>${escapeHtml(pEntry.filename)}</strong></p>
			<p class="galilee-explainer">${escapeHtml(t("settings.catalog_background_readonly"))}</p>
		`;
	} else {
		// avatars
		previewUrl = `/avatars/${pEntry.filename}`;
		const genreOptions = ["homme", "femme", "neutre"]
			.map((g) => `<option value="${g}" ${pEntry.genre === g ? "selected" : ""}>${escapeHtml(catalogEnumLabel("avatar_genre", g))}</option>`).join("");
		const ageOptions = ["enfant", "adulte", "senior"]
			.map((a) => `<option value="${a}" ${pEntry.ageCategory === a ? "selected" : ""}>${escapeHtml(catalogEnumLabel("avatar_age", a))}</option>`).join("");
		bodyHtml = `
			<p class="galilee-explainer">${escapeHtml(t("settings.catalog_field_filename_label"))} : <strong>${escapeHtml(pEntry.filename)}</strong></p>
			<label class="field-label">${escapeHtml(t("settings.catalog_field_genre"))}</label>
			<select id="catalogFieldGenre" class="field-input">${genreOptions}</select>
			<label class="field-label" style="margin-top:0.6rem;">${escapeHtml(t("settings.catalog_field_age"))}</label>
			<select id="catalogFieldAge" class="field-input">${ageOptions}</select>
		`;
	}

	const previewHtml = `<div class="catalog-zoombox-preview">${catalogThumbHtml(previewUrl, "catalog-row-thumb")}</div>`;
	openDialog(t("settings.catalog_zoombox_title"), previewHtml + bodyHtml, async () => {
		if (pKind === "fonds") return; // rien à éditer, la zoombox ne sert qu'à l'agrandissement
		let fields;
		if (pKind === "cartes") {
			fields = {
				niveau: el("catalogFieldNiveau").value,
				secteur: el("catalogFieldSecteur").value,
				visualId: el("catalogFieldVisualId").value || null,
				nom: readCatalogTextFields("nom"),
			};
		} else if (pKind === "visuels") {
			fields = { niveau: el("catalogFieldNiveau").value, etiquette: readCatalogTextFields("etiquette") };
		} else {
			fields = { genre: el("catalogFieldGenre").value, ageCategory: el("catalogFieldAge").value };
		}
		await Api.patchCatalogEntry(pKind, pEntry.id, fields);
		renderCatalogsPanel();
	});
}



// Construit une carte de partie, réutilisée par l'écran "Nouvelle partie" (5
// dernières) et l'écran "Parties récentes" (toutes, avec suppression).
function buildGameCard(g, withDelete) {
	const card = document.createElement("div");
	card.className = "game-card";
	const t = window.GecoI18n.t;
	// Remonté par un utilisateur (capture d'écran à l'appui) : même oubli que
	// PLUGIN_ID_TO_MONEY_SYSTEM ci-dessus - ce badge ne connaissait que deux
	// systèmes et aurait affiché "Monnaie libre" pour une partie en troc.
	const badgeClass = g.moneySystem === 1 ? "badge-debt" : (g.moneySystem === 2 ? "badge-troc" : "badge-libre");
	const badgeLabel = g.moneySystem === 1 ? t("money.debt") : (g.moneySystem === 2 ? t("money.troc") : t("money.libre"));
	card.innerHTML = `
		${withDelete ? `<button type="button" class="game-card-delete" title="${escapeHtml(t("games.delete_btn_title"))}">✕</button>` : ""}
		<h4>${escapeHtml(g.description || t("games.untitled", { id: g.id }))}</h4>
		<div class="meta">${escapeHtml(g.location || "")} · ${t("games.turn_meta", { turn: g.turnNumber, total: g.nbTurnsPlanned })}</div>
		<span class="badge ${badgeClass}">${badgeLabel}</span>`;
	card.onclick = () => renderGameDetail(g.id);

	if (withDelete) {
		const delBtn = card.querySelector(".game-card-delete");
		delBtn.onclick = (evt) => {
			// Empêche le clic de "remonter" jusqu'à la carte (qui ouvrirait la partie).
			evt.stopPropagation();
			confirmDeleteGame(g);
		};
	}
	return card;
}

function confirmDeleteGame(g) {
	const t = window.GecoI18n.t;
	openDialog(t("games.delete_confirm_title"), `
		<p>${t("games.delete_confirm_body", { name: escapeHtml(g.description || t("games.untitled", { id: g.id })) })}</p>
	`, async () => {
		await Api.deleteGame(g.id);
		// Rafraîchit la vue actuellement affichée (l'une des deux liste les parties).
		if (!el("view-all-games").classList.contains("hidden")) {
			renderAllGames();
		} else {
			renderGamesList();
		}
	});
	// Le bouton "Valider" par défaut du dialogue générique convient ici, mais son
	// libellé "Valider" est trompeur pour une suppression - on le personnalise.
	el("dlgOk").textContent = t("games.delete_confirm_btn");
}

// Édition d'un événement existant (principal/intérêt/date) : réservé aux corrections
// de saisie en cours de partie (typo, oubli...) - recalcule intégralement l'état de
// la partie côté serveur, comme le faisait le menu "Recalcul" de l'app originale.
function openEditEventDialog(e) {
	const t = window.GecoI18n.t;
	const localDatetime = new Date(e.timestamp - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
	openDialog(t("game.edit_event_title", { type: eventTypeLabel(e.type) }) + (e.playerName ? " — " + e.playerName : ""), `
		<label>${t("game.field_principal")}</label>
		<input id="fEditPrincipal" type="number" value="${e.principal}">
		<label>${t("game.field_interest")}</label>
		<input id="fEditInterest" type="number" value="${e.interest}">
		<label>${t("game.edit_event_datetime_label")}</label>
		<input id="fEditTstamp" type="datetime-local" value="${localDatetime}">
		<p style="font-size:0.78rem;color:var(--text-dim);margin-top:0.5rem">
			⚠️ ${t("game.edit_event_warning")}</p>
	`, async () => {
		const tstampInput = el("fEditTstamp").value;
		await Api.editEvent(state.currentGameId, e.id, {
			principal: parseInt(el("fEditPrincipal").value || "0", 10),
			interest: parseInt(el("fEditInterest").value || "0", 10),
			tstamp: tstampInput ? new Date(tstampInput).toISOString() : null,
		});
		renderGameDetail(state.currentGameId);
	});
}

function confirmDeleteEvent(e) {
	const t = window.GecoI18n.t;
	openDialog(t("game.delete_event_title"), `
		<p>${t("game.delete_event_body")}</p>
	`, async () => {
		await Api.deleteEvent(state.currentGameId, e.id);
		renderGameDetail(state.currentGameId);
	});
	el("dlgOk").textContent = t("games.delete_confirm_btn");
}

// ---------- Formulaires d'événement (généraux + par joueur) ----------
// Remonté par un utilisateur : les actions générales de la partie (pas liées à un
// joueur précis) doivent être directement accessibles sur la page, chacune avec son
// propre petit formulaire adapté - plutôt qu'un unique formulaire générique listant
// tous les types d'événements mélangés.
//
// playerMode : "none" (aucun joueur, ex. investissement banque) | "required"
// (sélection obligatoire dans une liste déroulante, ex. un joueur quitte la partie).
function openGeneralEventForm(title, eventType, playerMode) {
	const game = state.currentGame;
	const t = window.GecoI18n.t;
	const playerFieldHtml = playerMode === "required" ? `
		<label>${t("game.field_player_concerned")}</label>
		<select id="fEvtPlayer">
			${sortByName(game.players).map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
		</select>` : "";

	openDialog(title, `
		${playerFieldHtml}
		<label>${t("game.field_principal")}</label>
		<input id="fEvtPrincipal" type="number" value="0">
		<label>${t("game.field_interest")}</label>
		<input id="fEvtInterest" type="number" value="0">
	`, async () => {
		const playerId = playerMode === "required" ? parseInt(el("fEvtPlayer").value, 10) : null;
		await Api.recordEvent(state.currentGameId, {
			type: eventType,
			playerId,
			principal: parseInt(el("fEvtPrincipal").value || "0", 10),
			interest: parseInt(el("fEvtInterest").value || "0", 10),
		});
		renderGameDetail(state.currentGameId);
	});
}

// "Investissement banque" et "Bilan final banque" ont un comportement particulier,
// retrouvé en lisant le code source du programme original (HelperUI.java + Event.java
// sur https://gitlab.com/jytou/geconomicus_helper) : contrairement aux autres
// événements, le montant saisi par l'animateur est stocké dans le champ "intérêt" de
// l'événement (le principal reste toujours à 0), car cette somme provient des
// intérêts déjà gagnés par la banque (pas d'une nouvelle création de monnaie-dette).
// Le formulaire original propose aussi les cartes saisies (faibles/moyennes/fortes),
// absentes de notre ancien formulaire générique - ce qui faisait que le montant saisi
// n'était, en pratique, jamais correctement enregistré. Corrigé ici.
function openBankForm(title, eventType, amountLabel) {
	const t = window.GecoI18n.t;
	openDialog(title, `
		<label>${amountLabel}</label>
		<input id="fBankAmount" type="number" value="0">
		<label>${t("game.field_weak_cards")}</label>
		<input id="fBankWeakCards" type="number" value="0">
		<label>${t("game.field_medium_cards")}</label>
		<input id="fBankMediumCards" type="number" value="0">
		<label>${t("game.field_strong_cards")}</label>
		<input id="fBankStrongCards" type="number" value="0">
	`, async () => {
		await Api.recordEvent(state.currentGameId, {
			type: eventType,
			playerId: null,
			principal: 0,
			interest: parseInt(el("fBankAmount").value || "0", 10),
			weakCards: parseInt(el("fBankWeakCards").value || "0", 10),
			mediumCards: parseInt(el("fBankMediumCards").value || "0", 10),
			strongCards: parseInt(el("fBankStrongCards").value || "0", 10),
		});
		renderGameDetail(state.currentGameId);
	});
}

// "Un joueur quitte la partie" (monnaie dette), précisé par un utilisateur : le
// joueur commence par rembourser la banque s'il a un crédit en cours, PUIS
// l'animateur fait l'inventaire de ce qu'il lui reste (monnaie + cartes par
// valeur) avant qu'il ne quitte réellement. Deux étapes chaînées plutôt qu'un
// simple formulaire générique.
// Remonté par un utilisateur : contrairement aux autres actions générales, un
// échange de troc se fait typiquement EN PLEIN TOUR (voir son câblage sur
// btnTrocTrade, jamais masqué en cours de tour) - c'est le cœur du jeu, pas une
// étape de l'entre-deux-tours. Couvre les deux natures d'échange définies avec
// l'utilisateur (voir docs/10-etape-plugins-troc.md, règles 3 et 4) : bien-
// contre-bien (librement négocié, sans limite de temps) et bien-contre-service
// (seul le fournisseur dépense du temps de vie, jamais le bénéficiaire).
// Remonté par un utilisateur : contrairement aux autres actions générales, un
// échange de troc se fait typiquement EN PLEIN TOUR (voir son câblage sur
// btnTrocTrade, jamais masqué en cours de tour) - c'est le cœur du jeu, pas une
// étape de l'entre-deux-tours. Retour utilisateur : uniquement des
// transactions d'échange bien-contre-bien - jamais de don sans contrepartie,
// jamais de monnaie ni de jeton d'aucune sorte (les échanges de service et le
// temps de vie ont été retirés après un premier essai).
function openTrocTradeDialog() {
	const game = state.currentGame;
	const t = window.GecoI18n.t;
	const activePlayers = sortByName(game.players.filter((p) => p.active));
	if (activePlayers.length < 2) {
		alert(t("game.troc_trade_need_two_players"));
		return;
	}

	openDialog(t("game.action_troc_trade"), `
		<label>${t("game.troc_initiator_label")}</label>
		<select id="trocPlayerA">
			${activePlayers.map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
		</select>
		<label>${t("game.troc_counterparty_label")}</label>
		<select id="trocPlayerB">
			${activePlayers.map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
		</select>
		<label>${t("game.troc_goods_from_initiator_label")}</label>
		<input id="trocGoodsFromA" type="number" value="0" min="0">
		<label>${t("game.troc_goods_from_counterparty_label")}</label>
		<input id="trocGoodsFromB" type="number" value="0" min="0">
		<p id="trocValidationError" style="color:var(--danger);font-size:0.8rem;display:none;margin-top:0.4rem;"></p>
	`, async () => {
		const playerAId = parseInt(el("trocPlayerA").value, 10);
		const playerBId = parseInt(el("trocPlayerB").value, 10);
		if (playerAId === playerBId) {
			alert(t("game.troc_same_player_error"));
			throw new Error("validation"); // empêche openDialog de fermer la boîte de dialogue
		}
		const goodsFromA = parseInt(el("trocGoodsFromA").value || "0", 10);
		const goodsFromB = parseInt(el("trocGoodsFromB").value || "0", 10);
		// Remonté par un utilisateur : uniquement des transactions d'échange -
		// jamais de don sans contrepartie. Les deux joueurs doivent donner
		// quelque chose.
		if ((goodsFromA <= 0) || (goodsFromB <= 0)) {
			const msgEl = el("trocValidationError");
			msgEl.textContent = t("game.troc_one_sided_error");
			msgEl.style.display = "block";
			throw new Error("validation");
		}
		await Api.recordEvent(state.currentGameId, {
			type: "G", playerId: playerAId, counterpartyPlayerId: playerBId,
			goodsFromPlayer: goodsFromA, goodsFromCounterparty: goodsFromB,
		});
		renderGameDetail(state.currentGameId);
	});
}

function openPlayerQuitDialog() {
	const game = state.currentGame;
	const dlg = el("dlg");
	const t = window.GecoI18n.t;

	function renderPlayerSelect() {
		const activePlayers = sortByName(game.players.filter((p) => p.active));
		openDialog(t("game.action_player_quit"), `
			<label>${t("game.quit_player_label")}</label>
			<select id="fQuitPlayer">
				${activePlayers.map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
			</select>
		`, async () => {
			const playerId = parseInt(el("fQuitPlayer").value, 10);
			const player = activePlayers.find((p) => p.id === playerId);
			if (player.curDebt > 0) renderDebtSettlement(player); else renderInventory(player);
		});
	}

	function renderDebtSettlement(player) {
		openDialog(t("game.quit_debt_title", { name: escapeHtml(player.name) }), `
			<p>${t("game.quit_debt_body", { name: escapeHtml(player.name), debt: player.curDebt, interest: player.curInterest })}</p>
			<div style="display:flex;gap:0.6rem;">
				<button type="button" class="btn btn-small" id="fQuitRepay">${t("game.quit_repay_btn")}</button>
				<button type="button" class="btn btn-small" id="fQuitCannotPay">${t("event.type.CANNOT_PAY")}</button>
			</div>
		`, async () => {});
		el("dlgOk").classList.add("hidden"); // deux actions dédiées ci-dessus, pas de "Valider" générique
		el("fQuitRepay").onclick = async () => {
			await Api.recordEvent(state.currentGameId, {
				type: "R", playerId: player.id, principal: player.curDebt, interest: player.curInterest,
			});
			state.currentGame = await Api.getGame(state.currentGameId);
			renderInventory(state.currentGame.players.find((p) => p.id === player.id));
		};
		el("fQuitCannotPay").onclick = () => {
			dlg.close();
			// Réutilise le même formulaire de saisie automatique qu'ailleurs dans
			// l'app - pas de logique dupliquée. L'animateur peut recliquer sur
			// "Un joueur quitte la partie" ensuite pour finaliser l'inventaire de
			// départ une fois la saisie faite. "Annuler" revient à cette étape.
			openPlayerEventDialog(player, { allowedTypes: ["C"], onCancel: () => renderDebtSettlement(player) });
		};
	}

	// Remonté par un utilisateur : cette boîte de dialogue est atteignable
	// depuis n'importe quel système (le test ci-dessus sur curDebt ne
	// déclenche naturellement jamais pour la monnaie libre/le troc, qui n'ont
	// pas de dette - mais la suite du parcours reste la même) - elle affichait
	// pourtant toujours le même formulaire "dette" (monnaie + cartes), jamais
	// adapté. Corrigé pour suivre les mêmes règles que les autres étapes de
	// l'assistant (renderStepAllPlayersMoney / renderEndGameInventory).
	const isDebt = game.moneySystem === 1;
	const isTroc = game.moneySystem === 2;

	function renderInventory(player) {
		if (isTroc) {
			openDialog(t("game.quit_inventory_title", { name: escapeHtml(player.name) }), `
				<p>${t("game.quit_inventory_body", { name: escapeHtml(player.name) })}</p>
				<label>${t("game.field_weak_cards")}</label>
				<input id="fQuitWeak" type="number" value="0" min="0">
				<label>${t("game.field_medium_cards")}</label>
				<input id="fQuitMedium" type="number" value="0" min="0">
				<label>${t("game.field_strong_cards")}</label>
				<input id="fQuitStrong" type="number" value="0" min="0">
			`, async () => {
				const weak = parseInt(el("fQuitWeak").value || "0", 10);
				const medium = parseInt(el("fQuitMedium").value || "0", 10);
				const strong = parseInt(el("fQuitStrong").value || "0", 10);
				await Api.recordEvent(state.currentGameId, {
					type: "Q", playerId: player.id, goodsFromPlayer: weak + medium + strong,
				});
				renderGameDetail(state.currentGameId);
			});
			return;
		}
	// Dette : un seul champ "Monnaie restante" - contrairement aux cartes
	// valeurs (trois niveaux dans les deux systèmes), la monnaie dette n'a
	// jamais eu qu'un seul type de jeton. Vérifié dans le code de l'app Swing
	// d'origine (StatsFrame.addFromEvent) après une tentative erronée
	// d'alignement sur un tableur transmis qui ne reflétait pas fidèlement les
	// règles réelles - voir StatsService.computeGain pour l'historique complet.
	if (isDebt) {
		openDialog(t("game.quit_inventory_title", { name: escapeHtml(player.name) }), `
			<p>${t("game.quit_inventory_body", { name: escapeHtml(player.name) })}</p>
			<label>${t("game.field_remaining_money")}</label>
			<input id="fQuitMoney" type="number" value="0">
			<label>${t("game.field_weak_cards")}</label>
			<input id="fQuitWeak" type="number" value="0">
			<label>${t("game.field_medium_cards")}</label>
			<input id="fQuitMedium" type="number" value="0">
			<label>${t("game.field_strong_cards")}</label>
			<input id="fQuitStrong" type="number" value="0">
		`, async () => {
			await Api.recordEvent(state.currentGameId, {
				type: "Q", playerId: player.id,
				principal: parseInt(el("fQuitMoney").value || "0", 10), interest: 0,
				weakCards: parseInt(el("fQuitWeak").value || "0", 10),
				mediumCards: parseInt(el("fQuitMedium").value || "0", 10),
				strongCards: parseInt(el("fQuitStrong").value || "0", 10),
			});
			renderGameDetail(state.currentGameId);
		});
		return;
	}
	// Monnaie libre : jetons (faible/moyen/fort) ET cartes valeurs séparément -
	// ça, en revanche, correspond bien aux règles réelles (voir
	// StatsService.computeGain, branche libre inchangée).
	openDialog(t("game.quit_inventory_title", { name: escapeHtml(player.name) }), `
		<p>${t("game.quit_inventory_body", { name: escapeHtml(player.name) })}</p>
		<p class="cannot-pay-inventory-title">${t("wiz.death_du_tokens_subtitle")}</p>
		<div class="field-row">
			<div><label>${t("wiz.field_weak_tokens")}</label><input id="fQuitCoinWeak" type="number" value="0" min="0"></div>
			<div><label>${t("wiz.field_medium_tokens")}</label><input id="fQuitCoinMedium" type="number" value="0" min="0"></div>
		</div>
		<label>${t("wiz.field_strong_tokens")}</label>
		<input id="fQuitCoinStrong" type="number" value="0" min="0">
		<p class="cannot-pay-inventory-title" style="margin-top:0.6rem;">${t("wiz.death_du_cards_subtitle")}</p>
		<label>${t("game.field_weak_cards")}</label>
		<input id="fQuitWeak" type="number" value="0" min="0">
		<label>${t("game.field_medium_cards")}</label>
		<input id="fQuitMedium" type="number" value="0" min="0">
		<label>${t("game.field_strong_cards")}</label>
		<input id="fQuitStrong" type="number" value="0" min="0">
	`, async () => {
		await Api.recordEvent(state.currentGameId, {
			type: "Q", playerId: player.id,
			weakCoins: parseInt(el("fQuitCoinWeak").value || "0", 10),
			mediumCoins: parseInt(el("fQuitCoinMedium").value || "0", 10),
			strongCoins: parseInt(el("fQuitCoinStrong").value || "0", 10),
			weakCards: parseInt(el("fQuitWeak").value || "0", 10),
			mediumCards: parseInt(el("fQuitMedium").value || "0", 10),
			strongCards: parseInt(el("fQuitStrong").value || "0", 10),
		});
		renderGameDetail(state.currentGameId);
	});
}

	renderPlayerSelect();
}

// Formulaire d'événement propre à UN joueur précis (3e icône de sa ligne) : le
// joueur est déjà déterminé (pas de sélecteur), et seuls les types d'événements
// pertinents pour un joueur individuel sont proposés (voir PLAYER_EVENT_TYPES).

// Algorithme de saisie automatique ("Ne peut pas payer"), précisé par un
// utilisateur : étant donné ce que le joueur possède (jetons + cartes faibles/
// moyennes/fortes) et le montant que la banque décide de saisir, le programme
// prélève automatiquement dans cet ordre strict : les jetons d'abord, puis les
// cartes fortes (valeur 4), puis moyennes (valeur 2), puis faibles (valeur 1) -
// une carte entière est toujours prélevée en entier (jamais de fraction de
// carte), donc le montant réellement récupéré peut dépasser la cible visée.
function computeAutoSeizure(holdings, targetAmount) {
	let remaining = targetAmount;
	const seizedCoins = Math.max(0, Math.min(remaining, holdings.money));
	remaining -= seizedCoins;

	let seizedStrong = 0;
	while (remaining > 0 && seizedStrong < holdings.strongCards) { seizedStrong++; remaining -= 4; }
	let seizedMedium = 0;
	while (remaining > 0 && seizedMedium < holdings.mediumCards) { seizedMedium++; remaining -= 2; }
	let seizedWeak = 0;
	while (remaining > 0 && seizedWeak < holdings.weakCards) { seizedWeak++; remaining -= 1; }

	const recoveredValue = seizedCoins + seizedStrong * 4 + seizedMedium * 2 + seizedWeak;
	// Remonté par un utilisateur, avec un document de spécification détaillé : une
	// carte saisie en entier peut faire dépasser le montant visé (ex. une carte
	// forte de 4 saisie pour couvrir un reste de 1) - la banque doit alors rendre
	// la différence. Note technique : une répartition automatique en dénominations
	// précises n'est pas fiable ici (l'algorithme épuise toujours les jetons/
	// cartes les plus petits disponibles avant de devoir dépasser la cible avec
	// une carte plus grosse, donc il ne reste quasiment jamais de petites
	// coupures à rendre) - on expose donc le montant du trop-perçu tel quel,
	// l'animateur choisit physiquement comment le rendre.
	const overshoot = Math.max(0, recoveredValue - targetAmount);

	return {
		seizedCoins, seizedStrong, seizedMedium, seizedWeak,
		recoveredValue,
		cardsRemaining: (holdings.weakCards - seizedWeak) + (holdings.mediumCards - seizedMedium)
			+ (holdings.strongCards - seizedStrong),
		overshoot,
	};
}

// Classification automatique Saisie / Banqueroute / Prison, confirmée par
// l'utilisateur avec un exemple précis : une fois la saisie automatique
// calculée (voir computeAutoSeizure ci-dessus) :
//   - si la valeur récupérée ne couvre pas la dette totale (principal + intérêt)
//     → PRISON, le joueur perd tout ce qu'il lui restait ;
//   - sinon, si moins de 4 cartes lui restent → BANQUEROUTE (faillite
//     personnelle, passe un tour) ;
//   - sinon → simple SAISIE, il continue de jouer normalement.
// Précisé par l'utilisateur, avec un document de spécification détaillé : "si un
// joueur qui meurt n'a pas de quoi payer la banque, la banque lui prend tout, le
// joueur ne va pas en prison puisqu'il meurt" - et "on ne retrouve pas le concept
// de banqueroute ou de prison après le dernier tour de la partie" (tout le monde
// quitte de toute façon). Dans ces deux cas, exemptOfStatus=true : la banque
// saisit toujours, mais jamais de classification banqueroute/prison.
function classifyCannotPay(player, seizureResult, exemptOfStatus) {
	const t = window.GecoI18n.t;
	const owed = player.curDebt + player.curInterest;
	if (exemptOfStatus)
		return { code: "C", label: t("event.outcome.seizure_simple_label"), reason: t("event.outcome.seizure_simple_reason") };
	if (seizureResult.recoveredValue < owed)
		return { code: "P", label: t("event.outcome.prison_label"), reason: t("event.outcome.prison_reason") };
	if (seizureResult.cardsRemaining < 4)
		return { code: "B", label: t("event.outcome.bankrupt_label"), reason: t("event.outcome.bankrupt_reason") };
	return { code: "C", label: t("event.outcome.seizure_normal_label"), reason: t("event.outcome.seizure_normal_reason") };
}

// Remonté par un utilisateur : la liste des types proposés dans cette boîte de
// dialogue doit s'adapter au moment de la partie où elle est ouverte - inutile
// (voire trompeur) de proposer "Mort/Renaissance" avant même que la partie ait
// commencé, ou "Ne peut pas payer" en plein milieu d'un tour (ces deux actions
// n'ont de sens qu'à la fin d'un tour, dans l'assistant dédié).
//
// options :
//   allowedTypes  - sous-ensemble de PLAYER_EVENT_TYPES à proposer (défaut : tous)
//   defaultType   - code présélectionné dans la liste (défaut : le premier proposé)
//   prefillPrincipal / prefillInterest - valeurs pré-remplies mais éditables
function openPlayerEventDialog(player, options = {}) {
	const game = state.currentGame;
	const t = window.GecoI18n.t;
	const allowed = options.allowedTypes || PLAYER_EVENT_TYPES;
	const playerTypes = EVENT_TYPES.filter((et) => allowed.includes(et.code));
	const defaultType = options.defaultType && allowed.includes(options.defaultType) ? options.defaultType : playerTypes[0]?.code;
	const typeOptions = playerTypes.map((et) =>
		`<option value="${et.code}" ${et.code === defaultType ? "selected" : ""}>${escapeHtml(t(et.i18nKey))}</option>`).join("");
	const prefillPrincipal = options.prefillPrincipal ?? 0;
	const prefillInterest = options.prefillInterest ?? 0;

	openDialog(t("game.player_dialog_title", { name: escapeHtml(player.name) }), `
		${playerTypes.length > 1 ? `<label>${t("game.field_event_type")}</label><select id="fEvtType">${typeOptions}</select>`
			: `<input type="hidden" id="fEvtType" value="${defaultType}">
			   <p style="font-size:0.85rem;color:var(--text-dim);margin:0 0 0.6rem;">${escapeHtml(playerTypes[0] ? t(playerTypes[0].i18nKey) : "")}</p>`}
		<div id="fNormalFields">
			<label>${t("game.field_principal")}</label>
			<input id="fEvtPrincipal" type="number" value="${prefillPrincipal}">
			<label>${t("game.field_interest")}</label>
			<input id="fEvtInterest" type="number" value="${prefillInterest}">
			<p id="fEvtValidationError" style="color:var(--danger);font-size:0.8rem;display:none;margin-top:0.4rem;"></p>
		</div>
		<div id="fCannotPayFields" class="hidden">
			<p class="cannot-pay-intro"><strong>${t("game.cannotpay_intro")}</strong></p>
			<label>${t("game.field_player_money")}</label>
			<input id="fPlayerMoney" type="number" value="0">
			<label>${t("game.field_player_weak")}</label>
			<input id="fPlayerWeakCards" type="number" value="0">
			<label>${t("game.field_player_medium")}</label>
			<input id="fPlayerMediumCards" type="number" value="0">
			<label>${t("game.field_player_strong")}</label>
			<input id="fPlayerStrongCards" type="number" value="0">
			<label>${t("game.field_seizure_target")}</label>
			<input id="fSeizureTarget" type="number" value="0">
			<p class="cannot-pay-inventory-title">${t("game.cannotpay_inventory_title")}</p>
			<p id="cannotPaySeizureDetail" style="font-size:0.82rem;color:var(--text-dim)"></p>
			<p id="cannotPayOutcome" style="font-size:0.85rem;font-weight:700;margin-top:0.4rem;"></p>
		</div>
	`, async () => {
		const type = el("fEvtType").value;
		if (type === "C") {
			const holdings = {
				money: parseInt(el("fPlayerMoney").value || "0", 10),
				weakCards: parseInt(el("fPlayerWeakCards").value || "0", 10),
				mediumCards: parseInt(el("fPlayerMediumCards").value || "0", 10),
				strongCards: parseInt(el("fPlayerStrongCards").value || "0", 10),
			};
			const target = parseInt(el("fSeizureTarget").value || "0", 10);
			const seizure = computeAutoSeizure(holdings, target);
			const outcome = classifyCannotPay(player, seizure, options.exemptOfStatus);
			await Api.recordEvent(state.currentGameId, {
				type: outcome.code, playerId: player.id, principal: seizure.seizedCoins, interest: 0,
				weakCards: seizure.seizedWeak, mediumCards: seizure.seizedMedium, strongCards: seizure.seizedStrong,
			});
		} else {
			const principal = parseInt(el("fEvtPrincipal").value || "0", 10);
			const interest = parseInt(el("fEvtInterest").value || "0", 10);
			// Remonté par un utilisateur : un remboursement (intérêt seul ou crédit) ne
			// peut pas dépasser ce que le joueur doit réellement, ni dépasser la masse
			// monétaire actuellement en circulation dans le jeu (il ne peut pas exister
			// plus de monnaie que ce qui circule). Bloque la validation avec un message
			// explicite tant que ce n'est pas corrigé, sans fermer le dialogue.
			if (type === "I" || type === "R") {
				const errors = [];
				if (principal > player.curDebt)
					errors.push(t("game.validation_principal_exceeds", { principal, debt: player.curDebt }));
				if (interest > player.curInterest)
					errors.push(t("game.validation_interest_exceeds", { interest, due: player.curInterest }));
				if (principal + interest > game.moneyMass)
					errors.push(t("game.validation_total_exceeds", { total: principal + interest, mass: game.moneyMass }));
				if (errors.length > 0) {
					const msgEl = el("fEvtValidationError");
					msgEl.textContent = t("game.validation_error_prefix") + errors.join(" ; ") + ".";
					msgEl.style.display = "block";
					throw new Error("validation"); // empêche openDialog de fermer la boîte de dialogue
				}
			}
			await Api.recordEvent(state.currentGameId, { type, playerId: player.id, principal, interest });
		}
		// Remonté par un utilisateur : traitée depuis le bilan des joueurs endettés
		// de l'assistant (ex. "Ne peut pas payer" aboutissant à une banqueroute ou
		// une prison), cette boîte de dialogue ne doit PAS fermer tout l'assistant -
		// elle doit reprendre le bilan là où il en était, sinon on se retrouve dans
		// une boucle où l'assistant se referme puis se rouvre automatiquement dès
		// que le compte à rebours est déjà à 0 (endToastShown est réinitialisé par
		// startTurnTimer(), rappelé par renderGameDetail()).
		if (options.onSuccess) {
			await options.onSuccess();
			// Empêche openDialog (qui a appelé cette fonction) de fermer le dialogue
			// après ce onSubmit : on vient de le remettre à jour nous-même (via
			// onSuccess), il doit rester ouvert tel quel.
			throw new Error("handled-by-onSuccess");
		}
		renderGameDetail(state.currentGameId);
	}, options.onCancel);

	// Remonté par un utilisateur : passer à "Remboursement intérêt seul" doit
	// remettre automatiquement le principal à 0 (un remboursement d'intérêt seul
	// ne concerne par définition pas le principal).
	el("fEvtType").addEventListener("change", () => {
		if (el("fEvtType").value === "I") el("fEvtPrincipal").value = "0";
	});

	// Bascule entre les champs "normaux" (principal/intérêt, pour crédit/
	// remboursement...) et les champs dédiés à "Ne peut pas payer" (inventaire du
	// joueur + montant visé par la banque), avec calcul de la saisie automatique et
	// de la classification en direct à chaque saisie.
	function updateCannotPayVisibility() {
		const isCannotPay = el("fEvtType").value === "C";
		el("fCannotPayFields").classList.toggle("hidden", !isCannotPay);
		el("fNormalFields").classList.toggle("hidden", isCannotPay);
	}
	function updateOutcome() {
		if (el("fEvtType").value !== "C") return;
		const holdings = {
			money: parseInt(el("fPlayerMoney").value || "0", 10),
			weakCards: parseInt(el("fPlayerWeakCards").value || "0", 10),
			mediumCards: parseInt(el("fPlayerMediumCards").value || "0", 10),
			strongCards: parseInt(el("fPlayerStrongCards").value || "0", 10),
		};
		const target = parseInt(el("fSeizureTarget").value || "0", 10);
		const seizure = computeAutoSeizure(holdings, target);
		const outcome = classifyCannotPay(player, seizure, options.exemptOfStatus);
		el("cannotPaySeizureDetail").textContent = t("game.seizure_detail", {
			coins: seizure.seizedCoins, strong: seizure.seizedStrong, medium: seizure.seizedMedium,
			weak: seizure.seizedWeak, recovered: seizure.recoveredValue, remaining: seizure.cardsRemaining,
		}) + (seizure.overshoot > 0 ? t("game.seizure_overshoot", { overshoot: seizure.overshoot }) : "");
		el("cannotPayOutcome").textContent = t("game.outcome_line", { label: outcome.label, reason: outcome.reason });
	}
	el("fEvtType").addEventListener("change", () => { updateCannotPayVisibility(); updateOutcome(); });
	document.getElementById("fCannotPayFields").addEventListener("input", updateOutcome);
	updateCannotPayVisibility();
	updateOutcome();
}

// Renommage d'un joueur (icône crayon) : la modification est envoyée au serveur, qui
// fait foi pour tout le monde - pas de copie locale du nom à synchroniser ensuite.
function openRenamePlayerDialog(player) {
	const t = window.GecoI18n.t;
	openDialog(t("game.rename_player_title", { name: escapeHtml(player.name) }), `
		<label>${t("game.rename_player_label")}</label>
		<input id="fNewPlayerName" type="text" value="${escapeHtml(player.name)}">
		<p id="renameError" style="color:var(--danger);font-size:0.8rem;display:none;margin-top:0.5rem;"></p>
	`, async () => {
		try {
			await Api.renamePlayer(state.currentGameId, player.id, el("fNewPlayerName").value.trim());
			renderGameDetail(state.currentGameId);
		} catch (err) {
			// Erreur la plus probable : nom déjà pris - on la montre sans fermer le
			// dialogue, pour laisser une nouvelle chance de corriger la saisie.
			const msgEl = el("renameError");
			msgEl.textContent = t("game.rename_player_error");
			msgEl.style.display = "block";
			throw err; // empêche openDialog de fermer la boîte de dialogue
		}
	});
}

function confirmDeletePlayer(player) {
	const t = window.GecoI18n.t;
	openDialog(t("game.delete_player_title", { name: escapeHtml(player.name) }), `
		<p>${t("game.delete_player_body", { name: escapeHtml(player.name) })}</p>
	`, async () => {
		await Api.deletePlayer(state.currentGameId, player.id);
		renderGameDetail(state.currentGameId);
	});
	el("dlgOk").textContent = t("games.delete_confirm_btn");
}

async function renderGamesList() {
	stopTurnTimer();
	setMoneyTheme(1); // écran neutre : thème par défaut (dette)
	showView("view-games");
	el("navHome").classList.remove("hidden");
	el("navGame").classList.add("hidden");
	document.querySelectorAll("#navHome .active").forEach((b) => b.classList.remove("active"));
	el("navNewGame").classList.add("active");
	state.currentGameId = null;
	updateNewGameSummary();
	renderMoneyTypeChoices();

	const games = await Api.listGames();
	const container = el("gamesList");
	container.innerHTML = "";
	if (games.length === 0) {
		container.innerHTML = `<p style="color:var(--text-dim)">${window.GecoI18n.t("games.empty_new")}</p>`;
		el("btnSeeAllGames").classList.add("hidden");
		return;
	}
	// Les 5 parties les plus récentes seulement ici (l'ID croissant reflète l'ordre
	// de création) ; le reste est accessible via "Voir + →" -> renderAllGames().
	const recent = [...games].reverse().slice(0, 5);
	for (const g of recent) {
		container.appendChild(buildGameCard(g, false));
	}
	el("btnSeeAllGames").classList.toggle("hidden", games.length <= 5);
}

// ---------- Vue "Parties récentes" complète (toutes les parties, avec suppression) ----------
async function renderAllGames() {
	stopTurnTimer();
	setMoneyTheme(1);
	showView("view-all-games");
	el("navHome").classList.remove("hidden");
	el("navGame").classList.add("hidden");
	document.querySelectorAll("#navHome .active").forEach((b) => b.classList.remove("active"));
	el("navRecent").classList.add("active");
	state.currentGameId = null;

	const games = await Api.listGames();
	const container = el("allGamesList");
	container.innerHTML = "";
	if (games.length === 0) {
		container.innerHTML = `<p style="color:var(--text-dim)">${window.GecoI18n.t("games.empty_all")}</p>`;
		return;
	}
	const sorted = [...games].reverse(); // plus récentes en premier
	for (const g of sorted) {
		container.appendChild(buildGameCard(g, true));
	}
}

// ---------- Vue "Comparer des parties" (menu Statistiques, hors partie) ----------
// Équivalent web de ChooseGamesDialog + StatsFrame(List<Game>) côté Swing : compare
// N parties (typiquement une en monnaie dette et une en monnaie libre, jouées avec
// le même groupe de joueurs) joueur par joueur, en les recoupant par nom.
let compareReport = null;
let compareMode = "standard"; // "standard" | "corrected"

async function renderCompareView() {
	stopTurnTimer();
	showView("view-compare");
	el("navHome").classList.remove("hidden");
	el("navGame").classList.add("hidden");
	document.querySelectorAll("#navHome .active").forEach((b) => b.classList.remove("active"));
	el("navCompare").classList.add("active");
	state.currentGameId = null;
	compareReport = null;
	el("compareResults").classList.add("hidden");
	const t = window.GecoI18n.t;

	const games = await Api.listGames();
	const container = el("compareGamesList");
	const runBtn = el("btnRunComparison");
	container.innerHTML = "";
	runBtn.disabled = true;
	if (games.length === 0) {
		container.innerHTML = `<p style="color:var(--text-dim)">${t("games.empty_all")}</p>`;
		return;
	}
	const sorted = [...games].reverse(); // plus récentes en premier
	for (const g of sorted) {
		const isDebt = g.moneySystem === 1; // Game.MONEY_DEBT = 1 côté moteur
		const isTroc = g.moneySystem === 2; // Game.MONEY_TROC = 2 côté moteur
		// Remonté par un utilisateur : même oubli que PLUGIN_ID_TO_MONEY_SYSTEM et
		// buildGameCard ci-dessus - ce badge ne connaissait que deux systèmes.
		const badgeClass = isDebt ? "debt" : (isTroc ? "troc" : "libre");
		const badgeLabel = isDebt ? t("compare.badge_debt") : (isTroc ? t("compare.badge_troc") : t("compare.badge_libre"));
		const label = document.createElement("label");
		label.className = "compare-game-item";
		label.innerHTML = `
			<input type="checkbox" value="${g.id}">
			<span class="compare-game-badge ${badgeClass}">${badgeLabel}</span>
			<span><strong>${escapeHtml(g.description || t("compare.unnamed_game"))}</strong>
			<span class="compare-game-meta"> — ${escapeHtml(g.curdate || "")}${g.location ? " · " + escapeHtml(g.location) : ""}</span></span>`;
		container.appendChild(label);
	}
	container.querySelectorAll('input[type="checkbox"]').forEach((cb) => {
		cb.addEventListener("change", () => {
			runBtn.disabled = container.querySelectorAll('input[type="checkbox"]:checked').length === 0;
		});
	});
	runBtn.onclick = async () => {
		const ids = [...container.querySelectorAll('input[type="checkbox"]:checked')].map((cb) => cb.value);
		runBtn.disabled = true;
		runBtn.textContent = t("compare.running");
		try {
			compareReport = await Api.getComparison(ids);
			compareMode = "standard";
			el("compareResults").classList.remove("hidden");
			renderComparisonChart();
		} finally {
			runBtn.disabled = false;
			runBtn.textContent = t("compare.run_btn");
		}
	};
}

// Dessine l'histogramme groupé (une barre par joueur et par partie), sur le modèle
// des onglets "Aggrégés standards"/"Aggrégés corrigés" de StatsFrame - la banque
// (si présente, seulement pour les parties en monnaie dette) apparaît toujours en
// dernière position, comme dans l'original.
function renderComparisonChart() {
	if (!compareReport) return;
	const t = window.GecoI18n.t;
	const rows = compareMode === "standard" ? compareReport.standard : compareReport.corrected;
	el("btnCompareStandard").classList.toggle("btn-accent", compareMode === "standard");
	el("btnCompareCorrected").classList.toggle("btn-accent", compareMode === "corrected");
	el("compareExplainer").textContent = compareMode === "standard"
		? t("compare.explainer_standard")
		: t("compare.explainer_corrected");

	const labels = rows.map((r) => r.playerName);
	const datasets = compareReport.games.map((g, i) => ({
		label: g.label,
		data: rows.map((r) => r.valuesPerGame[i]),
		// Remonté par un utilisateur : couleurs alignées sur celles utilisées partout
		// ailleurs dans l'application (bleu pour la monnaie dette, vert pour la
		// monnaie libre - voir --accent-debt/--accent-libre dans style.css), plutôt
		// que les couleurs orange/vert de l'app Swing d'origine utilisées ici par
		// erreur de cohérence.
		backgroundColor: g.isDebt ? "#2563eb" : "#16a34a",
	}));

	if (reportCharts.compare) reportCharts.compare.destroy();
	reportCharts.compare = trackChart(el("chartCompare"), {
		type: "bar",
		data: { labels, datasets },
		options: {
			responsive: true,
			maintainAspectRatio: false,
			plugins: { legend: { display: true, position: "top" } },
			scales: { y: { beginAtZero: true } },
		},
	});
}

// ---------- Vue détail d'une partie ----------
async function renderGameDetail(gameId) {
	// state.turnEnded n'est pas propre à une partie précise (simple indicateur
	// côté client) : en cas de navigation vers une AUTRE partie sans recharger la
	// page, on le réinitialise pour ne pas hériter par erreur de l'état de la
	// partie précédemment consultée. On repart sur l'hypothèse la plus prudente
	// ("en plein tour", options restreintes) plutôt que "entre-deux-tours"
	// (toutes options), pour ne jamais proposer par erreur des actions qui
	// n'auraient pas de sens à ce moment précis.
	if (state.currentGameId !== gameId) state.turnEnded = false;
	state.currentGameId = gameId;
	showView("view-game");
	el("navHome").classList.add("hidden");
	el("navGame").classList.remove("hidden");

	const game = await Api.getGame(gameId);
	state.currentGame = game;
	setMoneyTheme(game.moneySystem);
	const t = window.GecoI18n.t;

	// Étape 3 : le bouton "Inviter" (QR d'inscription joueur) n'a de sens
	// qu'en mode smartphone - remonté par l'utilisateur ("il faudrait que ce
	// QR code n'apparaisse que si on a cliqué la partie au smartphone dans le
	// paramètre"). Relit les réglages à chaque affichage plutôt que de se
	// fier à un état mis en cache : ce bouton doit refléter le réglage
	// COURANT même si l'animateur ne s'est jamais rendu sur l'écran
	// Paramètres depuis le démarrage de l'application.
	Api.getSettings().then((settings) => {
		const isSmartphoneMode = settings.gameMode === "smartphone";
		el("btnInvitePlayers").classList.toggle("hidden", !isSmartphoneMode);
		if (!isSmartphoneMode) {
			el("inviteQrPanel").classList.add("hidden");
			el("inviteQrPanel").innerHTML = "";
		}
		// Étape 3 : historique des échanges par QR - même filtre que le bouton
		// "Inviter" ci-dessus (voir renderTransactionsPanel).
		el("transactionsPanelCard").classList.toggle("hidden", !isSmartphoneMode);
		if (!isSmartphoneMode) {
			el("transactionsPanelBody").classList.add("hidden");
			el("transactionsPanelBody").innerHTML = "";
		}
	});

	const isDebt = game.moneySystem === 1;
	// Troc (voir Game.MONEY_TROC, plugins/troc/manifest.json) : ni banque, ni
	// masse monétaire, ni crédits - plusieurs blocs du tableau de bord n'ont donc
	// aucun sens dans ce système et sont masqués ci-dessous.
	const isTroc = game.moneySystem === 2;
	el("gameTitle").textContent = game.description || t("games.untitled", { id: game.id });
	el("gameMoneyIcon").dataset.icon = isDebt ? "bank" : (isTroc ? "users" : "leaf");
	el("gameMoneyIcon").className = "money-choice-icon " + (isDebt ? "money-debt-color" : (isTroc ? "money-neutral-color" : "money-libre-color"));
	renderIcons(el("gameMoneyIcon"));
	el("turnBadge").textContent = t("game.turn_badge", { turn: game.turnNumber, total: game.nbTurnsPlanned });

	// Remonté par un utilisateur : affiche le code PIN de cette partie (si elle
	// en a un - voir Game.pin), pour que l'animateur puisse le communiquer
	// facilement (co-animateur, changement d'appareil...) sans avoir à le
	// retrouver ailleurs. Cliquer copie le code dans le presse-papiers.
	const pinBadge = el("gamePinBadge");
	if (game.pin) {
		pinBadge.textContent = `🔒 ${game.pin}`;
		pinBadge.classList.remove("hidden");
		pinBadge.onclick = () => {
			navigator.clipboard.writeText(game.pin).then(() => {
				pinBadge.textContent = `✓ ${t("game.pin_copied")}`;
				setTimeout(() => { pinBadge.textContent = `🔒 ${game.pin}`; }, 1500);
			});
		};
	} else {
		pinBadge.classList.add("hidden");
	}

	// Remonté par un utilisateur : le chrono ne doit être visible/actif qu'une fois
	// la partie explicitement démarrée (turnStartedAtEpochMs vaut 0 tant que ça n'a
	// pas été fait), pas dès l'arrivée sur cet écran.
	const started = game.turnStartedAtEpochMs > 0;
	// Remonté par un utilisateur (capture d'écran à l'appui) : une fois l'événement
	// "Fin de partie" enregistré (via l'assistant de fin de partie au dernier
	// tour), rien n'empêchait jusqu'ici de continuer à utiliser les contrôles de
	// tour comme si de rien n'était - la partie semblait "reprendre" un nouveau
	// tour alors qu'elle est définitivement terminée. Détecté ici directement
	// depuis l'historique (présence d'un événement de type "E"), plutôt que
	// d'ajouter un nouveau champ côté serveur pour ça.
	const gameEnded = game.events.some((e) => e.type === "END");
	el("btnStartGame").classList.toggle("hidden", started || gameEnded);
	el("turnTimer").classList.toggle("hidden", !started || gameEnded);
	el("btnTimerPause").classList.toggle("hidden", !started || gameEnded);
	el("btnTimerExtend").classList.toggle("hidden", !started || gameEnded);
	if (started && !gameEnded) {
		startTurnTimer(game);
	} else {
		stopTurnTimer();
	}

	// Remonté par un utilisateur (avec un schéma très clair à l'appui) : "Fin de
	// tour" et "Nouveau tour" sont deux actions bien distinctes, pas une seule.
	// "Fin de tour" ouvre le bilan (remboursements/morts/nouveaux crédits) ;
	// "Nouveau tour" est un simple bouton, sans popup, qui enregistre l'événement
	// et relance le chrono. Le serveur ne distingue pas encore ces deux états
	// (rien ne change côté API entre "tour en cours" et "bilan de tour terminé,
	// en attente du prochain tour") : state.turnEnded est donc un indicateur
	// purement côté client, remis à false à chaque chargement frais de la partie.
	if (started && !gameEnded) {
		el("btnEndTurn").classList.toggle("hidden", state.turnEnded);
		el("btnStartNewTurn").classList.toggle("hidden", !state.turnEnded);
		el("btnStartNewTurn").disabled = false; // réactivé à chaque rendu frais de la partie
	} else {
		el("btnEndTurn").classList.add("hidden");
		el("btnStartNewTurn").classList.add("hidden");
	}
	// Remonté par un utilisateur : "Un joueur quitte la partie" et "Fin de partie"
	// n'ont de sens qu'à l'entre-deux-tours, pas en plein milieu d'un tour actif -
	// et plus du tout une fois la partie officiellement terminée.
	const midTurn = started && !state.turnEnded;
	el("btnPlayerQuit").classList.toggle("hidden", midTurn || gameEnded);
	el("btnEndGame").classList.toggle("hidden", midTurn || gameEnded);
	// Les 4 autres actions générales (masse monétaire, rupture techno,
	// investissement/bilan banque) n'ont pas non plus de raison d'être une fois la
	// partie terminée.
	el("btnMoneyMassChange").classList.toggle("hidden", gameEnded || isTroc);
	el("btnTechBreakthrough").classList.toggle("hidden", gameEnded || isTroc);
	// Remonté par un utilisateur, avec un document de spécification détaillé :
	// pas de banque en monnaie libre ni en troc (pas de crédit, pas d'intérêts) -
	// ces deux boutons n'ont donc jamais de sens dans ces systèmes, indépendamment
	// de la fin de partie.
	el("btnBankInvestment").classList.toggle("hidden", gameEnded || !isDebt);
	el("btnBankAssessment").classList.toggle("hidden", gameEnded || !isDebt);
	// Remplace la zone du chrono par un message explicite, pour que ce soit
	// visuellement évident que la partie est terminée plutôt que de laisser un
	// vide silencieux à la place du chrono habituel.
	let endedBanner = el("gameEndedBanner");
	if (gameEnded) {
		if (!endedBanner) {
			endedBanner = document.createElement("span");
			endedBanner.id = "gameEndedBanner";
			endedBanner.className = "badge-pill game-ended-banner";
			el("turnBadge").insertAdjacentElement("afterend", endedBanner);
		}
		endedBanner.textContent = "🏁 " + t("game.ended_banner");
	} else if (endedBanner) {
		endedBanner.remove();
	}

	// Cartes statistiques (données réelles - voir Dtos.GameDetailDto côté serveur)
	el("statPlayers").textContent = game.activePlayersCount;
	// Remonté par un utilisateur : l'âge suivi côté serveur (GameDetailDto.avgAge)
	// est un nombre de TOURS écoulés depuis la naissance/renaissance - affiché ici
	// converti en années simulées (convention du jeu : 1 tour = 8 ans, la même
	// que pour "années de vie" ailleurs dans l'appli), pour ne jamais afficher un
	// chiffre ambigu sans préciser son unité.
	el("statAge").textContent = (game.avgAge * 8).toFixed(1);
	el("statMass").textContent = game.moneyMass;
	el("statCredits").textContent = game.totalCreditsOutstanding;
	// Remonté par un utilisateur : ni masse monétaire ni banque en troc - cette
	// carte n'a de sens qu'en monnaie dette/libre.
	el("statMassCard").classList.toggle("hidden", isTroc);
	// Remonté par un utilisateur, avec un document de spécification détaillé :
	// pas de crédit en monnaie libre ni en troc - cette carte n'a donc de sens
	// qu'en monnaie dette.
	el("statCreditsCard").classList.toggle("hidden", !isDebt);
	// Remonté par un utilisateur : statistique propre à la monnaie dette (la
	// monnaie libre a déjà son propre suivi via le module Galilée du rapport,
	// et le troc n'a pas de monnaie du tout).
	el("statAvgMoneyCard").classList.toggle("hidden", !isDebt);
	if (isDebt) {
		const avg = game.activePlayersCount > 0 ? Math.round(game.moneyMass / game.activePlayersCount) : 0;
		el("statAvgMoney").textContent = avg;
	}
	// Remonté par un utilisateur : "Reste à changer" d'un point de vue global,
	// puisqu'on connaît le DU. Calcul retrouvé dans le moteur (voir le traitement
	// de l'événement TOUR en monnaie libre) : la masse monétaire converge vers une
	// cible théorique de 7 × facteur carte/monnaie × nombre de joueurs actifs -
	// "reste à changer" est l'écart restant entre la masse actuelle et cette
	// cible. Vue globale pour l'instant (pas encore par joueur individuellement,
	// ce que permettra le suivi individuel prévu à l'étape 3). N'a de sens qu'en
	// monnaie libre (ni en dette, ni en troc qui n'a pas de masse monétaire).
	el("statChangeCard").classList.toggle("hidden", isDebt || isTroc);
	if (!isDebt && !isTroc) {
		const target = 7 * game.moneyCardsFactor * game.activePlayersCount;
		el("statChange").textContent = target - game.moneyMass;
	}
	// Troc : pas de masse monétaire, mais un chiffre qui lui est propre - le
	// nombre total de biens en circulation (somme des inventaires de tous les
	// joueurs actifs).
	el("statGoodsCard").classList.toggle("hidden", !isTroc);
	if (isTroc) {
		const activePlayers = game.players.filter((p) => p.active);
		el("statGoods").textContent = activePlayers.reduce((s, p) => s + p.goodsCount, 0);
	}
	// Bouton "Échange entre joueurs" : propre au troc. Contrairement aux autres
	// actions générales ci-dessus, un échange se fait typiquement EN PLEIN TOUR
	// (c'est le cœur du jeu) - jamais masqué en cours de tour, seulement en fin
	// de partie.
	el("btnTrocTrade").classList.toggle("hidden", gameEnded || !isTroc);
	// Remonté par un utilisateur : les onglets "Joueurs" et "Événements" ne font
	// que masquer l'un des deux panneaux déjà visibles sur "Partie en cours" - en
	// monnaie dette, jugés redondants et retirés. Si on quitte ces onglets alors
	// qu'ils étaient actifs (changement de partie), on s'assure de revenir sur
	// "Partie en cours" pour ne pas laisser le panneau masqué.
	el("navTabPlayers").classList.toggle("hidden", isDebt);
	el("navTabEvents").classList.toggle("hidden", isDebt);
	if (isDebt) {
		document.querySelectorAll(".game-layout > .panel-card").forEach((panel) => panel.classList.remove("hidden"));
		document.querySelectorAll("#navGame .active").forEach((b) => b.classList.remove("active"));
		document.querySelector('#navGame [data-tab="overview"]').classList.add("active");
	}

	// Statut d'un joueur, remonté par un utilisateur (prison, banqueroute, saisie,
	// crédit, mort/renaissance...). Dérivé des événements survenus DEPUIS le dernier
	// tour (pas d'un champ persistant : DEATH/PRISON/BANKRUPT ne modifient pas
	// "active" dans le moteur - vérifié dans le code - seul QUIT le fait). Un
	// joueur mort/en prison/en banqueroute ce tour-ci "passe son tour" jusqu'au
	// prochain événement TURN, qui efface ce statut.
	function getPlayerStatusBadge(player, game) {
		const sorted = [...game.events].sort((a, b) => a.timestamp - b.timestamp);
		let lastTurnIndex = -1;
		sorted.forEach((e, i) => { if (e.type === "TURN") lastTurnIndex = i; });
		const sinceLastTurn = sorted.slice(lastTurnIndex + 1).filter((e) => e.playerId === player.id);
		const hasType = (t) => sinceLastTurn.some((e) => e.type === t);

		if (hasType("DEATH")) return { text: "💀 " + t("game.status_dead"), cls: "status-dead" };
		if (hasType("PRISON")) return { text: "🔒 " + t("game.status_prison"), cls: "status-prison" };
		if (hasType("BANKRUPT")) return { text: "📉 " + t("game.status_bankrupt"), cls: "status-bankrupt" };
		if (player.curDebt > 0 && !player.visitedBank) return { text: "🔴 " + t("game.status_must_visit_bank"), cls: "status-bank" };
		if (player.curDebt > 0) return { text: "💳 " + t("game.status_has_credit"), cls: "status-credit" };
		return null;
	}

	const playersList = el("playersList");
	playersList.innerHTML = "";
	// Remonté par un utilisateur : toujours afficher les joueurs par ordre
	// alphabétique, en début de partie comme en cours de jeu.
	const sortedPlayers = [...game.players].sort((a, b) => a.name.localeCompare(b.name, "fr"));
	for (const p of sortedPlayers) {
		const li = document.createElement("li");
		const status = isDebt ? getPlayerStatusBadge(p, game) : null;
		const meta = isTroc
			? t("game.player_meta_troc", { goods: p.goodsCount })
			: isDebt
				? t("game.player_meta", { age: p.age, debt: p.curDebt, interest: p.curInterest })
				: t("game.player_meta_libre", { age: p.age });
		li.innerHTML = `
			<span class="player-name ${p.active ? "" : "player-inactive"}">${escapeHtml(p.name)}</span>
			<span class="event-row-actions">
				${status ? `<span class="status-badge ${status.cls}">${status.text}</span>` : ""}
				<span class="event-meta">${meta}</span>
				<button type="button" class="event-action-btn" title="${escapeHtml(t("game.player_rename_title"))}">✎</button>
				<button type="button" class="event-action-btn event-action-delete" title="${escapeHtml(t("game.player_delete_title"))}">✕</button>
				${isDebt ? `<button type="button" class="event-action-btn" title="${escapeHtml(t("game.player_add_event_title"))}">+</button>` : ""}
				${p.accessToken ? `<button type="button" class="event-action-btn" title="${escapeHtml(t("game.player_copy_link_title"))}">🔗</button>` : ""}
			</span>`;
		const buttons = li.querySelectorAll(".event-action-btn");
		const renameBtn = buttons[0];
		const deleteBtn = buttons[1];
		// Remonté par un utilisateur : lien individuel du joueur (voir Player.accessToken)
		// - le bouton "+" (événement pour ce joueur) n'existe qu'en monnaie dette, donc sa
		// position dans la liste des boutons dépend du système ; on prend toujours le
		// DERNIER bouton présent pour le lien, plutôt qu'un index fixe qui se déciderait
		// mal selon les cas.
		const linkBtn = p.accessToken ? buttons[buttons.length - 1] : null;
		const eventBtn = isDebt ? buttons[2] : null; // absent (undefined) en monnaie libre/troc
		renameBtn.onclick = () => openRenamePlayerDialog(p);
		deleteBtn.onclick = () => confirmDeletePlayer(p);
		if (linkBtn) linkBtn.onclick = async () => {
			const link = await buildPlayerLink(game.id, p.accessToken);
			navigator.clipboard.writeText(link).then(() => {
				linkBtn.textContent = "✓";
				setTimeout(() => { linkBtn.textContent = "🔗"; }, 1500);
			});
		};
		// Remonté par un utilisateur, avec un document de spécification détaillé :
		// pas de crédit/remboursement/mort géré au niveau d'un joueur individuel en
		// monnaie libre (tout passe par l'assistant de fin de tour, étape DU) -
		// l'icône "+" n'a donc pas lieu d'être dans ce système monétaire.
		if (eventBtn) eventBtn.onclick = () => {
			// Remonté par un utilisateur : la partie n'a pas encore commencé (avant le
			// premier "Démarrer la partie") -> seul un nouveau crédit a du sens (pas de
			// mort/renaissance ni de remboursement possible avant que la partie tourne).
			if (!started) {
				openPlayerEventDialog(p, { allowedTypes: ["N"], prefillPrincipal: 3, prefillInterest: 1 });
				return;
			}
			// Remonté par un utilisateur : pour un remboursement, pré-remplir avec ce
			// que le joueur doit RÉELLEMENT (pas un générique 3/1) - s'il doit 0
			// d'intérêt, le champ affiche bien 0, pas une valeur par défaut trompeuse.
			// Le générique 3/1 ne reste utile que pour "Nouveau crédit", où il n'y a par
			// définition rien à déduire de l'état actuel du joueur.
			// Remonté par un utilisateur : si sa dette totale dépasse la masse
			// monétaire actuellement en circulation (il ne peut de toute façon pas
			// rembourser plus que ce qui existe dans le jeu), les champs restent vides
			// par défaut plutôt que pré-remplis - une valeur pré-remplie mais
			// impossible à honorer serait trompeuse.
			const isRepay = p.curDebt > 0;
			const debtExceedsMoneyMass = p.curDebt + p.curInterest > game.moneyMass;
			// Remonté par un utilisateur : à l'entre-deux-tours (assistant terminé, ou
			// fermé prématurément et donc administré manuellement), TOUTES les options
			// redeviennent accessibles - sinon l'animateur serait bloqué. "Banqueroute"
			// et "Prison" restent volontairement absents de la liste manuelle (décision
			// confirmée précédemment : c'est le programme qui les détermine
			// automatiquement via "Ne peut pas payer", pas l'animateur).
			openPlayerEventDialog(p, {
				allowedTypes: state.turnEnded ? PLAYER_EVENT_TYPES : ["N", "I", "R"],
				defaultType: isRepay ? "R" : "N",
				prefillPrincipal: debtExceedsMoneyMass ? "" : (isRepay ? p.curDebt : 3),
				prefillInterest: debtExceedsMoneyMass ? "" : (isRepay ? p.curInterest : 1),
			});
		};
		playersList.appendChild(li);
	}

	const eventsList = el("eventsList");
	eventsList.innerHTML = "";
	const sortedEvents = [...game.events].sort((a, b) => b.timestamp - a.timestamp);
	for (const e of sortedEvents) {
		const li = document.createElement("li");
		li.innerHTML = `
			<span><span class="event-type">${escapeHtml(eventTypeLabel(e.type))}</span>
				${e.playerName ? " — " + escapeHtml(e.playerName) : ""}</span>
			<span class="event-row-actions">
				<span class="event-meta">${new Date(e.timestamp).toLocaleTimeString()}</span>
				<button type="button" class="event-action-btn" title="${escapeHtml(t("game.event_edit_title"))}">✎</button>
				<button type="button" class="event-action-btn event-action-delete" title="${escapeHtml(t("game.event_delete_title"))}">✕</button>
			</span>`;
		const [editBtn, deleteBtn] = li.querySelectorAll(".event-action-btn");
		editBtn.onclick = () => openEditEventDialog(e);
		deleteBtn.onclick = () => confirmDeleteEvent(e);
		eventsList.appendChild(li);
	}

	// Graphiques (Phase B) : requête séparée, calculée à la volée côté serveur à
	// partir de l'historique réel des événements (voir StatsService.java).
	try {
		const stats = await Api.getStats(gameId);
		renderCharts(stats, isDebt, game);
	} catch (err) {
		console.error("Impossible de charger les statistiques :", err);
	}
}

// ---------- Graphiques (Chart.js, hébergé localement dans js/vendor/ - voir index.html) ----------
function renderCharts(stats, isDebt, game) {
	const t = window.GecoI18n.t;
	// Garde défensive : si le CDN de Chart.js est bloqué (pare-feu, proxy d'entreprise,
	// absence de connexion internet...), "Chart" n'existe pas. Plutôt qu'une zone
	// blanche silencieuse et incompréhensible, on l'indique clairement.
	if (typeof Chart === "undefined") {
		document.querySelectorAll(".chart-container, .chart-container-small").forEach((c) => {
			c.innerHTML = `<p style="color:var(--text-dim);font-size:0.8rem;padding:1rem">${t("game.chart_unavailable")}</p>`;
		});
		return;
	}

	const isTroc = game.moneySystem === 2;
	const accent = isDebt ? "#2563eb" : (isTroc ? "#6b7280" : "#16a34a");
	const accentBg = isDebt ? "rgba(37,99,235,0.12)" : "rgba(22,163,74,0.12)";

	// Troc (voir Game.MONEY_TROC) : pas de masse monétaire du tout - ce graphique
	// n'a donc pas de sens dans ce système, contrairement aux deux autres.
	el("chartMoneyMassPanel").classList.toggle("hidden", isTroc);

	// Remonté par un utilisateur : "les graphiques semblent ne pas fonctionner...
	// soit le faire fonctionner, soit les masquer". Le typeof Chart ci-dessus ne
	// couvre que le cas "bibliothèque pas chargée" ; ce try/catch couvre TOUTE
	// autre erreur possible (donnée inattendue, etc.) - dans tous les cas, on
	// n'affiche jamais une zone vide sans explication : soit le graphique
	// fonctionne, soit un message clair apparaît à la place.
	try {
		if (!isTroc) {
			// Détruire l'instance précédente avant d'en recréer une (Chart.js lève une
			// erreur "Canvas is already in use" sinon, puisqu'on réutilise le même
			// <canvas> à chaque rafraîchissement de la vue de partie). La courbe de
			// masse monétaire a une échelle temporelle (un point par tour) qui ne se
			// prête pas à une simple mise à jour en place, contrairement au diagramme
			// crédits/dettes ci-dessous.
			if (state.charts.moneyMass) state.charts.moneyMass.destroy();

			state.charts.moneyMass = trackChart(el("chartMoneyMass"), {
				type: "line",
				data: {
					labels: stats.moneyMassHistory.map((p) => t("game.chart_turn_label", { n: p.turn })),
					datasets: [{
						data: stats.moneyMassHistory.map((p) => p.moneyMass),
						borderColor: accent,
						backgroundColor: accentBg,
						fill: true,
						tension: 0.3,
						pointRadius: 2,
					}],
				},
				options: {
					plugins: { legend: { display: false } },
					scales: { y: { beginAtZero: true } },
					maintainAspectRatio: false,
				},
			});
		}

		// Remonté par un utilisateur, avec un document de spécification détaillé :
		// même style "camembert" nommé (part en %) que la richesse par joueur en
		// monnaie libre ci-dessous, plutôt qu'un simple diagramme en barres - la
		// donnée réellement suivie en direct en monnaie dette est le crédit en
		// cours de chaque joueur (voir la limite honnête documentée plus bas pour
		// la monnaie libre, où c'est la richesse évaluée), donc c'est elle qui
		// alimente ce camembert : il s'ajuste de façon animée au fur et à mesure
		// que les crédits sont remboursés.
		if (isDebt) {
			el("chartWealthTitle").textContent = t("game.chart_wealth_title_debt");
			el("chartWealthInfo").title = t("game.chart_wealth_info_debt");
			const activePlayers = sortByName(game.players.filter((p) => p.active));
			const debts = activePlayers.map((p) => p.curDebt);
			const totalDebt = debts.reduce((s, d) => s + d, 0);
			const palette = ["#2563eb", "#93c5fd", "#f59e0b", "#fbbf24", "#dc2626", "#f87171", "#16a34a", "#86efac", "#7c3aed", "#c4b5fd"];
			const colors = activePlayers.map((p, i) => palette[i % palette.length]);
			if (state.charts.wealth && state.charts.wealth.config.type === "doughnut") {
				state.charts.wealth.data.labels = activePlayers.map((p) => p.name);
				state.charts.wealth.data.datasets[0].data = debts;
				state.charts.wealth.data.datasets[0].backgroundColor = colors;
				state.charts.wealth.update();
			} else {
				if (state.charts.wealth) state.charts.wealth.destroy();
				state.charts.wealth = trackChart(el("chartWealth"), {
					type: "doughnut",
					data: {
						labels: activePlayers.map((p) => p.name),
						datasets: [{ data: debts, backgroundColor: colors, borderWidth: 0 }],
					},
					options: { plugins: { legend: { display: false } }, maintainAspectRatio: false, cutout: "65%",
						animation: { duration: 500 } },
				});
			}
			const legend = el("wealthLegend");
			legend.innerHTML = activePlayers.length === 0
				? `<li style="color:var(--text-dim);font-size:0.8rem;">${t("game.legend_no_active_players")}</li>`
				: (totalDebt === 0
					? `<li style="color:var(--text-dim);font-size:0.8rem;">${t("game.legend_no_credit")}</li>`
					: activePlayers.map((p, i) => {
						const pct = Math.round((debts[i] / totalDebt) * 1000) / 10;
						return `<li><span><span class="swatch" style="background:${colors[i]}"></span>${escapeHtml(p.name)}</span>
							<strong>${pct}%</strong></li>`;
					}).join(""));
		} else if (isTroc) {
			// Troc : le nombre d'objets possédés est suivi en direct sur chaque
			// joueur (voir Player.goodsCount, mis à jour à chaque échange) - même
			// principe que le crédit en monnaie dette ci-dessus, en temps réel.
			el("chartWealthTitle").textContent = t("game.chart_wealth_title_troc");
			el("chartWealthInfo").title = t("game.chart_wealth_info_troc");
			const activePlayers = sortByName(game.players.filter((p) => p.active));
			const goods = activePlayers.map((p) => p.goodsCount);
			const totalGoods = goods.reduce((s, g) => s + g, 0);
			const palette = ["#6b7280", "#9ca3af", "#f59e0b", "#fbbf24", "#dc2626", "#f87171", "#16a34a", "#86efac", "#7c3aed", "#c4b5fd"];
			const colors = activePlayers.map((p, i) => palette[i % palette.length]);
			if (state.charts.wealth && state.charts.wealth.config.type === "doughnut") {
				state.charts.wealth.data.labels = activePlayers.map((p) => p.name);
				state.charts.wealth.data.datasets[0].data = goods;
				state.charts.wealth.data.datasets[0].backgroundColor = colors;
				state.charts.wealth.update();
			} else {
				if (state.charts.wealth) state.charts.wealth.destroy();
				state.charts.wealth = trackChart(el("chartWealth"), {
					type: "doughnut",
					data: {
						labels: activePlayers.map((p) => p.name),
						datasets: [{ data: goods, backgroundColor: colors, borderWidth: 0 }],
					},
					options: { plugins: { legend: { display: false } }, maintainAspectRatio: false, cutout: "65%",
						animation: { duration: 500 } },
				});
			}
			const legend = el("wealthLegend");
			legend.innerHTML = activePlayers.length === 0
				? `<li style="color:var(--text-dim);font-size:0.8rem;">${t("game.legend_no_active_players")}</li>`
				: (totalGoods === 0
					? `<li style="color:var(--text-dim);font-size:0.8rem;">${t("game.legend_no_goods")}</li>`
					: activePlayers.map((p, i) => {
						const pct = Math.round((goods[i] / totalGoods) * 1000) / 10;
						return `<li><span><span class="swatch" style="background:${colors[i]}"></span>${escapeHtml(p.name)}</span>
							<strong>${pct}%</strong></li>`;
					}).join(""));
		} else {
			// Remonté par un utilisateur : les tranches "Top 20% / 20-80% / Bottom
			// 20%" n'étaient pas assez parlantes - remplacées par une tranche par
			// joueur, nommée, montrant sa part personnelle de la richesse totale.
			// Limite honnête à connaître : cette donnée vient de la richesse évaluée
			// à la mort/sortie de partie d'un joueur (voir la note dans
			// StatsService.computeWealthByPlayer), pas d'un suivi continu - un
			// joueur encore actif et jamais mort/sorti affichera donc 0% ici tant
			// qu'il n'a pas connu au moins une évaluation.
			el("chartWealthTitle").textContent = t("game.chart_wealth_title_libre");
			el("chartWealthInfo").title = t("game.chart_wealth_info_libre");
			if (state.charts.wealth) state.charts.wealth.destroy();
			const wd = stats.wealthDistribution;
			const totalWealth = wd.playerWealths.reduce((s, p) => s + p.wealth, 0);
			const palette = ["#16a34a", "#86efac", "#2563eb", "#93c5fd", "#f59e0b", "#fbbf24", "#dc2626", "#f87171", "#7c3aed", "#c4b5fd"];
			const colors = wd.playerWealths.map((p, i) => palette[i % palette.length]);
			state.charts.wealth = trackChart(el("chartWealth"), {
				type: "doughnut",
				data: {
					labels: wd.playerWealths.map((p) => p.playerName),
					datasets: [{
						data: wd.playerWealths.map((p) => p.wealth),
						backgroundColor: colors,
						borderWidth: 0,
					}],
				},
				options: { plugins: { legend: { display: false } }, maintainAspectRatio: false, cutout: "65%" },
			});
			const legend = el("wealthLegend");
			legend.innerHTML = wd.playerWealths.length === 0
				? `<li style="color:var(--text-dim);font-size:0.8rem;">${t("game.legend_no_evaluated_players")}</li>`
				: wd.playerWealths.map((p, i) => {
					const pct = totalWealth > 0 ? Math.round((p.wealth / totalWealth) * 1000) / 10 : 0;
					return `<li><span><span class="swatch" style="background:${colors[i]}"></span>${escapeHtml(p.playerName)}</span>
						<strong>${pct}%</strong></li>`;
				}).join("");
		}
	} catch (err) {
		// On journalise l'erreur réelle dans la console (utile pour la diagnostiquer
		// précisément - voir le retour "console F12" demandé lors de précédents bugs)
		// plutôt que de la laisser passer silencieusement.
		console.error("Erreur lors de l'affichage des graphiques :", err);
		document.querySelectorAll(".chart-container, .chart-container-small").forEach((c) => {
			c.innerHTML = '<p style="color:var(--text-dim);font-size:0.8rem;padding:1rem">'
				+ "Graphique indisponible pour le moment (voir la Console du navigateur, F12, pour le détail).</p>";
		});
	}
}

// ---------- Connexion joueurs (étape 3, Phase A) ----------
// ---------- Documentation ----------
// Vue entièrement statique (pas d'appel API) : reste consultable même sans accès
// internet côté PC, contrairement aux liens externes qu'elle contient (voir la
// note "connexion internet requise" affichée à côté).
function renderDocs() {
	stopTurnTimer();
	showView("view-docs");
	// Accessible aussi bien depuis l'écran d'accueil que pendant une partie :
	// on ne masque/affiche pas navHome/navGame, contrairement aux autres vues,
	// pour ne pas perdre le contexte "en partie" si on y accède depuis là.
	document.querySelectorAll("#navHome .active, #navGame .active").forEach((b) => b.classList.remove("active"));
	if (!el("navHome").classList.contains("hidden")) el("navDocsHome").classList.add("active");
	if (!el("navGame").classList.contains("hidden")) el("navDocsGame").classList.add("active");

	// Lien vers la page HTML complète "Règles du jeu" (docs/<langue>/html/...),
	// dans la langue actuellement active de l'application.
	const t = window.GecoI18n.t;
	const lang = (window.GecoI18n && window.GecoI18n.getActiveLang()) || "fr";
	el("fullRulesLink").href = `/docs/${lang}/html/regles-du-jeu.html`;
	el("statsDocLink").href = `/docs/${lang}/html/statistiques.html`;

	// Remonté par un utilisateur : quand une partie est ouverte, affiche en plus
	// le fragment de règles propre à SON système d'échange (voir manifeste,
	// champ "documentation") - plutôt que de toujours rappeler indifféremment
	// tous les systèmes existants.
	const card = el("docsCurrentSystemCard");
	if (state.currentGameId && state.currentGame) {
		const pluginId = Object.keys(PLUGIN_ID_TO_MONEY_SYSTEM)
			.find((id) => PLUGIN_ID_TO_MONEY_SYSTEM[id] === state.currentGame.moneySystem);
		if (pluginId) {
			fetch(`/api/plugins/${pluginId}/docs/${lang}`)
				.then((res) => (res.ok ? res.text() : Promise.reject()))
				.then((html) => {
					el("docsCurrentSystemTitle").textContent = t("docs.current_system_title", { name: state.currentGame.description || pluginId });
					el("docsCurrentSystemContent").innerHTML = html;
					card.classList.remove("hidden");
				})
				.catch(() => card.classList.add("hidden"));
		} else {
			card.classList.add("hidden");
		}
	} else {
		card.classList.add("hidden");
	}

	// Détecte si le PDF de la TRM a été déposé localement (voir
	// public/docs-offline/README.md) pour proposer un lien fonctionnel même sans
	// connexion internet, plutôt qu'un lien mort si le fichier n'est pas encore là.
	const pdfPath = "/docs-offline/TheorieRelativedelaMonnaie.pdf";
	fetch(pdfPath, { method: "HEAD" })
		.then((res) => {
			el("docsOfflineStatus").innerHTML = res.ok
				? `<a href="${pdfPath}" target="_blank" class="btn btn-small">📄 Ouvrir le PDF de la TRM (archive locale)</a>`
				: `<span style="color:var(--text-dim);font-size:0.85rem">PDF pas encore ajouté localement — voir
					<code>public/docs-offline/README.md</code> pour l'installer, ou utilisez le lien en ligne ci-dessus.</span>`;
		})
		.catch(() => {
			el("docsOfflineStatus").innerHTML =
				'<span style="color:var(--text-dim);font-size:0.85rem">PDF pas encore ajouté localement.</span>';
		});
}

async function renderConnect() {
	showView("view-connect");
	el("navHome").classList.remove("hidden");
	el("navGame").classList.add("hidden");
	document.querySelectorAll("#navHome .active").forEach((b) => b.classList.remove("active"));
	el("navConnect").classList.add("active");

	const container = el("connectAddresses");
	container.innerHTML = '<p style="color:var(--text-dim)">Détection des adresses réseau...</p>';

	let networkInfo;
	try {
		networkInfo = await Api.getNetworkInfo();
	} catch (err) {
		container.innerHTML = '<p style="color:var(--danger)">Impossible de détecter les adresses réseau. '
			+ "Vérifiez votre configuration ou consultez docs/05-etape3-connectivite.md.</p>";
		return;
	}
	const addresses = networkInfo.addresses;
	if (!addresses || addresses.length === 0) {
		container.innerHTML = '<p style="color:var(--danger)">Aucune adresse réseau détectée. '
			+ "Vérifiez que l'ordinateur est bien connecté à un réseau (Wifi local ou partage de connexion).</p>";
		return;
	}

	const port = location.port || "7000";
	container.innerHTML = "";
	for (const addr of addresses) {
		const url = `http://${addr.address}:${port}`;
		const card = document.createElement("div");
		card.className = "connect-card" + (addr.likelyHotspotOrLan ? " likely" : "");
		card.innerHTML = `
			${addr.likelyHotspotOrLan ? '<span class="connect-badge">Probable</span>' : ""}
			<div class="connect-qr" id="qr-${addr.address.replace(/\./g, "-")}"></div>
			<div class="connect-url">${url}</div>
			<div class="connect-interface">Interface : ${addr.interfaceName}</div>`;
		container.appendChild(card);

		// QRCode.js (chargé en CDN, voir index.html) dessine directement dans l'élément
		// cible - pas besoin d'attendre un retour, le rendu est synchrone.
		if (typeof QRCode !== "undefined") {
			new QRCode(document.getElementById(`qr-${addr.address.replace(/\./g, "-")}`), {
				text: url, width: 160, height: 160,
			});
		} else {
			document.getElementById(`qr-${addr.address.replace(/\./g, "-")}`).textContent =
				"QR indisponible (bibliothèque non chargée)";
		}
	}

	// Remonté par un utilisateur (test réel avec un téléphone, 27/08/2026) :
	// rappelle explicitement que le scan caméra d'achat de cartes (étape 3)
	// exige HTTPS - la connexion "Rejoindre la partie" ci-dessus, elle, n'en a
	// pas besoin (voir docs/05-etape3-connectivite.md). Sans ce rappel, rien
	// n'indique qu'une IP différente doit être utilisée pour ce cas précis.
	const noteEl = document.createElement("p");
	noteEl.className = "galilee-explainer";
	noteEl.style.marginTop = "1rem";
	if (networkInfo.httpsPort) {
		const preferred = addresses.find((a) => a.likelyHotspotOrLan) || addresses[0];
		noteEl.innerHTML = `📷 Pour que l'<strong>achat de cartes par scan caméra</strong> fonctionne (mode smartphone), `
			+ `chaque joueur doit ouvrir son lien personnel en <strong>https://</strong> (pas http://), par exemple `
			+ `<code>https://${escapeHtml(preferred.address)}:${networkInfo.httpsPort}</code> - un avertissement de sécurité `
			+ `apparaîtra la première fois (certificat auto-signé), c'est normal : choisissez "Continuer quand même" / "Avancé".`;
	} else {
		noteEl.innerHTML = `⚠️ Le certificat HTTPS n'a pas pu être généré sur cette installation : `
			+ `le scan caméra d'achat de cartes (mode smartphone) ne fonctionnera pas. Consultez les logs du serveur au démarrage.`;
	}
	container.appendChild(noteEl);
}

// Étape 3 : QR/lien d'invitation propre à UNE partie (join.html?gameId=...) -
// distinct de renderConnect() ci-dessus, qui ne fait que tester la
// joignabilité réseau sans connaître de partie précise. Bascule
// affiché/masqué (voir btnInvitePlayers) plutôt qu'une modale.
async function renderInviteQr(gameId) {
	const panel = el("inviteQrPanel");
	const isOpening = panel.classList.contains("hidden");
	if (!isOpening) {
		panel.classList.add("hidden");
		panel.innerHTML = "";
		return;
	}
	panel.classList.remove("hidden");
	panel.innerHTML = '<p style="color:var(--text-dim);font-size:0.85rem;">Détection des adresses réseau...</p>';

	let networkInfo;
	try {
		networkInfo = await Api.getNetworkInfo();
	} catch (err) {
		panel.innerHTML = '<p style="color:var(--danger);font-size:0.85rem;">Impossible de détecter les adresses réseau.</p>';
		return;
	}
	const addresses = networkInfo.addresses || [];
	if (addresses.length === 0) {
		panel.innerHTML = '<p style="color:var(--danger);font-size:0.85rem;">Aucune adresse réseau détectée. '
			+ "Vérifiez que l'ordinateur est bien connecté à un réseau (Wifi local ou partage de connexion).</p>";
		return;
	}
	// L'inscription elle-même n'a pas besoin de HTTPS (voir
	// docs/05-etape3-connectivite.md) : HTTP suffit, plus simple (pas
	// d'avertissement de certificat à faire accepter avant même d'avoir
	// rejoint la partie). Seul l'achat de cartes par caméra en a besoin,
	// plus tard, une fois inscrit (voir buildPlayerLink()).
	const preferred = addresses.find((a) => a.likelyHotspotOrLan) || addresses[0];
	const port = location.port || "7000";
	const url = `http://${preferred.address}:${port}/join.html?gameId=${gameId}`;

	panel.innerHTML = `
		<div class="invite-qr-box" id="inviteQrCode"></div>
		<div class="invite-qr-url">${escapeHtml(url)}</div>
		<button type="button" id="btnCopyInviteLink" class="btn btn-small">📋 Copier le lien</button>`;
	if (typeof QRCode !== "undefined") {
		new QRCode(el("inviteQrCode"), { text: url, width: 180, height: 180 });
	} else {
		el("inviteQrCode").textContent = "QR indisponible (bibliothèque non chargée)";
	}
	el("btnCopyInviteLink").onclick = () => {
		navigator.clipboard.writeText(url).then(() => {
			el("btnCopyInviteLink").textContent = "✓ Copié";
			setTimeout(() => { el("btnCopyInviteLink").textContent = "📋 Copier le lien"; }, 1500);
		});
	};
}

// Étape 3, mode smartphone : historique des échanges par QR code (voir
// GameService.listTransactions côté serveur) - remonté par l'utilisateur
// ("historisation de toutes les transactions de tous les joueurs... qui
// échange avec qui, quand, combien"). Bascule affiché/masqué comme le
// panneau d'invitation ci-dessus : évite de charger cette donnée à chaque
// affichage de la vue partie si l'animateur ne la consulte pas.
async function toggleTransactionsPanel(gameId) {
	const body = el("transactionsPanelBody");
	const btn = el("btnToggleTransactions");
	const isOpening = body.classList.contains("hidden");
	if (!isOpening) {
		body.classList.add("hidden");
		body.innerHTML = "";
		btn.textContent = window.GecoI18n.t("game.transactions_toggle_btn");
		return;
	}
	body.classList.remove("hidden");
	btn.textContent = window.GecoI18n.t("game.transactions_toggle_btn_hide");
	await renderTransactionsPanel(gameId);
}

async function renderTransactionsPanel(gameId) {
	const t = window.GecoI18n.t;
	const body = el("transactionsPanelBody");
	body.innerHTML = `<p style="color:var(--text-dim);font-size:0.85rem;">${t("settings.catalog_loading")}</p>`;

	let transactions;
	let cardsCatalog;
	try {
		[transactions, cardsCatalog] = await Promise.all([Api.getTransactions(gameId), Api.getCatalog("cartes")]);
	} catch (err) {
		body.innerHTML = `<p style="color:var(--danger)">${t("game.transactions_load_error")}</p>`;
		return;
	}

	if (transactions.length === 0) {
		body.innerHTML = `<p style="color:var(--text-dim);font-size:0.85rem;">${t("game.transactions_empty")}</p>`;
		return;
	}

	// Nom d'affichage d'une carte (langue courante) - repli sur son
	// identifiant technique si elle a été retirée du catalogue depuis.
	function cardName(cardTypeId) {
		const entry = cardsCatalog.find((c) => c.id === cardTypeId);
		return entry ? (catalogTextValue(entry.nom) || cardTypeId) : cardTypeId;
	}

	// Agrégats par joueur (voir .activity-table, déjà utilisé pour le rapport
	// de fin de partie - même style, nouvelle donnée). "Partenaire principal"
	// : celui avec qui ce joueur a le plus échangé, tous rôles confondus -
	// répond directement au besoin "qui échange le plus avec qui".
	const byPlayer = new Map(); // name -> { sellCount, buyCount, sellVolume, buyVolume, partners: Map(name -> count) }
	function ensurePlayer(name) {
		if (!byPlayer.has(name)) byPlayer.set(name, { sellCount: 0, buyCount: 0, sellVolume: 0, buyVolume: 0, partners: new Map() });
		return byPlayer.get(name);
	}
	function bumpPartner(stats, partnerName) {
		stats.partners.set(partnerName, (stats.partners.get(partnerName) || 0) + 1);
	}
	for (const tx of transactions) {
		const seller = ensurePlayer(tx.sellerPlayerName);
		seller.sellCount++;
		seller.sellVolume += tx.totalCoinsValue;
		bumpPartner(seller, tx.buyerPlayerName);
		const buyer = ensurePlayer(tx.buyerPlayerName);
		buyer.buyCount++;
		buyer.buyVolume += tx.totalCoinsValue;
		bumpPartner(buyer, tx.sellerPlayerName);
	}
	function topPartner(stats) {
		let best = null;
		let bestCount = 0;
		stats.partners.forEach((count, name) => { if (count > bestCount) { best = name; bestCount = count; } });
		return best ? `${escapeHtml(best)} (${bestCount})` : "—";
	}

	const totalVolume = transactions.reduce((s, tx) => s + tx.totalCoinsValue, 0);
	const sortedPlayers = [...byPlayer.entries()].sort((a, b) => (b[1].sellCount + b[1].buyCount) - (a[1].sellCount + a[1].buyCount));

	const summaryHtml = `
		<p style="font-size:0.85rem;color:var(--text-dim);margin-bottom:0.9rem;">
			${t("game.transactions_summary", { count: transactions.length, volume: totalVolume })}
		</p>`;

	const activityHtml = `
		<table class="activity-table" style="margin-bottom:1.2rem;">
			<thead><tr>
				<th>${t("game.transactions_col_player")}</th>
				<th>${t("game.transactions_col_sells")}</th>
				<th>${t("game.transactions_col_buys")}</th>
				<th>${t("game.transactions_col_sell_volume")}</th>
				<th>${t("game.transactions_col_buy_volume")}</th>
				<th>${t("game.transactions_col_top_partner")}</th>
			</tr></thead>
			<tbody>
				${sortedPlayers.map(([name, s], i) => `
				<tr class="${i === 0 ? "top-player" : ""}">
					<td>${escapeHtml(name)}</td>
					<td>${s.sellCount}</td>
					<td>${s.buyCount}</td>
					<td>${s.sellVolume}</td>
					<td>${s.buyVolume}</td>
					<td>${topPartner(s)}</td>
				</tr>`).join("")}
			</tbody>
		</table>`;

	// Historique brut, du plus récent au plus ancien (déjà l'ordre renvoyé par
	// GameService.listTransactions) - qui a échangé quoi, avec qui, à quel
	// tour, pour quel montant.
	const historyHtml = `
		<ul class="events-list">
			${transactions.map((tx) => `
			<li>
				<strong>${escapeHtml(tx.sellerPlayerName)} → ${escapeHtml(tx.buyerPlayerName)}</strong>
				<span class="event-meta">${escapeHtml(cardName(tx.cardTypeId))} · ${t("game.transactions_turn_label", { n: tx.turnNumber })} · ${t("game.transactions_amount", { n: tx.totalCoinsValue })}</span>
			</li>`).join("")}
		</ul>`;

	body.innerHTML = summaryHtml + activityHtml + historyHtml;
}


// Charts dédiés à cette vue (instances séparées de celles du tableau de bord, pour
// pouvoir naviguer entre les deux vues sans conflit sur les mêmes <canvas>).
const reportCharts = { histogram: null, moneyMass: null, galilee: null, compare: null, bankProfit: null };

// Remonté par un utilisateur, avec la trace d'erreur exacte de la console
// ("TypeError: t.startsWith is not a function" dans chart.umd.js, déclenché
// depuis openChartZoom) : le zoom plein écran réutilisait `sourceChart.options`,
// qui n'est PAS un objet normal une fois le graphique construit - Chart.js le
// transforme en interne en un Proxy de résolution des options (scriptable/
// indexable), incompatible avec une réutilisation telle quelle dans un nouveau
// graphique. La solution : conserver à part une référence vers l'objet
// `options` D'ORIGINE (celui écrit tel quel dans le code, avant que Chart.js ne
// le transforme), pour chaque <canvas>, et s'en servir pour le zoom plutôt que
// d'introspecter l'instance Chart.js déjà construite.
const mChartOriginalConfigs = new WeakMap(); // canvas -> { type, data, options }
function trackChart(canvas, config) {
	mChartOriginalConfigs.set(canvas, config);
	return new Chart(canvas, config);
}

// Palette cyclique pour distinguer les courbes de plusieurs joueurs.
const GALILEE_PALETTE = ["#2563eb", "#16a34a", "#f97316", "#e11d48", "#7c3aed", "#0891b2", "#ca8a04", "#db2777"];

function renderGalileeChart(wealthOverTime, mode) {
	const t = window.GecoI18n.t;
	// Boutons de bascule : mise à jour visuelle de l'état actif.
	el("btnGalileeAbsolute").className = "btn btn-small" + (mode === "absolute" ? " active-mode" : "");
	el("btnGalileeRelative").className = "btn btn-small" + (mode === "relative" ? " active-mode" : "");

	if (typeof Chart === "undefined") {
		el("chartGalilee").parentElement.innerHTML =
			`<p style="color:var(--text-dim);font-size:0.8rem;padding:1rem">${t("game.chart_unavailable")}</p>`;
		return;
	}
	if (!wealthOverTime || !wealthOverTime.series || wealthOverTime.series.length === 0) {
		el("chartGalilee").parentElement.innerHTML =
			`<p style="color:var(--text-dim);font-size:0.8rem;padding:1rem">${t("report.galilee_no_data")}</p>`;
		return;
	}

	if (reportCharts.galilee) reportCharts.galilee.destroy();

	// Chaque joueur peut avoir un nombre de points différent (un point de plus à
	// chaque mort/bilan) : on donne à chaque point son propre {x, y} plutôt que de
	// s'appuyer sur un tableau de labels partagé construit depuis une seule série,
	// qui désalignerait les courbes dès que les morts surviennent à des tours
	// différents d'un joueur à l'autre.
	const maxTurn = Math.max(...wealthOverTime.series.flatMap((s) => s.points.map((p) => p.turn)));

	const datasets = wealthOverTime.series.map((s, i) => ({
		label: s.playerName,
		data: s.points.map((p) => ({ x: p.turn, y: mode === "relative" ? p.relativeToAverage : p.value })),
		borderColor: GALILEE_PALETTE[i % GALILEE_PALETTE.length],
		backgroundColor: "transparent",
		tension: 0.25,
		pointRadius: 2,
	}));

	// En mode relatif, une ligne pointillée à 1.0 matérialise "la moyenne" vers
	// laquelle les comptes convergent (le point central de la démonstration Galilée).
	if (mode === "relative") {
		datasets.push({
			label: t("report.galilee_average_reference"),
			data: [{ x: 0, y: 1 }, { x: maxTurn, y: 1 }],
			borderColor: "#9ca3af",
			borderDash: [6, 4],
			pointRadius: 0,
			borderWidth: 1.5,
		});
	}

	reportCharts.galilee = trackChart(el("chartGalilee"), {
		type: "line",
		data: { datasets },
		options: {
			plugins: { legend: { display: true, position: "bottom", labels: { boxWidth: 12, font: { size: 11 } } } },
			scales: {
				x: { type: "linear", ticks: { stepSize: 1, callback: (v) => t("game.chart_turn_label", { n: v }) } },
				y: { beginAtZero: mode === "absolute" },
			},
			maintainAspectRatio: false,
			// Clic sur un nom de la légende : Chart.js isole/masque nativement la
			// courbe correspondante (comportement par défaut), ce qui répond au besoin
			// de pouvoir comparer les joueurs individuellement sans code supplémentaire.
		},
	});
}

async function renderReport(gameId, includeBank = false) {
	stopTurnTimer();
	showView("view-report");
	const t = window.GecoI18n.t;

	const game = state.currentGame && state.currentGame.id === gameId ? state.currentGame : await Api.getGame(gameId);
	const report = await Api.getReport(gameId, includeBank);
	const activity = await Api.getActivity(gameId).catch(() => null);
	const wealthOverTime = await Api.getWealthOverTime(gameId).catch(() => null);
	const isDebt = game.moneySystem === 1;
	const isTroc = game.moneySystem === 2;
	const accent = isDebt ? "#2563eb" : "#16a34a";
	setMoneyTheme(game.moneySystem);

	el("reportTitle").textContent = `${t("report.title")} ${game.description || ""}`;
	el("reportMoneyIcon").dataset.icon = isDebt ? "bank" : "leaf";
	el("reportMoneyIcon").className = "money-choice-icon " + (isDebt ? "money-debt-color" : "money-libre-color");
	renderIcons(el("reportMoneyIcon"));

	// Remonté par un utilisateur, avec un document de spécification détaillé
	// (concept déjà présent dans l'app Swing originale, StatsFrame.java) : deux
	// vues possibles en monnaie dette - "sans banque" (uniquement les joueurs) et
	// "avec banque" (la banque comptée comme un joueur de plus, montrant sa part
	// réelle dans la richesse produite). N'a de sens qu'en monnaie dette (la
	// monnaie libre n'a pas de banque qui accumule un profit de la même façon).
	const bankToggle = el("reportBankToggle");
	bankToggle.classList.toggle("hidden", !isDebt);
	if (isDebt) {
		bankToggle.innerHTML = `
			<button type="button" class="btn btn-small ${!includeBank ? "active" : ""}" id="reportNoBankBtn">${t("report.toggle_no_bank")}</button>
			<button type="button" class="btn btn-small ${includeBank ? "active" : ""}" id="reportWithBankBtn">${t("report.toggle_with_bank")}</button>`;
		el("reportNoBankBtn").onclick = () => renderReport(gameId, false);
		el("reportWithBankBtn").onclick = () => renderReport(gameId, true);
	}

	// Avertissement si des joueurs encore actifs n'ont pas été "clôturés" (voir la
	// note dans StatsService.computeFinalReport : leur richesse n'est comptabilisée
	// qu'au moment de leur événement Mort/Fin de partie).
	const warning = el("reportWarning");
	if (report.notYetFinalizedPlayers > 0) {
		warning.textContent = "⚠ " + t("report.not_finalized_warning", { count: report.notYetFinalizedPlayers });
		warning.classList.remove("hidden");
	} else {
		warning.classList.add("hidden");
	}

	el("reportStatCards").innerHTML = `
		<div class="stat-card"><span class="stat-icon" data-icon="clock"></span><div><span class="stat-value">${report.yearsSimulated}</span><span class="stat-label">${t("report.stat_years")}</span></div></div>
		<div class="stat-card"><span class="stat-icon" data-icon="calendar"></span><div><span class="stat-value">${report.nbTurnsPlanned}</span><span class="stat-label">${t("report.stat_turns")}</span></div></div>
		<div class="stat-card"><span class="stat-icon" data-icon="users"></span><div><span class="stat-value">${report.totalPlayers}</span><span class="stat-label">${t("game.stat_players")}</span></div></div>
		<div class="stat-card"><span class="stat-icon" data-icon="bank"></span><div><span class="stat-value">${report.finalMoneyMass}</span><span class="stat-label">${t("report.stat_final_mass")}</span></div></div>
		<div class="stat-card"><span class="stat-icon" data-icon="credit"></span><div><span class="stat-value">${report.totalProduction}</span><span class="stat-label">${t("report.stat_total_production")}</span></div></div>`;
	renderIcons(el("reportStatCards"));

	el("reportMetrics").innerHTML = `
		<div><span class="stat-value">${report.average}</span><span class="stat-label">${t("report.metric_average")}</span></div>
		<div><span class="stat-value">${report.median}</span><span class="stat-label">${t("report.metric_median")}</span></div>
		<div><span class="stat-value">${report.stdDev}</span><span class="stat-label">${t("report.metric_stddev")}</span></div>
		<div><span class="stat-value">${report.giniIndex}</span><span class="stat-label">${t("report.metric_gini")}</span></div>
		<div><span class="stat-value">${report.povertyThreshold}</span><span class="stat-label">${t("report.metric_poverty")} <span class="info-icon" title="${escapeHtml(t("report.metric_poverty_info"))}">ⓘ</span></span></div>
		<div><span class="stat-value">${report.playersUnderThreshold}</span><span class="stat-label">${t("report.metric_poor_players")}</span></div>
		<div><span class="stat-value">${report.modestThreshold}</span><span class="stat-label">${t("report.metric_modest")} <span class="info-icon" title="${escapeHtml(t("report.metric_modest_info"))}">ⓘ</span></span></div>
		<div><span class="stat-value">${report.playersModest}</span><span class="stat-label">${t("report.metric_modest_players")}</span></div>`;

	// Activité par joueur : qui a fait le plus de transactions, le plus emprunté,
	// brassé le plus de volume - triée par volume brassé décroissant (déjà fait
	// côté serveur), on met juste en valeur la première ligne (le "plus actif").
	// Remonté par un utilisateur : en monnaie libre, aucun événement individuel
	// n'existe pour retracer les échanges entre joueurs - cette section
	// n'aurait donc jamais rien de significatif à montrer, elle est masquée
	// entièrement. La colonne "Emprunté" n'a de sens qu'en monnaie dette (ni la
	// monnaie libre ni le troc n'ont de crédit).
	el("activitySection").classList.toggle("hidden", !isDebt && !isTroc);
	el("activityBorrowedCol").classList.toggle("hidden", !isDebt);
	if (activity && (isDebt || isTroc)) {
		el("activityGlobalVolume").textContent =
			t("report.activity_global", { count: activity.globalTransactionCount, volume: activity.globalVolumeMoved });
		el("activityTableBody").innerHTML = activity.byPlayer
			.map((p, i) => `
				<tr class="${i === 0 && p.volumeMoved > 0 ? "top-player" : ""}">
					<td>${escapeHtml(p.playerName)}${i === 0 && p.volumeMoved > 0 ? " 🏆" : ""}</td>
					<td>${p.transactionCount}</td>
					${isDebt ? `<td>${p.creditsTaken}</td>` : ""}
					<td>${p.volumeMoved}</td>
				</tr>`)
			.join("");
	} else if (isDebt || isTroc) {
		el("activityTableBody").innerHTML = `<tr><td colspan="${isDebt ? 4 : 3}" style="color:var(--text-dim)">${t("report.activity_unavailable")}</td></tr>`;
	}

	renderGalileeChart(wealthOverTime, "relative");
	el("btnGalileeAbsolute").onclick = () => renderGalileeChart(wealthOverTime, "absolute");
	el("btnGalileeRelative").onclick = () => renderGalileeChart(wealthOverTime, "relative");

	// Export réel du rapport en JSON téléchargeable (pas une simple décoration) :
	// utile pour le "compte rendu" que la notice officielle recommande de publier.
	// Placé avant la garde Chart.js ci-dessous pour rester fonctionnel même si les
	// graphiques n'ont pas pu se charger.
	el("btnExportReport").onclick = () => {
		const blob = new Blob([JSON.stringify({ game: game.description, moneySystem: game.moneySystem, report }, null, 2)],
			{ type: "application/json" });
		const url = URL.createObjectURL(blob);
		const a = document.createElement("a");
		a.href = url;
		a.download = `rapport-${game.description || game.id}.json`.replace(/\s+/g, "_");
		a.click();
		URL.revokeObjectURL(url);
	};

	// Remonté par un utilisateur, pour finaliser l'étape 2 : redemande les
	// données brutes complètes au serveur (contrairement à l'export du rapport
	// ci-dessus, déjà calculé et disponible localement). Passe par un fetch
	// (via getStoredGamePin) plutôt qu'une simple navigation directe - une
	// partie protégée par PIN a besoin de l'en-tête X-Game-Pin, qu'une
	// navigation ne peut pas envoyer.
	el("btnExportGameData").onclick = async () => {
		const headers = {};
		const pin = getStoredGamePin(gameId);
		if (pin) headers["X-Game-Pin"] = pin;
		const res = await fetch(`/api/games/${gameId}/export`, { headers });
		if (!res.ok) {
			alert(`Erreur ${res.status}`);
			return;
		}
		const blob = await res.blob();
		const url = URL.createObjectURL(blob);
		const a = document.createElement("a");
		a.href = url;
		a.download = `geconomicus-partie-${gameId}-${(game.description || "partie").replace(/\s+/g, "_")}.json`;
		a.click();
		URL.revokeObjectURL(url);
	};

	if (typeof Chart === "undefined") {
		document.querySelectorAll("#view-report .chart-container").forEach((c) => {
			c.innerHTML = `<p style="color:var(--text-dim);font-size:0.8rem;padding:1rem">${t("game.chart_unavailable")}</p>`;
		});
		return;
	}

	if (reportCharts.histogram) reportCharts.histogram.destroy();
	if (reportCharts.moneyMass) reportCharts.moneyMass.destroy();
	if (reportCharts.bankProfit) { reportCharts.bankProfit.destroy(); reportCharts.bankProfit = null; }

	// Remonté par un utilisateur, avec une capture d'écran de l'app Swing
	// originale à l'appui (StatsFrame.AggregatedStats) : "l'histogramme" montre
	// en réalité une barre par JOUEUR (nommé), pas des tranches groupées comme
	// on l'avait fait initialement - avec 3 lignes de référence horizontales
	// (moyenne, écart-type, seuil de pauvreté), sur la même échelle que les
	// barres. Repris à l'identique dans l'esprit (couleurs et style modernisés,
	// à la demande explicite de l'utilisateur), via un graphique Chart.js mixte
	// barres + lignes plutôt que le dessin Graphics2D bas niveau de l'original.
	const meanMinusStdDev = Math.max(0, report.average - report.stdDev);
	reportCharts.histogram = trackChart(el("chartHistogram"), {
		data: {
			labels: report.playerWealths.map((p) => p.playerName),
			datasets: [
				{
					type: "bar",
					label: t("report.legend_wealth"),
					data: report.playerWealths.map((p) => p.wealth),
					backgroundColor: accent,
					order: 3,
				},
				{
					type: "line",
					label: t("report.legend_average"),
					data: report.playerWealths.map(() => report.average),
					borderColor: "#1f2430",
					borderWidth: 2,
					pointRadius: 0,
					order: 1,
				},
				{
					type: "line",
					label: t("report.legend_stddev"),
					data: report.playerWealths.map(() => meanMinusStdDev),
					borderColor: "#1f2430",
					borderWidth: 2,
					borderDash: [6, 4],
					pointRadius: 0,
					order: 1,
				},
				{
					type: "line",
					label: t("report.legend_poverty_threshold"),
					data: report.playerWealths.map(() => report.povertyThreshold),
					borderColor: "#dc2626",
					borderWidth: 2,
					borderDash: [2, 3],
					pointRadius: 0,
					order: 1,
				},
			],
		},
		options: {
			plugins: { legend: { display: true, position: "top", labels: { boxWidth: 20, font: { size: 11 } } } },
			scales: { y: { beginAtZero: true }, x: { ticks: { autoSkip: false, maxRotation: 60, minRotation: 45 } } },
			maintainAspectRatio: false,
		},
	});

	reportCharts.moneyMass = trackChart(el("chartReportMoneyMass"), {
		type: "line",
		data: {
			labels: report.moneyMassHistory.map((p) => t("game.chart_turn_label", { n: p.turn })),
			datasets: [{ data: report.moneyMassHistory.map((p) => p.moneyMass), borderColor: accent, fill: false, tension: 0.3 }],
		},
		options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } }, maintainAspectRatio: false },
	});

	// Remonté par un utilisateur : histogramme dédié à la banque uniquement
	// (monnaie dette, "Avec banque" activé) - part des bénéfices (intérêts
	// perçus + valeurs saisies) dans sa richesse totale, distincte de la
	// richesse des joueurs déjà montrée plus haut. Voir
	// StatsService.computeBankProfitBreakdown, null pour les deux autres
	// systèmes ou si le rapport ne compte pas la banque.
	const bankPanel = el("chartBankProfitPanel");
	if (report.bankProfitBreakdown && report.bankProfitBreakdown.total > 0) {
		bankPanel.classList.remove("hidden");
		const bp = report.bankProfitBreakdown;
		reportCharts.bankProfit = trackChart(el("chartBankProfit"), {
			type: "doughnut",
			data: {
				labels: [t("report.bank_profit_label"), t("report.bank_reinvested_label")],
				datasets: [{ data: [bp.profit, bp.reinvested], backgroundColor: ["#16a34a", "#93c5fd"], borderWidth: 0 }],
			},
			options: {
				plugins: { legend: { display: true, position: "bottom" } },
				maintainAspectRatio: false,
				cutout: "60%",
			},
		});
	} else {
		bankPanel.classList.add("hidden");
	}
}

// ---------- Minuteur de tour synchronisé (Phase C) ----------
// Le temps restant se calcule à partir de deux valeurs partagées par le serveur
// (turnDurationSeconds, turnStartedAtEpochMs) plutôt que d'un décompte local : ainsi,
// deux navigateurs ouverts sur la même partie affichent le même temps restant, à la
// seconde près, sans avoir à échanger de messages "tick" en continu.
const TIMER_RING_CIRCUMFERENCE = 2 * Math.PI * 52;

// Remonté par un utilisateur (écran Paramètres) : réglages globaux (langue par
// défaut déjà gérée par i18n.js, son ici) - rafraîchis au démarrage et après
// toute modification depuis l'écran Paramètres, voir renderSettingsView().
let mAppSettings = { defaultLanguage: "fr", soundMuted: false, soundVolume: 100, protectionEnabled: false };
// Étape 3, mode smartphone (écran Paramètres) : onglet actif du panneau des
// trois tableaux (Cartes/Visuels/Avatars) - voir renderCatalogsPanel().
let mSettingsCatalogKind = "cartes";
// Cache du GET /api/network-info (adresses locales + port HTTPS) - remonté par
// un utilisateur (27/08/2026, premier test réel sur téléphone) : le lien
// personnel d'un joueur ne doit JAMAIS être construit à partir de
// window.location.origin (ça vaudrait "localhost" si l'animateur consulte le
// tableau de bord via localhost - inutilisable depuis un autre appareil).
// Voir buildPlayerLink() ci-dessous. Mis en cache car appelé à chaque clic sur
// le lien "🔗" d'un joueur, pas seulement depuis l'écran "Connexion joueurs".
let mNetworkInfoCache = null;
async function getCachedNetworkInfo() {
	if (!mNetworkInfoCache) mNetworkInfoCache = await Api.getNetworkInfo();
	return mNetworkInfoCache;
}
// Construit le lien personnel d'un joueur (voir Player.accessToken) en HTTPS
// + IP locale quand c'est possible (nécessaire pour que le scan caméra
// d'achat de cartes, étape 3, fonctionne directement depuis ce lien - une
// page chargée en HTTPS peut tout faire qu'une page HTTP peut faire, jamais
// l'inverse) ; repli en HTTP si le certificat n'a pas pu être généré sur
// cette installation (le reste de la page fonctionne quand même, seul le
// scan caméra serait alors indisponible).
async function buildPlayerLink(gameId, accessToken) {
	const info = await getCachedNetworkInfo();
	const addr = (info.addresses || []).find((a) => a.likelyHotspotOrLan) || (info.addresses || [])[0];
	if (!addr) {
		// Aucune adresse réseau détectée : dernier recours, l'origine actuelle
		// (mieux qu'un lien qui ne se construit pas du tout, même si ce sera
		// "localhost" si c'est ce que l'animateur utilise).
		return `${window.location.origin}/player-view.html?gameId=${gameId}&token=${accessToken}`;
	}
	const scheme = info.httpsPort ? "https" : "http";
	const port = info.httpsPort || (location.port || "7000");
	return `${scheme}://${addr.address}:${port}/player-view.html?gameId=${gameId}&token=${accessToken}`;
}
async function refreshAppSettings() {
	try {
		const res = await fetch("/api/settings");
		if (res.ok) mAppSettings = await res.json();
	} catch (err) {
		console.warn("Réglages indisponibles, valeurs par défaut conservées.", err);
	}
}

// Remonté par un utilisateur : un coup de sifflet (comme dans un match de foot)
// doit accompagner chaque démarrage/arrêt du compte à rebours. Fichier fourni par
// l'utilisateur, déposé dans public/sounds/whistle.mp3 - joué directement, un seul
// et même son pour le démarrage et l'arrêt (la demande décrit un seul coup de
// sifflet, pas deux sons distincts).
function playWhistle(kind) {
	// Remonté par un utilisateur (écran Paramètres) : le son peut être coupé
	// entièrement, ou son volume réglé - s'applique aussi bien au fichier fourni
	// qu'au secours synthétisé (voir playSynthesizedWhistle).
	if (mAppSettings.soundMuted) return;
	try {
		// new Audio() à chaque appel plutôt qu'une instance unique réutilisée :
		// permet à deux sifflets rapprochés (ex. Pause puis Reprendre coup sur
		// coup) de se superposer proprement, sans que le second coupe le premier
		// en cours de lecture.
		const audio = new Audio("/sounds/whistle.mp3");
		audio.volume = Math.max(0, Math.min(100, mAppSettings.soundVolume)) / 100;
		audio.play().catch((err) => {
			// Lecture bloquée (ex. navigateur qui exige une interaction utilisateur
			// préalable) ou fichier introuvable : secours synthétisé plutôt que de
			// rester silencieux.
			console.error("Lecture du sifflet impossible, secours synthétisé :", err);
			playSynthesizedWhistle(kind);
		});
	} catch (err) {
		playSynthesizedWhistle(kind);
	}
}

// Secours si le fichier audio ne peut pas être lu (voir playWhistle ci-dessus) :
// un sifflet synthétisé directement via l'API Web Audio (une tonalité brève et
// aiguë), pour ne jamais rester silencieux.
let mAudioCtx = null;
function playSynthesizedWhistle(kind) {
	try {
		if (!mAudioCtx) mAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
		if (mAudioCtx.state === "suspended") mAudioCtx.resume();
		const now = mAudioCtx.currentTime;
		// Remonté par un utilisateur (écran Paramètres) : le volume réglé s'applique
		// aussi au secours synthétisé - 0.25 était le pic de gain d'origine (volume
		// 100%), mis à l'échelle du réglage courant.
		const peakGain = 0.25 * (Math.max(0, Math.min(100, mAppSettings.soundVolume)) / 100);
		function blast(startAt, durationSec) {
			const osc = mAudioCtx.createOscillator();
			const gain = mAudioCtx.createGain();
			osc.connect(gain);
			gain.connect(mAudioCtx.destination);
			osc.type = "square";
			osc.frequency.setValueAtTime(2200, startAt);
			gain.gain.setValueAtTime(0.0001, startAt);
			gain.gain.exponentialRampToValueAtTime(Math.max(0.0001, peakGain), startAt + 0.02);
			gain.gain.exponentialRampToValueAtTime(0.0001, startAt + durationSec);
			osc.start(startAt);
			osc.stop(startAt + durationSec);
		}
		if (kind === "start")
		// Un seul coup de sifflet, comme un coup d'envoi.
			blast(now, 0.35);
		else
		// Deux coups brefs, comme un coup de sifflet final.
		{
			blast(now, 0.18);
			blast(now + 0.25, 0.2);
		}
	} catch (err) {
		// Le son est un agrément, pas une fonctionnalité critique : une erreur ici
		// (navigateur trop ancien, API Web Audio bloquée...) ne doit jamais empêcher
		// le chrono lui-même de fonctionner.
		console.error("Son du chrono indisponible :", err);
	}
}

function startTurnTimer(game) {
	stopTurnTimer();
	// Remonté par un utilisateur : coup de sifflet parasite en cliquant sur
	// "Valider et faire renaître" dans l'assistant, une fois le tour déjà
	// terminé. Cause réelle : chaque enregistrement d'événement déclenche une
	// notification WebSocket qui rafraîchit le tableau de bord (renderGameDetail
	// -> startTurnTimer), et endToastShown était réinitialisé à CHAQUE appel -
	// pas seulement quand un nouveau tour démarre vraiment. Résultat : tant que
	// le compte à rebours restait à 0 (le tour suivant n'a pas encore démarré),
	// chaque étape validée dans l'assistant rejouait le sifflet de fin de tour
	// et retentait de rouvrir l'assistant. Ne réinitialiser que si on suit
	// effectivement un tour différent de la dernière fois (turnStartedAtEpochMs
	// a changé) règle ça sans perdre le sifflet "une fois par vraie fin de tour".
	if (state.timer.lastTurnStartedAt !== game.turnStartedAtEpochMs) {
		state.timer.endToastShown = false;
		state.timer.lastTurnStartedAt = game.turnStartedAtEpochMs;
	}
	// Remonté par un utilisateur : la pause est désormais un vrai état partagé,
	// stocké côté serveur (game.pausedRemainingSeconds), plutôt qu'un indicateur
	// purement local à ce navigateur - le libellé du bouton reflète cet état.
	el("btnTimerPause").textContent = game.pausedRemainingSeconds != null ? "Reprendre" : "Pause";

	const update = () => {
		let remaining;
		if (game.pausedRemainingSeconds != null) {
			// En pause : le temps restant est figé côté serveur, on ne le recalcule
			// pas depuis turnStartedAtEpochMs (qui ne représente plus le temps réel
			// écoulé pendant que la pause est active).
			remaining = game.pausedRemainingSeconds;
		} else {
			const elapsedSeconds = (Date.now() - game.turnStartedAtEpochMs) / 1000;
			remaining = Math.max(0, game.turnDurationSeconds - elapsedSeconds);
		}
		const mins = Math.floor(remaining / 60);
		const secs = Math.floor(remaining % 60);
		el("timerText").textContent = `${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
		const fraction = game.turnDurationSeconds > 0 ? remaining / game.turnDurationSeconds : 0;
		el("timerRingProgress").style.strokeDashoffset = TIMER_RING_CIRCUMFERENCE * (1 - fraction);

		// Remonté par un utilisateur : une info-bulle "Fin de tour" doit apparaître
		// 3 secondes à chaque fois que le compte à rebours arrive à 0 - une seule fois
		// par tour (endToastShown évite de la redéclencher à chaque tick suivant, le
		// compte restant bloqué à 0 jusqu'à ce que l'animateur agisse). L'assistant de
		// fin de tour s'ouvre aussi automatiquement à ce moment-là - mais jamais
		// pendant une pause (le compte à rebours étant figé, ce serait un
		// déclenchement à retardement dès la reprise, pas immédiat).
		if (remaining <= 0 && !state.timer.endToastShown && game.pausedRemainingSeconds == null) {
			state.timer.endToastShown = true;
			playWhistle("stop");
			const toast = el("turnEndToast");
			// Remonté par un utilisateur, avec un document de spécification détaillé :
			// au dernier tour de la partie, l'info-bulle affiche "Fin du dernier tour"
			// plutôt que le "Fin de tour" habituel.
			toast.textContent = game.turnNumber >= game.nbTurnsPlanned
				? window.GecoI18n.t("game.toast_end_last_turn") : window.GecoI18n.t("game.toast_end_turn");
			toast.classList.remove("hidden");
			setTimeout(() => { toast.classList.add("hidden"); toast.textContent = window.GecoI18n.t("game.toast_end_turn"); }, 3000);
			openEndOfTurnWizard();
		}
	};
	update();
	state.timer.intervalId = setInterval(update, 1000);
}

function stopTurnTimer() {
	if (state.timer.intervalId) clearInterval(state.timer.intervalId);
	state.timer.intervalId = null;
}

// ---------- Assistant de fin de tour (Phase C) ----------
// Remplace l'ancien bouton "Nouveau tour" qui enregistrait l'événement immédiatement :
// suit maintenant la séquence de la maquette (résumé du tour -> décès -> nouveaux-nés
// -> préparation) avant de réellement faire avancer le tour.
async function openEndOfTurnWizard() {
	// Remonté par un utilisateur (avec capture d'écran à l'appui) : "boucle
	// infinie" qui repropose sans arrêt le bilan des joueurs endettés. Vraie
	// cause trouvée : le canal WebSocket renvoie ses messages à TOUS les clients
	// connectés, y compris celui qui vient de provoquer le changement lui-même
	// (ex: un enregistrement d'événement depuis l'assistant) - chaque message
	// reçu rappelle renderGameDetail(), qui rappelle startTurnTimer(), qui
	// réinitialise le indicateur "info-bulle déjà affichée pour ce tour". Tant
	// que le compte à rebours reste bloqué à 0 (aucun nouveau tour enregistré,
	// ce qui est précisément le cas pendant tout l'entre-deux-tours), le tick
	// suivant du minuteur redéclenchait donc l'assistant depuis le début. Garde-
	// fou direct ici : ne jamais rouvrir l'assistant s'il est déjà ouvert, quelle
	// que soit la cause qui a tenté de le redéclencher.
	if (state.wizardOpen) return;
	state.wizardOpen = true;

	const game = state.currentGame;
	const dlg = el("dlg");
	const t = window.GecoI18n.t;

	// Les boutons génériques de la boîte de dialogue (Annuler/Valider) ne conviennent
	// pas à un assistant à plusieurs étapes : on les masque et on injecte nos propres
	// boutons "Continuer" à l'intérieur de #dlgBody, étape par étape.
	// Remonté par un utilisateur (à plusieurs reprises) : pas de bouton "Fermer"
	// pendant l'entre-deux-tours - l'assistant doit être suivi jusqu'au bout, sans
	// échappatoire qui laisserait la partie dans un état à moitié traité. Ça n'est
	// plus nécessaire maintenant que la cause réelle du blocage ("Ne peut pas
	// payer" fermant tout l'assistant par erreur) est corrigée : il n'y a plus de
	// raison de vouloir sortir en cours de route.
	el("dlgOk").classList.add("hidden");
	el("dlgCancel").classList.add("hidden");
	const restoreDefaultButtons = () => {
		el("dlgOk").classList.remove("hidden");
		el("dlgCancel").classList.remove("hidden");
		el("dlgCancel").textContent = t("common.cancel");
	};

	// Bug réel trouvé suite à un retour utilisateur (capture d'écran à l'appui) :
	// un "Valider" fantôme, sans effet, restait visible en revenant à une étape de
	// l'assistant depuis un sous-formulaire (ex. "Ne peut pas payer") - car ce
	// sous-formulaire passe par openDialog(), qui rend systématiquement "Valider"
	// visible (comportement voulu pour les dialogues normaux), sans que rien ne le
	// recache au retour dans l'assistant. Chaque étape de l'assistant appelle
	// désormais cette fonction en tout premier, plutôt que de compter sur le seul
	// appel initial fait par openEndOfTurnWizard() - une garantie plus robuste,
	// qui tient peu importe le chemin emprunté pour atteindre l'étape.
	const hideGenericButtons = () => {
		el("dlgOk").classList.add("hidden");
		el("dlgCancel").classList.add("hidden");
	};

	// Bug réel trouvé suite à un retour utilisateur : contrairement à openDialog(),
	// cet assistant ne réinitialise jamais le gestionnaire de soumission du
	// formulaire #dlgForm - il restait donc accroché à celui laissé par la
	// DERNIÈRE boîte de dialogue ouverte via openDialog() (ex: "+ Joueur",
	// "Renommer"...). Une simple touche Entrée dans un champ de l'assistant
	// (ex: le montant d'un nouveau crédit) déclenchait alors une soumission
	// implicite du formulaire, exécutant ce gestionnaire périmé et fermant tout,
	// sans jamais exécuter le vrai code de l'étape en cours. On neutralise
	// explicitement toute soumission pendant la durée de l'assistant : chaque
	// étape gère elle-même ses propres boutons (tous en type="button").
	el("dlgForm").onsubmit = (e) => e.preventDefault();

	let selectedDeathIds = [];
	// Remonté par un utilisateur : nouvel ordre des étapes en monnaie libre -
	// l'inventaire en JETONS de TOUS les joueurs actifs (mourants ET survivants)
	// se fait maintenant en premier, avant même de savoir qui meurt vraiment
	// (au sens "déclenche un événement de mort") - ainsi le contrôle de collecte
	// (déclaré vs masse monétaire réelle) se vérifie AVANT que quoi que ce soit
	// ne modifie la masse monétaire, plutôt qu'en même temps que la mort/
	// renaissance comme précédemment (source de confusion : la mort applique
	// à la fois un retrait ET un bonus, les deux mélangés dans une seule
	// étape rendaient le contrôle difficile à interpréter). Clé = id joueur,
	// valeur = { weak, medium, strong }.
	let allPlayersMoneyInventory = {};

	// Étape 3, monnaie libre : historique des transactions smartphone de cette
	// partie (voir Api.getTransactions), chargé une seule fois à l'ouverture de
	// l'assistant plutôt qu'à chaque passage par renderStepAllPlayersMoney -
	// utilisé pour pré-remplir automatiquement le bilan de chaque joueur (voir
	// computeLibrePrefill ci-dessous). Tableau vide (pas d'erreur bloquante) si
	// le chargement échoue - l'assistant reste utilisable, juste sans pré-
	// remplissage, comme avant cette fonctionnalité.
	let allTransactionsThisGame = [];

	// Étape 3, monnaie libre : dernier solde en jetons connu d'un joueur, à
	// partir du plus récent événement WEALTH_CHECKPOINT/DEATH/QUIT le
	// concernant dans l'historique (voir game.events, déjà chargé). Renvoie
	// {weak:0, medium:0, strong:0} si aucun de ces événements n'existe encore
	// pour ce joueur (avant le tout premier tour où cette automatisation est
	// utilisée sur cette partie, ou joueur qui vient de rejoindre) - point de
	// départ honnête plutôt qu'une valeur inventée : l'animateur peut toujours
	// corriger le champ pré-rempli si la réalité physique diffère.
	function computeLastKnownLibreCoins(playerId) {
		const relevant = game.events
			.filter((e) => (e.playerId === playerId)
				&& ["WEALTH_CHECKPOINT", "DEATH", "QUIT"].includes(e.type))
			.sort((a, b) => b.timestamp - a.timestamp);
		if (relevant.length === 0) return { weak: 0, medium: 0, strong: 0 };
		const latest = relevant[0];
		return { weak: latest.weakCoins, medium: latest.mediumCoins, strong: latest.strongCoins };
	}

	// Étape 3, monnaie libre : mouvement net de jetons d'un joueur pour le tour
	// EN COURS (celui que l'assistant est en train de clôturer) via les
	// transactions smartphone - vendeur : +valeur reçue, acheteur : -valeur
	// payée. Ne regarde QUE les transactions de ce tour précis (Transaction.
	// turnNumber, déjà posé côté serveur) : celles des tours précédents sont
	// déjà reflétées dans le dernier solde connu (computeLastKnownLibreCoins),
	// les compter à nouveau serait un double comptage.
	function computeThisTurnTransactionDelta(playerId) {
		return allTransactionsThisGame
			.filter((tx) => tx.turnNumber === game.turnNumber)
			.reduce((sum, tx) => {
				if (tx.sellerPlayerId === playerId) return sum + tx.totalCoinsValue;
				if (tx.buyerPlayerId === playerId) return sum - tx.totalCoinsValue;
				return sum;
			}, 0);
	}

	// Pré-remplissage complet pour un joueur (monnaie libre uniquement) :
	// dernier solde connu + mouvements smartphone de ce tour, décomposé en
	// jetons faibles/moyens/forts via le même algorithme déjà utilisé pour les
	// suggestions de DU (computeTokenBreakdown, plus bas dans ce fichier).
	function computeLibrePrefill(playerId) {
		const last = computeLastKnownLibreCoins(playerId);
		const lastValue = (last.weak + (2 * last.medium) + (4 * last.strong)) * game.weakCoinValue;
		const total = Math.max(0, lastValue + computeThisTurnTransactionDelta(playerId));
		return computeTokenBreakdown(total, game.weakCoinValue);
	}

	// Partagée entre renderStep0() (affichage) et le retour après "Ne peut pas
	// payer" (pour savoir s'il reste encore quelqu'un à traiter) - même logique
	// dans les deux cas, un seul endroit à maintenir.
	// Recalcule à chaque appel depuis game.events (plutôt que d'utiliser une seule
	// fois turnEvents/sumBy, périmés dès que game est rafraîchi en cours de route -
	// même défaut que celui déjà corrigé pour l'inventaire des morts).
	function computeEventsSinceLastTurn() {
		const sorted = [...game.events].sort((a, b) => a.timestamp - b.timestamp);
		let lastTurnIndex = -1;
		sorted.forEach((e, i) => { if (e.type === "TURN") lastTurnIndex = i; });
		return sorted.slice(lastTurnIndex + 1);
	}

	function computeIndebtedPlayers() {
		// Remonté par un utilisateur (vrai bug, pas un souci d'affichage) : cette
		// exemption ("payé ses intérêts ce tour-ci, sera tenu de tout régler au
		// tour SUIVANT") n'a plus de sens au DERNIER tour - il n'y a pas de tour
		// suivant vers lequel reporter. Un joueur qui n'a réglé que ses intérêts
		// doit impérativement solder son crédit maintenant, avant de quitter la
		// partie, sans quoi il disparaissait purement et simplement de la liste.
		const alreadyPaidInterestThisTurn = isLastTurn ? new Set() : new Set(
			computeEventsSinceLastTurn().filter((e) => e.type === "INTEREST_ONLY").map((e) => e.playerId));
		return sortByName(game.players.filter((p) =>
			p.active && p.curDebt > 0 && !alreadyPaidInterestThisTurn.has(p.id)));
	}

	const isDebt = game.moneySystem === 1;
	// Troc (voir Game.MONEY_TROC, plugins/troc/manifest.json) : entre deux tours,
	// pas de crédits à gérer ni de DU à calculer - seulement les morts/
	// renaissances (biens) puis le renouvellement du temps de vie. Les échanges
	// eux-mêmes se font en plein tour (voir btnTrocTrade sur le tableau de bord),
	// pas ici.
	const isTroc = game.moneySystem === 2;
	// Remonté par un utilisateur : au dernier tour, l'assistant ne propose plus les
	// étapes habituelles (remboursements normaux, décès, nouveaux crédits, DU) -
	// il enchaîne directement sur la sortie de tous les joueurs puis la fin de
	// partie (voir renderEndGameInventory/renderEndGameSummary plus bas).
	const isLastTurn = game.turnNumber >= game.nbTurnsPlanned;

	// Étape 3, monnaie libre uniquement : charge l'historique des transactions
	// smartphone une seule fois, avant toute étape de l'assistant (voir
	// allTransactionsThisGame plus haut) - inutile en dette/troc, qui n'ont pas
	// (encore, pour le troc) cette automatisation.
	if (!isDebt && !isTroc) {
		try {
			allTransactionsThisGame = await Api.getTransactions(state.currentGameId);
		} catch (err) {
			allTransactionsThisGame = [];
		}
	}

	// Remonté par un utilisateur, avec la formule précisée (et confirmée via les
	// règles officielles - geconomicus.glibre.org/libre_money.html, qui indique
	// une moyenne de monnaie par joueur de 7 DU) : DU(t) = masse monétaire /
	// (7 × nombre de joueurs actifs), tronqué. Pur outil de calcul pour aider
	// l'animateur à distribuer physiquement les jetons - ne touche jamais
	// game.moneyMass, qui continue d'être suivi séparément par la formule de
	// convergence déjà existante (pas de double comptage).
	function computeCurrentDU() {
		if (game.activePlayersCount === 0) return 0;
		// Remonté par un utilisateur : la division elle-même n'a jamais utilisé de
		// facteur, mais game.moneyMass (le nombre divisé) est indirectement gonflé
		// par le facteur carte/monnaie (chaque "Rejoindre la partie" ajoute
		// 7 × facteur à la masse monétaire, voir Event.java côté moteur) - le DU
		// affiché ici dépendait donc quand même du facteur configuré. On divise
		// maintenant aussi par le facteur pour l'annuler : le DU sort désormais
		// identique quel que soit le facteur choisi à la création de la partie.
		return Math.floor(game.moneyMass / (7 * game.activePlayersCount * game.moneyCardsFactor));
	}

	// Remonté par un utilisateur, avec le document de spécification détaillé à
	// l'appui (Déroulement_partie.pdf) : l'assistant doit d'abord faire gérer le ou
	// les morts (inventaire, puis renaissance avec le DU seul), et SEULEMENT ENSUITE
	// faire gérer l'évolution du DU des autres joueurs - deux étapes bien séparées,
	// pas un seul écran combiné. Le joueur mort ne doit plus apparaître dans l'étape
	// suivante ("il a été traité à part en tout premier", cf. le document).
	// Remonté par un utilisateur : décompose une valeur (en unités de jeton
	// faible) en une suggestion concrète de jetons faibles/moyens/forts à donner
	// - pour que l'animateur sache exactement quoi remettre physiquement au
	// joueur, plutôt qu'un simple total qu'il doit reconvertir de tête. Algorithme
	// glouton (priorité aux jetons forts, puis moyens, puis faibles) : ce n'est
	// qu'une SUGGESTION qui minimise le nombre de jetons, l'animateur reste libre
	// de composer autrement du moment que la valeur totale donnée est la même.
	function computeTokenBreakdown(pTotalValue, pWeakCoinValue) {
		let units = Math.max(0, Math.round(pTotalValue / (pWeakCoinValue || 1)));
		const strong = Math.floor(units / 4);
		units -= strong * 4;
		const medium = Math.floor(units / 2);
		units -= medium * 2;
		return { weak: units, medium, strong };
	}

	// Étape 1 (nouvel ordre demandé par un utilisateur) : inventaire en JETONS de
	// TOUS les joueurs actifs, mourants et survivants confondus - avant même de
	// déclencher quoi que ce soit. Objectif : établir un contrôle de collecte
	// propre (déclaré vs masse monétaire réelle) AVANT que la mort ne vienne
	// modifier la masse monétaire (retrait de l'inventaire + bonus de
	// renaissance), ce qui rendait le contrôle difficile à interpréter quand
	// les deux étaient mélangés dans la même étape.
	function renderStepAllPlayersMoney() {
		hideGenericButtons();
		const activePlayers = sortByName(game.players.filter((p) => p.active));
		allPlayersMoneyInventory = {};

		el("dlgTitle").textContent = t("wiz.all_players_money_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.all_players_money_intro")}</p>
			<p class="galilee-explainer">${t("wiz.all_players_money_prefill_note")}</p>
			${activePlayers.length === 0 ? `<p>${t("game.legend_no_active_players")}</p>` : activePlayers.map((p) => {
				// Étape 3, monnaie libre : pré-rempli à partir du dernier solde connu
				// + des transactions smartphone de ce tour (voir computeLibrePrefill) -
				// l'animateur n'a plus qu'à valider, ou corriger si la réalité
				// physique diffère (voir la note d'explication ci-dessus).
				const prefill = computeLibrePrefill(p.id);
				return `
			<fieldset class="death-inventory-player" data-player-id="${p.id}">
				<legend>${escapeHtml(p.name)}${selectedDeathIds.includes(p.id) ? ` <span class="status-badge status-bank">${t("wiz.mandatory_dying_badge")}</span>` : ""}</legend>
				<div class="field-row">
					<div><label>${t("wiz.field_weak_tokens")}</label><input type="number" class="amWeak" value="${prefill.weak}" min="0"></div>
					<div><label>${t("wiz.field_medium_tokens")}</label><input type="number" class="amMedium" value="${prefill.medium}" min="0"></div>
				</div>
				<label>${t("wiz.field_strong_tokens")}</label>
				<input type="number" class="amStrong" value="${prefill.strong}" min="0">
			</fieldset>`;
			}).join("")}
			<div id="allPlayersMoneyCheckBlock" style="margin-top:0.8rem;padding-top:0.6rem;border-top:1px solid var(--border);">
				<p class="am-remaining" style="font-weight:600;"></p>
			</div>
			<button type="button" class="btn btn-primary btn-block" id="wizNextAllPlayersMoney">${t("wiz.continue_btn")}</button>`;
		el("wizNextAllPlayersMoney").onclick = () => {
			document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
				const playerId = parseInt(fieldset.dataset.playerId, 10);
				allPlayersMoneyInventory[playerId] = {
					weak: parseInt(fieldset.querySelector(".amWeak").value || "0", 10),
					medium: parseInt(fieldset.querySelector(".amMedium").value || "0", 10),
					strong: parseInt(fieldset.querySelector(".amStrong").value || "0", 10),
				};
			});
			if (selectedDeathIds.length > 0) renderStepDyingCardsDU();
			else renderStepOtherDU();
		};

		function updateCheck() {
			let collected = 0;
			document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
				const weak = parseInt(fieldset.querySelector(".amWeak").value || "0", 10);
				const medium = parseInt(fieldset.querySelector(".amMedium").value || "0", 10);
				const strong = parseInt(fieldset.querySelector(".amStrong").value || "0", 10);
				collected += (weak + 2 * medium + 4 * strong) * game.weakCoinValue;
			});
			const remaining = game.moneyMass - collected;
			const elc = document.querySelector(".am-remaining");
			elc.textContent = t("wiz.collection_check_result", { remaining, mass: game.moneyMass, collected });
			elc.style.color = remaining === 0 ? "var(--accent-libre)" : "var(--text-dim)";
		}
		document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
			fieldset.addEventListener("input", updateCheck);
		});
		updateCheck();
	}

	// Étape 2 : inventaire des CARTES (uniquement) des joueurs qui meurent ce
	// tour - leurs jetons ont déjà été collectés à l'étape précédente. Une fois
	// validé, déclenche réellement la mort/renaissance (l'événement DEATH,
	// jamais enregistré avant ce point).
	function renderStepDyingCardsDU() {
		hideGenericButtons();
		const du = computeCurrentDU();
		const dying = sortByName(game.players.filter((p) => p.active && selectedDeathIds.includes(p.id)));

		el("dlgTitle").textContent = t("wiz.death_du_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.death_du_intro", { du })}</p>
			${dying.map((p) => `
			<fieldset class="death-inventory-player" data-player-id="${p.id}">
				<legend>${t("wiz.dying_this_turn", { name: escapeHtml(p.name) })}</legend>
				<label>${t("game.field_weak_cards")}</label>
				<input type="number" class="duCardWeak" value="0" min="0">
				<label>${t("game.field_medium_cards")}</label>
				<input type="number" class="duCardMedium" value="0" min="0">
				<label>${t("game.field_strong_cards")}</label>
				<input type="number" class="duCardStrong" value="0" min="0">
				<p class="du-result" style="font-size:0.82rem;color:var(--text-dim);margin-top:0.4rem;"></p>
			</fieldset>`).join("")}
			<button type="button" class="btn btn-primary btn-block" id="wizNextDeathDU">${t("wiz.validate_rebirth_btn")}</button>`;
		el("wizNextDeathDU").onclick = async () => {
			// Enregistré comme un vrai événement de mort (même principe que
			// l'inventaire des morts en monnaie dette), pas seulement un calcul.
			// Remonté par un utilisateur : les jetons viennent de l'étape précédente
			// (allPlayersMoneyInventory, déjà collectés et vérifiés) - seules les
			// cartes sont saisies ici.
			for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
				const playerId = parseInt(fieldset.dataset.playerId, 10);
				const coins = allPlayersMoneyInventory[playerId] || { weak: 0, medium: 0, strong: 0 };
				const weakCards = parseInt(fieldset.querySelector(".duCardWeak").value || "0", 10);
				const mediumCards = parseInt(fieldset.querySelector(".duCardMedium").value || "0", 10);
				const strongCards = parseInt(fieldset.querySelector(".duCardStrong").value || "0", 10);
				await Api.recordEvent(state.currentGameId, {
					type: "D", playerId, principal: 0, interest: 0,
					weakCoins: coins.weak, mediumCoins: coins.medium, strongCoins: coins.strong,
					weakCards, mediumCards, strongCards,
				});
			}
			// Rafraîchit l'état local avant l'étape suivante, qui a besoin de savoir qui
			// est encore actif (mêmes précautions que renderStepDeathInventory côté dette).
			state.currentGame = await Api.getGame(state.currentGameId);
			Object.assign(game, state.currentGame);
			renderStepOtherDU();
		};

		function updatePlayerResult(fieldset) {
			const playerId = parseInt(fieldset.dataset.playerId, 10);
			const coins = allPlayersMoneyInventory[playerId] || { weak: 0, medium: 0, strong: 0 };
			const currentValue = (coins.weak + 2 * coins.medium + 4 * coins.strong) * game.weakCoinValue;
			// Remonté par un utilisateur : préciser l'unité ("3 jetons", pas juste
			// "3") et détailler concrètement quoi redonner par niveau, plutôt qu'un
			// total que l'animateur devrait reconvertir de tête.
			const breakdown = computeTokenBreakdown(du, game.weakCoinValue);
			fieldset.querySelector(".du-result").innerHTML =
				`${escapeHtml(t("wiz.death_du_result", { currentValue, du }))}<br>` +
				t("wiz.death_du_breakdown", { weak: breakdown.weak, medium: breakdown.medium, strong: breakdown.strong });
		}
		document.querySelectorAll(".death-inventory-player").forEach((fieldset) => updatePlayerResult(fieldset));
	}

	// Troc (voir Game.MONEY_TROC, docs/10-etape-plugins-troc.md) : morts/
	// renaissances d'abord, comme demandé - biens comptés (pré-remplis avec ce
	// que le logiciel suit déjà en direct, voir Player.goodsCount), puis
	// renaissance avec la dotation de départ (moteur, voir Event.applyEvent cas
	// DEATH). Contrairement à la monnaie libre, PAS d'étape séparée pour les
	// autres joueurs : le renouvellement du temps de vie de tout le monde est
	// entièrement automatique (voir Event.applyEvent, cas TURN) dès que
	// l'animateur valide le récap de fin de tour (renderStep4) - les échanges
	// eux-mêmes se font en plein tour, pas ici (voir btnTrocTrade).
	function renderStepDeathTroc() {
		hideGenericButtons();
		if (selectedDeathIds.length === 0)
		// Personne ne meurt ce tour : rien à faire ici, direction le récap.
		{
			renderStep4();
			return;
		}
		const dying = sortByName(game.players.filter((p) => p.active && selectedDeathIds.includes(p.id)));

		el("dlgTitle").textContent = t("wiz.death_troc_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.death_troc_intro", { n: game.startingGoods })}</p>
			${dying.map((p) => `
			<fieldset class="death-inventory-player" data-player-id="${p.id}">
				<legend>${t("wiz.dying_this_turn", { name: escapeHtml(p.name) })}</legend>
				<label>${t("game.field_weak_cards")}</label>
				<input type="number" class="trocWeak" value="0" min="0">
				<label>${t("game.field_medium_cards")}</label>
				<input type="number" class="trocMedium" value="0" min="0">
				<label>${t("game.field_strong_cards")}</label>
				<input type="number" class="trocStrong" value="0" min="0">
			</fieldset>`).join("")}
			<button type="button" class="btn btn-primary btn-block" id="wizNextDeathTroc">${t("wiz.validate_rebirth_btn")}</button>`;
		el("wizNextDeathTroc").onclick = async () => {
			for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
				const playerId = parseInt(fieldset.dataset.playerId, 10);
				// Remonté par un utilisateur : l'assistant demande le détail par
				// niveau de carte (comme en dette/libre), pas un seul chiffre - mais
				// une carte compte toujours pour 1 quel que soit son niveau (règle 7
				// de docs/10-etape-plugins-troc.md), donc le total envoyé reste une
				// simple somme, sans pondération par niveau.
				const weak = parseInt(fieldset.querySelector(".trocWeak").value || "0", 10);
				const medium = parseInt(fieldset.querySelector(".trocMedium").value || "0", 10);
				const strong = parseInt(fieldset.querySelector(".trocStrong").value || "0", 10);
				await Api.recordEvent(state.currentGameId, { type: "D", playerId, goodsFromPlayer: weak + medium + strong });
			}
			state.currentGame = await Api.getGame(state.currentGameId);
			Object.assign(game, state.currentGame);
			renderStep4();
		};
	}

	// Étape finale : distribution du DU aux joueurs restés en jeu tout le tour.
	// Remonté par un utilisateur : leurs jetons ont déjà été collectés et
	// vérifiés à l'étape 1 (renderStepAllPlayersMoney) - plus besoin de les
	// redemander ici, cette étape se contente d'indiquer, pour chacun, quoi lui
	// redonner (actuel + DU).
	function renderStepOtherDU() {
		hideGenericButtons();
		const du = computeCurrentDU();
		const staying = sortByName(game.players.filter((p) => p.active && !selectedDeathIds.includes(p.id)));

		el("dlgTitle").textContent = t("wiz.other_du_title");
		// Remonté par un utilisateur : rendre visible le détail du calcul étape par
		// étape (base 7 × joueurs actifs, puis le facteur carte/monnaie appliqué),
		// pas seulement le résultat final - pour que l'animateur puisse vérifier
		// et refaire le calcul de son côté s'il le souhaite.
		const baseTarget = 7 * game.activePlayersCount;
		const target = baseTarget * game.moneyCardsFactor;
		el("dlgBody").innerHTML = `
			<p>${t("wiz.du_value_intro", { du })}</p>
			<p style="font-size:0.85rem;color:var(--text-dim);">${t("wiz.du_formula", { du, mass: game.moneyMass, count: game.activePlayersCount, factor: game.moneyCardsFactor })}</p>
			<p style="font-size:0.85rem;color:var(--text-dim);">${t("wiz.target_formula", {
				count: game.activePlayersCount, base: baseTarget, factor: game.moneyCardsFactor, target,
			})}</p>
			<p>${t("wiz.other_du_intro")}</p>
			${staying.length === 0 ? `<p>${t("wiz.no_other_active_player")}</p>` : staying.map((p) => {
				const coins = allPlayersMoneyInventory[p.id] || { weak: 0, medium: 0, strong: 0 };
				const currentValue = (coins.weak + 2 * coins.medium + 4 * coins.strong) * game.weakCoinValue;
				const total = currentValue + du;
				const breakdown = computeTokenBreakdown(total, game.weakCoinValue);
				return `
			<fieldset class="death-inventory-player" data-player-id="${p.id}">
				<legend>${escapeHtml(p.name)}</legend>
				<p class="du-result" style="font-size:0.82rem;color:var(--text-dim);">
					${escapeHtml(t("wiz.du_result", { currentValue, du, total }))}<br>
					${t("wiz.death_du_breakdown", { weak: breakdown.weak, medium: breakdown.medium, strong: breakdown.strong })}
				</p>
			</fieldset>`;
			}).join("")}
			<button type="button" class="btn btn-primary btn-block" id="wizNextOtherDU">${t("wiz.continue_btn")}</button>`;
		el("wizNextOtherDU").onclick = () => renderStep4();
	}

	// Remonté par un utilisateur : "avant toute chose, il faut faire un état
	// inventaire pour chaque joueur endetté - capable de rembourser ou pas". Cette
	// étape liste chaque joueur ayant une dette en cours, avec un accès rapide aux
	// 3 actions possibles (réutilise exactement la même logique de classification
	// automatique que le formulaire par joueur, voir classifyCannotPay plus haut).
	function renderStep0() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.step0_title");
		// Remonté par un utilisateur : un joueur ayant remboursé ses intérêts pendant
		// le tour (mais pas forcément le crédit en entier) n'est "pas fautif" et n'a
		// rien à faire de plus à l'entre-deux-tours - il ne sera tenu de rembourser
		// intégralement qu'à la fin du tour SUIVANT, pas celui-ci.
		const indebted = computeIndebtedPlayers();
		// Remonté par un utilisateur, avec un document de spécification détaillé à
		// l'appui : un joueur qui meurt CE tour a l'OBLIGATION de régler son crédit
		// maintenant (la banque ne peut pas "attendre" avec un joueur qui va
		// disparaître) - alors qu'un joueur qui reste en jeu peut tout à fait
		// continuer à devoir de l'argent d'un tour sur l'autre, parfois toute la
		// partie, au bon vouloir de la banque/animateur. D'où deux listes
		// distinctes : l'une bloquante, l'autre facultative.
		const dyingIndebted = indebted.filter((p) => selectedDeathIds.includes(p.id));
		const otherIndebted = indebted.filter((p) => !selectedDeathIds.includes(p.id));

		function renderIndebtedRow(p, isDying) {
			const canPayInterest = p.curInterest > 0 && p.curInterest <= game.moneyMass;
			const canPayCredit = (p.curDebt + p.curInterest) <= game.moneyMass;
			return `
				<li data-player-id="${p.id}" data-dying="${isDying ? "1" : "0"}">
					<strong>${escapeHtml(p.name)}</strong>
					${isDying ? `<span class="status-badge status-bank">${t("wiz.mandatory_dying_badge")}</span>` : ""}
					<span class="event-meta">${t("wiz.debt_interest_meta", { debt: p.curDebt, interest: p.curInterest })}</span>
					<span class="wizard-inline-actions">
						${canPayInterest ? `<button type="button" class="btn btn-small" data-action="interest">${t("wiz.repay_interest_btn")}</button>` : ""}
						${canPayCredit ? `<button type="button" class="btn btn-small" data-action="credit">${t("wiz.repay_credit_btn")}</button>` : ""}
						<button type="button" class="btn btn-small" data-action="cannotpay">${t("event.type.CANNOT_PAY")}</button>
					</span>
				</li>`;
		}

		el("dlgBody").innerHTML = `
			<p style="font-size:0.82rem;color:var(--text-dim)">${t("wiz.current_money_mass_label")}
				<strong id="wizStep0MoneyMass">${game.moneyMass}</strong></p>
			${dyingIndebted.length > 0 ? `
			<p><strong>${t("wiz.dying_indebted_intro")}</strong></p>
			<ul class="wizard-checklist" id="wizIndebtedList">
				${dyingIndebted.map((p) => renderIndebtedRow(p, true)).join("")}
			</ul>` : ""}
			<p>${t("wiz.other_indebted_intro")}</p>
			<ul class="wizard-checklist" id="wizOtherIndebtedList">
				${otherIndebted.length === 0 ? `<li>${t("wiz.no_other_indebted")}</li>` : otherIndebted.map((p) => renderIndebtedRow(p, false)).join("")}
			</ul>
			<button type="button" class="btn btn-primary btn-block" id="wizNext0">${t("wiz.continue_btn")}</button>`;
		el("wizNext0").onclick = () => {
			// Remonté par un utilisateur : impossible de continuer tant qu'un joueur
			// qui meurt ce tour a encore un crédit en cours non réglé - contrairement
			// aux autres joueurs endettés, ce n'est pas facultatif pour lui.
			if (dyingIndebted.length > 0) {
				alert(t("wiz.dying_indebted_alert"));
				return;
			}
			if (isLastTurn) renderEndGameInventory();
			else if (selectedDeathIds.length > 0) renderStepDeathInventory();
			else renderStep3();
		};

		document.querySelectorAll("#wizIndebtedList [data-action], #wizOtherIndebtedList [data-action]").forEach((btn) => {
			btn.onclick = async () => {
				const li = btn.closest("[data-player-id]");
				const playerId = parseInt(li.dataset.playerId, 10);
				const isDying = li.dataset.dying === "1";
				const player = game.players.find((p) => p.id === playerId);
				const action = btn.dataset.action;
				if (action === "interest") {
					await Api.recordEvent(state.currentGameId, {
						type: "I", playerId, principal: 0, interest: player.curInterest,
					});
				} else if (action === "credit") {
					await Api.recordEvent(state.currentGameId, {
						type: "R", playerId, principal: player.curDebt, interest: player.curInterest,
					});
				} else {
					// "Ne peut pas payer" pendant le bilan de tour utilise exactement le même
					// formulaire (avec saisie de cartes et classification automatique) que
					// l'icône "+" d'une ligne de joueur - pas de logique dupliquée. On sait
					// déjà pourquoi on est là : pas besoin de reproposer le choix du type.
					// Remonté par un utilisateur : "Annuler" doit revenir au bilan des
					// joueurs endettés plutôt que fermer tout l'assistant. Un joueur qui
					// meurt ce tour (ou n'importe qui au dernier tour de la partie) n'est
					// jamais mis en banqueroute/prison - la banque saisit, c'est tout.
					dlg.close();
					openPlayerEventDialog(player, {
						allowedTypes: ["C"],
						exemptOfStatus: isDying || isLastTurn,
						onCancel: renderStep0,
						onSuccess: async () => {
							state.currentGame = await Api.getGame(state.currentGameId);
							Object.assign(game, state.currentGame);
							// Remonté par un utilisateur : si ce joueur était le dernier
							// endetté (banqueroute/prison venant de le régler), il n'y a
							// plus de raison de revenir sur cette étape - on passe
							// directement à la suivante plutôt que de réafficher un bilan
							// vide "aucun joueur endetté ce tour-ci".
							const stillIndebted = computeIndebtedPlayers();
							if (stillIndebted.length > 0) renderStep0();
							else if (isLastTurn) renderEndGameInventory();
							else if (selectedDeathIds.length > 0) renderStepDeathInventory();
							else renderStep3();
						},
					});
					return; // la partie sera rafraîchie par openPlayerEventDialog lui-même
				}
				// Rafraîchit l'état local du jeu (dette mise à jour) avant de redessiner cette
				// même étape, pour que la liste reflète immédiatement le remboursement.
				state.currentGame = await Api.getGame(state.currentGameId);
				Object.assign(game, state.currentGame);
				renderStep0();
			};
		});
	}

	// Remonté par un utilisateur : au dernier tour, une fois les crédits réglés
	// (étape 0), chaque joueur actif quitte la partie - il faut dresser
	// l'inventaire de chacun (monnaie restante + cartes par valeur), exactement
	// comme le fait déjà "Un joueur quitte la partie" à tout moment de la partie,
	// mais ici pour tout le monde d'un coup plutôt qu'un joueur à la fois.
	function renderEndGameInventory() {
		hideGenericButtons();
		const activePlayers = sortByName(game.players.filter((p) => p.active));

		// Troc : demande le détail par niveau de carte (comme en dette/libre),
		// pas un seul chiffre - une carte compte toujours pour 1 quel que soit
		// son niveau (règle 7 de docs/10-etape-plugins-troc.md), donc le total
		// envoyé reste une simple somme, sans pondération. Contrairement aux
		// deux autres systèmes, pas de distinction monnaie/cartes ni de calcul
		// de masse monétaire à vérifier.
		if (isTroc) {
			el("dlgTitle").textContent = t("wiz.end_inventory_title");
			el("dlgBody").innerHTML = `
				<p>${t("wiz.end_inventory_intro")}</p>
				${activePlayers.length === 0 ? `<p>${t("game.legend_no_active_players")}</p>` : activePlayers.map((p) => `
					<fieldset class="death-inventory-player" data-player-id="${p.id}">
						<legend>${escapeHtml(p.name)}</legend>
						<label>${t("game.field_weak_cards")}</label>
						<input type="number" class="eqWeak" value="0" min="0">
						<label>${t("game.field_medium_cards")}</label>
						<input type="number" class="eqMedium" value="0" min="0">
						<label>${t("game.field_strong_cards")}</label>
						<input type="number" class="eqStrong" value="0" min="0">
					</fieldset>`).join("")}
				<button type="button" class="btn btn-primary btn-block" id="wizNextEndInventory">${t("wiz.validate_continue_btn")}</button>`;
			el("wizNextEndInventory").onclick = async () => {
				for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
					const playerId = parseInt(fieldset.dataset.playerId, 10);
					const weak = parseInt(fieldset.querySelector(".eqWeak").value || "0", 10);
					const medium = parseInt(fieldset.querySelector(".eqMedium").value || "0", 10);
					const strong = parseInt(fieldset.querySelector(".eqStrong").value || "0", 10);
					await Api.recordEvent(state.currentGameId, {
						type: "Q", playerId, goodsFromPlayer: weak + medium + strong,
					});
				}
				state.currentGame = await Api.getGame(state.currentGameId);
				Object.assign(game, state.currentGame);
				renderEndGameSummary();
			};
			return;
		}

		// Monnaie libre : demande les jetons (faible/moyen/fort) ET les cartes
		// valeurs (faible/moyenne/forte) séparément - même principe que dans
		// l'entre-deux-tours (bug remonté par un utilisateur : jusqu'ici,
		// un seul "Monnaie restante" ne permettait pas à StatsService.computeGain()
		// de calculer correctement la richesse d'un joueur en monnaie libre, qui a
		// besoin des jetons ET des cartes séparément).
		if (!isDebt) {
			el("dlgTitle").textContent = t("wiz.end_inventory_title");
			el("dlgBody").innerHTML = `
				<p>${t("wiz.end_inventory_intro")}</p>
				<p class="du-remaining" style="font-weight:600;"></p>
				<p class="du-remaining" id="eqCoinsRemaining" style="font-weight:600;"></p>
				${activePlayers.length === 0 ? `<p>${t("game.legend_no_active_players")}</p>` : activePlayers.map((p) => `
					<fieldset class="death-inventory-player" data-player-id="${p.id}">
						<legend>${escapeHtml(p.name)}</legend>
						<p class="cannot-pay-inventory-title">${t("wiz.death_du_tokens_subtitle")}</p>
						<div class="field-row">
							<div><label>${t("wiz.field_weak_tokens")}</label><input type="number" class="eqCoinWeak" value="0" min="0"></div>
							<div><label>${t("wiz.field_medium_tokens")}</label><input type="number" class="eqCoinMedium" value="0" min="0"></div>
						</div>
						<label>${t("wiz.field_strong_tokens")}</label>
						<input type="number" class="eqCoinStrong" value="0" min="0">
						<p class="cannot-pay-inventory-title" style="margin-top:0.6rem;">${t("wiz.death_du_cards_subtitle")}</p>
						<div class="field-row">
							<div><label>${t("game.field_weak_cards")}</label><input type="number" class="eqWeak" value="0" min="0"></div>
							<div><label>${t("game.field_medium_cards")}</label><input type="number" class="eqMedium" value="0" min="0"></div>
						</div>
						<label>${t("game.field_strong_cards")}</label>
						<input type="number" class="eqStrong" value="0" min="0">
					</fieldset>`).join("")}
				<button type="button" class="btn btn-primary btn-block" id="wizNextEndInventory">${t("wiz.validate_continue_btn")}</button>`;
			el("wizNextEndInventory").onclick = async () => {
				for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
					const playerId = parseInt(fieldset.dataset.playerId, 10);
					await Api.recordEvent(state.currentGameId, {
						type: "Q", playerId,
						weakCoins: parseInt(fieldset.querySelector(".eqCoinWeak").value || "0", 10),
						mediumCoins: parseInt(fieldset.querySelector(".eqCoinMedium").value || "0", 10),
						strongCoins: parseInt(fieldset.querySelector(".eqCoinStrong").value || "0", 10),
						weakCards: parseInt(fieldset.querySelector(".eqWeak").value || "0", 10),
						mediumCards: parseInt(fieldset.querySelector(".eqMedium").value || "0", 10),
						strongCards: parseInt(fieldset.querySelector(".eqStrong").value || "0", 10),
					});
				}
				state.currentGame = await Api.getGame(state.currentGameId);
				Object.assign(game, state.currentGame);
				renderEndGameSummary();
			};

			// Remonté par un utilisateur : en plus de la valeur monétaire déjà
			// suivie (game.moneyMass), un second compteur en direct affiche le
			// nombre BRUT de jetons/pièces restant à collecter (indépendant de leur
			// valeur) - utile pour vérifier qu'aucune pièce physique n'a été
			// oubliée, pas seulement que le compte est bon en valeur.
			function updateRemainingLibre() {
				let collectedValue = 0;
				let collectedCoinCount = 0;
				document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
					const cWeak = parseInt(fieldset.querySelector(".eqCoinWeak").value || "0", 10);
					const cMedium = parseInt(fieldset.querySelector(".eqCoinMedium").value || "0", 10);
					const cStrong = parseInt(fieldset.querySelector(".eqCoinStrong").value || "0", 10);
					collectedValue += (cWeak + 2 * cMedium + 4 * cStrong) * game.weakCoinValue;
					collectedCoinCount += cWeak + cMedium + cStrong;
				});
				const remainingValue = game.moneyMass - collectedValue;
				const el1 = document.querySelector(".du-remaining");
				el1.textContent = t("wiz.remaining_to_collect", { remaining: remainingValue, mass: game.moneyMass, collected: collectedValue });
				el1.style.color = remainingValue === 0 ? "var(--accent-libre)" : "var(--text-dim)";
				el("eqCoinsRemaining").textContent = t("wiz.remaining_coins_collected", { count: collectedCoinCount });
			}
			document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
				fieldset.addEventListener("input", updateRemainingLibre);
			});
			updateRemainingLibre();
			return;
		}

		el("dlgTitle").textContent = t("wiz.end_inventory_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.end_inventory_intro")}</p>
			<p class="du-remaining" style="font-weight:600;"></p>
			${activePlayers.length === 0 ? `<p>${t("game.legend_no_active_players")}</p>` : activePlayers.map((p) => `
				<fieldset class="death-inventory-player" data-player-id="${p.id}">
					<legend>${escapeHtml(p.name)}</legend>
					<div class="field-row">
						<div><label>${t("game.field_remaining_money")}</label><input type="number" class="eqMoney" value="0"></div>
						<div><label>${t("game.field_weak_cards")}</label><input type="number" class="eqWeak" value="0"></div>
					</div>
					<div class="field-row">
						<div><label>${t("game.field_medium_cards")}</label><input type="number" class="eqMedium" value="0"></div>
						<div><label>${t("game.field_strong_cards")}</label><input type="number" class="eqStrong" value="0"></div>
					</div>
				</fieldset>`).join("")}
			<button type="button" class="btn btn-primary btn-block" id="wizNextEndInventory">${t("wiz.validate_continue_btn")}</button>`;
		el("wizNextEndInventory").onclick = async () => {
			for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
				const playerId = parseInt(fieldset.dataset.playerId, 10);
				await Api.recordEvent(state.currentGameId, {
					type: "Q", playerId,
					principal: parseInt(fieldset.querySelector(".eqMoney").value || "0", 10), interest: 0,
					weakCards: parseInt(fieldset.querySelector(".eqWeak").value || "0", 10),
					mediumCards: parseInt(fieldset.querySelector(".eqMedium").value || "0", 10),
					strongCards: parseInt(fieldset.querySelector(".eqStrong").value || "0", 10),
				});
			}
			state.currentGame = await Api.getGame(state.currentGameId);
			Object.assign(game, state.currentGame);
			renderEndGameSummary();
		};

		// Remonté par un utilisateur : le logiciel connaît exactement la masse
		// monétaire globale en circulation (game.moneyMass) - un compteur en direct
		// aide l'animateur à vérifier, au fur et à mesure qu'il saisit l'inventaire
		// de chaque joueur, que le total collecté correspond bien à ce qui est
		// attendu (aucune monnaie oubliée ou comptée en double).
		function updateRemaining() {
			let collected = 0;
			document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
				const money = parseInt(fieldset.querySelector(".eqMoney").value || "0", 10);
				collected += money;
			});
			const remaining = game.moneyMass - collected;
			const el2 = document.querySelector(".du-remaining");
			el2.textContent = t("wiz.remaining_to_collect", { remaining, mass: game.moneyMass, collected });
			el2.style.color = remaining === 0 ? "var(--accent-libre)" : "var(--text-dim)";
		}
		document.querySelectorAll(".death-inventory-player").forEach((fieldset) => {
			fieldset.addEventListener("input", updateRemaining);
		});
		updateRemaining();
	}

	// Portage du principe de l'app Swing originale (StatsFrame.java) : la banque
	// est traitée comme un "joueur" à part entière dans le bilan final.
	// Remonté par un utilisateur : ce bilan doit refléter le même calcul net que
	// l'écran de statistiques (StatsService#computeBankWealth, qui rejoue
	// l'historique et soustrait le principal détruit sur les défauts), pas les
	// compteurs bruts de Game - on réutilise donc l'endpoint /report déjà exposé
	// plutôt que de dupliquer la logique côté client. Il n'y a pas de banque en
	// monnaie libre (cahier des charges), donc ce bloc ne s'affiche qu'en monnaie
	// dette.
	async function renderEndGameSummary() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.end_game_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.end_game_thanks", { system: isDebt ? t("wiz.money_system_debt") : t("wiz.money_system_libre") })}</p>
			<p id="wizBankSummary"></p>
			<label class="checkbox-label">
				<input type="checkbox" id="wizGoToStats">
				${t("wiz.go_to_stats_label")}
			</label>
			<button type="button" class="btn btn-new-turn btn-block" id="wizEndGame">${t("wiz.finish_btn")}</button>`;
		if (isDebt) {
			try {
				const report = await Api.getReport(state.currentGameId, true);
				const bank = report.playerWealths.find((p) => p.playerName === "Banque");
				el("wizBankSummary").innerHTML = `
					<span class="cannot-pay-inventory-title">${t("wiz.bank_summary_title")}</span>
					<ul class="wizard-checklist">
						<li>${t("wiz.bank_net_balance", { amount: bank ? bank.wealth : 0 })}</li>
					</ul>`;
			} catch (err) {
				el("wizBankSummary").textContent = "";
			}
		}
		el("wizEndGame").onclick = async () => {
			await Api.recordEvent(state.currentGameId, { type: "E", playerId: null, principal: 0, interest: 0 });
			const goToStats = el("wizGoToStats").checked;
			dlg.close();
			restoreDefaultButtons();
			state.wizardOpen = false;
			// Remonté par un utilisateur, avec un document de spécification détaillé :
			// une case à cocher (décochée par défaut) permet de rediriger vers les
			// statistiques de la partie plutôt que vers "Nouvelle partie".
			if (goToStats) renderReport(state.currentGameId);
			else renderGamesList();
		};
	}

	async function renderStep2() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.step2_title");
		const activePlayers = sortByName(game.players.filter((p) => p.active));
		// Suggestion de l'algorithme (portage de l'app originale) : affichée en
		// chargement, puis les cases correspondantes sont pré-cochées une fois
		// connue - l'animateur reste libre de tout décocher/cocher à sa guise.
		el("dlgBody").innerHTML = `
			<p>${t("wiz.step2_intro")}</p>
			<p id="wizDeathSuggestion" style="font-size:0.82rem;color:var(--text-dim)">${t("wiz.suggestion_computing")}</p>
			<ul class="wizard-checklist">
				${activePlayers.length === 0 ? `<li>${t("game.legend_no_active_players")}</li>` : activePlayers.map((p) => `
					<li><label><input type="checkbox" value="${p.id}" class="wizDeathCheck" data-name="${escapeHtml(p.name)}"> ${escapeHtml(p.name)} (${t("wiz.age_meta", { age: p.age })})</label></li>`).join("")}
			</ul>
			<button type="button" class="btn btn-primary btn-block" id="wizNext2">${t("wiz.continue_btn")}</button>`;
		el("wizNext2").onclick = () => {
			selectedDeathIds = [...document.querySelectorAll(".wizDeathCheck:checked")].map((cb) => parseInt(cb.value, 10));
			// Remonté par un utilisateur, avec un document de spécification détaillé à
			// l'appui : il faut savoir QUI meurt AVANT de regarder les crédits, pas
			// l'inverse - un joueur qui meurt a l'obligation de régler son crédit
			// (la banque ne peut pas "attendre" avec lui comme elle pourrait le faire
			// avec un joueur qui reste en jeu). D'où la bascule vers le bilan des
			// joueurs endettés ici, plutôt que directement vers l'inventaire des morts.
			// En monnaie libre (pas de crédit), on démarre maintenant par l'inventaire
			// en jetons de TOUS les joueurs actifs (voir renderStepAllPlayersMoney),
			// qui enchaîne ensuite sur les cartes des mourants puis la distribution du
			// DU aux survivants. Le troc suit le même principe mort-d'abord (voir
			// renderStepDeathTroc), mais avec des biens et du temps de vie plutôt
			// qu'un DU.
			if (isDebt) renderStep0(); else if (isTroc) renderStepDeathTroc(); else renderStepAllPlayersMoney();
		};

		try {
			const suggested = await Api.getSuggestedDeaths(state.currentGameId);
			el("wizDeathSuggestion").textContent = suggested.length
				? t("wiz.suggestion_found", { names: suggested.join(", ") })
				: t("wiz.suggestion_none");
			document.querySelectorAll(".wizDeathCheck").forEach((cb) => {
				if (suggested.includes(cb.dataset.name)) cb.checked = true;
			});
		} catch (err) {
			el("wizDeathSuggestion").textContent = "";
		}
	}

	// Remonté par un utilisateur : "quand un joueur meurt, il faut dresser son
	// inventaire (monnaie restante, cartes faibles/moyennes/fortes) - c'est très
	// important afin de le faire renaître complètement neuf et de remettre en jeu
	// son capital". Rien n'était demandé jusqu'ici à la mort d'un joueur. Cette
	// étape s'intercale entre la sélection des morts et les nouveaux-nés - après
	// le bilan des joueurs endettés (étape 0), qui a déjà géré la saisie
	// éventuelle par la banque avant que l'animateur ne fasse cet inventaire.
	function renderStepDeathInventory() {
		hideGenericButtons();
		if (selectedDeathIds.length === 0) {
			renderStep3();
			return;
		}
		el("dlgTitle").textContent = t("wiz.death_inventory_title");
		el("dlgBody").innerHTML = `
			<p>${t("wiz.death_inventory_intro")}</p>
			${selectedDeathIds.map((id) => {
				const p = game.players.find((pl) => pl.id === id);
				return `
				<fieldset class="death-inventory-player" data-player-id="${id}">
					<legend>${escapeHtml(p.name)}</legend>
					<!-- Remonté par un utilisateur : la monnaie dette n'a jamais eu qu'un
					     seul type de jeton (contrairement aux cartes valeurs, qui existent
					     bien à trois niveaux) - vérifié directement dans le code de
					     l'application Swing d'origine (StatsFrame.addFromEvent). Retour à
					     un seul champ "Monnaie restante", après une tentative erronée
					     d'alignement sur un tableur transmis qui ne reflétait pas
					     fidèlement les règles réelles. -->
					<div class="field-row">
						<div><label>${t("game.field_remaining_money")}</label><input type="number" class="diMoney" value="0"></div>
						<div><label>${t("game.field_weak_cards")}</label><input type="number" class="diWeak" value="0"></div>
					</div>
					<div class="field-row">
						<div><label>${t("game.field_medium_cards")}</label><input type="number" class="diMedium" value="0"></div>
						<div><label>${t("game.field_strong_cards")}</label><input type="number" class="diStrong" value="0"></div>
					</div>
				</fieldset>`;
			}).join("")}
			<button type="button" class="btn btn-primary btn-block" id="wizNextDeathInventory">${t("wiz.validate_inventory_btn")}</button>`;
		el("wizNextDeathInventory").onclick = async () => {
			for (const fieldset of document.querySelectorAll(".death-inventory-player")) {
				const playerId = parseInt(fieldset.dataset.playerId, 10);
				const money = parseInt(fieldset.querySelector(".diMoney").value || "0", 10);
				const weakCards = parseInt(fieldset.querySelector(".diWeak").value || "0", 10);
				const mediumCards = parseInt(fieldset.querySelector(".diMedium").value || "0", 10);
				const strongCards = parseInt(fieldset.querySelector(".diStrong").value || "0", 10);
				await Api.recordEvent(state.currentGameId, {
					type: "D", playerId, principal: money, interest: 0,
					weakCards, mediumCards, strongCards,
				});
			}
			// Remonté par un utilisateur (avec un plantage à la clé) : l'état local de
			// la partie n'était jamais rafraîchi après l'enregistrement des morts,
			// contrairement à ce qui est fait ailleurs dans l'assistant - les étapes
			// suivantes (Nouveaux-nés, Nouveaux crédits) travaillaient donc sur des
			// données périmées ne reflétant pas encore les morts qui viennent d'être
			// enregistrées.
			state.currentGame = await Api.getGame(state.currentGameId);
			Object.assign(game, state.currentGame);
			renderStep3();
		};
	}

	function renderStep3() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.step3_title");
		const names = game.players.filter((p) => selectedDeathIds.includes(p.id)).map((p) => p.name);
		el("dlgBody").innerHTML = `
			<p>${names.length ? t("wiz.step3_intro_with_deaths") : t("wiz.step3_intro_no_deaths")}</p>
			<ul class="wizard-checklist">${names.map((n) => `<li>${escapeHtml(n)}</li>`).join("")}</ul>
			<button type="button" class="btn btn-primary btn-block" id="wizNext3">${t("wiz.continue_btn")}</button>`;
		el("wizNext3").onclick = () => { if (isDebt) renderStepCredits(); else renderStep4(); };
	}

	// Remonté par un utilisateur : "avant de lancer un nouveau tour, il faut savoir
	// si d'autres personnes souhaitent faire des crédits - actuellement on n'a pas
	// la possibilité de refaire des crédits entre chaque tour". Cette étape permet
	// d'accorder autant de nouveaux crédits que nécessaire avant de continuer.
	function renderStepCredits() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.step_credits_title");
		// Remonté par un utilisateur, avec un exemple précis (Porthos en prison) :
		// un joueur qui vient de se retrouver en prison, en banqueroute, ou mort ce
		// tour-ci "passe son tour" - il ne doit pas non plus être proposé pour un
		// nouveau crédit. Réutilise directement getPlayerStatusBadge (même
		// détection que le badge affiché sur sa ligne), plutôt que de dupliquer la
		// logique une troisième fois.
		// Filet de sécurité : si ce calcul échoue pour une raison quelconque (ex.
		// donnée inattendue), on revient au filtrage simple plutôt que de laisser
		// l'étape bloquée avec un titre à jour mais un contenu resté sur l'étape
		// précédente (symptôme observé suite à un retour utilisateur).
		let eligiblePlayers;
		try {
			eligiblePlayers = sortByName(game.players.filter((p) => {
				if (!p.active || selectedDeathIds.includes(p.id)) return false;
				const status = getPlayerStatusBadge(p, game);
				const blockedStatuses = ["status-prison", "status-bankrupt", "status-dead"];
				return !status || !blockedStatuses.includes(status.cls);
			}));
		} catch (err) {
			console.error("Filtrage avancé des joueurs éligibles a échoué, repli sur le filtrage simple :", err);
			eligiblePlayers = sortByName(game.players.filter((p) => p.active && !selectedDeathIds.includes(p.id)));
		}
		el("dlgBody").innerHTML = `
			<p>${t("wiz.step_credits_intro")}</p>
			<p style="font-size:0.82rem;color:var(--text-dim)">${t("wiz.current_money_mass_label")}
				<strong id="wizCreditMoneyMass">${game.moneyMass}</strong></p>
			<div class="credit-row">
				<select id="wizCreditPlayer">
					${eligiblePlayers.map((p) => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join("")}
				</select>
				<input id="wizCreditAmount" type="number" value="3" title="${t("game.field_principal")}">
				<input id="wizCreditInterest" type="number" value="1" title="${t("game.field_interest")}">
				<button type="button" class="btn btn-new-turn" id="wizAddCredit">${t("wiz.grant_credit_btn")}</button>
			</div>
			<ul class="wizard-checklist" id="wizCreditsGranted"></ul>
			<button type="button" class="btn btn-primary btn-block" id="wizNextCredits">${t("wiz.continue_btn")}</button>`;
		el("wizNextCredits").onclick = renderStep4;
		if (eligiblePlayers.length === 0) {
			el("wizAddCredit").disabled = true;
			return;
		}
		el("wizAddCredit").onclick = async () => {
			const playerId = parseInt(el("wizCreditPlayer").value, 10);
			const principal = parseInt(el("wizCreditAmount").value || "0", 10);
			const interest = parseInt(el("wizCreditInterest").value || "0", 10);
			const player = game.players.find((p) => p.id === playerId);
			// Remonté par un utilisateur : possibilité de retirer un crédit accordé par
			// erreur (ex. double-clic sur "+ Accorder") - on garde l'ID de l'événement
			// créé pour pouvoir le supprimer via la croix, sans quitter cette étape.
			const event = await Api.recordEvent(state.currentGameId, { type: "N", playerId, principal, interest });
			// Remonté par un utilisateur, avec un document de spécification détaillé :
			// "au fur et à mesure que les crédits sont ajoutés, les joueurs sont mis à
			// jour ainsi que la masse monétaire globale" - rafraîchi ici à chaque
			// ajout, pas seulement à la suppression comme c'était le cas jusqu'ici.
			state.currentGame = await Api.getGame(state.currentGameId);
			Object.assign(game, state.currentGame);
			el("wizCreditMoneyMass").textContent = game.moneyMass;
			const li = document.createElement("li");
			li.innerHTML = `<span>${t("wiz.credit_granted_line", { name: escapeHtml(player.name), principal, interest })}</span>
				<button type="button" class="event-action-btn event-action-delete" title="${escapeHtml(t("wiz.remove_credit_title"))}">✕</button>`;
			li.querySelector(".event-action-btn").onclick = async () => {
				await Api.deleteEvent(state.currentGameId, event.id);
				state.currentGame = await Api.getGame(state.currentGameId);
				Object.assign(game, state.currentGame);
				el("wizCreditMoneyMass").textContent = game.moneyMass;
				li.remove();
			};
			el("wizCreditsGranted").appendChild(li);
		};
	}

	function renderStep4() {
		hideGenericButtons();
		el("dlgTitle").textContent = t("wiz.step4_title", { n: game.turnNumber });
		// Remonté par un utilisateur, avec un document de spécification détaillé à
		// l'appui : le récap final doit reprendre mort/renaissance + prison +
		// crédits - calculé fraîchement (pas depuis une variable figée à
		// l'ouverture de l'assistant, qui ne refléterait pas tout ce qui vient de
		// se passer pendant cette même session de l'assistant).
		const sinceLastTurn = computeEventsSinceLastTurn();
		const sumBy = (type, field) => sinceLastTurn.filter((e) => e.type === type).reduce((s, e) => s + e[field], 0);
		const namesByType = (type) => [...new Set(sinceLastTurn.filter((e) => e.type === type).map((e) => e.playerName))];
		const deadNames = namesByType("DEATH");
		const prisonNames = namesByType("PRISON");
		const bankruptNames = namesByType("BANKRUPT");
		const tradeGoodsCount = sinceLastTurn.filter((e) => e.type === "GOODS_TRADE").length;
		el("dlgBody").innerHTML = `
			<p>${t("wiz.step4_intro")}</p>
			<dl class="summary-list">
				${deadNames.length ? `<dt>${t("wiz.summary_deaths_label")}</dt><dd>${deadNames.map(escapeHtml).join(", ")}</dd>` : ""}
				${prisonNames.length ? `<dt>${t("event.type.PRISON")}</dt><dd>${prisonNames.map(escapeHtml).join(", ")}</dd>` : ""}
				${bankruptNames.length ? `<dt>${t("event.type.BANKRUPT")}</dt><dd>${bankruptNames.map(escapeHtml).join(", ")}</dd>` : ""}
				${isTroc ? `
				<dt>${t("event.type.GOODS_TRADE")}</dt><dd>${tradeGoodsCount}</dd>
				<dt>${t("game.stat_goods")}</dt><dd>${game.players.filter((p) => p.active).reduce((s, p) => s + p.goodsCount, 0)}</dd>`
				: isDebt ? `
				<dt>${t("wiz.summary_credits_granted_label")}</dt><dd>${sumBy("NEW_CREDIT", "principal")}</dd>
				<dt>${t("wiz.summary_repayments_label")}</dt><dd>${sumBy("REIMB_CREDIT", "principal")}</dd>
				<dt>${t("wiz.summary_interest_collected_label")}</dt><dd>${sumBy("INTEREST_ONLY", "interest")}</dd>
				<dt>${t("game.stat_mass")}</dt><dd>${game.moneyMass}</dd>` : `
				<dt>${t("wiz.summary_du_distributed_label")}</dt><dd>${computeCurrentDU()}</dd>
				<dt>${t("game.stat_mass")}</dt><dd>${game.moneyMass}</dd>`}
			</dl>
			<label class="checkbox-label">
				<input type="checkbox" id="wizAutoStart" checked>
				${t("wiz.autostart_label", { n: game.turnNumber + 1 })}
			</label>
			<button type="button" class="btn btn-new-turn btn-block" id="wizFinish">${t("common.validate")}</button>`;
		el("wizFinish").onclick = async () => {
			const autoStart = el("wizAutoStart").checked;
			// Étape 3, monnaie libre : pose un WEALTH_CHECKPOINT pour CHAQUE joueur
			// actif (pas seulement les survivants) - voir Event.java pour le
			// raisonnement complet. Toujours posé, que "démarrer automatiquement le
			// tour suivant" soit coché ou non : ce point représente le solde
			// confirmé à l'instant où l'animateur valide cette dernière étape, pas
			// une conséquence du démarrage du tour suivant. Posé AVANT l'événement
			// TURN (voir plus bas) - StatsService.computeWealthOverTime lit cette
			// valeur fraîche au moment même où il traite le point TURN qui suit.
			//
			// Cas particulier des joueurs qui viennent de mourir/renaître CE
			// tour-ci (voir selectedDeathIds) : leur DEATH event ne représente que
			// ce qu'ils avaient AVANT renaissance (l'inventaire saisi à la mort),
			// pas leur nouveau départ - sans ce point de contrôle séparé pour eux
			// aussi, le pré-remplissage du PROCHAIN tour lirait à tort cette valeur
			// pré-mort comme "dernier solde connu". Leur renaissance se fait avec
			// le DU seul (voir plugins/libre/manifest.json, "onRebirth").
			if (!isDebt && !isTroc) {
				const du = computeCurrentDU();
				for (const p of game.players.filter((pl) => pl.active)) {
					let breakdown;
					if (selectedDeathIds.includes(p.id)) {
						breakdown = computeTokenBreakdown(du, game.weakCoinValue);
					} else {
						const coins = allPlayersMoneyInventory[p.id] || { weak: 0, medium: 0, strong: 0 };
						const currentValue = (coins.weak + (2 * coins.medium) + (4 * coins.strong)) * game.weakCoinValue;
						breakdown = computeTokenBreakdown(currentValue + du, game.weakCoinValue);
					}
					await Api.recordEvent(state.currentGameId, {
						type: "W", playerId: p.id, principal: 0, interest: 0,
						weakCoins: breakdown.weak, mediumCoins: breakdown.medium, strongCoins: breakdown.strong,
					});
				}
			}
			// Remonté par un utilisateur : une case à cocher (cochée par défaut, mais
			// décochable) permet de démarrer automatiquement le tour suivant en même
			// temps qu'on valide cette dernière étape, plutôt que de devoir fermer puis
			// recliquer séparément sur le bouton "Nouveau tour" du tableau de bord.
			if (autoStart) {
				await Api.recordEvent(state.currentGameId, { type: "T", playerId: null, principal: 0, interest: 0 });
			}
			dlg.close();
			restoreDefaultButtons();
			state.turnEnded = !autoStart;
			state.wizardOpen = false;
			renderGameDetail(state.currentGameId);
			// Remonté par un utilisateur : une info-bulle "Nouveau tour" doit
			// apparaître, symétrique à celle de "Fin de tour", quand le tour suivant
			// démarre automatiquement depuis cette case à cocher.
			if (autoStart) {
				const toast = el("turnEndToast");
				toast.textContent = "▶ " + window.GecoI18n.t("game.toast_new_turn");
				toast.classList.remove("hidden");
				setTimeout(() => { toast.classList.add("hidden"); toast.textContent = window.GecoI18n.t("game.toast_end_turn"); }, 3000);
			}
		};
	}

	dlg.showModal();
	// Remonté par un utilisateur, avec le document de spécification détaillé à
	// l'appui : à la fin du DERNIER tour, il n'y a jamais de mort (dans aucun des
	// deux systèmes de monnaie) - l'assistant doit vérifier que c'est bien la fin
	// de partie et aller directement à l'inventaire de sortie de tous les joueurs,
	// sans jamais passer par l'étape de sélection des morts. En monnaie dette,
	// renderStep0 (bilan des crédits en cours) reste malgré tout la première étape,
	// car les crédits en cours doivent être vérifiés avant que les joueurs ne
	// quittent la partie (cf. PDF : "l'assistant de fin de partie ... commence par
	// vérifier les crédits bancaires en cours").
	if (isLastTurn) {
		if (isDebt) renderStep0();
		else renderEndGameInventory();
	} else {
		// Tour normal : en monnaie libre aussi, il faut savoir qui meurt AVANT de
		// faire les inventaires/distribuer le DU - les deux systèmes démarrent donc
		// par la même étape de sélection des morts.
		renderStep2();
	}
}

// ---------- Navigation entre vues ----------
function showView(id) {
	document.querySelectorAll(".view").forEach((v) => v.classList.add("hidden"));
	el(id).classList.remove("hidden");
}

// ---------- Dialogues ----------
function openDialog(title, bodyHtml, onSubmit, onCancel) {
	el("dlgTitle").textContent = title;
	el("dlgBody").innerHTML = bodyHtml;
	renderIcons(el("dlgBody"));
	// Bug réel confirmé par une capture d'écran utilisateur : le bouton "Valider"
	// pouvait rester invisible et "Annuler" afficher "Fermer" si l'assistant de fin
	// de tour avait été utilisé juste avant (il les modifie temporairement pour ses
	// propres besoins). openDialog() restaure désormais SYSTÉMATIQUEMENT l'état par
	// défaut des deux boutons à chaque ouverture, plutôt que de compter sur chaque
	// appelant pour le faire correctement - la robustesse ne doit pas dépendre de
	// la discipline de tous les appelants.
	el("dlgOk").textContent = window.GecoI18n.t("common.validate");
	el("dlgOk").classList.remove("hidden");
	el("dlgCancel").textContent = window.GecoI18n.t("common.cancel");
	const dlg = el("dlg");
	dlg.showModal();
	el("dlgForm").onsubmit = async (e) => {
		e.preventDefault();
		// Attend la fin de onSubmit (souvent un appel réseau) avant de fermer, et ne
		// ferme PAS en cas d'erreur - ce qui permet à onSubmit d'afficher un message
		// d'erreur dans le corps du dialogue sans perdre la saisie de l'utilisateur
		// (ex: renommage avec un nom déjà pris). Bug trouvé en construisant cette
		// fonctionnalité : auparavant le dialogue se fermait immédiatement, sans
		// attendre, quel que soit le résultat de l'action.
		try {
			await onSubmit();
			dlg.close();
		}
		catch (err) {
			console.error(err);
		}
	};
	// Remonté par un utilisateur : "Annuler" pendant l'assistant de fin de tour
	// doit revenir à l'étape précédente plutôt que tout fermer. Comportement par
	// défaut inchangé (fermeture) pour tous les autres appels, qui ne fournissent
	// pas ce paramètre.
	el("dlgCancel").onclick = () => { dlg.close(); if (onCancel) onCancel(); };
}

// ---------- Actions ----------
function bindActions() {
	// Navigation sidebar : les boutons portant data-view basculent d'écran
	document.querySelectorAll("[data-view]").forEach((btn) => {
		btn.addEventListener("click", () => renderGamesList());
	});

	// Bouton "Connexion joueurs" (étape 3, Phase A).
	el("navConnect").addEventListener("click", () => renderConnect());

	// Documentation : accessible depuis l'accueil ET pendant une partie (l'animateur
	// peut vouloir s'y référer à tout moment), d'où deux boutons distincts appelant
	// la même fonction.
	el("navDocsHome").addEventListener("click", () => renderDocs());
	// Lien "Documentation" dans l'écran Connexion joueurs : pointe vers la page de
	// documentation dédiée à la connexion des joueurs (docs/<langue>/html/...),
	// PAS vers la documentation générale du jeu (monnaie dette/libre) qui n'a pas
	// de rapport avec cet écran - corrige un lien qui menait au mauvais contenu.
	// Ouvert dans un nouvel onglet pour ne pas interrompre la partie en cours.
	function fixConnectDocsLink() {
		const link = document.getElementById("connectDocsLink");
		if (!link) return;
		const lang = (window.GecoI18n && window.GecoI18n.getActiveLang()) || "fr";
		link.href = `/docs/${lang}/html/connexion-joueurs.html`;
		link.target = "_blank";
		link.rel = "noopener";
	}
	fixConnectDocsLink();
	// Le lien est recréé à chaque (re)traduction (voir data-i18n-html dans
	// i18n.js) : on le corrige de nouveau à chaque fois plutôt qu'une seule fois.
	if (window.GecoI18n) window.GecoI18n.onChange(fixConnectDocsLink);

	// Remonté par un utilisateur, en traitant l'écran "Nouvelle partie" : les
	// textes générés dynamiquement (résumé, badge "en ligne", cartes de parties)
	// ne passent pas par data-i18n - il faut donc explicitement les régénérer à
	// chaque changement de langue, sinon ils restent bloqués dans l'ancienne
	// langue jusqu'au prochain événement qui les redessine.
	if (window.GecoI18n) window.GecoI18n.onChange(() => {
		if (!el("view-games").classList.contains("hidden")) { updateNewGameSummary(); renderGamesList(); }
		if (!el("view-all-games").classList.contains("hidden")) renderAllGames();
		if (!el("view-game").classList.contains("hidden") && state.currentGameId) renderGameDetail(state.currentGameId);
		if (!el("view-settings").classList.contains("hidden")) renderSettingsView();
		if (!el("view-docs").classList.contains("hidden")) renderDocs();
		setConnStatus(el("connStatus").classList.contains("conn-online"));
	});

	el("navDocsGame").addEventListener("click", () => renderDocs());

	// "Nouvelle partie" (menu) : ramène à l'écran de création + 5 dernières parties.
	el("navNewGame").addEventListener("click", () => renderGamesList());

	// "Parties récentes" (menu) et "Voir + →" (écran Nouvelle partie) mènent tous
	// deux à la liste complète des parties, avec suppression possible.
	el("navRecent").addEventListener("click", () => renderAllGames());
	el("btnSeeAllGames").addEventListener("click", () => renderAllGames());

	// "Statistiques" (menu, hors partie) : comparaison de plusieurs parties, voir
	// renderCompareView (équivalent web de ChooseGamesDialog + StatsFrame(List<Game>)).
	el("navCompare").addEventListener("click", () => renderCompareView());
	el("navSettings").addEventListener("click", () => renderSettingsView());
	// Remonté par un utilisateur : le mode strict TRM verrouille le facteur
	// carte/monnaie à 1 - terrain de jeu simplifié pour ce mode, sans facteur à
	// gérer. Le champ redevient modifiable si l'animateur décoche l'option.
	el("fStrictTrm").addEventListener("change", () => {
		const factorField = el("fMoneyCardsFactor");
		factorField.disabled = el("fStrictTrm").checked;
		if (el("fStrictTrm").checked) factorField.value = "1";
	});
	el("btnCompareStandard").addEventListener("click", () => { compareMode = "standard"; renderComparisonChart(); });
	el("btnCompareCorrected").addEventListener("click", () => { compareMode = "corrected"; renderComparisonChart(); });

	// Onglets "en partie" (Partie en cours / Joueurs / Événements) : on affiche/masque
	// les panneaux correspondants dans le tableau de bord. On y revient aussi si on
	// était sur la vue "Statistiques" (Phase D), d'où le showView() explicite ici.
	document.querySelectorAll("#navGame [data-tab]").forEach((btn) => {
		btn.addEventListener("click", () => {
			document.querySelectorAll("#navGame .active").forEach((b) => b.classList.remove("active"));
			btn.classList.add("active");
			showView("view-game");
			if (state.currentGame) startTurnTimer(state.currentGame); // relance si on revenait de la vue rapport
			const tab = btn.dataset.tab;
			document.querySelectorAll(".game-layout > .panel-card").forEach((panel) => panel.classList.remove("hidden"));
			if (tab === "players") document.querySelector(".game-layout > .panel-card:nth-child(2)").classList.add("hidden");
			if (tab === "events") document.querySelector(".game-layout > .panel-card:nth-child(1)").classList.add("hidden");
		});
	});

	// Bouton "Statistiques" du menu : ouvre le rapport de fin de partie (Phase D).
	el("navStats").addEventListener("click", () => {
		document.querySelectorAll("#navGame .active").forEach((b) => b.classList.remove("active"));
		el("navStats").classList.add("active");
		renderReport(state.currentGameId);
	});

	// Sélecteur type de monnaie (écran "Nouvelle partie") : remonté par un
	// utilisateur, les choix proposés ici dépendent maintenant des systèmes
	// activés dans les Paramètres (voir plugins/*/manifest.json et l'écran
	// Paramètres) plutôt que de deux boutons figés en dur - les boutons sont
	// donc générés dynamiquement, voir renderMoneyTypeChoices()/
	// wireMoneyChoiceClicks(), appelées depuis renderGamesList() à chaque
	// affichage de l'écran (pas ici, au chargement de la page, puisque les
	// boutons n'existent pas encore à ce moment-là).

	el("fTurns").addEventListener("change", (e) => {
		state.newGame.turns = parseInt(e.target.value, 10);
		updateNewGameSummary();
	});

	// Remonté par un utilisateur : reprendre les joueurs d'une partie existante
	// (option A, choisie pour l'étape 2) - utile pour comparer monnaie dette et
	// monnaie libre avec les mêmes joueurs, l'intérêt même du jeu. S'appuie
	// uniquement sur les routes déjà existantes (liste des parties, ajout de
	// joueur), pas de nouvelle route nécessaire.
	document.querySelectorAll('input[name="playerSource"]').forEach((radio) => {
		radio.addEventListener("change", async () => {
			const reuse = el("fPlayerSourceReuse").checked;
			el("fReusePlayersBlock").classList.toggle("hidden", !reuse);
			if (!reuse || el("fReuseGameSelect").options.length > 0) return;
			// Chargement paresseux : seulement au premier choix de "Reprendre",
			// pour ne pas interroger le serveur si l'animateur ne s'en sert jamais.
			const games = await Api.listGames();
			el("fReuseGameSelect").innerHTML = games
				.map((g) => `<option value="${g.id}">${escapeHtml(g.description || "Partie #" + g.id)}</option>`).join("");
			if (games.length > 0) await loadReusePlayersList(games[0].id);
		});
	});
	el("fReuseGameSelect").addEventListener("change", (e) => loadReusePlayersList(parseInt(e.target.value, 10)));
	async function loadReusePlayersList(gameId) {
		const game = await Api.getGame(gameId);
		el("fReusePlayersList").innerHTML = game.players.length === 0
			? "<li>Cette partie n'a aucun joueur.</li>"
			: game.players.map((p) => `
				<li><label><input type="checkbox" class="fReusePlayerCheck" value="${escapeHtml(p.name)}" checked> ${escapeHtml(p.name)}</label></li>`).join("");
	}

	el("fTurnDuration").addEventListener("change", (e) => {
		state.newGame.turnDuration = parseInt(e.target.value, 10);
		updateNewGameSummary();
	});

	el("btnNewGame").onclick = async () => {
		// Garde-fou (le bouton est déjà désactivé dans ce cas, voir
		// selectMoneyChoice() - ceci protège contre un appel direct malgré tout,
		// ex. depuis les outils de développement) : impossible de créer une
		// partie dans un système que le moteur ne sait pas encore faire
		// fonctionner (voir plugin.engineReady).
		if (state.newGame.moneySystem === undefined) {
			alert(window.GecoI18n.t("newgame.not_playable_yet_title"));
			return;
		}
		const game = await Api.createGame({
			moneySystem: state.newGame.moneySystem,
			nbTurnsPlanned: state.newGame.turns,
			animatorPseudo: el("fAnimatorName").value,
			animatorEmail: "",
			description: el("fDesc").value,
			curDate: new Date().toISOString().slice(0, 10),
			location: el("fLoc").value,
			moneyCardsFactor: parseInt(el("fMoneyCardsFactor").value || "1", 10),
			weakCoinValue: parseFloat(el("fWeakCoinValue").value || "1"),
			tokenPenalty: el("fTokenPenalty").checked,
			turnDurationSeconds: state.newGame.turnDuration * 60,
			// Troc uniquement (voir plugins/troc/manifest.json) : 0 signifie "garder
			// la valeur par défaut du moteur" (voir GameService.createGame).
			startingGoods: 0,
			// Monnaie libre uniquement (voir Game.strictTrm côté moteur) - false pour
			// les deux autres systèmes, où le réglage n'a aucun effet de toute façon.
			strictTrm: state.newGame.pluginId === "libre" && el("fStrictTrm").checked,
		});
		// Remonté par un utilisateur : le PIN tout juste généré (si la protection
		// est active) doit être mémorisé IMMÉDIATEMENT, avant tout autre appel API
		// concernant cette partie (voir renderGameDetail juste après, qui
		// échouerait sinon en 403 sans que l'animateur ait eu la moindre chance de
		// connaître ce PIN - c'est exactement le bug remonté et corrigé ici).
		if (game.pin) {
			storeGamePin(game.id, game.pin);
			alert(window.GecoI18n.t("game.pin_created_alert", { pin: game.pin }));
		}
		// Reprise des joueurs d'une partie existante (option A) : les ajoute un par
		// un après la création, en réutilisant simplement la route d'ajout de
		// joueur déjà existante - pas de mécanisme spécial nécessaire.
		if (el("fPlayerSourceReuse").checked) {
			const names = [...document.querySelectorAll(".fReusePlayerCheck:checked")].map((cb) => cb.value);
			for (const name of names) {
				await Api.addPlayer(game.id, name);
			}
		}
		renderGameDetail(game.id);
	};

	el("btnAddPlayer").onclick = () => {
		openDialog(window.GecoI18n.t("game.add_player_title"), `
			<label>${window.GecoI18n.t("game.add_player_name_label")}</label>
			<input id="fName" type="text" autofocus>
		`, async () => {
			await Api.addPlayer(state.currentGameId, el("fName").value);
			renderGameDetail(state.currentGameId);
		});
	};

	// Étape 3 : invitation par QR code/lien, propre à CETTE partie (voir
	// index.html pour le raisonnement complet - ce bouton avait été retiré à
	// l'étape 2, prévu pour revenir ici une fois l'étape 3 commencée).
	// Bascule affiché/masqué plutôt qu'une boîte de dialogue séparée : les
	// joueurs scannent souvent en regardant l'écran de l'animateur par-dessus
	// son épaule, pas confortable dans une modale qui se referme au clic
	// extérieur.
	el("btnInvitePlayers").onclick = () => renderInviteQr(state.currentGameId);
	el("btnToggleTransactions").onclick = () => toggleTransactionsPanel(state.currentGameId);

	// Ouvre l'assistant de fin de tour (résumé -> décès -> nouveaux-nés -> préparation)
	// au lieu d'enregistrer directement l'événement "nouveau tour".
	// "Fin de tour" ouvre le bilan complet (remboursements, décès, nouveaux
	// crédits) - distinct de "Nouveau tour" ci-dessous, qui ne fait qu'enregistrer
	// l'événement de tour et relancer le chrono, sans aucune autre étape.
	// Remonté par un utilisateur, avec un document de spécification détaillé : la
	// fin d'un tour est déclenchée soit par le compte à rebours à 0, soit par un
	// clic manuel ici - les deux doivent afficher la même info-bulle.
	el("btnEndTurn").onclick = () => {
		// Remonté par un utilisateur : le chrono continuait de tourner en arrière-
		// plan après un clic manuel sur "Fin de tour" (seul le passage naturel à 0
		// l'arrêtait indirectement, via la garde sur le compte à rebours dans
		// update()) - arrêté ici explicitement, comme il se doit dès que le tour
		// est terminé, quelle que soit la façon dont ça a été déclenché.
		stopTurnTimer();
		playWhistle("stop");
		const toast = el("turnEndToast");
		toast.textContent = state.currentGame.turnNumber >= state.currentGame.nbTurnsPlanned
			? window.GecoI18n.t("game.toast_end_last_turn") : window.GecoI18n.t("game.toast_end_turn");
		toast.classList.remove("hidden");
		setTimeout(() => { toast.classList.add("hidden"); toast.textContent = window.GecoI18n.t("game.toast_end_turn"); }, 3000);
		openEndOfTurnWizard();
	};

	// Remonté par un utilisateur (avec schéma à l'appui) : "Nouveau tour" doit être
	// une action simple et directe, sans fenêtre, qui enregistre l'événement de
	// tour et relance immédiatement le chrono.
	el("btnStartNewTurn").onclick = async () => {
		// Remonté par un utilisateur : impossible de se retrouver avec un tour au-delà
		// de ceux prévus (ex. tour 9/8) - double garde-fou, ici et sur la visibilité
		// du bouton lui-même (voir plus haut dans renderGameDetail).
		if (state.currentGame.turnNumber >= state.currentGame.nbTurnsPlanned) return;
		// Remonté par un utilisateur : le bouton ne doit pas rester cliquable pendant
		// qu'un tour est en cours - désactivé immédiatement ici (avant même l'appel
		// réseau) pour empêcher un double-clic rapide d'enregistrer deux fois
		// l'événement de tour avant que l'affichage n'ait eu le temps de se mettre à
		// jour, en plus du masquage habituel une fois la partie rafraîchie.
		el("btnStartNewTurn").disabled = true;
		await Api.recordEvent(state.currentGameId, { type: "T", playerId: null, principal: 0, interest: 0 });
		playWhistle("start");
		state.turnEnded = false;
		renderGameDetail(state.currentGameId);
	};

	// Actions générales de la partie (remonté par un utilisateur : disponibles
	// directement sur la page plutôt que dans un formulaire générique mélangé avec
	// les actions par joueur - voir openGeneralEventForm ci-dessus).
	el("btnMoneyMassChange").onclick = () => openGeneralEventForm(window.GecoI18n.t("game.dialog_money_mass_title"), "M", "none");
	el("btnPlayerQuit").onclick = () => openPlayerQuitDialog();
	el("btnTechBreakthrough").onclick = () => openGeneralEventForm(window.GecoI18n.t("game.action_tech"), "X", "required");
	el("btnBankInvestment").onclick = () => openBankForm(window.GecoI18n.t("game.action_bank_investment"), "S", window.GecoI18n.t("game.bank_form_invested_amount"));
	el("btnBankAssessment").onclick = () => openBankForm(window.GecoI18n.t("game.action_bank_assessment"), "A", window.GecoI18n.t("game.bank_form_final_amount"));
	el("btnTrocTrade").onclick = () => openTrocTradeDialog();
	el("btnEndGame").onclick = () => openGeneralEventForm(window.GecoI18n.t("game.action_end_game"), "E", "none");

	// Démarre effectivement la partie (lance le chrono) : distinct de la création,
	// remonté par un utilisateur pour que le chrono ne tourne pas "dans le vide"
	// pendant que l'animateur configure encore ses joueurs. Bug corrigé : ce
	// premier démarrage doit enregistrer un vrai événement de tour (comme le fait
	// le bouton "Nouveau tour" par la suite), pas seulement démarrer le chrono en
	// silence - sinon rien n'apparaît dans l'historique des événements pour le
	// début du tour 1.
	el("btnStartGame").onclick = async () => {
		await Api.recordEvent(state.currentGameId, { type: "T", playerId: null, principal: 0, interest: 0 });
		playWhistle("start");
		renderGameDetail(state.currentGameId);
	};

	// Remonté par un utilisateur : la pause doit être partagée par tous les écrans
	// connectés (tableau de bord ET assistant de fin de tour), pas seulement
	// visuelle sur l'écran qui a cliqué "Pause" - d'où un vrai appel API plutôt
	// qu'un simple indicateur local.
	el("btnTimerPause").onclick = async () => {
		if (state.currentGame.pausedRemainingSeconds != null) {
			await Api.resumeTurn(state.currentGameId);
			playWhistle("start");
		} else {
			await Api.pauseTurn(state.currentGameId);
			playWhistle("stop");
		}
		renderGameDetail(state.currentGameId);
	};
	el("btnTimerExtend").onclick = async () => {
		await Api.extendTurn(state.currentGameId, 30);
		renderGameDetail(state.currentGameId);
	};

	// Annule la dernière action enregistrée (équivalent touche [z] de l'app Swing
	// originale). Rappelable plusieurs fois de suite pour annuler successivement
	// plusieurs actions.
	el("btnUndo").onclick = async () => {
		try {
			await Api.undo(state.currentGameId);
			renderGameDetail(state.currentGameId);
		} catch (err) {
			alert(window.GecoI18n.t("game.undo_nothing_alert"));
		}
	};
}

function escapeHtml(str) {
	const div = document.createElement("div");
	div.textContent = str ?? "";
	return div.innerHTML;
}

// ---------- Zoom plein écran sur un graphique (Statistiques) ----------
// Remonté par un utilisateur : un bouton sur chaque graphique l'ouvre en grand,
// par-dessus l'écran, avec une animation de zoom, une bordure qui encadre et une
// croix pour fermer (voir #chartZoomOverlay dans index.html). Générique : un seul
// mécanisme pour TOUS les graphiques Chart.js de l'application (actuels et futurs),
// plutôt qu'un bouton câblé à la main pour chacun.
// Remonté par un utilisateur, avec la trace d'erreur exacte de la console
// ("TypeError: t.startsWith is not a function" dans chart.umd.js) : s'appuyer sur
// Chart.getChart() puis relire chart.options ne fonctionne PAS, parce que
// chart.options n'est plus un objet normal une fois le graphique construit -
// c'est un Proxy interne de résolution des options, que Chart.js refuse de
// réutiliser tel quel pour un second graphique. La solution retenue : voir
// trackChart() ci-dessus, qui conserve la config d'origine (plate, non
// transformée) de chaque graphique dans mChartOriginalConfigs.
let mZoomChart = null;
function openChartZoom(sourceCanvas, title) {
	if (typeof Chart === "undefined") {
		console.error("Zoom impossible : Chart.js n'est pas chargé.");
		return;
	}
	const original = mChartOriginalConfigs.get(sourceCanvas);
	if (!original) {
		console.error("Zoom impossible : aucune configuration d'origine retrouvée pour ce <canvas>.", sourceCanvas);
		return;
	}
	el("chartZoomTitle").textContent = title || "";
	if (mZoomChart) mZoomChart.destroy();
	// On ne déplace jamais le <canvas> d'origine (Chart.js n'aime pas qu'on lui
	// retire son canvas pendant qu'une instance y est attachée) : on recrée une
	// deuxième instance, dans le canvas du zoom, avec le même type/données/options
	// - la donnée (`data`) est la MÊME référence d'objet que celle du petit
	// graphique (mise à jour automatiquement si ce dernier change en direct,
	// ex. le camembert de la partie en cours), mais les "options" sont clonées
	// pour ne jamais faire fuiter maintainAspectRatio/animation vers le petit
	// graphique d'origine.
	mZoomChart = new Chart(el("chartZoomCanvas"), {
		type: original.type,
		data: original.data,
		options: { ...original.options, maintainAspectRatio: false, animation: false },
	});
	el("chartZoomOverlay").classList.remove("hidden");
}

function closeChartZoom() {
	el("chartZoomOverlay").classList.add("hidden");
	if (mZoomChart) { mZoomChart.destroy(); mZoomChart = null; }
}

function initChartZoomButtons() {
	el("chartZoomClose").addEventListener("click", closeChartZoom);
	el("chartZoomOverlay").addEventListener("click", (e) => { if (e.target === el("chartZoomOverlay")) closeChartZoom(); });
	document.addEventListener("keydown", (e) => {
		if (e.key === "Escape" && !el("chartZoomOverlay").classList.contains("hidden")) closeChartZoom();
	});
	// Un bouton par conteneur de graphique existant dans la page au chargement -
	// ajouté une seule fois (les futurs rendus/mises à jour de graphiques
	// réutilisent le même <canvas>, pas besoin de reposer le bouton à chaque fois).
	// Remonté par un utilisateur : le zoom est retiré des graphiques du tableau
	// de bord "Partie en cours" (masse monétaire, camembert crédits/richesse) -
	// il reste disponible sur les écrans Statistiques/Comparer des parties.
	// Chaque conteneur est traité indépendamment (try/catch) : un souci sur l'un
	// d'entre eux ne doit jamais empêcher les autres boutons d'être posés (un
	// .forEach() normal se serait arrêté net au premier throw).
	document.querySelectorAll(".chart-container canvas[id]").forEach((canvas) => {
		try {
			if (canvas.closest("#view-game")) return;
			const container = canvas.closest(".chart-container");
			if (!container) {
				console.error("Zoom : impossible de trouver le conteneur .chart-container pour ce canvas.", canvas.id);
				return;
			}
			if (container.querySelector(".chart-zoom-btn")) return;
			const btn = document.createElement("button");
			btn.type = "button";
			btn.className = "chart-zoom-btn";
			btn.title = "Agrandir";
			btn.setAttribute("aria-label", "Agrandir ce graphique");
			btn.textContent = "⛶";
			btn.addEventListener("click", () => {
				try {
					const titleEl = container.closest(".panel-card")?.querySelector(".chart-title");
					openChartZoom(canvas, titleEl ? titleEl.textContent.trim() : "");
				} catch (err) {
					console.error("Zoom : erreur au clic sur le bouton d'agrandissement.", err);
				}
			});
			container.appendChild(btn);
		} catch (err) {
			console.error("Zoom : impossible de poser le bouton d'agrandissement sur ce graphique.", canvas.id, err);
		}
	});
}

// ---------- Init ----------
// Remonté par un utilisateur (le bouton de zoom ne réagissait pas du tout, sans la
// moindre erreur visible) : chaque étape d'initialisation est isolée dans son
// propre try/catch. Avant ce correctif, une erreur non attrapée dans n'importe
// laquelle de ces fonctions (ex. bindActions(), très volumineuse) aurait empêché
// TOUTES les suivantes de s'exécuter - y compris initChartZoomButtons() - sans
// qu'aucun message ne s'affiche nulle part si la console n'était pas ouverte.
function safeInit(label, fn) {
	try {
		fn();
	} catch (err) {
		console.error(`Échec de l'initialisation "${label}" :`, err);
	}
}
safeInit("renderIcons", renderIcons);
safeInit("bindActions", bindActions);
safeInit("initChartZoomButtons", initChartZoomButtons);
safeInit("refreshAppSettings", refreshAppSettings);
safeInit("connectWs", connectWs);
safeInit("renderGamesList", renderGamesList);
