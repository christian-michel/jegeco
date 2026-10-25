// player-view.js — "Mon espace joueur" (étape 3, mode smartphone). Remplace
// l'ancien script inline de player-view.html : même principe d'accès (jeton
// individuel dans l'URL, voir Player.accessToken), étendu avec le flux
// d'achat/vente de cartes par QR code (voir §5.1 du cahier des charges,
// décisions prises avec l'utilisateur le 27/08/2026, puis ajustées le même
// jour après relecture de docs/05-etape3-connectivite.md) :
//   - Offre créée côté SERVEUR (voir TradeOfferService), identifiée par un
//     code COURT (6 caractères, alphabet non ambigu) - le QR n'encode que ce
//     code (scan plus rapide/fiable qu'un gros JSON), et le même code peut
//     être tapé à la main par l'acheteur : un seul mécanisme pour les deux
//     usages, plutôt que deux systèmes parallèles.
//   - Scan par CAMÉRA (bibliothèque jsQR) ET saisie manuelle en repli (voir
//     docs/05-etape3-connectivite.md, qui prévoyait déjà ce repli).
//   - Anti-rejeu : le code est retiré du serveur de façon atomique dès sa
//     consommation (usage unique réel, pas juste un nonce côté client).
//
// Portée volontairement limitée (comme le modèle de données Transaction) :
// la carte vendue est choisie dans le catalogue "Cartes" (le TYPE de bien),
// pas dans un inventaire personnel réel - ce logiciel ne sait pas encore
// quels biens précis un joueur détient (voir Player.weakCards/mediumCards/
// strongCards, qui ne comptent que par NIVEAU, pas par bien nommé). Tant
// qu'un vrai inventaire par bien n'existe pas, le vendeur choisit simplement
// "ce que je veux vendre maintenant" dans le catalogue complet.

const t = (key, vars) => (window.GecoI18n ? window.GecoI18n.t(key, vars) : key);
const el = (id) => document.getElementById(id);

// Étape 3 (30/08/2026) : icônes reprises du design de référence fourni par
// l'utilisateur (bibliothèque Lucide, https://lucide.dev, licence ISC) -
// SVG intégrés ICI plutôt que chargés depuis unpkg.com par CDN comme dans
// l'exemple fourni : l'app doit rester utilisable sans accès internet réel
// pendant un atelier (même principe déjà appliqué aux polices et à la
// bibliothèque de QR code, voir js/vendor/). currentColor hérite la couleur
// du texte de l'élément parent, exactement comme le ferait la police d'icônes.
const ICONS_SVG = {
	home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><path d="M9 22V12h6v10"></path></svg>',
	inbox: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-6l-2 3h-4l-2-3H2"></path><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"></path></svg>',
	"bar-chart-2": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>',
	user: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>',
	scan: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h2"></path><path d="M17 3h2a2 2 0 0 1 2 2v2"></path><path d="M21 17v2a2 2 0 0 1-2 2h-2"></path><path d="M7 21H5a2 2 0 0 1-2-2v-2"></path></svg>',
	"chevron-left": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>',
	"chevron-down": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>',
	"chevron-right": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>',
	"edit-2": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"></path></svg>',
	"refresh-cw": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v5h5"></path><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path><path d="M16 16h5v5"></path></svg>',
	"sliders-horizontal": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="21" y1="4" x2="14" y2="4"></line><line x1="10" y1="4" x2="3" y2="4"></line><line x1="21" y1="12" x2="12" y2="12"></line><line x1="8" y1="12" x2="3" y2="12"></line><line x1="21" y1="20" x2="16" y2="20"></line><line x1="12" y1="20" x2="3" y2="20"></line><line x1="14" y1="2" x2="14" y2="6"></line><line x1="8" y1="10" x2="8" y2="14"></line><line x1="16" y1="18" x2="16" y2="22"></line></svg>',
	"layout-grid": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="14" width="7" height="7" rx="1"></rect><rect x="3" y="14" width="7" height="7" rx="1"></rect></svg>',
	list: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"></line><line x1="8" y1="12" x2="21" y2="12"></line><line x1="8" y1="18" x2="21" y2="18"></line><line x1="3" y1="6" x2="3.01" y2="6"></line><line x1="3" y1="12" x2="3.01" y2="12"></line><line x1="3" y1="18" x2="3.01" y2="18"></line></svg>',
	history: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v5h5"></path><path d="M12 7v5l4 2"></path></svg>',
	keyboard: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"></rect><path d="M6 8h.01"></path><path d="M10 8h.01"></path><path d="M14 8h.01"></path><path d="M18 8h.01"></path><path d="M6 12h.01"></path><path d="M10 12h.01"></path><path d="M14 12h.01"></path><path d="M18 12h.01"></path><path d="M7 16h10"></path></svg>',
	zap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>',
};
function iconSvg(name) {
	return ICONS_SVG[name] || "";
}

// Avatar choisi par le joueur à l'inscription (voir player.js,
// buildAvatarConfigForSubmit/avatarConfigJson - même structure, portée ici
// telle quelle) - remonté par l'utilisateur (30/08/2026) : l'écran "Mon
// profil" doit reprendre cette même image, pas un simple emoji générique.
// Deux formes possibles : {type:"gallery", filename} - une vraie image du
// catalogue d'avatars - ou {type:"custom", ...} - un portrait paramétrique,
// généré ici en SVG (fonction dupliquée depuis player.js : les deux pages
// sont chargées séparément, pas de module partagé dans ce projet).
function buildAvatarSVG(cfg) {
	const skin = cfg.skinColor;
	const hairColor = cfg.hairColor;
	let hair = "";
	if (cfg.hairStyle === "short") {
		hair = `<path d="M14 42 Q50 2 86 42 L86 26 Q50 10 14 26 Z" fill="${hairColor}"/>`;
	} else if (cfg.hairStyle === "long") {
		hair = `<path d="M12 46 Q50 -2 88 46 L91 92 L79 92 L76 52 Q50 22 24 52 L21 92 L9 92 Z" fill="${hairColor}"/>`;
	} else if (cfg.hairStyle === "curly") {
		hair = `<circle cx="23" cy="36" r="13" fill="${hairColor}"/><circle cx="40" cy="20" r="14" fill="${hairColor}"/>`
			+ `<circle cx="60" cy="20" r="14" fill="${hairColor}"/><circle cx="77" cy="36" r="13" fill="${hairColor}"/>`;
	}
	let accessory = "";
	if (cfg.accessory === "glasses") {
		accessory = `<g stroke="#1f2430" stroke-width="3" fill="none">`
			+ `<circle cx="37" cy="53" r="11"/><circle cx="63" cy="53" r="11"/><line x1="48" y1="53" x2="52" y2="53"/></g>`;
	} else if (cfg.accessory === "hat") {
		accessory = `<rect x="22" y="8" width="56" height="11" rx="3" fill="#1f2430"/>`
			+ `<rect x="33" y="-4" width="34" height="18" rx="5" fill="#1f2430"/>`;
	}
	return `<svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
		<circle cx="50" cy="56" r="38" fill="${skin}"/>
		<circle cx="38" cy="53" r="4" fill="#1f2430"/>
		<circle cx="62" cy="53" r="4" fill="#1f2430"/>
		<path d="M40 69 Q50 77 60 69" stroke="#1f2430" stroke-width="3" fill="none" stroke-linecap="round"/>
		${hair}
		${accessory}
	</svg>`;
}

// Construit le HTML à injecter dans .avatar-wrapper (voir renderProfile) -
// image réelle si l'avatar vient de la galerie, SVG généré si personnalisé,
// repli sur l'emoji générique si la configuration est absente/corrompue
// (ex. joueur ajouté manuellement par l'animateur, sans jamais passer par
// l'auto-inscription smartphone).
function buildProfileAvatarHtml(pAvatarConfigJson) {
	if (!pAvatarConfigJson) return `<span aria-hidden="true">🧑</span>`;
	try {
		const cfg = JSON.parse(pAvatarConfigJson);
		if (cfg.type === "gallery" && cfg.filename) {
			return `<img src="/avatars/${escapeHtmlLocal(cfg.filename)}" class="avatar-img" alt=""
				onerror="this.outerHTML='<span aria-hidden=&quot;true&quot;>🧑</span>'">`;
		}
		if (cfg.type === "custom") return buildAvatarSVG(cfg);
	} catch (err) {
		// Configuration corrompue (ne devrait jamais arriver) : repli silencieux.
	}
	return `<span aria-hidden="true">🧑</span>`;
}

// (Ancienne décomposition en jetons physiques faible/moyen/fort - retirée le
// 08/09/2026, remontée par l'utilisateur : "il faut aussi retirer
// l'affichage des jetons faibles moyens et forts sur les écrans d'accueil et
// de profil des joueurs". Sans plus aucun appelant depuis ce retrait -
// supprimée ici, ainsi que sa DUPLICATION accidentelle plus bas dans ce même
// fichier, jamais nécessaire : les deux pages ne sont PAS chargées
// séparément dans ce cas précis, contrairement à ce qu'indiquait le
// commentaire original.)


const params = new URLSearchParams(window.location.search);
// Remonté par l'utilisateur (09/09/2026) : "un ou plusieurs moyens à mettre
// en œuvre... pour ne plus avoir d'erreur silencieuse et toujours avoir des
// logs ou des pistes en cas de problème... aussi bien sur le serveur que sur
// les applications smartphone des joueurs" - suite directe de plusieurs
// sessions de débogage ralenties par l'absence d'outils de développement sur
// smartphone (impossible d'ouvrir la console du navigateur). Journal
// PERSISTANT en mémoire (survit à la navigation entre écrans, jamais à un
// rechargement complet de la page - un choix délibéré : pas besoin de
// stockage local, juste assez pour couvrir "quelque chose vient de mal se
// passer, montre-moi quoi" sans configuration ni permission supplémentaire).
// Consultable/copiable via un petit bouton dédié (voir wireDebugPanel plus
// bas) - jamais visible tant qu'aucune anomalie n'a été capturée, pour ne
// pas encombrer l'écran d'un joueur dont la partie se déroule normalement.
const debugLog = [];
const MAX_DEBUG_LOG_ENTRIES = 100; // borne raisonnable : assez pour reconstituer un incident, jamais une fuite mémoire sur une longue partie
function pushDebugLog(pLevel, ...pArgs) {
	const text = pArgs.map((a) => {
		if (a instanceof Error) return `${a.message}\n${a.stack || ""}`;
		if (typeof a === "object") { try { return JSON.stringify(a); } catch { return String(a); } }
		return String(a);
	}).join(" ");
	debugLog.push(`[${new Date().toISOString().slice(11, 19)}] ${pLevel}: ${text}`);
	if (debugLog.length > MAX_DEBUG_LOG_ENTRIES) debugLog.shift();
	// Affiche aussi dans la vraie console, pour ne rien perdre sur un appareil
	// où elle EST accessible (PC de test, débogage à distance Chrome/Safari).
	(pLevel === "ERREUR" ? console.error : console.log)(...pArgs);
	updateDebugBadge();
}
// Capture tout ce qui, autrement, ne serait JAMAIS visible sur un smartphone
// sans outils de développement : une exception JS non attrapée nulle part
// (window.onerror) et une Promise rejetée sans .catch (unhandledrejection,
// le piège le plus courant avec async/await - un oubli facile, silencieux
// par défaut dans tous les navigateurs).
window.addEventListener("error", (e) => {
	pushDebugLog("ERREUR", "Erreur JS non attrapée :", e.error || e.message, "à", `${e.filename}:${e.lineno}`);
});
window.addEventListener("unhandledrejection", (e) => {
	pushDebugLog("ERREUR", "Promesse rejetée non attrapée :", e.reason);
});
// Intercepte TOUS les appels fetch() de cette page - journalise automatiquement
// toute requête réseau qui échoue (statut non-2xx) ou qui ne peut même pas
// aboutir (serveur injoignable, coupure réseau...), sans avoir à modifier
// individuellement chaque appel existant ni futur. Le comportement réel
// (valeur retournée, exception éventuelle) reste EXACTEMENT identique - cette
// interception n'observe et ne journalise que ce qui se passe, jamais elle ne
// le modifie.
const nativeFetch = window.fetch.bind(window);
window.fetch = async (...pArgs) => {
	try {
		const res = await nativeFetch(...pArgs);
		if (!res.ok) pushDebugLog("ERREUR", "Requête échouée :", pArgs[0], "->", res.status);
		return res;
	} catch (err) {
		pushDebugLog("ERREUR", "Requête réseau impossible :", pArgs[0], "->", err.message);
		throw err;
	}
};

const state = {
	gameId: params.get("gameId"),
	token: params.get("token"),
	player: null,
	cardsCatalog: null,
	visualsCatalog: null,
	sellSelection: null, // { entry, visual } une fois une carte choisie
	sellPrice: { weak: 0, medium: 0, strong: 0 },
	sellExpandedGroups: new Set(),
	qrCountdownInterval: null,
	scanStream: null,
	scanRAF: null,
	pendingOffer: null, // TradeOfferDto résolu (scan ou saisie), en attente de confirmation
};

// ---------- Traduction des valeurs fixes du catalogue (même principe que côté animateur, voir app.js) ----------
function catalogEnumLabel(prefix, code) {
	if (!code) return "";
	const key = `catalog.${prefix}.${code}`;
	const translated = t(key);
	return translated === key ? code : translated;
}
function catalogTextValue(value) {
	if (!value || (typeof value !== "object")) return value || "";
	const lang = window.GecoI18n ? window.GecoI18n.getActiveLang() : "fr";
	if (value[lang]) return value[lang];
	if (value.fr) return value.fr;
	const first = Object.values(value).find((v) => v);
	return first || "";
}

const CARD_LEVELS = ["faible", "moyenne", "forte", "tresforte"];

// ---------- Navigation entre écrans (un seul visible à la fois, sauf viewError) ----------
const SCREENS = ["viewContent", "profileScreen", "sellPicker", "sellPrice", "sellQr", "scanCamera", "scanManual",
	"scanConfirm", "tradeResult", "myCardsScreen", "leaderboardScreen", "historyScreen", "creditRequestScreen"];

// Étape 3 (30/08/2026) : titre + bouton retour de l'en-tête (voir
// .app-header) selon l'écran affiché - les 4 onglets principaux
// (Accueil/Cartes/Stats/Profil) n'ont pas de bouton retour (on y accède par
// la barre de nav basse, jamais en \"remontant\"), tous les autres en ont un.
const SCREEN_HEADERS = {
	viewContent: { titleKey: "playerView.nav_home", back: null },
	profileScreen: { titleKey: "playerView.nav_profile_short", back: null },
	myCardsScreen: { titleKey: "playerView.nav_my_cards", back: null },
	leaderboardScreen: { titleKey: "playerView.nav_leaderboard", back: null },
	sellPicker: { titleKey: "trade.sell_pick_title", back: "viewContent" },
	sellPrice: { titleKey: "trade.sell_price_title", back: "sellPicker" },
	sellQr: { titleKey: "trade.btn_sell", back: "sellPrice" },
	// scanCamera : pas d'entrée ici - cet écran a désormais son propre en-tête
	// intégré (.scanner-header, voir player-view.html), il ne passe plus par
	// .app-header/updateAppHeader() comme les autres.
	scanManual: { titleKey: "trade.manual_entry_title", back: "scanCamera" },
	// BUG TROUVÉ ET CORRIGÉ (10/10/2026, second agent de revue - campagne de
	// validation troc+smartphone, capture
	// "005-Partie1-4j-troc-v2-refus-reciprocite.png") : cette clé restait
	// figée sur "trade.confirm_title" ("Confirmer l'achat ?") même en troc,
	// où renderScanConfirm() adapte pourtant déjà CORRECTEMENT le <h2> du
	// CORPS de l'écran ("trade.confirm_title_swap", voir son commentaire du
	// 18/09/2026) - mais resolveCode() appelle renderScanConfirm(offer) PUIS
	// showScreen("scanConfirm"), et ce second appel (via updateAppHeader
	// ci-dessous) écrasait toute valeur dynamique avec cette clé statique :
	// l'EN-TÊTE (.app-header, barre du haut) affichait donc "Confirmer
	// l'achat ?" pendant que le CORPS affichait "Confirmer l'échange ?" -
	// repéré sur une capture d'écran réelle d'un échange troc, jamais un
	// achat (docs/10-etape-plugins-troc.md, règle 6). titleKey accepte
	// désormais aussi une fonction (voir updateAppHeader plus bas) pour ce
	// seul écran dont le titre dépend du système monétaire - corrige la
	// cause racine plutôt que l'ordre d'appel resolveCode (plus robuste si
	// un futur appelant montre cet écran sans repasser par renderScanConfirm
	// après showScreen).
	scanConfirm: { titleKey: () => (isTrocGame() ? "trade.confirm_title_swap" : "trade.confirm_title"), back: "viewContent" },
	tradeResult: { titleKey: "playerView.nav_home", back: null },
	historyScreen: { titleKey: "playerView.nav_history", back: "profileScreen" },
	creditRequestScreen: { titleKey: "playerView.credit_request_title", back: "viewContent" },
};

function showScreen(id) {
	stopCamera(); // toujours couper la caméra en quittant scanCamera, quel que soit l'écran de destination
	clearQrCountdown();
	SCREENS.forEach((s) => el(s).classList.toggle("hidden", s !== id));
	updateAppHeader(id);
	// Remet le bon onglet actif dans la barre de nav basse - "Accueil" au
	// retour au hub, "Profil" pour l'historique (accessible uniquement depuis
	// l'onglet Profil, voir SCREEN_HEADERS), sinon aucun changement (un écran
	// de vente/achat n'appartient à aucun onglet précis).
	if (id === "viewContent") setActiveNav("navBtnHome");
	else if (id === "historyScreen") setActiveNav("navBtnProfile");
}

// Met à jour le titre + bouton retour de l'en-tête (voir .app-header et
// SCREEN_HEADERS) - appelée à chaque changement d'écran (showScreen).
function updateAppHeader(id) {
	const config = SCREEN_HEADERS[id];
	if (!config) return;
	// titleKey est une simple clé statique pour la grande majorité des
	// écrans, mais peut aussi être une fonction qui la calcule au moment de
	// l'affichage (10/10/2026, voir le commentaire détaillé sur
	// scanConfirm dans SCREEN_HEADERS ci-dessus) - nécessaire pour le seul
	// écran dont le titre dépend du système monétaire (isTrocGame()),
	// jamais figé à l'ouverture du fichier comme le serait une clé simple.
	const titleKey = typeof config.titleKey === "function" ? config.titleKey() : config.titleKey;
	el("appHeaderTitle").textContent = t(titleKey);
	const backBtn = el("appHeaderBack");
	backBtn.classList.toggle("hidden", !config.back);
	backBtn.innerHTML = config.back ? iconSvg("chevron-left") : "";
	backBtn.onclick = config.back ? () => showScreen(config.back) : null;
}

// Met en évidence l'onglet actif de la barre de navigation basse (voir
// player-view.html) - un seul actif à la fois.
function setActiveNav(activeId) {
	["navBtnHome", "navBtnCards", "navBtnStats", "navBtnProfile"].forEach((id) => {
		el(id).classList.toggle("active", id === activeId);
	});
}

// ---------- Consultation (ex-refresh() de l'ancienne version de cette page) ----------
// Étape 3, troc : vrai si la partie courante utilise le troc (voir
// Game.MONEY_TROC = 2, déjà exposé au client via PlayerSelfViewDto.
// moneySystem, ajouté le 28/08/2026) - détermine si les écrans de vente/achat
// parlent de jetons (dette/libre) ou de cartes données en échange (troc).
function isTrocGame() {
	return state.player && (state.player.moneySystem === 2);
}

// Étape 3, monnaie libre : symétrique de isTrocGame()/isDebtGame() - voir
// Game.MONEY_LIBRE = 0. Détermine si le stock de cartes proposé à la vente
// doit être contraint par l'inventaire réel du joueur (voir openSellPicker).
function isLibreGame() {
	return state.player && (state.player.moneySystem === 0);
}

// Étape 3, monnaie dette : vrai si la partie courante utilise la dette (voir
// Game.MONEY_DEBT = 1) - détermine si le bouton "Demander un crédit" est
// proposé (voir CreditRequestService).
function isDebtGame() {
	return state.player && (state.player.moneySystem === 1);
}

// Étape 3, monnaie dette + smartphone (17/09/2026, remonté par l'utilisateur :
// "1 jeton faible = 1 unité monétaire... disparition des jetons forts, moyens
// et faibles... on parle d'unités monétaires, pas de jetons") - vrai
// uniquement pour un joueur RÉELLEMENT suivi par smartphone (pioche de
// cartes + jetons physiques, voir PlayerSelfViewDto.hasStartingAllocation,
// dérivé de Player.startingCardsJson != null côté serveur), jamais pour un
// simple réglage global "mode smartphone" qui aurait pu changer depuis la
// création de CETTE partie (même principe déjà établi ailleurs, voir
// Event.isSmartphoneTrackedGame côté moteur). Consigne explicite de
// l'utilisateur : "les consignes qui vont suivre pour définir la partie
// monnaie dette avec smartphone ne doivent en rien affecter la partie
// classique" - la dette CLASSIQUE n'a jamais de startingCardsJson (aucune
// pioche n'y est jamais distribuée, voir GameService.dealStartingHandsForLibreIfNeeded),
// donc cette fonction y reste FAUSSE par construction, sans jamais avoir
// besoin de consulter le réglage global AppSettings.gameMode ici.
function isSmartphoneTrackedPlayer() {
	return !!(state.player && state.player.hasStartingAllocation);
}

// Vrai si cet écran doit n'afficher/manipuler QUE des unités monétaires,
// jamais un nombre de jetons brut - la libre l'a toujours fait (voir
// isLibreGame ci-dessus), la dette rejoint cette règle UNIQUEMENT quand elle
// est suivie par smartphone (voir isSmartphoneTrackedPlayer ci-dessus) : la
// dette classique garde intégralement son affichage historique (nombre de
// jetons + ligne "soit X unités monétaires").
function usesMonetaryUnitsOnly() {
	return isLibreGame() || (isDebtGame() && isSmartphoneTrackedPlayer());
}

async function refreshPlayer() {
	if (!state.gameId || !state.token) {
		el("viewError").classList.remove("hidden");
		el("mobileContainer").classList.add("hidden");
		return;
	}
	// Remonté par l'utilisateur (09/09/2026) : rafales de 4 à 6 échecs
	// IDENTIQUES dans la même seconde, de façon très régulière à chaque
	// entre-deux-tours - plausiblement lié au verrouillage d'écran du
	// téléphone pendant les quelques minutes où l'animateur gère le tour
	// suivant (le joueur ne touche pas son téléphone à ce moment-là) : au
	// réveil, plusieurs minuteurs "en retard" peuvent se déclencher presque
	// simultanément, juste au moment où le wifi lui-même reprend sa
	// connexion - plusieurs appels à cette MÊME fonction en même temps,
	// tous vaguement au même instant. Garde-fou simple, efficace quelle que
	// soit la cause exacte (verrouillage d'écran, message WebSocket,
	// rafraîchissement périodique... tous appellent cette même fonction) :
	// ignore purement et simplement un appel si un précédent est encore en
	// cours - inutile de multiplier les tentatives simultanées vers le même
	// point, ça ne fait qu'aggraver une éventuelle instabilité réseau au
	// moment précis où elle se produit déjà.
	if (state.refreshInFlight) return;
	state.refreshInFlight = true;
	try {
		const res = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}`);
		if (!res.ok) throw new Error("not found");
		state.player = await res.json();
		// Remonté par l'utilisateur (09/09/2026, captures du nouveau journal de
		// diagnostic à l'appui) : un simple accroc wifi passager (quelques
		// secondes, très courant sur un réseau partagé avec de nombreux
		// téléphones) faisait disparaître TOUTE l'application au profit de
		// l'écran d'erreur, dès le tout premier échec de ce rafraîchissement
		// périodique (toutes les 5 secondes) - un joueur pouvait ainsi croire
		// le jeu cassé alors que seul son wifi avait eu un accroc d'une
		// seconde. Remis à zéro uniquement en cas de SUCCÈS : un accroc isolé
		// ne doit jamais, à lui seul, effacer l'écran du joueur.
		state.refreshFailureCount = 0;
		el("viewError").classList.add("hidden");
		// Remonté par l'utilisateur (02/09/2026, précisé le 07/09/2026 : "quand
		// on met le compte à rebours en pause, l'info bulle indique fin de tour
		// au lieu de pause") : détecté par transition de tradingAllowed (true ->
		// false) - GameService.isTradingAllowed couvre aussi bien la pause
		// explicite que l'expiration naturelle du minuteur ET la fin de partie,
		// un seul indicateur ne permettait donc pas de distinguer ces cas -
		// limite assumée à l'époque, levée ici via le nouveau champ isPaused
		// (PlayerSelfViewDto), qui indique précisément si c'est une pause
		// explicite plutôt qu'une vraie fin de tour.
		// Comparaison stricte à true (pas juste "vrai") : au tout premier
		// chargement, state.previousTradingAllowed vaut undefined, jamais
		// une fausse transition détectée sur une partie déjà en pause à
		// l'arrivée du joueur.
		if ((state.previousTradingAllowed === true) && (state.player.tradingAllowed === false)) {
			showToast(t(state.player.isPaused ? "playerView.turn_paused_toast" : "playerView.turn_ended_toast"));
		}
		state.previousTradingAllowed = state.player.tradingAllowed;
		// Bouton "Demander un crédit" : monnaie dette uniquement (voir isDebtGame()).
		el("btnOpenCreditRequest").classList.toggle("hidden", !isDebtGame());
		const details = el("playerDetails");
		let detailsHtml = "";
		// Remonte par l'utilisateur (24/10/2026, PDF "Retours - 20261024", page 1) :
		// cette meme information (credit en cours + reste a rembourser) est
		// desormais affichee directement SOUS la carte Solde (voir
		// balanceCreditSublines, renderDashboard() ci-dessous) - ce paragraphe
		// generique n'est donc plus necessaire pour la dette (seul systeme ou
		// curDebt/curInterest sont renseignes), garde "au cas ou" pour tout
		// autre systeme futur plutot que supprime.
		if (!isDebtGame() && ((state.player.curDebt > 0) || (state.player.curInterest > 0)))
			detailsHtml += `<p>${t("playerView.current_credit", { debt: state.player.curDebt, interest: state.player.curInterest })}</p>`;
		details.innerHTML = detailsHtml;
		await Promise.all([renderDashboard(), renderProfile()]);
		// BUG TROUVÉ ET CORRIGÉ (remonté par l'utilisateur, PDF "Retours -
		// 20260919" : "le joueur qui a vendu a conservé la carte après la
		// transaction" + "le joueur a accumulé 5 cartes identiques sans que
		// cela ne déclenche de carré"). Investigation : côté serveur,
		// l'inventaire est entièrement DÉRIVÉ de l'historique Transaction/
		// CardSquareEvent à chaque lecture (voir computePlayerCardInventory) -
		// aucune duplication n'y est possible par construction. La VRAIE cause
        // est un écran "Mes cartes" (et "Classement") laissé ouvert qui ne se
		// rafraîchissait QUE sur un message WebSocket ciblé (vente confirmée
		// avec la modal encore ouverte, voir handleOwnSaleCompleted ; carré
		// encaissé, voir enqueueSquareAnimation) - jamais par le
		// rafraîchissement périodique déjà en place ici pour le tableau de
		// bord/le profil (5 secondes). Un vendeur qui referme sa modal avant
		// que l'acheteur ne scanne (cas courant : il pose son téléphone en
		// attendant), ou un message WebSocket manqué lors d'un accroc réseau
		// (déjà documenté ailleurs comme un risque connu de ce mode multi-
		// téléphones), laissait alors ces deux écrans figés sur un instantané
		// périmé - jamais corrigé tant que le joueur ne quittait/revenait pas
		// manuellement dessus. Corrigé en les alignant sur le même
		// rafraîchissement périodique que le reste de l'écran joueur, mais
		// UNIQUEMENT s'ils sont l'écran actuellement affiché (jamais de
		// requête réseau superflue en arrière-plan sur un écran invisible).
		if (!el("myCardsScreen").classList.contains("hidden")) renderMyCards();
		if (!el("leaderboardScreen").classList.contains("hidden")) renderLeaderboard();
	} catch (err) {
		// Tolère quelques échecs consécutifs (voir le commentaire ci-dessus) -
		// seuil choisi pour couvrir un accroc wifi typique de quelques
		// secondes (3 échecs à 5 secondes d'intervalle = ~15 secondes) sans
		// pour autant masquer indéfiniment une VRAIE coupure prolongée, qui
		// doit bien finir par prévenir le joueur. Ne s'applique QU'APRÈS un
		// premier chargement déjà réussi (state.player déjà rempli) : au
		// tout premier chargement de la page, rien de bon à préserver sur
		// l'écran - mieux vaut prévenir tout de suite que de laisser un
		// écran vide pendant ~15 secondes sans aucune indication.
		state.refreshFailureCount = (state.refreshFailureCount || 0) + 1;
		if (state.player && (state.refreshFailureCount < 3)) return;
		el("viewError").classList.remove("hidden");
		el("mobileContainer").classList.add("hidden");
		return;
	} finally {
		// Toujours remis à zéro, quel que soit le chemin de sortie ci-dessus
		// (succès, échec toléré, échec affiché) - sans quoi ce garde-fou
		// bloquerait DÉFINITIVEMENT tout rafraîchissement futur dès le
		// premier appel, un bug bien pire que celui qu'il est censé corriger.
		state.refreshInFlight = false;
	}
	el("mobileContainer").classList.remove("hidden");
	// Synchronise l'overlay "Prison !" sur l'état réel du joueur (voir
	// syncPrisonOverlayFromPlayerState, plus bas dans ce fichier) - couvre le
	// rechargement/la reconnexion pendant que le joueur est toujours en
	// prison, ET le filet de sécurité si le message WS "turn_ending" a été
	// manqué (même esprit que tous les autres filets de sécurité réseau de
	// cette fonction).
	//
	// BUG TROUVÉ ET CORRIGÉ (11/10/2026, agent d'audit indépendant, seconde
	// relecture de la campagne de validation Prison) : cet appel vivait
	// auparavant juste après `state.player = await res.json();`, donc AVANT
	// la ligne ci-dessus qui retire la classe "hidden" de #mobileContainer.
	// Or #prisonAnimOverlay/#prisonAnimTitleHost sont des DESCENDANTS de
	// #mobileContainer (voir player-view.html) - tant que ce conteneur
	// porte encore "hidden" (display:none, qui supprime le rendu de TOUT le
	// sous-arbre, quel que soit le position:fixed des descendants), le titre
	// SVG vectoriel (js/vendor/cartoon-text.js, fitViewBox() via
	// svg.getBBox()) mesure une boîte de 0×0 au moment du tout premier
	// rafraîchissement d'une page qui vient de charger/recharger : le mot
	// "Prison !" se retrouvait bien dans le DOM mais avec un viewBox
	// dégénéré, donc invisible - reproduit par capture d'écran puis
	// confirmé par inspection du DOM (textContent correct, bbox vide).
	// playPrisonAnimation() (déclenchée en direct par le message WebSocket
	// "prison", jamais au tout premier rafraîchissement d'une page) n'était
	// JAMAIS affectée : #mobileContainer est alors déjà visible depuis
	// longtemps. Corrigé en déplaçant cet appel ICI, après que
	// #mobileContainer soit garanti visible - showPrisonOverlayFrozen()
	// peut alors mesurer correctement le SVG du titre, comme pour l'entrée
	// "en direct".
	syncPrisonOverlayFromPlayerState();
	// Ne bascule sur le hub que si aucun autre écran d'échange n'est déjà affiché
	// (le rafraîchissement périodique ne doit pas interrompre une vente/un achat en cours).
	if (SCREENS.every((s) => el(s).classList.contains("hidden"))) showScreen("viewContent");
}

// ============================================================
// Onglet "Accueil" : Tableau de bord (solde, évolution, dernière activité,
// classement) - voir player-view.html, mockup de référence fourni par
// l'utilisateur le 30/08/2026 (repris quasi verbatim, voir player.css).
// ============================================================
async function renderDashboard() {
	// Remonté par l'utilisateur (24/10/2026, PDF "Retours - 20261024", page 1) :
	// "ajouter le portrait avatar du joueur... avec son nom affiché à droite"
	// sur l'écran d'accueil - même style que l'écran "Profil" existant
	// (buildProfileAvatarHtml(), réutilisé tel quel). Appliqué aux TROIS
	// systèmes monétaires smartphone (demande explicite : "ces changements
	// concernent potentiellement aussi libre/troc pour la cohérence
	// visuelle"), jamais conditionné à isDebtGame().
	el("homeIdentityAvatar").innerHTML = buildProfileAvatarHtml(state.player.avatarConfigJson);
	el("homeIdentityName").textContent = state.player.name;

	// Le solde en jetons n'a de sens qu'en dette/libre - le troc n'a jamais de
	// jetons par principe (voir docs/10-etape-plugins-troc.md, règle 3).
	el("balanceCard").classList.toggle("hidden", isTrocGame());
	// Remonté par l'utilisateur (24/10/2026, PDF "Retours - 20261024", page 1) :
	// sous le nombre d'unités monétaires de la carte "Solde", ajouter en
	// petites lignes grises le nombre de crédits en cours et le montant
	// restant à rembourser (dette+smartphone uniquement - curDebt/curInterest
	// ne sont renseignés que pour ce système). HYPOTHÈSE documentée : le
	// moteur (Player.curDebt/curInterest) agrège déjà tous les crédits en
	// cours d'un même joueur en un seul total (voir Event.java, NEW_CREDIT/
	// REIMB_CREDIT, toujours des += / -=), sans distinguer plusieurs crédits
	// séparés - et depuis le correctif du 20/10/2026 ("un joueur déjà endetté
	// ne peut plus redemander de crédit par smartphone", vérifié CÔTÉ
	// SERVEUR), un joueur suivi par smartphone ne peut en pratique jamais
	// avoir plus d'UN crédit actif à la fois par ce canal. "Nombre de crédits
	// en cours" est donc toujours 0 ou 1 ici - affiché comme tel, jamais
	// recalculé à partir d'un historique d'événements séparé qui n'existe
	// pas côté client.
	if (isDebtGame()) {
		const hasCredit = (state.player.curDebt > 0) || (state.player.curInterest > 0);
		el("balanceCreditSublines").classList.toggle("hidden", !hasCredit);
		if (hasCredit) {
			el("balanceCreditCountLine").textContent = t("playerView.home_credit_count_line", { n: 1 });
			el("balanceCreditRemainingLine").textContent = t("playerView.home_credit_remaining_line",
				{ principal: state.player.curDebt, interest: state.player.curInterest });
		}
	} else {
		el("balanceCreditSublines").classList.add("hidden");
	}
	if (!isTrocGame()) {
		// Remonté par l'utilisateur (08/09/2026) : "il faut aussi retirer
		// l'affichage des jetons faibles moyens et forts sur les écrans
		// d'accueil et de profil des joueurs" - depuis que le DU n'est plus
		// jamais distribué qu'en jetons faibles (voir computeDuBreakdown côté
		// assistant), cette décomposition n'a plus d'intérêt (toujours "100%
		// faibles" en pratique) - retirée ici, jamais réaffichée.
		el("balanceCardBreakdown").innerHTML = "";
		// BUG TROUVÉ ET CORRIGÉ (13/09/2026, PDF "Retours_-_20260913_2.pdf") :
		// une première correction avait ajouté une ligne "soit X unités
		// monétaires" SOUS le nombre de jetons - insuffisant pour la monnaie
		// LIBRE, où l'utilisateur demande explicitement de "ne plus afficher
		// les jetons, afficher les unités monétaires à la place" (le nombre de
		// jetons brut reste une source de confusion même accompagné d'une
		// clarification, dès que weakCoinValue != 1). En monnaie libre, le
		// nombre ET le libellé PRINCIPAUX deviennent donc directement la
		// valeur monétaire - plus de ligne secondaire "soit...". À l'époque de
		// CE correctif (13/09/2026), la monnaie DETTE n'était pas concernée
		// (prix des cartes FIXE en jetons, jamais en DU) et gardait le double
		// affichage (nombre de jetons + ligne "soit X unités monétaires") -
		// affirmation PÉRIMÉE depuis le 17/09/2026 (voir isSmartphoneTrackedPlayer/
		// usesMonetaryUnitsOnly ci-dessus) : la dette+smartphone, une fois
		// suivie, rejoint elle aussi ce même affichage "unités monétaires
		// seules" (voir le bloc juste en dessous) - seule la dette CLASSIQUE ou
		// pas-encore-suivie garde le double affichage historique (bloc "else"
		// plus bas).
		if (usesMonetaryUnitsOnly()) {
			// BUG TROUVÉ ET CORRIGÉ (14/10/2026, remonté par l'utilisateur, PDF
			// "Retours - 20261014" : un crédit de 3 jetons approuvé affichait
			// "1.5 unités monétaires" sur une partie dette+smartphone dont
			// "Valeur d'une pièce faible" valait 0.5) : jetonsToMonetaryUnits()
			// multiplie par game.weakCoinValue, un réglage propre à la monnaie
			// LIBRE (conversion d'une valeur réelle en un nombre de jetons
			// PHYSIQUES, voir computeStartingJetonsPerPlayer/cardPriceInDU côté
			// serveur) - jamais un facteur d'échelle pour la dette, où 1 jeton
			// faible = 1 unité monétaire TOUJOURS (Event.java, cas NEW_CREDIT :
			// `player.setJetonWeak(player.getJetonWeak() + principal)`, un
			// ajout direct, jamais mis à l'échelle). Ce bloc (usesMonetaryUnitsOnly
			// === true) couvre bien la dette+smartphone UNE FOIS suivie (voir
			// son commentaire ci-dessus, "la dette rejoint cette règle
			// UNIQUEMENT quand elle est suivie par smartphone") - un ancien
			// commentaire quelques lignes plus bas (désormais corrigé) affirmait
			// à tort que "la monnaie DETTE n'est jamais concernée" par ce bloc,
			// ce qui n'est plus vrai depuis le 17/09/2026 (voir
			// isSmartphoneTrackedPlayer) : cette affirmation périmée a
			// vraisemblablement motivé l'oubli de ce cas précis lors de l'ajout
			// de jetonsToMonetaryUnits() ici. "Valeur d'une pièce faible" reste
			// un champ réglable pour la dette (voir plugins/dette/manifest.json,
			// defaut 1 mais jamais verrouillé) - un animateur qui le change
			// (ex. 2) aurait vu CHAQUE solde dette+smartphone doublé à tort sans
			// ce correctif, pas seulement ce cas de test à 0.5.
			el("balanceCardSubtitle").textContent = isLibreGame() ? t("trade.balance_label_monetary") : t("trade.balance_label");
			// isLibreGame() : voir roundUpIfExactlyHalf() - uniquement en
			// monnaie libre, jamais en dette+smartphone (hors périmètre du
			// retour utilisateur du 21/09/2026, non concernée par ce point).
			el("balanceCardValue").textContent = isLibreGame()
				? roundUpIfExactlyHalf(jetonsToMonetaryUnits(state.player.tradeBalance))
				: state.player.tradeBalance;
			el("balanceUnitLabel").textContent = t("playerView.stat_unit_monetary");
			el("balanceMonetaryEquiv").textContent = "";
			el("balanceMonetaryEquiv").classList.add("hidden");
		} else {
			// CORRECTIF (15/10/2026, trouvé en seconde relecture indépendante du
			// correctif du 14/10/2026 ci-dessus, PDF "Retours - 20261014") : ce
			// bloc "else" est celui où usesMonetaryUnitsOnly() est FAUX, donc la
			// dette CLASSIQUE ou - cas réel et atteignable - un joueur dette
			// SMARTPHONE pas encore "tracké" (hasStartingAllocation encore faux,
			// avant que performSmartphoneMiseEnPlaceIfNeeded() n'ait tourné,
			// c'est-à-dire tant que l'animateur n'a pas encore cliqué "Démarrer
			// la partie" - fenêtre d'attente explicitement décrite par
			// l'utilisateur dans le correctif n°2 du 14/10/2026). Un commentaire
			// précédent affirmait à tort l'inverse ("Monnaie dette+smartphone")
			// et avait fait remplacer ici l'affichage "jetons + soit X unités
			// monétaires" par "unités monétaires" seules - incohérent avec
			// renderProfile() (onglet Profil), resté lui sur l'ancien affichage
			// pour ce même cas. Restauré à l'identique de renderProfile()/de
			// l'ancien comportement : ce bloc ne concerne QUE la dette classique
			// ou pas-encore-trackée, jamais la dette+smartphone une fois trackée
			// (qui passe par le bloc usesMonetaryUnitsOnly() ci-dessus). Troc
			// exclu de ce bloc (voir isTrocGame() plus haut, balanceCard masquée
			// dans ce cas).
			el("balanceCardSubtitle").textContent = t("trade.balance_label");
			el("balanceCardValue").textContent = state.player.tradeBalance;
			el("balanceUnitLabel").textContent = t("playerView.stat_unit_coins");
			el("balanceMonetaryEquiv").classList.remove("hidden");
			el("balanceMonetaryEquiv").textContent = t("playerView.balance_monetary_equiv",
				{ n: jetonsToMonetaryUnits(state.player.tradeBalance) });
		}
	}

	// Historique du joueur (achats + ventes) - sert à la fois à l'évolution
	// "ce tour" et à la carte "dernière activité" ci-dessous, une seule
	// requête pour les deux plutôt que de la dupliquer.
	let txs = [];
	try {
		txs = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/transactions`).then((r) => r.json());
	} catch (err) {
		txs = [];
	}

	// Évolution "ce tour" : ne connaît pas directement le numéro de tour
	// courant de la partie (route joueur volontairement minimale, voir
	// GecoServer) - on utilise le plus RÉCENT numéro de tour vu dans les
	// transactions du joueur comme approximation raisonnable de "le tour en
	// cours", cohérent tant que le joueur a déjà échangé au moins une fois.
	const evolutionCard = el("evolutionCard");
	if ((txs.length > 0) && !isTrocGame()) {
		const latestTurn = Math.max(...txs.map((tx) => tx.turnNumber));
		const thisTurnTxs = txs.filter((tx) => tx.turnNumber === latestTurn);
		let delta = 0;
		for (const tx of thisTurnTxs) {
			if (tx.sellerPlayerId === state.player.id) delta += tx.totalCoinsValue;
			if (tx.buyerPlayerId === state.player.id) delta -= tx.totalCoinsValue;
		}
		evolutionCard.classList.remove("hidden");
		const amountEl = el("evolutionAmount");
		// BUG TROUVÉ ET CORRIGÉ (13/09/2026, PDF "Retours_-_20260913_2.pdf",
		// suite - carte "Dernière activité" ci-dessous) : même raisonnement
		// appliqué ici par cohérence - delta est un mouvement de JETONS
		// (Transaction.totalCoinsValue), jamais converti. En monnaie libre,
		// affiché en unités monétaires comme le reste de cet écran.
		// BUG TROUVÉ ET CORRIGÉ (14/10/2026, même cause que balanceCardValue
		// dans renderDashboard() ci-dessus : jetonsToMonetaryUnits() ne doit
		// JAMAIS s'appliquer en dette+smartphone, uniquement en libre - voir
		// son commentaire détaillé). isLibreGame() plutôt que
		// usesMonetaryUnitsOnly() seul pour ce choix de conversion précis.
		const displayDelta = isLibreGame() ? jetonsToMonetaryUnits(delta) : delta;
		amountEl.textContent = (displayDelta >= 0 ? "+" : "") + displayDelta;
		amountEl.classList.toggle("negative", displayDelta < 0);
		renderEvolutionSparkline(thisTurnTxs, state.player.id);
	} else {
		evolutionCard.classList.add("hidden");
	}

	// Dernière activité (achat ou vente la plus récente, toutes monnaies confondues).
	const activityCard = el("activityCard");
	if (txs.length > 0) {
		const latest = txs[0]; // déjà trié par date décroissante côté serveur (voir listPlayerTransactions)
		const isSale = latest.sellerPlayerId === state.player.id;
		const partner = isSale ? latest.buyerPlayerName : latest.sellerPlayerName;
		const cardName = await resolveCardName(latest.cardTypeId);
		const verbKey = isSale ? "playerView.activity_sold_to" : "playerView.activity_bought_from";
		activityCard.classList.remove("hidden");
		// BUG TROUVÉ ET CORRIGÉ (13/09/2026, PDF "Retours_-_20260913_2.pdf",
		// remonté par l'utilisateur après coup : "oui aligne aussi") : cette
		// carte affichait encore le montant BRUT en jetons ("vendu pour 25
		// jetons") - même mode monnaie libre où le reste de l'écran n'affiche
		// plus que des unités monétaires (voir plus haut). game.
		// transactions_amount reste "{n} jetons" (jamais changé, réutilisé
		// tel quel par l'écran ANIMATEUR - voir app.js, historique des
		// transactions d'une partie) - nouvelle clé dédiée
		// playerView.transactions_amount_monetary pour ne jamais toucher cet
		// autre usage.
		// BUG TROUVÉ ET CORRIGÉ (14/10/2026, même cause que balanceCardValue
		// dans renderDashboard() ci-dessus) : le CHOIX de libellé ("unités
		// monétaires" vs "jetons") reste bien piloté par usesMonetaryUnitsOnly()
		// (dette+smartphone ET libre affichent toutes deux "unités
		// monétaires", jamais "jetons") - seule la VALEUR ne doit être
		// multipliée par weakCoinValue (jetonsToMonetaryUnits) qu'en libre.
		const amountLabel = latest.isGoodsTrade
			? t("playerView.history_goods_amount_short", { n: latest.buyerWeakGoods + latest.buyerMediumGoods + latest.buyerStrongGoods })
			: t(usesMonetaryUnitsOnly() ? "playerView.transactions_amount_monetary" : "game.transactions_amount",
				{ n: isLibreGame() ? jetonsToMonetaryUnits(latest.totalCoinsValue) : latest.totalCoinsValue });
		// BUG TROUVÉ ET CORRIGÉ (18/10/2026, campagne de test 2/6/16 joueurs,
		// capture d'écran d'un téléphone troc+smartphone) : un échange DIRECT
		// carte-contre-carte (Transaction.isCardSwap, troc) s'affichait
		// "Vous avez acheté Granite à Bruno pour 0 jetons." - un achat et un
		// montant en jetons, en contradiction avec la règle 6 du troc ("jamais
		// de jetons ni de valeur monétaire", docs/10-etape-plugins-troc.md), et
		// sans jamais dire quelle carte avait été donnée en retour. Le vendeur
		// (offre scannée) donne cardTypeId et reçoit swapCardTypeId ; l'acheteur
		// (celui qui a scanné) l'inverse.
		if (latest.isCardSwap) {
			const givenId = isSale ? latest.cardTypeId : latest.swapCardTypeId;
			const receivedId = isSale ? latest.swapCardTypeId : latest.cardTypeId;
			el("activityText").innerHTML = t("playerView.activity_swapped_with", {
				given: `<strong>${escapeHtmlLocal(await resolveCardName(givenId))}</strong>`,
				received: `<strong>${escapeHtmlLocal(await resolveCardName(receivedId))}</strong>`,
				name: `<strong>${escapeHtmlLocal(partner)}</strong>`,
			});
		} else el("activityText").innerHTML = t(verbKey, {
			card: `<strong>${escapeHtmlLocal(cardName)}</strong>`,
			name: `<strong>${escapeHtmlLocal(partner)}</strong>`,
			amount: `<strong>${amountLabel}</strong>`,
		});
		el("activityIconBadge").innerHTML = iconSvg("refresh-cw");
		el("activityTime").textContent = formatRelativeTime(latest.timestamp);
	} else {
		activityCard.classList.add("hidden");
	}

	// Classement (aperçu, les 4 premiers - voir GameService.computeLeaderboard).
	await renderLeaderboardInto("dashLeaderboardCard", "dashLeaderboardList", 4);
}

// Petit graphique d'évolution (voir .sparkline-chart) - une ligne simple
// reliant le solde cumulé après chaque transaction du tour, pas une vraie
// série temporelle complexe : suffisant pour donner un sens de tendance,
// cohérent avec le niveau de détail du reste de l'écran.
function renderEvolutionSparkline(turnTxs, playerId) {
	const svg = el("evolutionSparkline");
	if (turnTxs.length === 0) {
		svg.innerHTML = "";
		return;
	}
	let running = 0;
	const points = [0];
	for (const tx of [...turnTxs].reverse()) { // du plus ancien au plus récent pour tracer dans l'ordre
		if (tx.sellerPlayerId === playerId) running += tx.totalCoinsValue;
		if (tx.buyerPlayerId === playerId) running -= tx.totalCoinsValue;
		points.push(running);
	}
	const min = Math.min(...points);
	const max = Math.max(...points);
	const range = (max - min) || 1;
	const coords = points.map((v, i) => {
		const x = 10 + (i * (100 / Math.max(points.length - 1, 1)));
		const y = 50 - ((v - min) / range) * 40;
		return `${x.toFixed(1)},${y.toFixed(1)}`;
	});
	const isPositive = points[points.length - 1] >= points[0];
	const color = isPositive ? "#2ed573" : "var(--danger, #dc2626)";
	svg.innerHTML = `<polyline points="${coords.join(" ")}" fill="none" stroke="${color}" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"></polyline>`;
}

// "il y a X min/h" - horodatage relatif pour la carte "dernière activité".
function formatRelativeTime(epochMs) {
	const diffMin = Math.max(0, Math.round((Date.now() - epochMs) / 60000));
	if (diffMin < 1) return t("playerView.time_just_now");
	if (diffMin < 60) return t("playerView.time_minutes_ago", { n: diffMin });
	const diffH = Math.round(diffMin / 60);
	return t("playerView.time_hours_ago", { n: diffH });
}

// Résout le nom affichable d'une carte à partir de son identifiant - charge
// le catalogue au besoin (mis en cache dans state, comme partout ailleurs).
async function resolveCardName(cardTypeId) {
	if (!state.cardsCatalog) state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
	const entry = state.cardsCatalog.find((c) => c.id === cardTypeId);
	return entry ? (catalogTextValue(entry.nom) || cardTypeId) : cardTypeId;
}

// ============================================================
// Onglet "Profil" : identité + statistiques personnelles (voir
// player-view.html, mockup de référence du 30/08/2026).
// ============================================================
async function renderProfile() {
	el("profileName").textContent = state.player.name;
	// state.player.quit (04/10/2026, voir Player.quit) : un joueur qui a quitté
	// en libre+strict TRM+smartphone continue de toucher le DU - message
	// distinct du simple "hors jeu" générique, pour qu'il comprenne pourquoi
	// son solde continue de grandir malgré son statut "inactif".
	el("profileStatus").textContent = state.player.quit ? t("playerView.status_quit_still_receiving_du")
		: state.player.active ? t("playerView.status_active") : t("playerView.status_inactive");
	el("profileAvatarWrapper").innerHTML = buildProfileAvatarHtml(state.player.avatarConfigJson);
	// Remonté par l'utilisateur (08/09/2026) : même simplification que
	// renderDashboard() - la décomposition par dénomination n'a plus d'intérêt.
	el("statCoinsBreakdown").innerHTML = "";
	// BUG TROUVÉ ET CORRIGÉ (13/09/2026, PDF "Retours_-_20260913_2.pdf") : même
	// raisonnement que renderDashboard() ci-dessus - en monnaie libre, "ne
	// plus afficher les jetons, afficher les unités monétaires à la place"
	// (le nombre ET le libellé principaux, pas seulement une ligne
	// complémentaire). La monnaie DETTE garde le double affichage (nombre de
	// jetons + ligne "soit X unités monétaires").
	if (isTrocGame()) {
		el("statCoins").textContent = "—";
		el("statCoinsUnitLabel").textContent = t("playerView.stat_unit_coins");
		el("statCoinsMonetaryEquiv").textContent = "";
	} else if (usesMonetaryUnitsOnly()) {
		// isLibreGame() : voir roundUpIfExactlyHalf() plus haut - même
		// périmètre que balanceCardValue ci-dessus (onglet Accueil).
		// BUG TROUVÉ ET CORRIGÉ (14/10/2026, même cause que renderDashboard()
		// ci-dessus, voir son commentaire détaillé : jetonsToMonetaryUnits()
		// multiplie par weakCoinValue, un réglage propre à la libre, jamais un
		// facteur d'échelle en dette+smartphone où 1 jeton faible = 1 unité
		// monétaire toujours) - même correctif ici, sur l'onglet Profil.
		el("statCoins").textContent = isLibreGame()
			? roundUpIfExactlyHalf(jetonsToMonetaryUnits(state.player.tradeBalance))
			: state.player.tradeBalance;
		el("statCoinsUnitLabel").textContent = t("playerView.stat_unit_monetary");
		el("statCoinsMonetaryEquiv").textContent = "";
	} else {
		el("statCoins").textContent = state.player.tradeBalance;
		el("statCoinsUnitLabel").textContent = t("playerView.stat_unit_coins");
		el("statCoinsMonetaryEquiv").textContent = t("playerView.stat_coins_monetary_equiv",
			{ n: jetonsToMonetaryUnits(state.player.tradeBalance) });
	}
	// BUG TROUVÉ (remonté par l'utilisateur, 05/09/2026, captures d'écran à
	// l'appui - écart entre "Profil" et "Mes cartes") : goodsCount est un
	// champ EXPLICITEMENT documenté comme propre au système TROC (voir
	// Player.java : "toujours = weakGoods + mediumGoods + strongGoods"),
	// jamais mis à jour par les échanges en MONNAIE LIBRE (qui dérivent
	// l'inventaire réel de l'historique des transactions/carrés, voir
	// GameService.computePlayerCardInventory - exactement ce qu'utilise déjà
	// "Mes cartes") - il restait donc bloqué à sa valeur de départ (la
	// donne initiale), au lieu de refléter les échanges effectués. Corrigé :
	// en monnaie libre, on récupère le VRAI inventaire (même route que "Mes
	// cartes") et on en fait la somme - goodsCount reste utilisé tel quel
	// pour les autres systèmes (troc/dette), où il est correctement
	// maintenu.
	// Élargi à la dette+smartphone (17/09/2026), puis au troc+smartphone
	// (18/09/2026) : depuis qu'elles partagent la même pioche/mécanique de
	// carrés que la libre (voir GameService.dealStartingHandsForLibreIfNeeded,
	// désormais commune aux trois), leur inventaire réel se dérive lui aussi
	// de l'historique des transactions, jamais de goodsCount (jamais mis à
	// jour par un échange smartphone, voir le commentaire ci-dessus - reste
	// juste pour le troc CLASSIQUE, dont le tableau de bord "Échange entre
	// joueurs" le maintient toujours à jour) - gardé sur
	// isSmartphoneTrackedPlayer() plutôt que usesMonetaryUnitsOnly() car ce
	// choix dépend du MÉCANISME de suivi des cartes, pas du libellé jeton/
	// unité monétaire (sans objet pour le troc, qui n'a jamais eu ni l'un ni
	// l'autre).
	if (isLibreGame() || ((isDebtGame() || isTrocGame()) && isSmartphoneTrackedPlayer())) {
		try {
			const inventory = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/card-inventory`).then((r) => r.json());
			el("statCards").textContent = Object.values(inventory).reduce((sum, n) => sum + n, 0);
		} catch (err) {
			el("statCards").textContent = state.player.goodsCount || 0; // repli prudent, mieux vaut un chiffre approximatif qu'un écran cassé
		}
	} else {
		el("statCards").textContent = state.player.goodsCount || 0;
	}

	let txs = [];
	try {
		txs = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/transactions`).then((r) => r.json());
	} catch (err) {
		txs = [];
	}
	const buys = txs.filter((tx) => tx.buyerPlayerId === state.player.id).length;
	const sells = txs.filter((tx) => tx.sellerPlayerId === state.player.id).length;
	el("statBuys").textContent = buys;
	el("statSells").textContent = sells;
}

// ============================================================
// Onglet "Cartes" : Mes cartes, grille par catégorie ou liste triable (voir
// player-view.html, mockup de référence du 30/08/2026 - repris quasi
// verbatim). GameService.computePlayerCardInventory : dérivé des
// transactions smartphone, rien avant leur mise en usage (limite assumée,
// voir la note affichée en cas d'inventaire vide).
// ============================================================

// Couleur de tuile par NIVEAU (pas par secteur - remonté par l'utilisateur
// le 31/08/2026 : "toutes les cartes d'une même valeur ont toujours la même
// couleur... par convention, toutes les cartes de valeur faible ont un fond
// jaune. Il ne faut pas de bleu ni vert dans le fond si ce sont des cartes
// de valeur faible.") - correction d'une erreur de conception antérieure
// (SECTOR_TILE_COLOR, colorait par secteur, retirée).
const LEVEL_TILE_COLOR = { faible: "yellow", moyenne: "green", forte: "blue", tresforte: "purple" };
// Valeur de référence d'une carte par niveau - geconomicus.glibre.org/libre_money.html :
// "les cartes de valeur la plus basse valent chacune 3, les valeurs
// moyennes 6, les valeurs hautes 12" (progression ×2, tresforte extrapolée à 24).
const LEVEL_VALUE = { faible: 3, moyenne: 6, forte: 12, tresforte: 24 };

async function renderMyCards() {
	showScreen("myCardsScreen");
	// Remonté par l'utilisateur (24/10/2026, PDF "Retours - 20261024", page 2) :
	// même bloc solde+crédits que l'écran d'accueil (voir renderDashboard()),
	// dette+smartphone uniquement.
	if (isDebtGame()) {
		el("myCardsCreditSummary").classList.remove("hidden");
		el("myCardsCreditBalanceValue").textContent = state.player.tradeBalance;
		el("myCardsCreditUnitLabel").textContent = t("playerView.stat_unit_coins");
		const hasCredit = (state.player.curDebt > 0) || (state.player.curInterest > 0);
		el("myCardsCreditSublines").classList.toggle("hidden", !hasCredit);
		if (hasCredit) {
			el("myCardsCreditCountLine").textContent = t("playerView.home_credit_count_line", { n: 1 });
			el("myCardsCreditRemainingLine").textContent = t("playerView.home_credit_remaining_line",
				{ principal: state.player.curDebt, interest: state.player.curInterest });
		}
	} else {
		el("myCardsCreditSummary").classList.add("hidden");
	}
	if (!state.myCardsViewMode) state.myCardsViewMode = "grid";
	// Remonté par l'utilisateur (31/08/2026) : "il faut faire de valeur
	// l'affichage par défaut" - auparavant "catégorie".
	if (!state.myCardsSortMode) state.myCardsSortMode = "value";

	// Remonté par l'utilisateur le 31/08/2026 : "on ne voit plus les boutons de
	// filtres" en vue liste - la barre entière était masquée dans ce mode, sans
	// aucun moyen de revenir à la grille. Toujours visible désormais, dans les
	// deux vues.
	el("btnCardsViewGrid").classList.toggle("active", state.myCardsViewMode === "grid");
	el("btnCardsViewList").classList.toggle("active", state.myCardsViewMode === "list");
	el("btnCardsViewGrid").innerHTML = iconSvg("layout-grid");
	el("btnCardsViewList").innerHTML = iconSvg("list");
	el("myCardsSortIcon").innerHTML = iconSvg("chevron-down");
	el("myCardsSortSelect").value = state.myCardsSortMode;

	const body = el("myCardsBody");
	body.innerHTML = `<p style="color:var(--text-muted);font-size:0.85rem;">${t("settings.catalog_loading")}</p>`;
	try {
		if (!state.cardsCatalog) state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
		if (!state.visualsCatalog) state.visualsCatalog = await fetch("/api/catalogs/visuels").then((r) => r.json());
		const inventory = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/card-inventory`).then((r) => r.json());
		const cardIds = Object.keys(inventory);
		if (cardIds.length === 0) {
			body.innerHTML = `<p style="color:var(--text-muted);font-size:0.85rem;">${t("playerView.my_cards_empty")}</p>`;
			return;
		}
		const items = [];
		for (const cardId of cardIds) {
			const entry = state.cardsCatalog.find((c) => c.id === cardId);
			if (!entry) continue; // carte retirée du catalogue depuis - on ignore plutôt que de casser l'écran
			const visual = state.visualsCatalog.find((v) => v.id === entry.visualId);
			const label = visual ? (catalogTextValue(visual.etiquette) || catalogTextValue(entry.nom)) : catalogTextValue(entry.nom);
			items.push({
				entry, visual, count: inventory[cardId], label: label || entry.id,
				value: LEVEL_VALUE[entry.niveau] || 0,
				tileColor: LEVEL_TILE_COLOR[entry.niveau] || "yellow",
			});
		}

		if (state.myCardsViewMode === "grid") renderMyCardsGrid(body, items);
		else renderMyCardsList(body, items);
	} catch (err) {
		body.innerHTML = `<p style="color:var(--danger)">${t("game.transactions_load_error")}</p>`;
	}
}

function cardThumbInner(item) {
	return item.visual
		? `<img src="/cartes/${escapeHtmlLocal(item.visual.filename)}" alt="">`
		: `<span class="card-emoji" aria-hidden="true">🖼️</span>`;
}

// Regroupement partagé grille/liste, selon le critère de tri choisi -
// remonté par l'utilisateur (31/08/2026) : "il faut que le titre ET le type
// de classement et d'affichage changent en fonction du filtre", pas
// seulement l'ordre des cartes à l'intérieur d'un groupement toujours par
// secteur. Trois comportements bien distincts :
// - "category" : regroupé par secteur (Alimentation, Agriculture...), comme
//   avant - le seul mode qui regroupe par secteur.
// - "value" (DÉSORMAIS LE TRI PAR DÉFAUT) : regroupé par NIVEAU (voir
//   LEVEL_VALUE) - "Cartes de valeur faible" en premier, puis moyenne,
//   forte, tresforte - indépendamment du secteur.
// - "quantity" : AUCUN regroupement, une liste plate triée de la carte la
//   plus nombreuse à la moins nombreuse (voir "on affiche de la carte qu'on
//   a dans le plus grand nombre d'exemplaires vers la carte qu'on a dans le
//   plus petit nombre" - pas de titre de groupe dans ce mode).
// Renvoie toujours un tableau de {title, count, cards} - title === null en
// mode "quantity" (aucun en-tête à afficher).
function groupCardsForDisplay(items, sortMode) {
	if (sortMode === "quantity") {
		return [{ title: null, count: null, cards: [...items].sort((a, b) => b.count - a.count) }];
	}
	if (sortMode === "value") {
		const order = ["faible", "moyenne", "forte", "tresforte"];
		const groups = new Map();
		for (const item of items) {
			const level = item.entry.niveau;
			if (!groups.has(level)) groups.set(level, []);
			groups.get(level).push(item);
		}
		// Rotation des valeurs (22/09/2026, "révolution économique" - voir
		// Game.revolutionCount/cardPriceInDuTable) : remonté par l'utilisateur
		// - "vérifie que les cartes changent bien au niveau de l'organisation
		// des cartes sur l'écran du smartphone des joueurs (filtre et
		// organisation de cartes par valeur)". Le NOM de chaque catégorie
		// physique (ex. "faible") ne change jamais - c'est toujours la même
		// pioche/le même mécanisme de carré - mais après une révolution, ce
		// n'est PLUS forcément la moins chère : les groupes sont donc
		// désormais ORDONNÉS par prix ACTUEL croissant (le moins cher en
		// premier, cohérent avec ce tri "par valeur"), et le prix courant est
		// affiché dans le titre pour lever toute ambiguïté entre le nom de la
		// catégorie et sa valeur du moment. Uniquement en monnaie libre (seul
		// système où le prix varie réellement, voir isLibreGame) - la dette
		// garde son barème fixe, l'ordre physique reste donc son seul critère
		// pertinent, inchangé.
		const priceTable = isLibreGame() ? cardPriceInDuTable() : null;
		const orderedLevels = priceTable
			? order.filter((level) => groups.has(level)).sort((a, b) => priceTable[a] - priceTable[b])
			: order.filter((level) => groups.has(level));
		return orderedLevels.map((level) => {
			const cards = groups.get(level);
			// Pas de suffixe d'unité (ex. "u.m.") : convention déjà établie
			// ailleurs dans cet écran (voir balanceCardValue/jetonsToMonetaryUnits) -
			// un nombre brut d'unités monétaires, sans libellé, le contexte
			// (l'écran "Mes cartes") suffit à le faire comprendre.
			const priceSuffix = priceTable
				? ` · ${jetonsToMonetaryUnits(computeLibreCardPrice(level).weak)}`
				: "";
			return {
				title: t("playerView.cards_group_value_title", { level: catalogEnumLabel("level", level) }) + priceSuffix,
				count: cards.reduce((sum, c) => sum + c.count, 0),
				cards,
			};
		});
	}
	// "category" (par secteur, comportement historique de cet écran).
	const groups = new Map();
	for (const item of items) {
		const sector = item.entry.secteur || "ressources";
		if (!groups.has(sector)) groups.set(sector, []);
		groups.get(sector).push(item);
	}
	return [...groups.entries()].map(([sector, cards]) => ({
		title: catalogEnumLabel("sector", sector),
		count: cards.reduce((sum, c) => sum + c.count, 0),
		cards,
	}));
}

// Vue grille : une rangée défilante par groupe (voir groupCardsForDisplay
// pour ce qui définit un "groupe" selon le tri choisi) - voir
// .category-section/.cards-scroll-container/.game-card. data-card-id sur
// chaque tuile (voir openCardModal) : le clic ouvre la carte en grand plutôt
// que de scraper le texte affiché, on a déjà toute la donnée structurée ici.
function renderMyCardsGrid(body, items) {
	const groups = groupCardsForDisplay(items, state.myCardsSortMode);
	body.innerHTML = groups.map((group) => `
		<section class="category-section">
			${group.title ? `
			<div class="category-header">
				<h2 class="category-title">${escapeHtmlLocal(group.title)}</h2>
				<span class="category-count">${group.count}</span>
			</div>` : ""}
			<div class="cards-scroll-container">
				${group.cards.map((item) => `
				<div class="game-card card-${item.tileColor}" data-card-id="${escapeHtmlLocal(item.entry.id)}">
					<span class="card-name">${escapeHtmlLocal(item.label)}</span>
					<div class="card-image-wrapper">${cardThumbInner(item)}</div>
					<div class="card-quantity-badge">×${item.count}</div>
				</div>`).join("")}
			</div>
		</section>`).join("");
	wireCardModalClicks(body, items);
}

// Vue liste : même regroupement que la grille (voir groupCardsForDisplay) -
// remonté par l'utilisateur (31/08/2026) : "quand on passe en affichage
// liste... on ne voit plus le type de classement" - la vue liste n'affichait
// jusqu'ici AUCUN titre de groupe, quel que soit le tri. Corrigé : mêmes
// titres que la grille, juste des lignes au lieu de tuiles.
function renderMyCardsList(body, items) {
	const groups = groupCardsForDisplay(items, state.myCardsSortMode);
	body.innerHTML = groups.map((group) => `
		<section class="category-section">
			${group.title ? `
			<div class="category-header">
				<h2 class="category-title">${escapeHtmlLocal(group.title)}</h2>
				<span class="category-count">${group.count}</span>
			</div>` : ""}
			<ul class="cards-list-view">
				${group.cards.map((item) => `
				<li class="card-list-item" data-card-id="${escapeHtmlLocal(item.entry.id)}">
					<div class="item-left">
						<div class="item-thumb card-${item.tileColor}">${cardThumbInner(item)}</div>
						<span class="item-name">${escapeHtmlLocal(item.label)}</span>
					</div>
					<div class="item-right">
						<span class="item-value">${item.value}</span>
						<span class="item-quantity-badge">×${item.count}</span>
					</div>
				</li>`).join("")}
			</ul>
		</section>`).join("");
	wireCardModalClicks(body, items);
}

// ============================================================
// Modal carte agrandie + retournement 3D (30/08/2026) - comportement fourni
// par l'utilisateur en référence exacte (voir player-view.html/player.css) :
// clic sur une carte -> agrandissement + fond assombri ; glissement
// horizontal -> retournement animé façon dessin animé, présentant le dos
// avec le QR code. Différence assumée par rapport à la référence : notre
// jeu impose un PRIX librement fixé par le vendeur (pas de QR "déjà prêt"),
// donc le dos affiche d'abord une petite étape prix, puis le VRAI QR d'une
// VRAIE offre TradeOfferService une fois généré - jamais un QR de
// démonstration externe.
// ============================================================
function wireCardModalClicks(container, items) {
	container.querySelectorAll(".game-card, .card-list-item").forEach((cardEl) => {
		cardEl.addEventListener("click", () => {
			const item = items.find((i) => i.entry.id === cardEl.dataset.cardId);
			if (item) openCardModal(item);
		});
	});
}

// (Seconde copie retirée le 08/09/2026 - voir le commentaire équivalent plus
// haut dans ce même fichier.)

// Valeur automatique d'une carte, en jetons, selon son niveau.
// Remonté par un utilisateur : "minimise le nombre de saisies humaines. Tout ce
// qui peut être automatisé doit l'être... la valeur de la carte est définie
// dans le code", plutôt que de laisser le vendeur fixer librement un prix
// (steppers manuels, retirés en dette/libre - toujours utilisés en troc, voir
// plus bas, cette simplification ne concernant que les jetons).
//
// Remonté par l'utilisateur (09/09/2026) : "pour les cartes, elles ne se
// calculent en DU que sur la partie monnaie libre avec le smartphone. En
// mode classique on reprend le code existant et en monnaie dette les cartes
// ont un prix en jetons." LEVEL_JETON_PRICE (prix FIXE, 3 jetons de la
// dénomination correspondante) reste donc utilisé UNIQUEMENT pour la monnaie
// DETTE - jamais changé. La monnaie LIBRE utilise désormais un prix
// EXPRIMÉ EN DU (voir computeLibreCardPrice ci-dessous), converti en jetons
// FAIBLES uniquement via le DU COURANT - un prix qui varie donc réellement
// d'un tour à l'autre, suivant le DU, contrairement au prix fixe de la
// monnaie dette.
// Remonté par l'utilisateur (17/09/2026) : "1 jeton faible = 1 unité
// monétaire... disparition des jetons forts, moyens et faibles. En partie
// monnaie dette avec smartphone, il n'y a que des jetons faibles." - ce
// tableau ne servait jusqu'ici QUE pour la dette (voir openCardModal
// ci-dessous, jamais atteint par la libre/le troc), qui n'avait encore
// jamais de pioche de cartes réelle (voir dealStartingHandsForLibreIfNeeded,
// désormais élargi à la dette). Toutes les valeurs converties en jetons
// FAIBLES uniquement, en conservant EXACTEMENT le même barème de valeur
// qu'avant (faible=3, moyenne=3×2=6, forte=3×4=12, tresforte=6×4=24 -
// convention weak=1/medium=2/strong=4 déjà utilisée partout ailleurs dans ce
// fichier, voir Game.computeMoneyMassFromActivePlayersJetons côté moteur).
const LEVEL_JETON_PRICE = {
	faible: { weak: 3, medium: 0, strong: 0 },
	moyenne: { weak: 6, medium: 0, strong: 0 },
	forte: { weak: 12, medium: 0, strong: 0 },
	tresforte: { weak: 24, medium: 0, strong: 0 },
};

// Remonté par l'utilisateur (09/09/2026) : "il faut fixer le prix d'une carte
// de monnaie libre en DU. Par exemple, une carte faible est égale à 0.5DU."
// Mêmes proportions que l'ancien système en valeur abstraite (1:2:4:8),
// jamais changées - seule leur unité change (DU au lieu d'une valeur
// abstraite fixe). Portage EXACT de Game.cardPriceInDU côté moteur - jamais
// réinventée séparément, pour que le prix affiché ici corresponde TOUJOURS
// exactement à ce que le serveur validera au moment de l'achat (voir
// GameService.levelValue, la même formule, portée à l'identique).
// Correctif (13/09/2026), remonté par l'utilisateur : le prix d'une carte
// faible en DU est désormais réglable par partie (écran "Nouvelle partie",
// champ "Valeur d'une carte faible en DU ?", voir Game.weakCardValueInDU) au
// lieu d'être figé à 0.5 pour toutes les parties - PlayerSelfViewDto expose
// cette valeur (weakCardValueInDU) pour que ce calcul reste synchronisé avec
// le serveur sans jamais dupliquer une constante qui pourrait diverger.
// Correctif (22/09/2026), "rotation des valeurs" - suite utilisateur : "quand
// on arrive à faire un carré de cartes très fortes... il peut être
// intéressant de mettre en place une rotation des valeurs, d'autant que cela
// est conforme à la règle du jeu" (voir geconomicus.glibre.org/rules.html,
// "révolution économique") - à chaque carré réellement bouclé depuis le
// niveau physique le plus haut ("tresforte") vers "faible" (voir
// GameService.checkAndCashInSquares), Game.revolutionCount augmente de 1 et
// le barème tourne d'un cran : le niveau physique le moins avancé dans le
// cycle prend le prix le plus élevé. Portage EXACT de Game.cardPriceInDU
// côté moteur (même ordre LEVEL_ORDER, même formule de rotation) - jamais
// réinventée séparément, PlayerSelfViewDto expose revolutionCount pour le
// même motif que weakCardValueInDU juste au-dessus.
const CARD_LEVEL_ORDER = ["faible", "moyenne", "forte", "tresforte"];
function cardPriceInDuTable() {
	const weak = (state.player && state.player.weakCardValueInDU) || 0.5;
	const revolutionCount = (state.player && state.player.revolutionCount) || 0;
	const basePricesByRank = [weak, weak * 2, weak * 4, weak * 8];
	const table = {};
	CARD_LEVEL_ORDER.forEach((level, physicalIndex) => {
		const nbLevels = CARD_LEVEL_ORDER.length;
		const priceRank = (((physicalIndex - revolutionCount) % nbLevels) + nbLevels) % nbLevels;
		table[level] = basePricesByRank[priceRank];
	});
	return table;
}

// Prix d'une carte de monnaie LIBRE, en jetons faibles PHYSIQUES à cet
// instant précis - varie à chaque tour puisque le DU courant (voir
// PlayerSelfViewDto.currentDuValue, calculé UNE SEULE FOIS côté serveur,
// jamais recalculé séparément ici pour écarter tout risque de divergence)
// change lui-même à chaque tour.
// Remonté par l'utilisateur (13/09/2026, PDF "petits ajustements pour limiter
// le risque de confusion... entre les jetons et les unités monétaires") :
// convertit un nombre de jetons FAIBLES en unités monétaires (weakCoinValue,
// voir PlayerSelfViewDto) - arrondi à 2 décimales pour l'affichage (le solde
// réel, lui, reste toujours un compte entier de jetons, jamais arrondi).
function jetonsToMonetaryUnits(pJetons) {
	const weakCoinValue = (state.player && state.player.weakCoinValue) || 1;
	return Math.round(pJetons * weakCoinValue * 100) / 100;
}

// Remonté par un utilisateur (21/09/2026, retour après une vraie partie en
// monnaie libre + smartphone) : "sur l'écran de l'assistant et sur l'écran
// des joueurs qui ont un score avec xxx,5 arrondi à l'entier supérieur. Par
// exemple : 53,5 devient 54". Volontairement ciblé sur le cas EXACT ".5"
// (ne touche jamais un solde qui n'est pas pile sur une moitié d'unité) -
// voir la même fonction (nommée pareil) côté app.js, jetonsToMonetaryUnits()
// n'est elle PAS modifiée pour ne pas changer les autres affichages
// (delta de transaction, montant d'activité...) non concernés par ce retour.
function roundUpIfExactlyHalf(pValue) {
	const fraction = pValue - Math.floor(pValue);
	return Math.abs(fraction - 0.5) < 1e-9 ? Math.ceil(pValue) : pValue;
}

function computeLibreCardPrice(pLevel) {
	const priceInDU = cardPriceInDuTable()[pLevel] || 0;
	const currentDU = (state.player && state.player.currentDuValue) || 0;
	const weakCoinValue = (state.player && state.player.weakCoinValue) || 1;
	const priceInMonetaryUnits = priceInDU * currentDU;
	return { weak: Math.max(0, Math.round(priceInMonetaryUnits / weakCoinValue)), medium: 0, strong: 0 };
}

function openCardModal(item) {
	state.cardModalItem = item;
	state.cardModalPrice = { weak: 0, medium: 0, strong: 0 };
	state.cardModalOffer = null;
	clearCardModalCountdown();

	const flip = el("cardModalFlip");
	flip.className = `flip-card card-${item.tileColor}`; // réinitialise aussi is-flipped (retiré, pas dans la liste de classes)

	el("cardModalTitle").textContent = item.label;
	el("cardModalBackTitle").textContent = item.label;
	el("cardModalCount").textContent = `×${item.count}`;
	// Juste l'illustration (transparente), plus le composant carte complet
	// avec son propre fond - remonté par l'utilisateur (31/08/2026, voir le
	// commentaire en tête de la modal dans player-view.html) : le fond de LA
	// MODAL (déjà coloré par niveau, voir flip.className ci-dessus) doit
	// occuper tout l'espace, sans un second fond imbriqué à l'intérieur.
	el("cardModalArt").innerHTML = item.visual
		? `<img src="/cartes/${escapeHtmlLocal(item.visual.filename)}" alt="">`
		: `<span class="modal-big-emoji" aria-hidden="true">🖼️</span>`;

	el("cardModalOverlay").classList.add("active");

	// "Échange avec la pioche" (18/10/2026) : mécanisme COMMUN aux trois
	// systèmes, indépendant du flux vente/échange ci-dessous - configuré
	// AVANT le "return" anticipé de la pause (juste en dessous) pour que la
	// même règle serveur (isTradingAllowed) s'y applique aussi, exactement
	// comme pour une vente. Nouvelle carte ouverte : on repart toujours d'un
	// message d'erreur vide (refreshPileExchangeButton(), lui, ne le touche
	// plus jamais - voir son commentaire).
	el("cardModalPileExchangeError").style.display = "none";
	el("cardModalPileExchangeError").textContent = "";
	refreshPileExchangeButton();

	// Remonté par l'utilisateur (31/08/2026) : les échanges doivent être
	// bloqués pendant que le compte à rebours de tour est en pause (voir
	// PlayerSelfViewDto.tradingAllowed, calculé côté serveur - même règle
	// exacte que la vérification faite là-bas avant d'accepter une offre :
	// ce contrôle client n'est qu'un raccourci pour éviter d'attendre un
	// aller-retour serveur avant d'afficher le message, le serveur reste
	// seul juge final).
	if (state.player && (state.player.tradingAllowed === false)) {
		el("cardModalBackBody").innerHTML = `<p class="qr-instruction" style="color:#fff;font-weight:700;">${escapeHtmlLocal(t("trade.trading_paused"))}</p>`;
		return;
	}

	// Dette/libre : prix automatique, le QR est généré tout de suite (prêt
	// dès que le joueur swipe, pas d'étape manuelle intermédiaire) - "la
	// personne clique sur la carte, swipe pour la vendre", rien de plus.
	// Troc + SMARTPHONE (18/09/2026, remonté par l'utilisateur : "le système
	// d'échange de cartes doit être repensé... [le joueur] la swipe, ce qui
	// la retourne. La carte présente son QRcode.") : rejoint désormais
	// EXACTEMENT le même chemin immédiat que dette/libre - plus de steppers,
	// plus de "prix" à négocier (jamais de jetons ni d'unité monétaire en
	// troc, voir docs/10-etape-plugins-troc.md, règle 3) - le QR encode
	// directement CETTE carte précise, prête à être scannée par l'autre
	// joueur pour un échange 1-pour-1 (voir generateCardModalQr,
	// GameService.recordCardSwap pour les règles de validation côté serveur).
	el("cardModalBackBody").innerHTML = `<p style="color:#666;font-size:0.85rem;">${escapeHtmlLocal(t("settings.catalog_loading"))}</p>`;
	if (!isTrocGame()) {
		// Remonté par l'utilisateur (09/09/2026) : "les cartes ne se calculent
		// en DU que sur la partie monnaie libre avec le smartphone... en
		// monnaie dette les cartes ont un prix en jetons" - LEVEL_JETON_PRICE
		// (prix fixe) reste utilisé pour la dette, computeLibreCardPrice
		// (variable, suivant le DU courant) pour la libre.
		const p = isLibreGame() ? computeLibreCardPrice(item.entry.niveau)
			: (LEVEL_JETON_PRICE[item.entry.niveau] || { weak: 0, medium: 0, strong: 0 });
		state.cardModalPrice = { ...p };
	}
	generateCardModalQr(item);
}

function closeCardModal() {
	el("cardModalOverlay").classList.remove("active");
	clearCardModalCountdown();
	// BUG TROUVÉ (remonté par l'utilisateur, 05/09/2026) : state.cardModalOffer
	// n'était JAMAIS remis à null ici - une fois qu'un joueur avait ouvert sa
	// modal de vente UNE SEULE FOIS (très fréquent, c'est le moyen principal
	// de vendre une carte), cet état restait "vrai" indéfiniment, bloquant en
	// permanence la bascule automatique vers "Mes cartes" au début du tour
	// suivant (voir connectPlayerWs : "!state.cardModalOffer" ne devenait
	// alors plus jamais vrai) - le sifflet jouait quand même (inconditionnel),
	// ce qui explique exactement le symptôme observé ("j'ai bien le sifflet
	// mais... je n'ai pas vu" la bascule d'écran).
	state.cardModalOffer = null;
	state.cardModalItem = null;
	// Laisse le temps à l'animation de fermeture de se jouer avant de
	// réinitialiser le retournement - même principe que le reste de l'app
	// (voir .stepDone-exit dans player.css).
	setTimeout(() => { el("cardModalFlip").classList.remove("is-flipped"); }, 300);
}

async function generateCardModalQr(item) {
	const btn = el("cardModalGenerateBtn");
	if (btn) btn.disabled = true;
	try {
		const priceFields = isTrocGame()
			? { weakGoodsWanted: state.cardModalPrice.weak, mediumGoodsWanted: state.cardModalPrice.medium, strongGoodsWanted: state.cardModalPrice.strong }
			: { weakCoins: state.cardModalPrice.weak, mediumCoins: state.cardModalPrice.medium, strongCoins: state.cardModalPrice.strong };
		const offer = await createTradeOffer(item.entry, priceFields);
		state.cardModalOffer = offer;
		// Prix automatique affiché pour transparence (voir LEVEL_JETON_PRICE) -
		// le vendeur voit ce qui a été fixé pour lui, même s'il n'a rien saisi.
		const priceParts = [];
		if (!isTrocGame()) {
			if (state.cardModalPrice.weak > 0) priceParts.push(t("trade.price_weak", { n: state.cardModalPrice.weak }));
			if (state.cardModalPrice.medium > 0) priceParts.push(t("trade.price_medium", { n: state.cardModalPrice.medium }));
			if (state.cardModalPrice.strong > 0) priceParts.push(t("trade.price_strong", { n: state.cardModalPrice.strong }));
		}
		const priceLine = (priceParts.length > 0)
			? `<p class="qr-instruction" style="font-weight:700;margin-bottom:-4px;">${escapeHtmlLocal(priceParts.join(" + "))}</p>` : "";
		// Étape 3, troc + smartphone (18/09/2026) : bouton "Échanger" ajouté sous
		// le QR - remonté par l'utilisateur : "Ajout d'un bouton échange dessous
		// le QRcode. Le joueur clique sur le bouton. Le scan de QRcode s'ouvre.
		// Le joueur peut scanner le QRcode de la carte de l'autre joueur qu'il
		// souhaite échanger avec lui." L'AUTRE joueur doit avoir fait de même
		// sur SON téléphone (retourné une de ses cartes, obtenu son propre QR)
		// avant que l'un des deux ne scanne le code de l'autre - voir
		// state.trocSwapOfferedItem, qui retient CETTE carte précise (celle
		// affichée ici) comme "ce que je donne" pour la suite du scan (voir
		// openScan ci-dessous et confirmPurchase, qui l'utilise pour appeler
		// /redeem-swap plutôt que /redeem).
		const swapBtnHtml = isTrocGame()
			? `<button type="button" class="btn-primary" id="cardModalSwapBtn" style="margin-top:0.4rem;">${escapeHtmlLocal(t("trade.btn_swap"))}</button>`
			: "";
		el("cardModalBackBody").innerHTML = `
			${priceLine}
			<div class="qr-container"><div id="cardModalQrBox"></div></div>
			<p class="qr-code-text" id="cardModalQrCodeText"></p>
			<p class="qr-instruction">${escapeHtmlLocal(t("trade.qr_instructions"))}</p>
			<div class="qr-timer"><span aria-hidden="true">⏱️</span><span id="cardModalCountdownValue">01:30</span></div>
			${swapBtnHtml}
			<button type="button" class="btn-cancel-link" id="cardModalCancelBtn" style="color:#fff;">${escapeHtmlLocal(t("trade.btn_cancel_sell"))}</button>`;
		// eslint-disable-next-line no-undef
		new QRCode(el("cardModalQrBox"), { text: offer.code, width: 140, height: 140, correctLevel: QRCode.CorrectLevel.M });
		// Remonté par l'utilisateur (08/09/2026) : "les navigateurs web sur
		// smartphone ne permettent pas d'ouvrir des outils de dev... peut-être
		// faudrait-il ajouter un système de codes à copier-coller sous le QR
		// code" - déjà présent sur l'AUTRE écran de vente (voir
		// renderQrAndCountdown/sellQrCodeText plus bas dans ce fichier), mais
		// manquait ici, sur celui-ci (la modale de carte) - ajouté pour la
		// cohérence, et pour permettre un test complet achat/vente entièrement
		// depuis des onglets de navigateur PC (voir aussi la nouvelle saisie
		// manuelle repérée le même jour, déjà construite - voir manualCodeInput
		// - mais jusque-là accessible uniquement via ce SECOND écran).
		el("cardModalQrCodeText").textContent = offer.code;
		el("cardModalCancelBtn").addEventListener("click", (e) => { e.stopPropagation(); closeCardModal(); });
		if (isTrocGame()) {
			el("cardModalSwapBtn").addEventListener("click", (e) => {
				e.stopPropagation();
				state.trocSwapOfferedItem = item;
				// Masque juste le calque de la modale (sans réinitialiser
				// cardModalItem/cardModalOffer/trocSwapOfferedItem comme le ferait
				// closeCardModal()) : le scan reste un écran DISTINCT géré par
				// showScreen (voir cette fonction, qui ne touche jamais
				// cardModalOverlay), sans quoi les deux resteraient visuellement
				// superposés.
				el("cardModalOverlay").classList.remove("active");
				clearCardModalCountdown();
				openScan();
			});
		}
		startCardModalCountdown(offer.expiresAt);
	} catch (err) {
		el("cardModalBackBody").innerHTML = `<p class="qr-instruction" style="color:#fff;">${escapeHtmlLocal(err.message)}</p>`;
	} finally {
		if (btn) btn.disabled = false;
	}
}

function startCardModalCountdown(expiresAt) {
	clearCardModalCountdown();
	const tick = () => {
		const remainingMs = expiresAt - Date.now();
		if (remainingMs <= 0) {
			clearCardModalCountdown();
			el("cardModalCountdownValue").textContent = t("trade.qr_expired");
			return;
		}
		const totalSec = Math.ceil(remainingMs / 1000);
		el("cardModalCountdownValue").textContent = `${Math.floor(totalSec / 60)}:${String(totalSec % 60).padStart(2, "0")}`;
	};
	tick();
	state.cardModalCountdownInterval = setInterval(tick, 1000);
}

function clearCardModalCountdown() {
	if (state.cardModalCountdownInterval) {
		clearInterval(state.cardModalCountdownInterval);
		state.cardModalCountdownInterval = null;
	}
}

// Glissement horizontal -> retournement (voir le comportement fourni par
// l'utilisateur) : tactile ET souris (pour tester sur ordinateur). Un simple
// clic (sans déplacement significatif) NE retourne PAS la carte, seul un
// vrai glissement le fait - évite un retournement accidentel au moindre tap.
function initCardModalGestures() {
	const flip = el("cardModalFlip");
	let startX = 0;
	let dragging = false;
	const threshold = 40;

	function handleSwipeEnd(endX) {
		if (Math.abs(endX - startX) > threshold) flip.classList.toggle("is-flipped");
	}
	flip.addEventListener("touchstart", (e) => { startX = e.touches[0].clientX; }, { passive: true });
	flip.addEventListener("touchend", (e) => { handleSwipeEnd(e.changedTouches[0].clientX); });
	flip.addEventListener("mousedown", (e) => { dragging = true; startX = e.clientX; });
	flip.addEventListener("mouseup", (e) => { if (dragging) { dragging = false; handleSwipeEnd(e.clientX); } });

	el("cardModalCloseBtn").addEventListener("click", closeCardModal);
	el("cardModalOverlay").addEventListener("click", (e) => {
		if (e.target === el("cardModalOverlay")) closeCardModal();
	});
	el("cardModalPileExchangeBtn").addEventListener("click", (e) => {
		e.stopPropagation();
		if (state.cardModalItem) exchangeCardWithPile(state.cardModalItem);
	});
}

// ============================================================
// "Échange avec la pioche" (18/10/2026, demandé par l'utilisateur suite à la
// campagne de test 2/6/16 joueurs - voir docs/14-reflexion-echange-pioche.md
// pour le document de cadrage validé, et GameService.exchangeWithPile pour
// le mécanisme serveur) : rend une carte à la pioche de son niveau, en
// pioche une autre (modèle DIFFÉRENT) du MÊME niveau - gratuit, 1 fois par
// joueur et par tour, niveau "tresforte" inclus. COMMUN aux trois systèmes
// monétaires (dette/libre/troc), contrairement au reste de cette modale
// (vente dette/libre, échange carte-contre-carte troc) : jamais de prix,
// jamais de QR, jamais d'offre à faire scanner par un autre joueur - un
// simple appel serveur direct.
// ============================================================
// N'efface JAMAIS cardModalPileExchangeError lui-même : cette fonction est
// aussi appelée depuis le bloc catch de exchangeCardWithPile (pour
// recalculer disabled/le libellé après un refus serveur) - un effacement ICI
// écraserait immédiatement le message d'erreur que ce même bloc catch vient
// de poser juste avant, le rendant invisible (bug trouvé par un test
// Playwright dédié, 18/10/2026 : le badge "Pioche déjà échangée ce tour"
// s'affichait bien, mais le message de refus texte restait vide). Les
// appelants qui veulent repartir d'un état propre (ex. openCardModal, à
// l'ouverture) effacent errEl eux-mêmes AVANT d'appeler cette fonction.
function refreshPileExchangeButton() {
	const btn = el("cardModalPileExchangeBtn");
	const pausedOrEnded = state.player && (state.player.tradingAllowed === false);
	const alreadyUsed = state.player && (state.player.pileExchangeUsedThisTurn === true);
	btn.disabled = Boolean(pausedOrEnded || alreadyUsed);
	btn.textContent = alreadyUsed && !pausedOrEnded
		? t("playerView.pile_exchange_used")
		: t("playerView.pile_exchange_btn");
}

async function exchangeCardWithPile(item) {
	const btn = el("cardModalPileExchangeBtn");
	const errEl = el("cardModalPileExchangeError");
	if (btn.disabled) return;
	btn.disabled = true;
	errEl.style.display = "none";
	errEl.textContent = "";
	try {
		const res = await fetch(`/api/games/${state.gameId}/pile-exchange`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ playerId: state.player.id, accessToken: state.token, cardTypeId: item.entry.id }),
		});
		const body = await res.json().catch(() => null);
		if (!res.ok) throw new Error((body && body.error) || t("playerView.pile_exchange_error_generic"));
		// Succès : la carte donnée a quitté la main du joueur, une autre (d'un
		// modèle différent, même niveau) y est entrée - on ferme la modale
		// (son contenu, ancré sur "item", ne correspond plus à rien de précis)
		// et on rafraîchit l'état complet du joueur : refreshPlayer() relit
		// pileExchangeUsedThisTurn ET redessine l'onglet "Mes cartes" s'il est
		// déjà affiché (voir son code, qui appelle renderMyCards() dans ce cas).
		showToast(t("playerView.pile_exchange_success"));
		closeCardModal();
		await refreshPlayer();
	} catch (err) {
		errEl.textContent = err.message;
		errEl.style.display = "block";
		// Un refus "déjà échangé ce tour" peut survenir alors que l'état local
		// (state.player, chargé à l'ouverture de la modale) est encore
		// périmé (ex. deux onglets du même joueur) - on relit l'état réel
		// avant de décider si le bouton doit rester désactivé ou pas.
		await refreshPlayer();
		refreshPileExchangeButton();
	}
}

// ============================================================
// ÉTAPE 3 : "Classement" (Stats) - voir GameService.computeLeaderboard.
// Fonction partagée avec l'aperçu du tableau de bord (renderDashboard) :
// même rendu, juste une profondeur (nombre de lignes) différente.
// ============================================================
async function renderLeaderboard() {
	showScreen("leaderboardScreen");
	await renderLeaderboardInto("leaderboardScreen", "leaderboardBody", null);
}

// pCardWrapperId : id de la carte englobante (masquée s'il n'y a personne à
// classer) - pLimit : null = tout le monde, un nombre = les N premiers
// seulement (aperçu du tableau de bord).
async function renderLeaderboardInto(pCardWrapperId, pListId, pLimit) {
	const wrapper = el(pCardWrapperId);
	const list = el(pListId);
	list.innerHTML = `<li style="color:var(--text-muted);font-size:0.85rem;">${t("settings.catalog_loading")}</li>`;
	try {
		let entries = await fetch(`/api/games/${state.gameId}/leaderboard`).then((r) => r.json());
		if (entries.length === 0) {
			wrapper.classList.add("hidden");
			return;
		}
		wrapper.classList.remove("hidden");
		// Remonté par l'utilisateur (24/10/2026, PDF "Retours - 20261024", page 1) :
		// "la section Classement de l'écran d'accueil doit montrer TOUS les
		// joueurs (ou être scrollable), pas seulement les 4 premiers inscrits" -
		// pLimit n'est plus utilisé pour TRONQUER la liste (aucun appelant ne
		// perd d'information), seulement pour savoir si on est sur l'aperçu du
		// tableau de bord (CSS .leaderboard-list--scrollable, hauteur limitée +
		// défilement) ou sur l'écran "Classement" dédié (liste complète, pas de
		// défilement imposé par une hauteur fixe).
		list.classList.toggle("leaderboard-list--scrollable", !!pLimit);
		// Couleurs de badge tournantes (voir .avatar-badge, mockup de référence)
		// - gardées comme REPLI uniquement désormais : remplacées par un vrai
		// portrait avatar dès qu'il est disponible (même demande, page 1 :
		// "remplacer les badges 'lettre colorée' du classement par de petits
		// portraits avatar réels" - réutilise buildProfileAvatarHtml(), déjà
		// utilisé par l'écran Profil, jamais une seconde implémentation).
		const badgeColors = ["bg-teal", "bg-pink", "bg-purple"];
		list.innerHTML = entries.map((e, i) => {
			const isMe = e.playerId === state.player.id;
			const initial = escapeHtmlLocal((e.playerName || "?").charAt(0).toUpperCase());
			const avatarHtml = e.avatarConfigJson
				? `<div class="avatar-badge avatar-badge-photo ${isMe ? "me" : ""}">${buildProfileAvatarHtml(e.avatarConfigJson)}</div>`
				: `<div class="avatar-badge ${badgeColors[i % badgeColors.length]} ${isMe ? "me" : ""}">${initial}</div>`;
			return `
			<li class="leaderboard-item">
				<div class="player-info">
					${avatarHtml}
					<span class="player-name">${escapeHtmlLocal(e.playerName)}${isMe ? ` (${escapeHtmlLocal(t("playerView.leaderboard_you"))})` : ""}</span>
				</div>
				<span class="player-score">${e.value}</span>
			</li>`;
		}).join("");
	} catch (err) {
		list.innerHTML = `<li style="color:var(--danger)">${t("game.transactions_load_error")}</li>`;
	}
}

// ============================================================
// "Historique" personnel (accessible depuis l'onglet Profil) - voir
// GameService.listPlayerTransactions.
// ============================================================
async function renderHistory() {
	showScreen("historyScreen");
	const body = el("historyBody");
	body.innerHTML = `<li style="color:var(--text-muted);font-size:0.85rem;">${t("settings.catalog_loading")}</li>`;
	try {
		if (!state.cardsCatalog) state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
		// Deux journaux distincts (voir CardSquareEvent.java : un carré n'est
		// PAS une Transaction, c'est une interaction avec la pioche partagée,
		// pas un échange entre deux joueurs) - fusionnés ici, triés par date,
		// pour un historique unique du point de vue du joueur. Remonté par
		// l'utilisateur (31/08/2026) : "toutes ces opérations sont enregistrées
		// dans l'historique du tour, dans le smartphone du joueur".
		// Troisième journal (18/10/2026) : "échange avec la pioche" - même
		// principe que squares ci-dessus, une interaction avec la pioche
		// partagée plutôt qu'avec un autre joueur (voir PileExchangeEvent.java).
		const [txs, squares, pileExchanges] = await Promise.all([
			fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/transactions`).then((r) => r.json()),
			fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/squares`).then((r) => r.json()),
			fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/pile-exchanges`).then((r) => r.json()),
		]);
		if ((txs.length === 0) && (squares.length === 0) && (pileExchanges.length === 0)) {
			body.innerHTML = `<li style="color:var(--text-muted);font-size:0.85rem;">${t("playerView.history_empty")}</li>`;
			return;
		}
		const items = [
			...txs.map((tx) => ({ kind: "transaction", timestamp: tx.timestamp, data: tx })),
			...squares.map((sq) => ({ kind: "square", timestamp: sq.timestamp, data: sq })),
			...pileExchanges.map((px) => ({ kind: "pileExchange", timestamp: px.timestamp, data: px })),
		].sort((a, b) => b.timestamp - a.timestamp);
		body.innerHTML = items.map((item) => {
			if (item.kind === "transaction") return historyTransactionRowHtml(item.data);
			if (item.kind === "square") return historySquareRowHtml(item.data);
			return historyPileExchangeRowHtml(item.data);
		}).join("");
	} catch (err) {
		body.innerHTML = `<li style="color:var(--danger)">${t("game.transactions_load_error")}</li>`;
	}
}

function historyTransactionRowHtml(tx) {
	const isSale = tx.sellerPlayerId === state.player.id;
	// 18/10/2026 (même bug que la carte "Dernière activité", voir
	// renderDashboard) : un échange troc carte-contre-carte s'affichait
	// "Acheté à X  −0" / "Vendu à X  +0" - jamais une vente ni un montant
	// (règle 6 du troc), et la carte REÇUE n'apparaissait nulle part. Ligne
	// dédiée : carte reçue en titre, "Échangé avec X : donnée → reçue",
	// badge neutre ⇄ (un échange de même valeur, ni gain ni perte).
	if (tx.isCardSwap) {
		const nameOf = (id) => { const e = state.cardsCatalog.find((c) => c.id === id); return e ? (catalogTextValue(e.nom) || id) : id; };
		const givenName = nameOf(isSale ? tx.cardTypeId : tx.swapCardTypeId);
		const receivedName = nameOf(isSale ? tx.swapCardTypeId : tx.cardTypeId);
		const swapPartner = isSale ? tx.buyerPlayerName : tx.sellerPlayerName;
		return `
		<li class="leaderboard-item">
			<div class="player-info">
				<div class="avatar-badge bg-teal">⇄</div>
				<div>
					<div class="player-name">${escapeHtmlLocal(receivedName)}</div>
					<div style="font-size:0.74rem;color:var(--text-muted);">${escapeHtmlLocal(t("playerView.history_swapped_with", { name: swapPartner, given: givenName, received: receivedName }))} · ${escapeHtmlLocal(t("game.transactions_turn_label", { n: tx.turnNumber }))}</div>
				</div>
			</div>
			<span class="player-score">⇄</span>
		</li>`;
	}
	const entry = state.cardsCatalog.find((c) => c.id === tx.cardTypeId);
	const cardName = entry ? (catalogTextValue(entry.nom) || tx.cardTypeId) : tx.cardTypeId;
	const partner = isSale ? tx.buyerPlayerName : tx.sellerPlayerName;
	const verbKey = isSale ? "playerView.history_sold_to" : "playerView.history_bought_from";
	// Vendre = on reçoit (badge vert, +) ; acheter = on donne (badge
	// rouge, -) - vrai pour les jetons comme pour les cartes en troc
	// (voir Transaction.buyerWeakGoods&co, toujours donné par l'ACHETEUR).
	const amountValue = tx.isGoodsTrade
		? (tx.buyerWeakGoods + tx.buyerMediumGoods + tx.buyerStrongGoods)
		: tx.totalCoinsValue;
	const amountLabel = tx.isGoodsTrade
		? t("playerView.history_goods_amount_short", { n: amountValue })
		: String(amountValue);
	return `
	<li class="leaderboard-item">
		<div class="player-info">
			<div class="avatar-badge ${isSale ? "bg-teal" : "bg-pink"}">${isSale ? "+" : "−"}</div>
			<div>
				<div class="player-name">${escapeHtmlLocal(cardName)}</div>
				<div style="font-size:0.74rem;color:var(--text-muted);">${escapeHtmlLocal(t(verbKey, { name: partner }))} · ${escapeHtmlLocal(t("game.transactions_turn_label", { n: tx.turnNumber }))}</div>
			</div>
		</div>
		<span class="player-score" style="color:${isSale ? "var(--green-positive)" : "var(--danger, #dc2626)"};">${isSale ? "+" : "−"}${escapeHtmlLocal(amountLabel)}</span>
	</li>`;
}

// Ligne d'historique pour un carré encaissé (voir CardSquareEventDto) -
// distincte visuellement d'une transaction (badge violet ⬡, jamais +/-
// puisqu'un carré est neutre en richesse par construction, voir
// StatsService.computeGain/docs/10-etape-plugins-troc.md).
function historySquareRowHtml(sq) {
	const cashedEntry = state.cardsCatalog.find((c) => c.id === sq.cashedCardTypeId);
	const promotedEntry = state.cardsCatalog.find((c) => c.id === sq.promotedCardTypeId);
	const cashedName = cashedEntry ? (catalogTextValue(cashedEntry.nom) || sq.cashedCardTypeId) : sq.cashedCardTypeId;
	const promotedName = promotedEntry ? (catalogTextValue(promotedEntry.nom) || sq.promotedCardTypeId) : sq.promotedCardTypeId;
	const breakthroughBadge = sq.triggeredBreakthrough
		? `<span style="color:var(--primary-purple);font-weight:800;"> ⚡ ${escapeHtmlLocal(t("playerView.history_square_breakthrough"))}</span>` : "";
	// Badge "révolution" (22/09/2026, "rotation des valeurs") - distinct de
	// triggeredBreakthrough (rupture technologique, une seule fois par
	// partie) : celui-ci se répète à chaque carré tresforte réellement
	// bouclé, voir CardSquareEvent.isTriggeredRevolution.
	const revolutionBadge = sq.triggeredRevolution
		? `<span style="color:var(--danger, #dc2626);font-weight:800;"> 🔄 ${escapeHtmlLocal(t("playerView.history_square_revolution", { n: sq.revolutionCountAfter }))}</span>` : "";
	return `
	<li class="leaderboard-item">
		<div class="player-info">
			<div class="avatar-badge bg-purple">⬡</div>
			<div>
				<div class="player-name">${escapeHtmlLocal(t("playerView.history_square_title"))}</div>
				<div style="font-size:0.74rem;color:var(--text-muted);">${escapeHtmlLocal(t("playerView.history_square_detail", { cashed: cashedName, promoted: promotedName }))} · ${escapeHtmlLocal(t("game.transactions_turn_label", { n: sq.turnNumber }))}${breakthroughBadge}${revolutionBadge}</div>
			</div>
		</div>
	</li>`;
}

// Ligne d'historique pour un "échange avec la pioche" (18/10/2026, voir
// PileExchangeEventDto) - badge neutre 🔄 distinct du carré (⬡, violet) et
// de la transaction (+/−, vert/rouge) : ni un gain ni une perte de
// richesse, exactement comme un échange troc carte-contre-carte.
function historyPileExchangeRowHtml(px) {
	const givenEntry = state.cardsCatalog.find((c) => c.id === px.givenCardTypeId);
	const receivedEntry = state.cardsCatalog.find((c) => c.id === px.receivedCardTypeId);
	const givenName = givenEntry ? (catalogTextValue(givenEntry.nom) || px.givenCardTypeId) : px.givenCardTypeId;
	const receivedName = receivedEntry ? (catalogTextValue(receivedEntry.nom) || px.receivedCardTypeId) : px.receivedCardTypeId;
	return `
	<li class="leaderboard-item">
		<div class="player-info">
			<div class="avatar-badge bg-teal">🔄</div>
			<div>
				<div class="player-name">${escapeHtmlLocal(receivedName)}</div>
				<div style="font-size:0.74rem;color:var(--text-muted);">${escapeHtmlLocal(t("playerView.history_pile_exchange_detail", { given: givenName, received: receivedName }))} · ${escapeHtmlLocal(t("game.transactions_turn_label", { n: px.turnNumber }))}</div>
			</div>
		</div>
	</li>`;
}

// ============================================================
// Étape 3, monnaie dette : demande de crédit auprès de l'animateur/la
// banque - voir CreditRequestService côté serveur. Bug trouvé le 30/08/2026
// (remonté par l'utilisateur via les logs de la console : "renderCreditRequest
// is not defined") : le bouton et l'écran existaient déjà depuis la
// construction de cette fonctionnalité, mais cette fonction elle-même
// n'avait JAMAIS été écrite - une erreur non rattrapée dans initTradeUI()
// empêchait TOUT le câblage des boutons suivants de s'exécuter, d'où
// l'écran entièrement vide observé.
// ============================================================
async function renderCreditRequest() {
	showScreen("creditRequestScreen");
	const body = el("creditRequestBody");
	body.innerHTML = `<p style="color:var(--text-muted);font-size:0.85rem;">${t("settings.catalog_loading")}</p>`;
	try {
		// Une demande existe-t-elle déjà pour ce joueur (n'importe quel statut) ?
		// Corrigé le 14/10/2026 (remonté par l'utilisateur, PDF "Retours -
		// 20261014") : cette route renvoie désormais 200+corps `null` (plutôt
		// qu'un 404) quand ce joueur n'a encore jamais fait de demande - un
		// état NORMAL, pas une erreur (voir GecoServer.java pour le détail
		// complet). `res.ok` seul ne suffit donc plus à distinguer "pas de
		// demande" d'une vraie erreur réseau : il faut aussi vérifier que le
		// corps n'est pas `null`.
		const res = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/credit-requests`);
		if (res.ok) {
			const request = await res.json();
			if (request) {
				renderCreditRequestStatus(request);
				return;
			}
		}
	} catch (err) {
		// Erreur réseau (pas un simple 404 "aucune demande") : on retombe quand
		// même sur le formulaire plutôt que de bloquer l'écran - le joueur peut
		// toujours réessayer d'envoyer sa demande.
	}
	renderCreditRequestForm();
}

// Montant FIXE d'une demande de crédit smartphone (14/10/2026, demande
// utilisateur explicite, PDF "Retours - 20261014" : "Par défaut, il est
// écrit 10. Il faut écrire 3 et que la valeur ne soit pas modifiable.") -
// remplace l'ancien champ libre (min=1, valeur par défaut 10, modifiable)
// par une valeur unique imposée, jamais choisie par le joueur. Constante
// nommée plutôt qu'un "3" répété deux fois (gabarit + soumission) - un seul
// endroit à changer si ce montant devait un jour être revu.
const CREDIT_REQUEST_FIXED_AMOUNT = 3;

function renderCreditRequestForm() {
	// Règle remontée par l'utilisateur (20/10/2026, PDF "Retours - 20261020",
	// point 2) : "lorsqu'un joueur a une dette... il ne peut pas contracter de
	// nouveau crédit depuis son smartphone. S'il veut faire un crédit
	// supplémentaire, il doit obligatoirement aller le demander à
	// l'animateur." Vérifié ici en PREMIER lieu (avant d'afficher le
	// formulaire, qu'il s'agisse du tout premier passage - renderCreditRequest
	// - ou d'une "Nouvelle demande" depuis renderCreditRequestStatus, les deux
	// chemins passant par CETTE même fonction) - raccourci UX seulement, le
	// serveur revérifie de toute façon la même règle (voir
	// POST /api/games/{id}/credit-requests, jamais une confiance aveugle au
	// client).
	if (state.player && (state.player.curDebt > 0)) {
		el("creditRequestBody").innerHTML =
			`<p style="text-align:center;font-size:0.9rem;color:var(--text-muted);">${escapeHtmlLocal(t("playerView.credit_request_already_indebted"))}</p>`;
		return;
	}
	el("creditRequestBody").innerHTML = `
		<label class="field-label" for="creditRequestAmount" data-i18n="playerView.credit_request_amount_label">Montant souhaité</label>
		<input id="creditRequestAmount" class="field-input" type="number" value="${CREDIT_REQUEST_FIXED_AMOUNT}" readonly aria-readonly="true">
		<p id="creditRequestError" class="field-error hidden"></p>
		<button type="button" class="btn-primary btn-block" id="btnSubmitCreditRequest">${escapeHtmlLocal(t("playerView.credit_request_submit"))}</button>`;
	el("btnSubmitCreditRequest").addEventListener("click", submitCreditRequest);
}

async function submitCreditRequest() {
	// Toujours le montant fixe (voir CREDIT_REQUEST_FIXED_AMOUNT ci-dessus) -
	// jamais relu depuis le champ (lecture seule désormais, mais on ne fait
	// pas non plus confiance à sa valeur DOM par principe : un champ en
	// lecture seule reste techniquement modifiable via les outils de
	// développement du navigateur, jamais une vraie barrière - la SEULE
	// garantie réelle serait côté serveur, mais /api/games/{id}/credit-requests
	// accepte volontairement n'importe quel montant positif pour rester
	// réutilisable si ce montant fixe devait un jour redevenir variable ;
	// imposer la valeur ICI, au moment de la construction de la requête,
	// suffit au besoin exprimé ("que la valeur ne soit pas modifiable").
	const amount = CREDIT_REQUEST_FIXED_AMOUNT;
	const errEl = el("creditRequestError");
	if (!amount || (amount <= 0)) {
		errEl.textContent = t("playerView.credit_request_invalid_amount");
		errEl.classList.remove("hidden");
		return;
	}
	errEl.classList.add("hidden");
	// Garde-fou "déjà en cours" (audit indépendant du 20/10/2026, voir le
	// correctif serveur détaillé dans CreditRequestService.createIfNoPending :
	// un double-tap sur ce bouton envoyait jusqu'ici DEUX POST
	// /credit-requests, créant deux demandes "pending" distinctes toutes les
	// deux approuvables séparément par l'animateur - un crédit double en cas
	// d'approbation des deux sans s'en rendre compte) - même principe déjà
	// appliqué ailleurs dans ce fichier pour des appels concurrents vers le
	// même point (voir CLAUDE.md, "Plusieurs appels concurrents... un
	// garde-fou 'déjà en cours' simple protège contre ça"). Défense en
	// profondeur seulement : la vraie garantie reste côté serveur
	// (createIfNoPending, atomique), ce bouton désactivé n'est qu'un confort
	// qui évite la plupart des doubles-envois avant même qu'ils n'atteignent
	// le réseau.
	const btn = el("btnSubmitCreditRequest");
	if (btn) btn.disabled = true;
	try {
		const res = await fetch(`/api/games/${state.gameId}/credit-requests`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ playerId: state.player.id, playerAccessToken: state.token, requestedPrincipal: amount }),
		});
		if (!res.ok) throw new Error(t("join.generic_error", { status: res.status }));
		renderCreditRequestStatus(await res.json());
	} catch (err) {
		errEl.textContent = err.message;
		errEl.classList.remove("hidden");
		if (btn) btn.disabled = false;
	}
}

function renderCreditRequestStatus(request) {
	const statusLabels = {
		pending: t("playerView.credit_request_status_pending"),
		approved: t("playerView.credit_request_status_approved"),
		declined: t("playerView.credit_request_status_declined"),
	};
	el("creditRequestBody").innerHTML = `
		<p style="text-align:center;font-size:0.95rem;">${escapeHtmlLocal(t("playerView.credit_request_summary", { amount: request.requestedPrincipal }))}</p>
		<p style="text-align:center;font-weight:700;color:var(--primary-purple);margin-top:0.4rem;">${escapeHtmlLocal(statusLabels[request.status] || request.status)}</p>
		${(request.status !== "pending") ? `<button type="button" class="btn-secondary btn-block" id="btnNewCreditRequest">${escapeHtmlLocal(t("playerView.credit_request_new"))}</button>` : ""}`;
	if (request.status !== "pending") el("btnNewCreditRequest").addEventListener("click", renderCreditRequestForm);
}

// ============================================================
// VENTE : étape 1 - choisir la carte
// ============================================================
async function openSellPicker() {
	showScreen("sellPicker");
	const container = el("sellCatalogGroups");
	container.textContent = t("settings.catalog_loading");
	if (!state.cardsCatalog) state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
	if (!state.visualsCatalog) state.visualsCatalog = await fetch("/api/catalogs/visuels").then((r) => r.json());

	// Étape 3, monnaie libre : contrairement au troc (pas encore de vrai
	// inventaire suivi), le stock de cartes est ici RÉEL et fini (voir le
	// document de cadrage du 28/08/2026 et GameService.
	// dealStartingHandsForLibreIfNeeded) - un joueur ne doit donc pouvoir
	// proposer à la vente QUE ce qu'il possède réellement, jamais l'intégralité
	// du catalogue comme c'était le cas jusqu'ici (un vrai trou : rien
	// n'empêchait de "vendre" une carte jamais possédée, créant une carte
	// fantôme et cassant le stock fixe qu'on vient tout juste de construire).
	// Élargi à la dette+smartphone (17/09/2026) : depuis qu'elle partage la
	// même pioche/mécanique de carrés que la libre, exactement le même risque
	// de carte fantôme s'applique - jamais pour la dette CLASSIQUE, qui n'a
	// toujours aucun inventaire suivi (voir isSmartphoneTrackedPlayer).
	let ownedCounts = null;
	if (isLibreGame() || (isDebtGame() && isSmartphoneTrackedPlayer())) {
		try {
			ownedCounts = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/card-inventory`)
				.then((r) => r.json());
		} catch (err) {
			ownedCounts = {}; // repli prudent : mieux vaut ne rien proposer que de casser l'écran
		}
	}

	container.innerHTML = CARD_LEVELS.map((level) => {
		let entries = state.cardsCatalog.filter((c) => c.niveau === level);
		if (ownedCounts) entries = entries.filter((c) => (ownedCounts[c.id] || 0) > 0);
		if (entries.length === 0) return "";
		const isOpen = state.sellExpandedGroups.has(level);
		return `
		<div class="trade-group">
			<button type="button" class="trade-group-header" data-group="${level}">
				<span class="trade-group-chevron">${isOpen ? "▾" : "▸"}</span>
				<span class="trade-group-title">${escapeHtmlLocal(catalogEnumLabel("level", level))}</span>
				<span class="trade-group-count">${entries.length}</span>
			</button>
			<div class="trade-group-body ${isOpen ? "" : "hidden"}">
				${entries.map((entry) => sellCardRowHtml(entry, ownedCounts ? ownedCounts[entry.id] : null)).join("")}
			</div>
		</div>`;
	}).join("");

	// Monnaie libre, aucune carte possédée du tout (rare : chacun démarre avec
	// 4 cartes - n'arrive que si tout a déjà été vendu) - message explicite
	// plutôt qu'un écran vide sans explication.
	if (ownedCounts && (container.innerHTML.trim() === "")) {
		container.innerHTML = `<p style="color:var(--text-dim);font-size:0.85rem;">${t("trade.sell_no_owned_cards")}</p>`;
	}

	container.querySelectorAll(".trade-group-header").forEach((header) => {
		header.addEventListener("click", () => {
			const key = header.dataset.group;
			if (state.sellExpandedGroups.has(key)) state.sellExpandedGroups.delete(key); else state.sellExpandedGroups.add(key);
			openSellPicker();
		});
	});
	container.querySelectorAll(".trade-card-row").forEach((row) => {
		row.addEventListener("click", () => {
			const entry = state.cardsCatalog.find((c) => c.id === row.dataset.id);
			const visual = state.visualsCatalog.find((v) => v.id === entry.visualId);
			openSellPrice(entry, visual);
		});
	});
}

function sellCardRowHtml(entry, pOwnedCount) {
	const visual = state.visualsCatalog.find((v) => v.id === entry.visualId);
	const thumb = visual
		? `<img src="/cartes/${escapeHtmlLocal(visual.filename)}" class="trade-card-thumb level-${entry.niveau}" onerror="this.outerHTML='<div class=&quot;trade-card-thumb-fallback level-${entry.niveau}&quot;>🖼️</div>'">`
		: `<div class="trade-card-thumb-fallback level-${entry.niveau}">🖼️</div>`;
	return `
	<button type="button" class="trade-card-row" data-id="${escapeHtmlLocal(entry.id)}">
		${thumb}
		<span class="trade-card-name">${escapeHtmlLocal(catalogTextValue(entry.nom) || entry.id)}</span>
		${(pOwnedCount != null) ? `<span class="trade-card-owned-count">×${pOwnedCount}</span>` : ""}
	</button>`;
}

function escapeHtmlLocal(s) {
	return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// Assemble le fond de carte (propre au niveau) + l'illustration du visuel +
// son étiquette (voir player.css, .geco-card - raisonnement complet là-bas).
// pSizeClass : "geco-card-sm" (vignette, listes) ou "geco-card-lg" (détail).
// pVisual peut être absent (catalogue incohérent, visuel supprimé...) - repli
// sur un simple encadré, jamais un écran cassé pour si peu.
function buildGameCardHtml(pEntry, pVisual, pSizeClass) {
	const niveau = pEntry.niveau;
	const bgSrc = `/cartes/fond_carte_${niveau}.png`;
	if (!pVisual) {
		return `
			<div class="geco-card ${pSizeClass}">
				<img class="geco-card-bg" src="${bgSrc}" alt="">
				<span class="geco-card-label">${escapeHtmlLocal(catalogTextValue(pEntry.nom) || pEntry.id)}</span>
			</div>`;
	}
	const label = catalogTextValue(pVisual.etiquette) || catalogTextValue(pEntry.nom) || pEntry.id;
	return `
		<div class="geco-card ${pSizeClass}">
			<img class="geco-card-bg" src="${bgSrc}" alt="">
			<img class="geco-card-illustration" src="/cartes/${escapeHtmlLocal(pVisual.filename)}" alt="">
			<span class="geco-card-label">${escapeHtmlLocal(label)}</span>
		</div>`;
}

// ============================================================
// VENTE : étape 2 - fixer le prix
// ============================================================
function openSellPrice(entry, visual) {
	state.sellSelection = { entry, visual };
	state.sellPrice = { weak: 0, medium: 0, strong: 0 };
	renderSellPriceCardInfo();
	renderPriceSteppers();
	// Étape 3, troc : relabelle l'écran pour demander des cartes voulues en
	// retour plutôt qu'un prix en jetons (voir isTrocGame() ; même trio de
	// compteurs weak/medium/strong dans les deux cas, juste une signification
	// différente - évite de dupliquer tout l'écran pour ça).
	const weakLabel = document.querySelector('[data-coin="weak"] .price-stepper-label');
	const mediumLabel = document.querySelector('[data-coin="medium"] .price-stepper-label');
	const strongLabel = document.querySelector('[data-coin="strong"] .price-stepper-label');
	if (isTrocGame()) {
		el("appHeaderTitle").textContent = t("trade.sell_goods_title");
		weakLabel.textContent = t("trade.goods_wanted_weak");
		mediumLabel.textContent = t("trade.goods_wanted_medium");
		strongLabel.textContent = t("trade.goods_wanted_strong");
	} else {
		el("appHeaderTitle").textContent = t("trade.sell_price_title");
		weakLabel.textContent = t("trade.coin_weak");
		mediumLabel.textContent = t("trade.coin_medium");
		strongLabel.textContent = t("trade.coin_strong");
	}
	showScreen("sellPrice");
}

function renderSellPriceCardInfo() {
	const { entry, visual } = state.sellSelection;
	const html = `
		${buildGameCardHtml(entry, visual, "geco-card-lg")}
		<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", entry.niveau))}</span>`;
	const infoEl = el("sellPriceCardInfo");
	infoEl.className = `trade-card-info level-${entry.niveau}`;
	infoEl.innerHTML = html;
}

function renderPriceSteppers() {
	document.querySelectorAll(".price-stepper").forEach((stepperEl) => {
		const coin = stepperEl.dataset.coin;
		stepperEl.querySelector(".stepper-value").textContent = state.sellPrice[coin];
	});
}

function initPriceSteppers() {
	document.querySelectorAll(".price-stepper .stepper-btn").forEach((btn) => {
		btn.addEventListener("click", () => {
			const coin = btn.closest(".price-stepper").dataset.coin;
			const delta = parseInt(btn.dataset.delta, 10);
			state.sellPrice[coin] = Math.max(0, state.sellPrice[coin] + delta);
			renderPriceSteppers();
		});
	});
}

// ============================================================
// VENTE : étape 3 - créer l'offre côté serveur, puis QR + compte à rebours
// ============================================================
// Remonté par l'utilisateur le 27/08/2026 ("il faut prévoir les deux [scan
// ET saisie manuelle]") : l'offre est désormais créée côté SERVEUR (voir
// TradeOfferService), identifiée par un code court - le QR n'encode plus
// qu'un texte de 6 caractères (scan plus rapide et plus fiable qu'un gros
// JSON), et ce même code peut être tapé à la main par l'acheteur (voir
// openScanManualEntry ci-dessous) : un seul mécanisme pour les deux usages.
async function createTradeOffer(entry, priceFields) {
	const res = await fetch(`/api/games/${state.gameId}/trade-offers`, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({
			sellerPlayerId: state.player.id,
			sellerAccessToken: state.token,
			cardTypeId: entry.id,
			cardLevel: entry.niveau,
			cardName: entry.nom, // table {langue: texte} - l'acheteur affichera dans SA propre langue
			...priceFields,
		}),
	});
	if (!res.ok) throw new Error(t("join.generic_error", { status: res.status }));
	return res.json();
}

async function createOfferAndShowQr() {
	const { entry } = state.sellSelection;
	const btn = el("btnGenerateQr");
	btn.disabled = true;
	try {
		// Étape 3, troc : la partie de l'offre change selon le système -
		// jetons demandés (dette/libre) ou cartes voulues en retour (troc,
		// voir isTrocGame()) - même trio de compteurs (state.sellPrice) dans
		// les deux cas, juste envoyés sous des noms de champs différents.
		const priceFields = isTrocGame()
			? { weakGoodsWanted: state.sellPrice.weak, mediumGoodsWanted: state.sellPrice.medium, strongGoodsWanted: state.sellPrice.strong }
			: { weakCoins: state.sellPrice.weak, mediumCoins: state.sellPrice.medium, strongCoins: state.sellPrice.strong };
		const offer = await createTradeOffer(entry, priceFields);
		renderQrAndCountdown(offer.code, offer.expiresAt);
	} catch (err) {
		showTradeResult(false, t("trade.result_error_title"), err.message);
	} finally {
		btn.disabled = false;
	}
}

function renderQrAndCountdown(code, expiresAt) {
	// Contexte de la carte, conservé visible pendant tout l'affichage du QR
	// (voir player-view.html) - même contenu que l'étape prix.
	el("sellQrCardInfo").innerHTML = el("sellPriceCardInfo").innerHTML;
	el("sellQrCardInfo").className = el("sellPriceCardInfo").className;

	const box = el("sellQrCode");
	box.innerHTML = "";
	// eslint-disable-next-line no-undef
	new QRCode(box, { text: code, width: 220, height: 220, correctLevel: QRCode.CorrectLevel.M });
	el("sellQrCodeText").textContent = code;

	el("qrExpiredMsg").classList.add("hidden");
	el("btnRegenerateQr").classList.add("hidden");
	el("coinTimer").classList.remove("coin-timer-expired");

	// Anneau "pièce qui se vide" (voir player-view.html) : la circonférence du
	// cercle (r=52) sert de référence pour le stroke-dashoffset - à 0 restant,
	// l'anneau doré a entièrement disparu, comme une pièce dépensée.
	const ring = el("coinTimerProgress");
	const CIRCUMFERENCE = 2 * Math.PI * 52;
	ring.style.strokeDasharray = `${CIRCUMFERENCE}`;
	const totalMs = expiresAt - Date.now();

	clearQrCountdown();
	const updateCountdown = () => {
		const remainingMs = Math.max(0, expiresAt - Date.now());
		const remaining = Math.round(remainingMs / 1000);
		const mm = Math.floor(remaining / 60);
		const ss = String(remaining % 60).padStart(2, "0");
		el("qrCountdownValue").textContent = `${mm}:${ss}`;
		const fraction = totalMs > 0 ? Math.max(0, Math.min(1, remainingMs / totalMs)) : 0;
		ring.style.strokeDashoffset = `${CIRCUMFERENCE * (1 - fraction)}`;
		if (remainingMs <= 0) {
			clearQrCountdown();
			el("coinTimer").classList.add("coin-timer-expired");
			el("qrExpiredMsg").classList.remove("hidden");
			el("btnRegenerateQr").classList.remove("hidden");
		}
	};
	updateCountdown();
	// Basé sur l'horodatage serveur (expiresAt), pas un décompte local reparti
	// de zéro : reste juste même si l'onglet a été mis en veille quelques
	// secondes (contrairement à un setInterval qui ne fait que décrémenter).
	state.qrCountdownInterval = setInterval(updateCountdown, 1000);

	showScreen("sellQr");
}

function clearQrCountdown() {
	if (state.qrCountdownInterval) {
		clearInterval(state.qrCountdownInterval);
		state.qrCountdownInterval = null;
	}
}

// ============================================================
// ACHAT : caméra + décodage, OU saisie manuelle - les deux résolvent le
// même code court auprès du serveur (voir TradeOfferService), un seul
// chemin de données pour les deux (voir resolveCode ci-dessous).
// ============================================================
// Format d'un code valide : exactement l'alphabet non ambigu utilisé par
// TradeOfferService côté serveur (voir CODE_ALPHABET) - permet d'ignorer
// silencieusement un QR d'une autre application pendant le scan, et de
// valider la saisie manuelle avant même d'appeler le serveur.
const OFFER_CODE_PATTERN = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$/;

let scanCanvasCtx = null;

async function openScan() {
	showScreen("scanCamera");
	el("scanCameraError").classList.add("hidden");
	// Remonté par l'utilisateur (31/08/2026) : inutile de laisser un joueur
	// scanner pendant que les échanges sont en pause - il échouerait de toute
	// façon au moment de confirmer (le serveur reste seul juge final, voir
	// GameService.isTradingAllowed). Message immédiat plutôt qu'une caméra
	// ouverte pour rien.
	if (state.player && (state.player.tradingAllowed === false)) {
		el("scanCameraError").textContent = t("trade.trading_paused");
		el("scanCameraError").classList.remove("hidden");
		return;
	}
	try {
		state.scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
	} catch (err) {
		el("scanCameraError").classList.remove("hidden");
		return;
	}
	const video = el("scanVideo");
	video.srcObject = state.scanStream;
	await video.play();

	// Flash/torche réelle (30/08/2026, mockup de référence) - support
	// variable selon les appareils/navigateurs (souvent absent sur iOS
	// Safari) : le bouton reste masqué par défaut (voir player-view.html) et
	// n'apparaît que si la capacité est réellement disponible sur CETTE
	// caméra, plutôt que d'afficher un bouton qui ne ferait rien.
	const [track] = state.scanStream.getVideoTracks();
	const capabilities = track.getCapabilities ? track.getCapabilities() : {};
	el("scannerFlashBtn").classList.toggle("hidden", !capabilities.torch);

	const canvas = document.createElement("canvas");
	scanCanvasCtx = canvas.getContext("2d", { willReadFrequently: true });
	state.scanRAF = requestAnimationFrame(scanTick);
}

// Une frame de la boucle de scan - se rappelle elle-même tant qu'aucun code
// valide n'est détecté ou que l'écran caméra reste actif (state.scanStream).
function scanTick() {
	if (!state.scanStream) return; // écran quitté entre-temps (stopCamera() a coupé le flux)
	const video = el("scanVideo");
	if (video.readyState === video.HAVE_ENOUGH_DATA) {
		const canvas = scanCanvasCtx.canvas;
		canvas.width = video.videoWidth;
		canvas.height = video.videoHeight;
		scanCanvasCtx.drawImage(video, 0, 0, canvas.width, canvas.height);
		const imageData = scanCanvasCtx.getImageData(0, 0, canvas.width, canvas.height);
		// eslint-disable-next-line no-undef
		const code = jsQR(imageData.data, imageData.width, imageData.height);
		if (code && code.data && OFFER_CODE_PATTERN.test(code.data.trim().toUpperCase())) {
			// Remonté par un utilisateur (28/08/2026) : la caméra était coupée ICI,
			// AVANT même de savoir si le code allait réellement aboutir - si
			// resolveCode() retombait sur son chemin "on continue de scanner"
			// (offre expirée entre-temps, propre carte...), il relançait la boucle
			// sur un flux déjà arrêté (state.scanStream == null), et scanTick()
			// s'arrêtait aussitôt sans jamais rien afficher : écran de caméra figé
			// et noir, sans erreur ni sortie possible. La caméra ne doit être
			// coupée qu'une fois le succès confirmé (voir resolveCode ci-dessous),
			// jamais avant.
			resolveCode(code.data.trim().toUpperCase(), true);
			return; // pas de nouvelle frame tant que le résultat n'est pas traité
		}
	}
	state.scanRAF = requestAnimationFrame(scanTick);
}

function stopCamera() {
	if (state.scanRAF) {
		cancelAnimationFrame(state.scanRAF);
		state.scanRAF = null;
	}
	if (state.scanStream) {
		state.scanStream.getTracks().forEach((track) => track.stop());
		state.scanStream = null;
	}
	// Réinitialise l'affichage du flash (le arrêt de la piste ci-dessus coupe
	// déjà la torche physique) - évite qu'un bouton reste visuellement "actif"
	// à la prochaine ouverture du scanner, avant que sa capacité réelle ne
	// soit re-détectée (voir openScan()).
	el("scannerFlashBtn").classList.remove("active");
}

// ---------- Saisie manuelle (repli, voir §5.1 du cahier des charges) ----------
function openManualEntry() {
	showScreen("scanManual");
	el("manualCodeInput").value = "";
	el("manualCodeError").classList.add("hidden");
	el("manualCodeInput").focus();
}

async function submitManualCode() {
	const raw = el("manualCodeInput").value.trim().toUpperCase();
	if (!OFFER_CODE_PATTERN.test(raw)) {
		el("manualCodeError").textContent = t("trade.manual_code_invalid_format");
		el("manualCodeError").classList.remove("hidden");
		return;
	}
	el("manualCodeError").classList.add("hidden");
	await resolveCode(raw, false);
}

// ---------- Résolution du code (commune scan/saisie manuelle) ----------
// pFromCamera : true si appelé depuis la boucle de scan (le flux caméra est
// encore actif à cet instant), false depuis la saisie manuelle (pas de
// caméra à gérer). Détermine comment réagir à un code invalide/expiré : en
// reprenant le scan (caméra encore vivante) ou en affichant une erreur
// textuelle (saisie manuelle).
async function resolveCode(code, pFromCamera) {
	try {
		const res = await fetch(`/api/games/${state.gameId}/trade-offers/${code}`);
		if (!res.ok) {
			if (pFromCamera) {
				// Toujours en vie à cet instant (voir le commentaire dans
				// scanTick) : on peut reprendre la boucle sans rien redémarrer.
				state.scanRAF = requestAnimationFrame(scanTick);
			} else {
				el("manualCodeError").textContent = t("trade.manual_code_not_found");
				el("manualCodeError").classList.remove("hidden");
			}
			return;
		}
		const offer = await res.json();
		if (offer.sellerPlayerId === state.player.id) {
			// On ne peut pas s'acheter sa propre carte.
			if (pFromCamera) {
				state.scanRAF = requestAnimationFrame(scanTick);
			} else {
				el("manualCodeError").textContent = t("trade.manual_code_own_card");
				el("manualCodeError").classList.remove("hidden");
			}
			return;
		}
		// Seulement maintenant qu'on sait qu'on quitte réellement l'écran caméra :
		// sans effet si pFromCamera est faux (déjà arrêtée ou jamais démarrée).
		stopCamera();
		state.pendingOffer = offer;
		renderScanConfirm(offer);
		showScreen("scanConfirm");
	} catch (err) {
		showTradeResult(false, t("trade.result_error_title"), err.message);
	}
}


function renderScanConfirm(offer) {
	// Remonté par l'utilisateur (18/09/2026) : "Confirmer l'achat devient
	// Confirmer l'échange" en troc, où il ne s'agit justement jamais d'un
	// achat (aucun jeton, échange direct carte-contre-carte - voir
	// GameService.recordCardSwap) - ce titre/bouton étaient jusqu'ici
	// partagés tels quels avec dette/libre (SCREEN_HEADERS/data-i18n
	// statiques dans player-view.html), corrects pour ces deux systèmes,
	// trompeurs pour le troc. Mis à jour ICI (appelée à chaque fois que cet
	// écran se peuple, voir resolveCode) plutôt que dans SCREEN_HEADERS (une
	// seule clé statique par écran, ne peut pas varier par système
	// monétaire) - h2 ET bouton haut, le bouton du bas (id=btnConfirmBuy)
	// est remis à son état normal par confirmPurchase() à la fin, qui suit
	// la même logique.
	document.querySelector("#scanConfirm h2").textContent = t(isTrocGame() ? "trade.confirm_title_swap" : "trade.confirm_title");
	el("btnConfirmBuy").textContent = t(isTrocGame() ? "trade.btn_confirm_swap" : "trade.btn_confirm_buy");
	const infoEl = el("scanConfirmInfo");
	infoEl.className = `trade-card-info level-${offer.cardLevel}`;
	// Reconstitue une "entrée catalogue" à partir de l'offre (elle ne porte
	// que cardTypeId/cardLevel/cardName, pas l'entrée complète) pour pouvoir
	// réutiliser buildGameCardHtml comme partout ailleurs - on retrouve
	// l'entrée réelle si le catalogue est disponible (résout le bon
	// visualId), sinon on retombe sur un objet minimal (nom/niveau connus
	// via l'offre elle-même, pas de visuel - buildGameCardHtml gère ce cas).
	const catalogEntry = (state.cardsCatalog || []).find((c) => c.id === offer.cardTypeId);
	const entry = catalogEntry || { id: offer.cardTypeId, niveau: offer.cardLevel, nom: offer.cardName };
	const visual = catalogEntry
		? (state.visualsCatalog || []).find((v) => v.id === catalogEntry.visualId)
		: null;
	const cardHtml = buildGameCardHtml(entry, visual, "geco-card-lg");

	if (isTrocGame()) {
		// Étape 3, troc + smartphone (18/09/2026) : échange DIRECT carte-contre-
		// carte, jamais de jetons ni de quantité négociée - voir
		// state.trocSwapOfferedItem, la carte que CE joueur a déjà retournée
		// avant de scanner (voir generateCardModalQr, bouton "Échanger").
		const offered = state.trocSwapOfferedItem;
		if (!offered) {
			// Cas limite (scan lancé depuis l'icône générale de la barre de
			// navigation plutôt que depuis le bouton "Échanger" d'une carte déjà
			// retournée) : sans carte à offrir, aucun échange n'est possible -
			// message explicite plutôt que de laisser deviner ou planter plus
			// loin (voir confirmPurchase, qui a le même garde-fou par prudence).
			infoEl.innerHTML = `
				${cardHtml}
				<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", offer.cardLevel))} · ${escapeHtmlLocal(t("trade.sold_by", { name: offer.sellerPlayerName }))}</span>`;
			el("scanConfirmBalanceRows").innerHTML = "";
			el("scanConfirmError").textContent = t("trade.swap_select_own_card_first");
			el("scanConfirmError").classList.remove("hidden");
			return;
		}
		const offeredCardHtml = buildGameCardHtml(offered.entry,
			(state.visualsCatalog || []).find((v) => v.id === offered.entry.visualId), "geco-card-lg");
		infoEl.innerHTML = `
			${cardHtml}
			<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", offer.cardLevel))} · ${escapeHtmlLocal(t("trade.sold_by", { name: offer.sellerPlayerName }))}</span>
			<span class="trade-card-info-price">${escapeHtmlLocal(t("trade.you_will_receive"))}</span>`;
		// Pas de lignes de solde en troc : il n'y a pas de jetons à suivre
		// (voir docs/10-etape-plugins-troc.md, règle 3) - à la place, montre la
		// carte donnée en retour (voir state.trocSwapOfferedItem ci-dessus).
		el("scanConfirmBalanceRows").innerHTML = `
			<p class="trade-card-info-price" style="margin-top:0.6rem;">${escapeHtmlLocal(t("trade.you_will_give_card"))}</p>
			${offeredCardHtml}`;
		el("scanConfirmError").classList.add("hidden");
		return;
	}

	const priceParts = [];
	if (offer.weakCoins > 0) priceParts.push(t("trade.price_weak", { n: offer.weakCoins }));
	if (offer.mediumCoins > 0) priceParts.push(t("trade.price_medium", { n: offer.mediumCoins }));
	if (offer.strongCoins > 0) priceParts.push(t("trade.price_strong", { n: offer.strongCoins }));
	const priceText = priceParts.length > 0 ? priceParts.join(" + ") : t("trade.price_free");

	infoEl.innerHTML = `
		${cardHtml}
		<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", offer.cardLevel))} · ${escapeHtmlLocal(t("trade.sold_by", { name: offer.sellerPlayerName }))}</span>
		<span class="trade-card-info-price">${escapeHtmlLocal(priceText)}</span>`;
	el("scanConfirmError").classList.add("hidden");

	// Solde avant/après (voir GameService.computeTradeBalance) - jamais
	// inventé : state.player.tradeBalance vient du dernier chargement
	// (refreshPlayer), la valeur "après" est calculée ici côté client pour un
	// affichage immédiat, mais c'est bien le SERVEUR qui fait foi au moment
	// de la confirmation réelle (voir confirmPurchase).
	const price = offer.weakCoins + (2 * offer.mediumCoins) + (4 * offer.strongCoins);
	const current = state.player.tradeBalance || 0;
	const after = current - price;
	el("scanConfirmBalanceRows").innerHTML = `
		<div class="trade-balance-row">
			<span class="trade-balance-row-label">${escapeHtmlLocal(t("trade.balance_row_current"))}</span>
			<span class="trade-balance-row-value">${current}</span>
		</div>
		<div class="trade-balance-row">
			<span class="trade-balance-row-label">${escapeHtmlLocal(t("trade.balance_row_to_pay"))}</span>
			<span class="trade-balance-row-value negative">−${price}</span>
		</div>
		<div class="trade-balance-row total">
			<span class="trade-balance-row-label">${escapeHtmlLocal(t("trade.balance_row_after"))}</span>
			<span class="trade-balance-row-value">${after}</span>
		</div>`;
}

async function confirmPurchase() {
	// Trace de diagnostic temporaire (08/09/2026) - remonté par l'utilisateur :
	// clic sur "Confirmer l'achat" sans aucun effet visible, sans erreur dans
	// la console. But : confirmer que cette fonction est bien appelée, et voir
	// EXACTEMENT où le flux s'arrête - à retirer une fois la cause confirmée.
	console.log("[DIAG achat] confirmPurchase() appelée, offer =", state.pendingOffer); //$NON-NLS-1$
	const offer = state.pendingOffer;
	const btn = el("btnConfirmBuy");
	// Étape 3, troc + smartphone (18/09/2026) : même garde-fou que
	// renderScanConfirm ci-dessus - sans carte déjà retournée, cet échange ne
	// peut pas aboutir (voir state.trocSwapOfferedItem).
	if (isTrocGame() && !state.trocSwapOfferedItem) {
		el("scanConfirmError").textContent = t("trade.swap_select_own_card_first");
		el("scanConfirmError").classList.remove("hidden");
		return;
	}
	btn.disabled = true;
	// "Confirmer l'échange" en troc, jamais "Confirmer l'achat" - voir le
	// même raisonnement dans renderScanConfirm ci-dessus.
	btn.textContent = t(isTrocGame() ? "trade.btn_confirming_swap" : "trade.btn_confirming");
	try {
		// Étape 3, troc + smartphone (18/09/2026, remonté par l'utilisateur :
		// "le système d'échange de cartes doit être repensé") : route DÉDIÉE
		// (/redeem-swap), jamais /redeem ci-dessous (dette/libre uniquement
		// désormais) - voir GameService.recordCardSwap pour les règles de
		// validation (même valeur, réciprocité bidirectionnelle).
		const endpoint = isTrocGame() ? "redeem-swap" : "redeem";
		const body = isTrocGame()
			? {
				buyerPlayerId: state.player.id, buyerAccessToken: state.token,
				offeredCardTypeId: state.trocSwapOfferedItem.entry.id,
				offeredCardLevel: state.trocSwapOfferedItem.entry.niveau,
			}
			: { buyerPlayerId: state.player.id, buyerAccessToken: state.token };
		console.log("[DIAG achat] envoi de la requête vers", `/api/games/${state.gameId}/trade-offers/${offer.code}/${endpoint}`); //$NON-NLS-1$
		const res = await fetch(`/api/games/${state.gameId}/trade-offers/${offer.code}/${endpoint}`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body),
		});
		console.log("[DIAG achat] réponse reçue, status =", res.status, "ok =", res.ok); //$NON-NLS-1$ //$NON-NLS-2$
		if (!res.ok) {
			const body2 = await res.json().catch(() => ({}));
			console.log("[DIAG achat] echec, corps de la reponse =", body2); //$NON-NLS-1$
			throw new Error(body2.error || body2.msg || t("join.generic_error", { status: res.status }));
		}
		// Le prix qui fait foi est celui renvoyé par le serveur (TransactionDto.
		// totalCoinsValue), pas une estimation côté client - même s'ils
		// coïncident presque toujours en pratique.
		const transaction = await res.json();
		console.log("[DIAG achat] transaction reussie =", transaction); //$NON-NLS-1$
		const cardName = catalogTextValue(offer.cardName) || offer.cardTypeId;
		if (isTrocGame()) {
			// Pas de solde en jetons à annoncer en troc - juste la confirmation
			// de l'échange (le nouvel inventaire sera visible au rafraîchissement
			// du hub, voir refreshPlayer()).
			const givenName = catalogTextValue(state.trocSwapOfferedItem.entry.nom) || state.trocSwapOfferedItem.entry.id;
			// BUG TROUVÉ ET CORRIGÉ (campagne de test troc+smartphone, 10/10/2026) :
			// le TITRE de l'écran de résultat restait "Achat réussi !"
			// (trade.result_success_title) même pour un échange troc, qui n'est
			// jamais un achat (aucun jeton, voir docs/10-etape-plugins-troc.md,
			// règle 6) - seul le CORPS du message (result_success_body_swap)
			// avait déjà été adapté. Titre dédié désormais, même principe que
			// renderScanConfirm/confirmPurchase plus haut dans ce fichier (déjà
			// adaptés au troc pour le reste de cet écran).
			showTradeResult(true, t("trade.result_success_title_swap"),
				t("trade.result_success_body_swap", { received: cardName, given: givenName }));
			state.trocSwapOfferedItem = null;
		} else {
			const newBalance = (state.player.tradeBalance || 0) - transaction.totalCoinsValue;
			showTradeResult(true, t("trade.result_success_title"),
				t("trade.result_success_body_balance", { name: cardName, balance: newBalance }),
				{ newBalance, delta: -transaction.totalCoinsValue });
		}
		refreshPlayer();
	} catch (err) {
		console.log("[DIAG achat] exception attrapee =", err.message, err); //$NON-NLS-1$
		// Remonté par l'utilisateur (02/09/2026) : "quand un joueur tente
		// d'acheter une carte mais qu'il n'a plus suffisamment de jetons...
		// il faut qu'une infobulle apparaisse 3 secondes" - message dédié,
		// plus clair pour le joueur que le texte technique renvoyé par le
		// serveur (voir GameService.recordTransaction, "Solde insuffisant
		// pour cet achat.") - détecté par correspondance de CE message précis
		// (le nôtre, pas un texte tiers fragile) plutôt qu'un code d'erreur
		// dédié, pour rester simple.
		if (err.message.includes("Solde insuffisant")) {
			showToast(t("trade.insufficient_balance_toast"));
		} else if (err.message.includes("Impossible de rendre la monnaie")) {
			// Remonté par l'utilisateur (07/09/2026) : "si le vendeur n'a pas
			// de quoi rendre la monnaie... la transaction est annulée. Une
			// info bulle apparaît 3 secondes impossible de rendre la
			// monnaie." Cas DISTINCT du solde insuffisant ci-dessus :
			// l'acheteur a largement assez en valeur totale, mais ni lui ni
			// le vendeur n'ont la pièce précise nécessaire pour tomber sur le
			// compte exact (voir GameService.findPaymentWithChange).
			showToast(t("trade.cannot_make_change_toast"));
		} else if (err.message.includes("Échange refusé")) {
			// Étape 3, troc + smartphone (18/09/2026) : "si ce n'est pas le cas,
			// la transaction est refusée. Une infobulle s'affiche 3 secondes à
			// l'écran avec le texte 'Echange refusé'." - GameService.recordCardSwap
			// renvoie plusieurs messages distincts commençant tous par "Échange
			// refusé" (même valeur, réciprocité, carte plus en main...) : un seul
			// texte fixe affiché au joueur pour tous ces cas, comme demandé -
			// le détail précis reste consultable côté journal serveur/débogage.
			showToast(t("trade.swap_refused_toast"));
			state.trocSwapOfferedItem = null;
		} else {
			el("scanConfirmError").textContent = err.message;
			el("scanConfirmError").classList.remove("hidden");
		}
	} finally {
		btn.disabled = false;
		btn.textContent = t(isTrocGame() ? "trade.btn_confirm_swap" : "trade.btn_confirm_buy");
	}
}

// Refonte du 31/08/2026 (remonté par l'utilisateur, code HTML/CSS fourni en
// référence exacte) : se ferme automatiquement après 3 secondes - plus de
// bouton "Retour" à cliquer - vers "Mes cartes" en cas de succès (on vient
// de vendre/acheter, la suite naturelle est d'y voir son inventoire à jour),
// vers l'accueil en cas d'échec. pBalanceInfo (optionnel) : {newBalance,
// delta} - affiché seulement pour un succès en dette/libre (jamais en troc,
// qui n'a pas de jetons, ni pour un échec).
function showTradeResult(success, title, body, pBalanceInfo) {
	el("tradeResultIcon").innerHTML = success
		? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>'
		: '<span aria-hidden="true">⚠️</span>';
	el("tradeResultIcon").className = `trade-result-v2-icon ${success ? "success" : "error"}`;
	el("tradeResultTitle").textContent = title;
	el("tradeResultTitle").classList.toggle("error", !success);
	el("tradeResultBody").textContent = body;

	const divider = document.querySelector("#tradeResult .trade-result-v2-divider");
	const footer = document.querySelector("#tradeResult .trade-result-v2-footer");
	if (success && pBalanceInfo) {
		divider.classList.remove("hidden");
		footer.classList.remove("hidden");
		el("tradeResultNewBalance").textContent = pBalanceInfo.newBalance;
		const deltaEl = el("tradeResultDelta");
		deltaEl.textContent = (pBalanceInfo.delta >= 0 ? "+" : "") + pBalanceInfo.delta;
		deltaEl.className = `trade-result-v2-delta ${pBalanceInfo.delta >= 0 ? "positive" : "negative"}`;
	} else {
		divider.classList.add("hidden");
		footer.classList.add("hidden");
	}

	showScreen("tradeResult");

	clearTimeout(state.tradeResultTimeout);
	state.tradeResultTimeout = setTimeout(() => {
		if (success) { renderMyCards(); setActiveNav("navBtnCards"); }
		else { showScreen("viewContent"); }
	}, 3000);
}

// ---------- Câblage des boutons (une fois, au chargement) ----------
function initTradeUI() {
	initCardModalGestures();
	// btnOpenSell (bouton "Vendre une carte" autonome sur l'onglet Cartes) a
	// été retiré du HTML le 31/08/2026 (remonté par l'utilisateur) - vendre se
	// fait désormais exclusivement en cliquant sur une carte (voir
	// openCardModal). openSellPicker()/l'écran sellPicker restent dans le code
	// (inoffensifs, jamais atteints par aucun chemin de clic) au cas où ce
	// choix serait reconsidéré, mais plus aucun bouton n'y mène.
	// Barre de navigation basse persistante (voir le raisonnement en tête de
	// player-view.html) - 5 emplacements : Accueil/Cartes/[scan]/Stats/Profil,
	// le bouton central reprenant l'action d'achat (openScan), déjà existante.
	el("navIcHome").innerHTML = iconSvg("home");
	el("navIcCards").innerHTML = iconSvg("inbox");
	el("navIcScan").innerHTML = iconSvg("scan");
	el("navIcStats").innerHTML = iconSvg("bar-chart-2");
	el("navIcProfile").innerHTML = iconSvg("user");
	el("navBtnHome").addEventListener("click", () => { showScreen("viewContent"); setActiveNav("navBtnHome"); });
	el("navBtnCards").addEventListener("click", () => { renderMyCards(); setActiveNav("navBtnCards"); });
	el("navBtnScan").addEventListener("click", openScan);
	// Écran caméra plein écran (30/08/2026) - icônes + boutons propres à cet
	// écran (en-tête, historique, clavier, flash).
	el("scannerCloseBtn").innerHTML = iconSvg("chevron-left");
	el("scannerHistoryIcon").innerHTML = iconSvg("history");
	el("scannerKeyboardIcon").innerHTML = iconSvg("keyboard");
	el("scannerFlashBtn").innerHTML = iconSvg("zap");
	el("scannerCloseBtn").addEventListener("click", () => {
		// Troc + smartphone : referme aussi la sélection en attente (voir
		// state.trocSwapOfferedItem) - un scan abandonné ne doit jamais laisser
		// une carte "prête à échanger" traîner pour une prochaine tentative sans
		// rapport.
		state.trocSwapOfferedItem = null;
		showScreen("viewContent");
	});
	el("scannerHistoryBtn").addEventListener("click", () => { renderHistory(); setActiveNav("navBtnProfile"); });
	el("scannerFlashBtn").addEventListener("click", async () => {
		const btn = el("scannerFlashBtn");
		const isOn = btn.classList.toggle("active");
		try {
			const [track] = (state.scanStream || { getVideoTracks: () => [] }).getVideoTracks();
			if (track) await track.applyConstraints({ advanced: [{ torch: isOn }] });
		} catch (err) {
			btn.classList.toggle("active", !isOn); // repli : la bascule visuelle suit l'échec, pas d'état "actif" mensonger
		}
	});
	el("navBtnStats").addEventListener("click", () => { renderLeaderboard(); setActiveNav("navBtnStats"); });
	el("navBtnProfile").addEventListener("click", () => { renderProfile(); showScreen("profileScreen"); setActiveNav("navBtnProfile"); });
	el("btnOpenHistoryFromProfile").addEventListener("click", renderHistory);
	// Bascule grille/liste + tri de "Mes cartes" (voir renderMyCards).
	el("btnCardsViewGrid").addEventListener("click", () => { state.myCardsViewMode = "grid"; renderMyCards(); });
	el("btnCardsViewList").addEventListener("click", () => { state.myCardsViewMode = "list"; renderMyCards(); });
	el("myCardsSortSelect").addEventListener("change", (e) => { state.myCardsSortMode = e.target.value; renderMyCards(); });
	el("btnOpenCreditRequest").addEventListener("click", renderCreditRequest);
	// btnOpenManualEntry (bouton "Saisir un code" autonome sur le hub) a
	// disparu avec la refonte du 30/08/2026 - la saisie manuelle reste
	// entièrement fonctionnelle via son repli déjà existant depuis l'écran
	// caméra (voir btnSwitchToManual juste en dessous), conformément au
	// mockup de référence (un seul bouton "scanner" dans la barre de nav,
	// jamais deux entrées séparées pour la même action d'achat).
	el("btnSwitchToManual").addEventListener("click", openManualEntry);

	document.querySelectorAll(".btn-back").forEach((btn) => {
		btn.addEventListener("click", () => {
			const section = btn.closest("section").id;
			if (section === "sellPicker") showScreen("viewContent");
			else if (section === "sellPrice") showScreen("sellPicker");
			else if (section === "sellQr") showScreen("sellPrice");
			else if (section === "scanCamera") showScreen("viewContent");
			else if (section === "scanManual") showScreen("viewContent");
		});
	});

	initPriceSteppers();
	el("btnGenerateQr").addEventListener("click", createOfferAndShowQr);
	el("btnRegenerateQr").addEventListener("click", createOfferAndShowQr);
	el("btnCancelSell").addEventListener("click", () => showScreen("viewContent"));

	el("btnSubmitManualCode").addEventListener("click", submitManualCode);
	el("manualCodeInput").addEventListener("keydown", (e) => { if (e.key === "Enter") submitManualCode(); });

	console.log("[DIAG achat] cablage du bouton, element trouve =", el("btnConfirmBuy")); //$NON-NLS-1$
	el("btnConfirmBuy").addEventListener("click", confirmPurchase);
	el("btnCancelBuy").addEventListener("click", () => showScreen("viewContent"));
	// btnTradeResultBack a été retiré du HTML le 31/08/2026 (remonté par
	// l'utilisateur) : l'écran de résultat se ferme désormais tout seul après
	// 3 secondes (voir showTradeResult), plus besoin de bouton "Retour".
}

// ---------- Démarrage ----------
// Même précaution que sur join.html : attendre la première traduction
// effective avant le premier refresh(), pour ne jamais afficher une clé brute
// le temps que la langue se charge (voir player.js pour la même logique).
let started = false;
async function startOnce() {
	if (started) return;
	started = true;
	// Catalogues cartes/visuels préchargés ici (pas seulement à l'ouverture de
	// "Vendre une carte") : un ACHETEUR arrivant directement sur l'écran de
	// confirmation (scan/saisie manuelle) en a besoin lui aussi pour assembler
	// la carte composite (voir buildGameCardHtml/renderScanConfirm).
	try {
		state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
		state.visualsCatalog = await fetch("/api/catalogs/visuels").then((r) => r.json());
	} catch (err) {
		// Pas bloquant : buildGameCardHtml sait se replier sur un encadré simple
		// si le visuel/catalogue attendu n'est pas disponible - mais toujours
		// journalisé (voir pushDebugLog, remonté par l'utilisateur le
		// 09/09/2026) plutôt qu'avalé en silence comme avant.
		pushDebugLog("ERREUR", "Échec du chargement des catalogues cartes/visuels :", err);
	}
	wireDebugPanel();
	initTradeUI();
	refreshPlayer();
	setInterval(refreshPlayer, 5000);
	connectPlayerWs();
}

// Remonté par l'utilisateur (09/09/2026) - voir pushDebugLog en tête de
// fichier pour le contexte complet. Badge invisible tant que debugLog est
// vide ; dès la première entrée, réapparaît et affiche le nombre d'entrées
// capturées - assez pour qu'un joueur ou l'animateur, en regardant par-dessus
// son épaule, remarque qu'"il s'est passé quelque chose" même sans savoir
// lire le détail lui-même.
function updateDebugBadge() {
	const badge = el("debugLogBadge");
	if (!badge) return;
	badge.classList.toggle("hidden", debugLog.length === 0);
	badge.textContent = debugLog.length > 0 ? `🐞${debugLog.length}` : "🐞";
}

function wireDebugPanel() {
	const badge = el("debugLogBadge");
	const panel = el("debugLogPanel");
	if (!badge || !panel) return;
	badge.addEventListener("click", () => {
		el("debugLogContent").textContent = debugLog.length > 0
			? debugLog.join("\n")
			: "(journal vide)";
		panel.classList.remove("hidden");
	});
	el("debugLogCloseBtn").addEventListener("click", () => panel.classList.add("hidden"));
	el("debugLogCopyBtn").addEventListener("click", async () => {
		try {
			await navigator.clipboard.writeText(debugLog.join("\n"));
			el("debugLogCopyBtn").textContent = "✓";
			setTimeout(() => { el("debugLogCopyBtn").textContent = t("playerView.debug_log_copy"); }, 1500);
		} catch (err) {
			// Presse-papier indisponible (contexte non sécurisé, permission refusée...) -
			// le contenu reste sélectionnable/copiable à la main dans tous les cas,
			// ce bouton n'est qu'un raccourci pratique, jamais le seul moyen.
		}
	});
}

// Étape 3 (31/08/2026) : connexion WebSocket - remonté par l'utilisateur en
// creusant l'écran "Vente réussie" (côté VENDEUR) : jusqu'ici, rien ne
// prévenait le vendeur que sa carte avait été vendue - il ne voyait que le
// compte à rebours de son QR arriver à 0, indiscernable d'un QR simplement
// expiré sans avoir jamais été scanné. Même mécanisme que app.js (voir
// connectWs côté animateur) : une seule connexion, reconnexion automatique.
// Coup de sifflet à chaque début de tour (remonté par l'utilisateur,
// 02/09/2026) - même fichier son que côté animateur (voir playWhistle()
// dans app.js), version simplifiée : pas de réglage volume/muet à
// consulter ici (ces réglages sont propres à l'écran animateur, jamais
// exposés au joueur). Secours synthétisé identique si la lecture du
// fichier échoue (ex. navigateur exigeant une interaction préalable).
let mPlayerAudioCtx = null;
// Infobulle temporaire (3 secondes) - remonté par l'utilisateur
// (02/09/2026) : "solde insuffisant" pendant un achat, "fin du tour" à
// l'expiration du minuteur. Plusieurs infobulles peuvent s'empiler
// brièvement (conteneur en colonne, voir .toast-container) plutôt que de
// s'écraser les unes les autres - chacune gère sa propre disparition,
// indépendamment des autres.
function showToast(pMessage) {
	const toast = document.createElement("div");
	toast.className = "toast";
	toast.textContent = pMessage;
	el("toastContainer").appendChild(toast);
	setTimeout(() => {
		toast.classList.add("toast-hide");
		setTimeout(() => toast.remove(), 250); // laisse l'animation de sortie se jouer avant de retirer l'élément
	}, 3000);
}

// Paramètre `kind` ajouté le 10/10/2026 (animation "Stop !" de fin de tour) -
// même fichier MP3 unique pour le début ET la fin (voir playWhistle côté
// animateur, app.js : "un seul et même son... la demande décrit un seul coup
// de sifflet, pas deux sons distincts") - seul le secours synthétisé
// distingue les deux (voir playSynthesizedPlayerWhistle ci-dessous), par
// cohérence avec ce même fichier.
function playPlayerWhistle(kind) {
	try {
		const audio = new Audio("/sounds/whistle.mp3");
		audio.play().catch(() => playSynthesizedPlayerWhistle(kind));
	} catch (err) {
		playSynthesizedPlayerWhistle(kind);
	}
}
function playSynthesizedPlayerWhistle(kind) {
	try {
		if (!mPlayerAudioCtx) mPlayerAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
		if (mPlayerAudioCtx.state === "suspended") mPlayerAudioCtx.resume();
		const ctx = mPlayerAudioCtx;
		function blast(startAt, durationSec) {
			const osc = ctx.createOscillator();
			const gain = ctx.createGain();
			osc.connect(gain);
			gain.connect(ctx.destination);
			osc.type = "square";
			osc.frequency.setValueAtTime(2200, startAt);
			gain.gain.setValueAtTime(0.0001, startAt);
			gain.gain.exponentialRampToValueAtTime(0.25, startAt + 0.02);
			gain.gain.exponentialRampToValueAtTime(0.0001, startAt + durationSec);
			osc.start(startAt);
			osc.stop(startAt + durationSec);
		}
		const now = ctx.currentTime;
		// Même convention que playSynthesizedWhistle côté animateur (app.js) :
		// un seul coup long pour le début ("start"), deux coups brefs pour la
		// fin - sans fichier MP3 disponible, cette distinction redevient utile.
		if (kind === "start") {
			blast(now, 0.35);
		} else {
			blast(now, 0.18);
			blast(now + 0.25, 0.2);
		}
	} catch (err) {
		// Ni fichier ni synthèse possible (navigateur très restrictif) : on
		// abandonne silencieusement, jamais bloquant pour la suite.
	}
}

let mPlayerWs = null;
function connectPlayerWs() {
	// BUG TROUVÉ (remonté par l'utilisateur, 05/09/2026 : "plusieurs coups de
	// sifflet consécutifs") : rien ne fermait jamais une connexion PRÉCÉDENTE
	// avant d'en ouvrir une nouvelle - sur un vrai smartphone (verrouillage
	// d'écran, changement WiFi/4G, longue partie...), une connexion peut
	// rester "fantôme" côté serveur pendant qu'une nouvelle se rétablit en
	// parallèle. Plusieurs connexions vivantes pouvaient alors s'accumuler
	// au fil de la partie, CHACUNE recevant et rejouant indépendamment toute
	// diffusion (chaque nouveau tour, autant de sifflets que de connexions
	// vivantes accumulées). Corrigé : au plus UNE connexion volontairement
	// ouverte à la fois - toute connexion précédente est explicitement
	// fermée avant d'en établir une nouvelle (onclose neutralisé sur
	// l'ancienne pour éviter qu'elle ne déclenche elle-même une reconnexion
	// en cascade).
	if (mPlayerWs) {
		mPlayerWs.onclose = null;
		mPlayerWs.close();
	}
	const proto = location.protocol === "https:" ? "wss" : "ws";
	// Remonté par un utilisateur (02/09/2026, anticipation d'un hébergement
	// internet) : un joueur reste toujours sur LA MÊME partie pendant toute
	// la durée de sa session (connue dès le chargement de la page, jamais
	// changée) - contrairement à l'animateur (voir connectWs dans app.js,
	// qui doit gérer un changement de partie EN COURS DE SESSION via un
	// message dédié), il suffit ici de transmettre gameId une seule fois, en
	// paramètre de connexion.
	const ws = new WebSocket(`${proto}://${location.host}/ws?gameId=${encodeURIComponent(state.gameId)}`);
	mPlayerWs = ws;
	ws.onclose = () => setTimeout(connectPlayerWs, 2000);
	ws.onerror = () => ws.close();
	ws.onmessage = (evt) => {
		const msg = JSON.parse(evt.data);
		if (String(msg.gameId) !== String(state.gameId)) return;
		if ((msg.type === "transaction") && state.player && (msg.payload.sellerPlayerId === state.player.id))
			handleOwnSaleCompleted(msg.payload);
		// Carré encaissé (voir GameService.checkAndCashInSquares, diffusé
		// depuis GecoServer) - remonté par l'utilisateur (31/08/2026) :
		// déclenche l'animation automatiquement, pour CE joueur uniquement.
		if ((msg.type === "square") && state.player && (msg.payload.playerId === state.player.id))
			enqueueSquareAnimation(msg.payload);
		// Mort du joueur (26/09/2026, voir GecoServer, route POST
		// /api/games/{id}/events) - remonté par l'utilisateur : "à l'entre
		// deux tours, lorsque les morts sont annoncés... les smartphones des
		// joueurs déclenchent une animation". Ciblage PAR JOUEUR, même
		// principe que le carré ci-dessus - seul le joueur qui vient de
		// mourir voit l'animation, jamais les autres joueurs de la partie.
		if ((msg.type === "death") && state.player && (msg.payload.playerId === state.player.id))
			enqueueDeathAnimation(msg.payload);
		// Animation "Prison !" (11/10/2026, dette+smartphone uniquement, voir
		// GecoServer, route POST /api/games/{id}/events, cas PRISON) - diffusée
		// au même instant que Player.prisonUntilTurn est posé, à l'entre-deux-
		// tours (voir Dtos.PlayerPrisonAnimationDto). Jamais de file d'attente
		// ici (contrairement à "death") : playPrisonAnimation() se garde déjà
		// elle-même contre un double déclenchement (voir mPrisonAnimShown).
		if ((msg.type === "prison") && state.player && (msg.payload.playerId === state.player.id))
			playPrisonAnimation();
		// Notification "Crédit accordé"/"Crédit refusé" (20/10/2026, remonté par
		// l'utilisateur, PDF "Retours - 20261020", point 3) : "l'écran de
		// smartphone du joueur [...] affiche une infobulle de 3 secondes qui dit
		// 'Crédit accordé' et qui le redirige vers l'écran des cartes. Et si
		// jamais l'animateur refuse le crédit, alors l'infobulle [...] écrit
		// 'Crédit refusé' [...]." Diffusé par GecoServer sur TROIS routes, pas
		// seulement .../approve et .../decline comme le commentaire d'origine
		// (20/10/2026) le croyait à tort : POST /api/games/{id}/credit-requests
		// (CRÉATION de la demande par le joueur LUI-MÊME) broadcast aussi
		// "creditRequest" - voir son commentaire "Notifie le tableau de bord
		// animateur en direct" - avec status="pending", pour que le tableau de
		// bord animateur se mette à jour sans attendre un polling. Ce message
		// arrive donc bien SUR LE TÉLÉPHONE DU DEMANDEUR LUI-MÊME dès l'envoi de
		// sa propre demande (son propre WebSocket suit la partie comme
		// n'importe quel autre, broadcast() ne l'exclut jamais - voir
		// GecoServer.broadcast()).
		// BUG TROUVÉ ET CORRIGÉ (audit indépendant du 20/10/2026, reproduit en
		// HTTP+WebSocket réel - script dédié, serveur réellement démarré,
		// requête POST /credit-requests réelle observée sur la connexion
		// WebSocket du joueur) : le code d'origine ne distinguait PAS "pending"
		// de "declined" (seul "approved" était testé, tout le reste tombait
		// dans la branche "refusé") - CONTRAIREMENT à ce qu'affirmait le
		// commentaire ci-dessus ("ce statut n'arrive jamais par ce canal"),
		// chaque demande envoyée par un joueur depuis son propre téléphone
		// déclenchait IMMÉDIATEMENT, sur ce même téléphone, un toast "Crédit
		// refusé" et une bascule vers l'écran Cartes - écrasant l'écran "En
		// attente" tout juste affiché par renderCreditRequestStatus() juste
		// après l'envoi (voir submitCreditRequest ci-dessus) - alors qu'aucune
		// décision n'avait encore été prise par l'animateur. Reproduit
		// systématiquement (statuts reçus: ["pending"] à chaque demande créée).
		// Corrigé en ne réagissant plus qu'aux VRAIES décisions de l'animateur
		// (status "approved" OU "declined" explicitement), en ignorant
		// "pending" comme n'importe quel autre statut inconnu futur.
		if ((msg.type === "creditRequest") && state.player && (msg.payload.playerId === state.player.id)
				&& ((msg.payload.status === "approved") || (msg.payload.status === "declined"))) {
			showToast(msg.payload.status === "approved"
				? t("playerView.credit_request_approved_toast") : t("playerView.credit_request_declined_toast"));
			// Même garde que "Commencez !"/"Stop !" ci-dessous : ne jamais
			// bousculer un achat/une vente EN COURS sur ce téléphone (modal carte
			// ouverte ou scan actif) - la bascule vers Cartes n'a alors pas lieu,
			// le joueur y retrouvera son nouveau solde normalement en y
			// retournant lui-même.
			refreshPlayer();
			if (!state.cardModalItem && !state.scanStream) {
				renderMyCards();
				setActiveNav("navBtnCards");
			}
		}
		// Révolution économique (22/09/2026, "rotation des valeurs" - voir
		// Game.revolutionCount/cardPriceInDuTable) : contrairement au carré
		// lui-même (animation propre au seul joueur concerné, ci-dessus), une
		// révolution change le PRIX de toutes les cartes pour TOUT LE MONDE -
		// chaque joueur doit donc en être informé, pas seulement celui qui a
		// fait le carré. rafraîchit le solde/les prix affichés (refreshPlayer
		// relit PlayerSelfViewDto, qui inclut le revolutionCount à jour) avant
		// le toast, pour que l'onglet Cartes montre déjà les bons prix si le
		// joueur l'ouvre juste après.
		if ((msg.type === "square") && msg.payload.triggeredRevolution) {
			refreshPlayer();
			showToast(t("playerView.revolution_toast"));
		}
		// Nouveau tour démarré (voir bouton "Démarrer la partie", app.js) -
		// remonté par l'utilisateur (02/09/2026) : "il faut aussi que l'écran
		// des joueurs montre automatiquement l'écran des Cartes". Ne
		// bouscule jamais un achat/une vente EN COURS (modal carte ouverte
		// ou caméra active) - seulement depuis un écran "passif" (accueil,
		// profil, stats, déjà sur Cartes...).
		// Nouveau tour démarré (voir bouton "Démarrer la partie"/l'assistant de
		// fin de tour, app.js) - remonté par l'utilisateur : "il faut que
		// chaque début de tour s'accompagne d'un coup de sifflet", en plus de
		// la bascule automatique déjà en place (voir plus haut, session
		// précédente). Le sifflet joue TOUJOURS, y compris pendant un
		// achat/une vente en cours (un simple son n'interrompt rien, contrairement
		// à la bascule d'écran juste en dessous, elle bien gardée).
		if ((msg.type === "event") && (msg.payload.type === "TURN")) {
			playPlayerWhistle("start");
			// Remonté par l'utilisateur (06/09/2026) : "les carrés s'enchaînent
			// même pendant l'entre-deux-tours. L'animation ne s'arrête plus." -
			// purge tout ce qui restait en file d'attente (voir
			// clearSquareAnimQueueOnNewTurn, plus bas) - un ancien carré du tour
			// précédent n'a plus le même intérêt une fois le nouveau tour
			// démarré. L'animation ACTUELLEMENT en cours, s'il y en a une,
			// continue naturellement jusqu'à sa fin - jamais interrompue en
			// plein milieu, seules les suivantes en attente sont annulées.
			clearSquareAnimQueueOnNewTurn();
			// Animation "Commencez !" plein écran (10/10/2026, remonté par
			// l'utilisateur, image jointe) remplace ici l'ancienne infobulle +
			// bascule immédiate vers l'onglet Cartes (toujours gardée si un
			// achat/une vente est EN COURS, voir ci-dessous) - la bascule vers
			// Cartes a maintenant lieu à la FIN de l'animation elle-même (voir
			// playTurnStartAnimation), pas ici.
			// BUG TROUVÉ ET CORRIGÉ (10/10/2026, seconde relecture indépendante
			// du commit b70216b) : la garde testait state.cardModalOffer, qui ne
			// devient non-null qu'une fois generateCardModalQr() revenu du
			// serveur (round-trip réseau) - or openCardModal() affiche déjà la
			// modal ET lance ce round-trip de façon synchrone (voir
			// state.cardModalItem, posé en tout premier dans openCardModal,
			// AVANT tout await). Reproduit en conditions réelles (réseau
			// ralenti, voir docs du 09/09/2026 sur les "accrocs wifi" fréquents
			// avec de nombreux téléphones) : un joueur qui venait de taper sur
			// une carte pour la vendre voyait l'animation plein écran
			// (pointer-events:auto) recouvrir intégralement sa modal encore en
			// cours de préparation - exactement le blocage de ~1,5s que cette
			// garde a été écrite pour éviter (voir le commentaire du commit
			// original), juste avec le mauvais indicateur. state.cardModalItem
			// est vrai dès l'ouverture de la modal (persiste jusqu'à
			// closeCardModal()), qu'une offre ait déjà été générée ou non -
			// c'est le bon test ici. state.cardModalOffer reste le bon test
			// ailleurs dans ce fichier (ex. handleOwnSaleCompleted) où il s'agit
			// de savoir si la modal correspond À CETTE offre précise, pas
			// simplement si une modal est ouverte - ne pas généraliser ce
			// correctif à ces autres usages.
			if (!state.cardModalItem && !state.scanStream) {
				enqueueTurnStartAnimation();
			} else {
				// Achat/vente en cours sur ce téléphone : ne jamais poser une
				// animation plein écran par-dessus (bloquerait la modal/le scan
				// pendant ~1,5s) - repli sur la simple infobulle d'avant cette
				// fonctionnalité, le joueur retrouvera l'onglet Cartes normalement
				// à la fermeture de sa modal/son scan, sans bascule forcée.
				showToast(t("playerView.turn_started_toast"));
			}
		}
		// Fin de tour (10/10/2026, nouvelle diffusion WS dédiée "turn_ending" -
		// voir GecoServer.java/app.js, Api.announceTurnEnd) - remonté par
		// l'utilisateur : animation "Stop !" plein écran symétrique de
		// "Commencez !" ci-dessus, même garde contre l'interruption d'un
		// achat/une vente en cours. Pas de sifflet "stop" séparé joué ICI :
		// contrairement au début de tour (sifflet joué immédiatement, même si
		// l'animation est différée par la garde ci-dessus), jouer le sifflet de
		// fin de tour n'a de sens qu'accompagné de l'animation - un joueur en
		// pleine transaction qui ne verra pas "Stop !" n'a pas besoin d'un
		// sifflet sans contexte visuel.
		if (msg.type === "turn_ending") {
			// Referme "Prison !" si elle était affichée (demande explicite :
			// "L'image disparaitra automatiquement à la fin du tour") - voir
			// dismissPrisonAnimation(), idempotente (ne fait rien si l'overlay
			// n'était pas affiché). Appelée AVANT la garde cardModalItem/
			// scanStream ci-dessous : un joueur en prison ne peut par
			// construction pas avoir de modal carte/scan ouvert (l'overlay
			// plein écran, au-dessus de tout, l'en empêche), donc aucun risque
			// de conflit avec elle.
			dismissPrisonAnimation();
			// Correctif anti-doublon : le rafraîchissement périodique
			// (refreshPlayer, toutes les 5s) détecte LUI AUSSI la fin de tour,
			// indépendamment de ce message WS (transition tradingAllowed true ->
			// false, voir plus haut dans ce fichier - mécanisme PRÉEXISTANT,
			// gardé tel quel comme filet de sécurité si ce message WS était
			// manqué, ex. reconnexion). Sans cette ligne, l'infobulle "Fin de
			// tour" de ce mécanisme plus ancien se déclencherait À NOUVEAU juste
			// après (ou pendant) l'animation/l'infobulle immédiate ci-dessous,
			// pour le même événement.
			state.previousTradingAllowed = false;
			// Même correctif que ci-dessus pour "Commencez !" (10/10/2026) :
			// state.cardModalItem, pas state.cardModalOffer (voir le
			// commentaire détaillé plus haut dans ce même fichier).
			if (!state.cardModalItem && !state.scanStream) {
				playPlayerWhistle("stop");
				enqueueTurnEndAnimation();
			} else {
				showToast(t("playerView.turn_ended_toast"));
			}
		}
	};
}

// Ma propre carte vient d'être vendue (voir connectPlayerWs) - seulement si
// la modal carte est ACTUELLEMENT ouverte sur CETTE offre (state.cardModalOffer) :
// si le joueur a déjà refermé la modal entre-temps, l'offre a bien été
// honorée côté serveur (le nouvel inventaire/solde apparaîtra au prochain
// rafraîchissement, voir refreshPlayer) mais on ne réagit pas ici pour
// éviter d'interrompre autre chose que le joueur ferait sur son téléphone.
function handleOwnSaleCompleted(transactionDto) {
	if (!state.cardModalOffer) return; // pas de modal ouverte sur une offre, rien à faire ici (voir le commentaire au-dessus)
	clearCardModalCountdown();
	closeCardModal();
	const cardName = state.cardModalItem ? state.cardModalItem.label : transactionDto.cardTypeId;
	const newBalance = (state.player.tradeBalance || 0) + transactionDto.totalCoinsValue;
	showTradeResult(true, t("trade.result_sale_success_title"),
		t("trade.result_success_body_sold", { name: cardName, buyer: transactionDto.buyerPlayerName }),
		{ newBalance, delta: transactionDto.totalCoinsValue });
	refreshPlayer();
}

// ============================================================
// File d'attente pour l'animation "Carré encaissé" (02/09/2026) - remonté
// par l'utilisateur : "j'ai eu des tours où j'ai reçu plus de 5 cartes à la
// suite d'un carré". Cause trouvée : si DEUX carrés se déclenchent coup sur
// coup pour le même joueur (le remplacement après un premier carré peut
// immédiatement en créer un second, voir la boucle while(true) de
// checkAndCashInSquares côté serveur, qui gère déjà ce cas correctement),
// GecoServer diffuse alors DEUX messages "square" rapprochés - et
// playSquareAnimation() (async, ~3,3 secondes) n'était protégée contre
// aucun appel concurrent : la seconde animation écrasait le DOM de la
// première EN PLEIN MILIEU (board.innerHTML = ...), pouvant faire
// apparaître un mélange des deux jeux de cartes à l'écran. Corrigé : chaque
// carré reçu est mis en file, un seul à la fois joue réellement, les
// suivants attendent que le précédent soit terminé.
//
// PLAFOND ajouté le 06/09/2026 - remonté par l'utilisateur : "les carrés
// s'enchaînent même pendant l'entre-deux-tours. L'animation ne s'arrête
// plus." Cause : avec un jeu de cartes trop restreint (avant le retour à la
// règle N+1 modèles), un grand nombre de carrés a pu s'enchaîner en peu de
// temps côté serveur - chacun individuellement borné (voir le filet de
// sécurité de checkAndCashInSquares), mais TOUS diffusés et empilés ici SANS
// LIMITE. Résultat : la file continue de se vider séquentiellement
// (~3,3 secondes par carré) bien après la fin du tour, INDÉPENDAMMENT de
// l'écran affiché - y compris pendant l'assistant de fin de tour, qui
// n'a pourtant aucun rapport avec cette file. Deux mesures :
// - MAX_QUEUED : au-delà de ce nombre de carrés en attente, les plus
//   anciens sont directement ESCAMOTÉS (jamais animés un par un) - seuls
//   les plus récents restent affichés, avec un total additionné dans le
//   sous-titre pour ne pas perdre l'information de ce qui a été gagné.
// - clearSquareAnimQueueOnNewTurn() : purge tout ce qui reste en attente
//   dès qu'un nouveau tour démarre (voir son appel dans connectPlayerWs) -
//   plus aucune "rediffusion" d'anciens carrés une fois le tour suivant
//   commencé, l'information n'a plus le même intérêt à ce stade.
const MAX_QUEUED_SQUARE_ANIMS = 3;
let mSquareAnimQueue = [];
let mSquareAnimRunning = false;
let mSquareAnimSkippedCount = 0;
function enqueueSquareAnimation(squareDto) {
	mSquareAnimQueue.push(squareDto);
	while (mSquareAnimQueue.length > MAX_QUEUED_SQUARE_ANIMS) {
		mSquareAnimQueue.shift(); // le plus ancien en attente est escamoté, jamais animé individuellement
		mSquareAnimSkippedCount++;
	}
	if (!mSquareAnimRunning) drainSquareAnimQueue();
}
async function drainSquareAnimQueue() {
	mSquareAnimRunning = true;
	while (mSquareAnimQueue.length > 0) {
		const next = mSquareAnimQueue.shift();
		// Capture puis remet à zéro AVANT de lancer l'animation - le nombre
		// escamoté depuis la dernière animation jouée est transmis pour être
		// mentionné dans le sous-titre (voir playSquareAnimation), plutôt que
		// de perdre silencieusement cette information.
		const skippedSinceLast = mSquareAnimSkippedCount;
		mSquareAnimSkippedCount = 0;
		await playSquareAnimation(next, skippedSinceLast); // toujours attendu en entier avant le suivant - jamais deux animations en même temps
	}
	mSquareAnimRunning = false;
}
// Purge tout ce qui reste en file (jamais l'animation DÉJÀ en cours, qui
// continue naturellement jusqu'à sa fin) - appelée à chaque nouveau tour.
function clearSquareAnimQueueOnNewTurn() {
	mSquareAnimQueue = [];
	mSquareAnimSkippedCount = 0;
}

// ============================================================
// Animation "Carré encaissé" (31/08/2026) - remonté par l'utilisateur : les
// carrés se produisaient en silence, sans aucun retour visuel, d'où sa
// confusion initiale ("je n'ai pas fait de carré, pourtant..."). Inspirée
// d'un code de référence fourni (Web Animations API : squash, convergence,
// implosion, flash+étoiles, apparition des 5 nouvelles cartes) - adaptée
// pour utiliser les VRAIES cartes du joueur (résolues depuis le catalogue,
// jamais des cartes génériques) et le fond indigo/violet déjà établi
// ailleurs (voir .square-anim-overlay dans player.css). Dure environ 3,3
// secondes puis referme automatiquement vers "Mes cartes". Appelée
// UNIQUEMENT depuis drainSquareAnimQueue() ci-dessus - jamais directement,
// pour garantir qu'une seule animation ne joue à la fois (voir le
// commentaire au-dessus).
async function playSquareAnimation(squareDto, pSkippedCount) {
	if (!state.cardsCatalog) state.cardsCatalog = await fetch("/api/catalogs/cartes").then((r) => r.json());
	if (!state.visualsCatalog) state.visualsCatalog = await fetch("/api/catalogs/visuels").then((r) => r.json());

	function resolveCard(cardTypeId, fallbackLevel) {
		const entry = state.cardsCatalog.find((c) => c.id === cardTypeId) || { id: cardTypeId, niveau: fallbackLevel, nom: {} };
		const visual = state.visualsCatalog.find((v) => v.id === entry.visualId) || null;
		const label = visual ? (catalogTextValue(visual.etiquette) || catalogTextValue(entry.nom)) : (catalogTextValue(entry.nom) || cardTypeId);
		return { entry, visual, label };
	}

	const cashed = resolveCard(squareDto.cashedCardTypeId, squareDto.cashedLevel);
	const promoted = resolveCard(squareDto.promotedCardTypeId, squareDto.promotedLevel);

	const overlay = el("squareAnimOverlay");
	const board = el("squareAnimBoard");
	// Révolution économique (22/09/2026, "rotation des valeurs") : un titre
	// distinct pour le carré qui déclenche RÉELLEMENT une révolution (jamais
	// pour un simple carré tresforte qui se replie faute d'autre modèle
	// disponible, voir triggeredRevolution côté serveur) - le joueur sait
	// immédiatement que ce carré-ci a un effet plus large que d'habitude.
	el("squareAnimTitle").textContent = squareDto.triggeredRevolution
		? t("playerView.square_anim_title_revolution")
		: t("playerView.square_anim_title");
	el("squareAnimSubtitle").textContent = "";
	board.classList.remove("shake-impact");
	board.innerHTML = "";
	for (let i = 0; i < 4; i++) {
		const cardEl = document.createElement("div");
		cardEl.className = "square-anim-card";
		cardEl.innerHTML = buildGameCardHtml(cashed.entry, cashed.visual, "geco-card-sm");
		board.appendChild(cardEl);
	}

	overlay.classList.add("active");
	await new Promise((r) => setTimeout(r, 350)); // laisse le temps aux cartes de se peindre avant de mesurer leur position

	const cardEls = [...board.children];
	const targetX = window.innerWidth / 2;
	const targetY = window.innerHeight / 2;

	// PHASE A : anticipation (squash), légèrement décalée d'une carte à l'autre.
	await Promise.all(cardEls.map((cardEl, i) => cardEl.animate([
		{ transform: "translate(0,0) scale(1,1)" },
		{ transform: "translate(0,8px) scale(1.12,0.88)", offset: 0.5 },
		{ transform: "translate(0,-10px) scale(0.9,1.1)" },
	], { duration: 160, delay: i * 25, easing: "ease-out", fill: "forwards" }).finished));

	// PHASE B : convergence vers le centre de l'écran.
	await Promise.all(cardEls.map((cardEl, i) => {
		const rect = cardEl.getBoundingClientRect();
		const dx = targetX - (rect.left + (rect.width / 2));
		const dy = targetY - (rect.top + (rect.height / 2));
		const rotZ = (i - 1.5) * 15;
		return cardEl.animate([
			{ transform: "translate(0,-10px) scale(0.9,1.1) rotate(0deg)" },
			{ transform: `translate(${dx}px, ${dy}px) rotate(${rotZ}deg) scale(0.85)` },
		], { duration: 420, easing: "cubic-bezier(0.25,1,0.5,1)", fill: "forwards" }).finished;
	}));

	// PHASE C : implosion (fusion des 4 cartes en un point).
	await Promise.all(cardEls.map((cardEl) => cardEl.animate([
		{ opacity: 1 },
		{ transform: "scale(0.1)", opacity: 0 },
	], { duration: 140, easing: "cubic-bezier(0.6,-0.28,0.735,0.045)", composite: "add", fill: "forwards" }).finished));
	board.innerHTML = "";

	// PHASE D : impact - secousse de l'écran, flash lumineux, étoiles projetées
	// façon dessin animé (voir la demande explicite de l'utilisateur : "des
	// animations et des étoiles").
	board.classList.add("shake-impact");
	const flash = document.createElement("div");
	flash.className = "square-anim-flash";
	flash.style.left = `${targetX}px`;
	flash.style.top = `${targetY}px`;
	document.body.appendChild(flash);
	flash.animate([
		{ transform: "scale(0.2)", opacity: 0 },
		{ transform: "scale(1.4)", opacity: 1, offset: 0.3 },
		{ transform: "scale(2.2)", opacity: 0 },
	], { duration: 400, easing: "ease-out" }).finished.then(() => flash.remove());

	const stars = ["⭐", "✨", "🌟"];
	for (let i = 0; i < 16; i++) {
		const p = document.createElement("div");
		p.className = "square-anim-particle";
		p.textContent = stars[i % stars.length];
		p.style.left = `${targetX}px`;
		p.style.top = `${targetY}px`;
		document.body.appendChild(p);
		const angle = (i / 16) * Math.PI * 2;
		const dist = 90 + (Math.random() * 70);
		const px = Math.cos(angle) * dist;
		const py = Math.sin(angle) * dist;
		p.animate([
			{ transform: "translate(-50%,-50%) scale(1.1)", opacity: 1 },
			{ transform: `translate(calc(-50% + ${px}px), calc(-50% + ${py}px)) scale(0)`, opacity: 0 },
		], { duration: 500 + (Math.random() * 200), easing: "cubic-bezier(0.1,0.9,0.2,1)" }).finished.then(() => p.remove());
	}
	await new Promise((r) => setTimeout(r, 200));

	// PHASE E : apparition des 5 nouvelles cartes - les VRAIES cartes reçues
	// (4 de remplacement au même niveau + 1 promue, mise en valeur par un
	// liseré doré, voir .square-anim-card.promoted).
	// pSkippedCount > 0 (voir MAX_QUEUED_SQUARE_ANIMS/drainSquareAnimQueue) :
	// d'autres carrés ont été encaissés entre-temps sans animation dédiée
	// (file d'attente trop chargée) - mentionné ici plutôt que perdu
	// silencieusement, pour que le joueur sache qu'il a gagné plus que ce
	// qu'il voit à l'écran.
	const detailText = t("playerView.square_anim_detail", { cashed: cashed.label, promoted: promoted.label });
	el("squareAnimSubtitle").textContent = pSkippedCount > 0
		? detailText + " " + t("playerView.square_anim_skipped", { count: pSkippedCount })
		: detailText;
	const revealItems = [...squareDto.replenishedCardIds.map((id) => ({ id, promotedFlag: false })),
		{ id: squareDto.promotedCardTypeId, promotedFlag: true }];
	const newEls = revealItems.map((item) => {
		const resolved = resolveCard(item.id, item.promotedFlag ? squareDto.promotedLevel : squareDto.cashedLevel);
		const cardEl = document.createElement("div");
		cardEl.className = `square-anim-card${item.promotedFlag ? " promoted" : ""}`;
		cardEl.style.opacity = "0";
		cardEl.innerHTML = buildGameCardHtml(resolved.entry, resolved.visual, "geco-card-sm");
		board.appendChild(cardEl);
		return cardEl;
	});
	await Promise.all(newEls.map((cardEl, i) => cardEl.animate([
		{ transform: "translateY(40px) scale(0.3)", opacity: 0 },
		{ transform: "translateY(-10px) scale(1.15)", opacity: 1, offset: 0.75 },
		{ transform: "translateY(0) scale(1)", opacity: 1 },
	], { duration: 420, delay: i * 60, easing: "cubic-bezier(0.175,0.885,0.32,1.275)", fill: "forwards" }).finished));

	// Laisse le résultat visible un instant, puis referme automatiquement vers
	// "Mes cartes" - "l'écran d'animation disparaisse et laisse apparaître
	// l'écran cartes du joueur".
	await new Promise((r) => setTimeout(r, 1300));
	overlay.classList.remove("active");
	renderMyCards();
	setActiveNav("navBtnCards");
}

// ============================================================
// Animation "Mort du joueur" (26/09/2026) - remonté par l'utilisateur : "à
// l'entre deux tours, lorsque les morts sont annoncés... les smartphones
// des joueurs déclenchent une animation". Inspirée d'un code de référence
// fourni par l'utilisateur (dézoom rapide depuis la lune, puis "coup de
// tampon" du titre) - le texte est rendu par js/vendor/cartoon-text.js
// (fourni par l'utilisateur) plutôt que le gradient orange/cyan codé en
// dur du mockup d'origine : seul cet outil vectoriel s'adapte
// automatiquement à une traduction plus longue ou plus courte que le
// français (voir le commentaire en tête de ce fichier vendorisé - décision
// confirmée avec l'utilisateur avant implémentation, "il faut que les
// textes soient faits en code"). Déclenchée par l'événement WebSocket
// "death" (voir connectPlayerWs plus haut), diffusé UNIQUEMENT pour un
// joueur réellement suivi par smartphone, agnostique du système monétaire
// (dette/libre/troc) - voir GecoServer.
//
// File d'attente calquée sur enqueueSquareAnimation/drainSquareAnimQueue
// ci-dessus, par pure prudence défensive : un joueur ne meurt normalement
// QU'UNE SEULE FOIS par confirmation de l'animateur (contrairement aux
// carrés, qui peuvent s'enchaîner plusieurs fois de suite pour le même
// joueur), donc cette file ne devrait en pratique jamais contenir plus
// d'un élément à la fois - mais garantit malgré tout qu'un double message
// WebSocket (rejeu réseau, reconnexion...) ne fasse jamais se chevaucher
// deux animations en plein milieu.
let mDeathAnimQueue = [];
let mDeathAnimRunning = false;
function enqueueDeathAnimation(deathDto) {
	mDeathAnimQueue.push(deathDto);
	if (!mDeathAnimRunning) drainDeathAnimQueue();
}
async function drainDeathAnimQueue() {
	mDeathAnimRunning = true;
	// Correctif (26/09/2026, seconde relecture indépendante d'une première
	// relecture) : le try/finally À L'INTÉRIEUR de playDeathAnimation ne
	// couvre que sa propre séquence d'animation - une exception levée AVANT
	// (ex. el("deathAnimZoom") introuvable, page HTML désynchronisée du JS)
	// laisserait mDeathAnimRunning bloqué à `true` pour toujours, sans jamais
	// rejouer aucune animation suivante. Ce filet de sécurité englobant
	// garantit que le drapeau est TOUJOURS remis à `false`, quoi qu'il arrive.
	try {
		while (mDeathAnimQueue.length > 0) {
			mDeathAnimQueue.shift();
			// Correctif (27/09/2026, relecture indépendante de "Renaissance !",
			// confirmée par une seconde relecture qui en a mesuré la vraie
			// conséquence) : un try/catch PAR ÉLÉMENT, ici - le try/finally
			// englobant ci-dessus protège bien mDeathAnimRunning, mais une
			// exception levée AVANT le try INTERNE de playDeathAnimation/
			// playRebirthAnimation (ex. un élément DOM introuvable) faisait
			// jusqu'ici ABANDONNER toute la boucle `while` : l'élément déjà
			// retiré de la file (`shift()` a déjà eu lieu) ne rejouait jamais,
			// et un tout NOUVEL événement de mort ultérieur rejouait alors
			// deux cycles complets à la suite (l'ancien resté implicitement
			// "en cours" plus le nouveau) - mesuré en conditions réelles :
			// ~20 secondes d'animations au lieu de 10, sans aucun message
			// d'erreur pour le joueur. Même politique d'erreur que partout
			// ailleurs dans ce fichier : journaliser via pushDebugLog et
			// continuer, jamais un échec silencieux ni un abandon de la
			// boucle.
			try
			{
				await playDeathAnimation(); // toujours attendu en entier avant la suivante - jamais deux animations en même temps
			}
			catch (err)
			{
				pushDebugLog("ERREUR", "Animation \"Mort du joueur\" (file d'attente) interrompue :", err);
			}
			// Enchaînement "Renaissance !" (27/09/2026, demande utilisateur,
			// confirmée explicitement - question posée, réponse actée) : ce
			// moteur de jeu ne connaît qu'un seul événement DEATH qui
			// déclenche à la fois la mort ET la renaissance immédiate -
			// jamais un second événement WebSocket distinct pour "la
			// renaissance". Les deux animations restent deux fonctions/blocs
			// CSS totalement indépendants et réutilisables séparément (voir
			// playRebirthAnimation) - seul CET enchaînement précis, ici, les
			// relie l'une à l'autre pour raconter les deux temps forts du
			// même instant de jeu.
			try
			{
				await playRebirthAnimation();
			}
			catch (err)
			{
				pushDebugLog("ERREUR", "Animation \"Renaissance !\" (file d'attente) interrompue :", err);
			}
		}
	} finally {
		mDeathAnimRunning = false;
	}
}

// Synthèse sonore d'un impact sourd (Web Audio API, aucun fichier externe -
// même principe que playSynthesizedPlayerWhistle ci-dessus, réutilise le
// même AudioContext partagé plutôt que d'en recréer un à chaque appel comme
// le faisait le code de référence fourni). Jamais bloquant : un navigateur
// qui refuse l'audio (politique d'autoplay, contexte trop restrictif)
// abandonne silencieusement, l'animation visuelle continue normalement.
function playDeathImpactSound() {
	try {
		if (!mPlayerAudioCtx) mPlayerAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
		if (mPlayerAudioCtx.state === "suspended") mPlayerAudioCtx.resume();
		const ctx = mPlayerAudioCtx;
		const now = ctx.currentTime;

		const osc = ctx.createOscillator();
		const gain = ctx.createGain();
		osc.type = "triangle";
		osc.frequency.setValueAtTime(160, now);
		osc.frequency.exponentialRampToValueAtTime(25, now + 0.25);
		gain.gain.setValueAtTime(0.9, now);
		gain.gain.exponentialRampToValueAtTime(0.001, now + 0.28);
		osc.connect(gain);
		gain.connect(ctx.destination);
		osc.start(now);
		osc.stop(now + 0.28);

		const bufferSize = Math.floor(ctx.sampleRate * 0.15);
		const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
		const data = buffer.getChannelData(0);
		for (let i = 0; i < bufferSize; i++) data[i] = (Math.random() * 2) - 1;
		const noise = ctx.createBufferSource();
		noise.buffer = buffer;
		const filter = ctx.createBiquadFilter();
		filter.type = "lowpass";
		filter.frequency.setValueAtTime(400, now);
		filter.frequency.exponentialRampToValueAtTime(50, now + 0.15);
		const noiseGain = ctx.createGain();
		noiseGain.gain.setValueAtTime(0.5, now);
		noiseGain.gain.exponentialRampToValueAtTime(0.01, now + 0.18);
		noise.connect(filter);
		filter.connect(noiseGain);
		noiseGain.connect(ctx.destination);
		noise.start(now);
	} catch (err) {
		// Ni synthèse audio possible (navigateur très restrictif) : on
		// abandonne silencieusement, jamais bloquant pour l'animation visuelle.
	}
}

// Durées calquées EXACTEMENT sur les transitions/animations CSS
// correspondantes (.death-anim-zoom / .death-anim-title-host, voir
// player.css) - à garder synchronisées si l'une des deux change.
const DEATH_ANIM_ZOOM_START_DELAY_MS = 50; // laisse peindre l'état "zoomed-in" avant de déclencher la transition
// Timing revu une seconde fois (27/09/2026, second retour utilisateur avec
// fichier de référence "test1.html" à l'appui) : "il faudrait lui donner un
// autre timing afin de la rendre plus dynamique quand on joue" - remplace le
// réglage précédent (2s/3s, 5s au total) par un rythme plus proche de celui
// du fichier fourni. Ce fichier chaîne mort→renaissance en ~1,45s (délai
// 50ms + dézoom 550ms + un impact quasi instantané suivi d'un maintien
// d'environ 900ms) : le dézoom (720ms→550ms) et le délai initial (30ms→50ms)
// reprennent directement ses valeurs (voir aussi la transition CSS
// correspondante dans player.css, .death-anim-zoom, à garder synchronisée).
// L'IMPACT_MS ci-dessous reste à 450ms (inchangé) : c'est la durée du
// tremblement d'écran .death-anim-zoom.shake-impact, déjà définie dans
// player.css - le changer aurait désynchronisé JS et CSS pour un gain
// minime, alors que le fichier de référence utilise une valeur très proche
// (400ms) pour son propre tremblement.
const DEATH_ANIM_DEZOOM_MS = 550;
const DEATH_ANIM_IMPACT_MS = 450;
// Confirmé par question explicite posée à l'utilisateur (27/09/2026) entre
// "aussi rapide que la référence" (~1,5s + ~1,5s ≈ 3s au total) et "garder
// 2s+3s=5s" - réponse : "Aussi rapide que la référence". Écran de mort donc
// ramené à 1,5s (reste ~0,45s d'affichage fixe après l'impact - le tampon
// "Mort du joueur" reste lisible, juste plus bref).
const DEATH_ANIM_TOTAL_DISPLAY_MS = 1500;

// Correctif (27/09/2026, relecture indépendante du timing 2s+3s) : le
// try/finally de playDeathAnimation/playRebirthAnimation ne protège que d'une
// EXCEPTION, pas d'une attente qui ne se termine jamais - or
// CartoonText.render attend document.fonts.load(), et la police "Sora" en
// graisse forte n'est utilisée nulle part ailleurs dans cet écran : elle
// n'était téléchargée qu'au moment même de la PREMIÈRE mort, au beau milieu
// de l'animation. Vérifié dans le navigateur en retenant cette seule requête
// : écran de mort resté plein écran (pointer-events: auto, téléphone
// bloqué) et file d'attente figée tant que la requête ne répondait pas (12 s
// observées, sans limite en soi). Double correctif : (1) police préchargée dès
// la fin du chargement de la page (ci-dessous - la même requête "900" que
// cartoon-text.js, résolue vers la graisse 800 vendorisée), pour ne plus
// dépendre du réseau pendant l'animation ; (2) attente du rendu du titre
// plafonnée (renderAnimTitle) - au-delà, la séquence continue sans attendre
// (journalisé via pushDebugLog, jamais silencieux), l'écran se referme donc
// toujours. Le plafond reste très au-dessus d'un rendu normal (mesuré : 7 à
// ~190 ms, y compris processeur ralenti x6 et police à télécharger).
const ANIM_TITLE_FONT_FAMILY = "'Sora','Luckiest Guy','Rubik',sans-serif";
const ANIM_TITLE_RENDER_TIMEOUT_MS = 1500;
// Lancé APRÈS l'événement "load" : un chargement de police en cours retarde
// sinon cet événement (vérifié : police bloquée = page jamais "chargée").
window.addEventListener("load", () => {
	if (document.fonts && document.fonts.load) {
		document.fonts.load("900 1em Sora").catch((err) =>
			pushDebugLog("ERREUR", "Préchargement de la police des animations mort/renaissance impossible :", err));
	}
});
async function renderAnimTitle(host, text) {
	let timer = null;
	const timeout = new Promise((resolve) => { timer = setTimeout(() => resolve("timeout"), ANIM_TITLE_RENDER_TIMEOUT_MS); });
	try {
		const outcome = await Promise.race([
			window.CartoonText.render(host, text, { maxLines: 2, fontFamily: ANIM_TITLE_FONT_FAMILY }),
			timeout,
		]);
		if (outcome === "timeout")
			pushDebugLog("ERREUR", `Rendu du titre d'animation trop lent (> ${ANIM_TITLE_RENDER_TIMEOUT_MS} ms), animation poursuivie sans l'attendre :`, text);
	} finally {
		clearTimeout(timer);
	}
}

// Appelée UNIQUEMENT depuis drainDeathAnimQueue() ci-dessus - jamais
// directement, pour garantir qu'une seule animation ne joue à la fois.
async function playDeathAnimation() {
	const overlay = el("deathAnimOverlay");
	const zoom = el("deathAnimZoom");
	const titleHost = el("deathAnimTitleHost");

	// Correctif (seconde relecture indépendante, 26/09/2026) : l'état de départ
	// "zoomed-in" doit s'appliquer INSTANTANÉMENT - sans ça, la transition CSS
	// de .death-anim-zoom (0,72 s) s'appliquait aussi à cette mise en place :
	// l'image partait de scale(1) vers 3,6 pendant les 30 ms d'attente, puis
	// repartait vers 1 - mesuré dans le navigateur, le zoom ne dépassait jamais
	// ~1,3 à 1,6, le "dézoom depuis la lune" était donc quasi invisible.
	zoom.style.transition = "none";
	zoom.classList.remove("zoomed-out", "shake-impact");
	zoom.classList.add("zoomed-in");
	void zoom.offsetWidth; // force le calcul du style "zoomed-in" avant de rétablir la transition
	zoom.style.transition = "";
	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "0";
	// Correctif (même relecture) : PAS de titleHost.innerHTML = "" ici -
	// CartoonText.render() vide lui-même le conteneur, de façon synchrone avec
	// le nouveau rendu. Le vider ICI, alors que le ResizeObserver posé par
	// cartoon-text.js lors de la mort PRÉCÉDENTE est toujours actif, lui
	// faisait reconstruire l'ancien titre en plein rappel d'observation -
	// erreur "ResizeObserver loop completed with undelivered notifications"
	// systématique dès la 2e mort d'un même joueur, remontée par pushDebugLog
	// (badge 🐞 visible sur son téléphone). L'ancien titre reste invisible
	// jusqu'au nouveau rendu (opacity 0 ci-dessus + classe stamp-in retirée).

	// Correctif (même relecture) : apparition INSTANTANÉE de l'overlay (même
	// technique que le zoom ci-dessus) - le fondu CSS de 0,25s (.death-anim-
	// overlay, pensé pour une fermeture douce) se déclenchait aussi à
	// l'ouverture et se superposait au dézoom : l'écran normal du joueur
	// restait visible en transparence pendant le gros plan sur la lune.
	// Fermeture (fin de fonction, "finally" ci-dessous) : conserve le fondu
	// normal, une disparition progressive reste voulue là.
	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";
	// Correctif (même relecture) : try/finally - sans lui, la moindre exception
	// (ex. CartoonText.render qui échoue) laissait l'écran plein écran ACTIF
	// indéfiniment (pointer-events: auto, téléphone du joueur bloqué jusqu'au
	// rechargement de la page) et mDeathAnimRunning à true pour toujours
	// (plus aucune animation de mort ensuite) - vérifié en simulant un échec
	// de rendu dans le navigateur. Le déroulé normal est strictement inchangé.
	try {
		await new Promise((r) => setTimeout(r, DEATH_ANIM_ZOOM_START_DELAY_MS));

		// ÉTAPE 1 : dézoom rapide depuis la lune jusqu'à la scène complète.
		zoom.classList.remove("zoomed-in");
		zoom.classList.add("zoomed-out");
		await new Promise((r) => setTimeout(r, DEATH_ANIM_DEZOOM_MS));

		// ÉTAPE 2 : le titre (multilingue, voir js/vendor/cartoon-text.js)
		// apparaît façon "coup de tampon", avec tremblement d'écran + son
		// d'impact synchronisés.
		titleHost.style.opacity = "";
		// fontFamily (même relecture, 26/09/2026) : aucune des polices cartoon
		// par défaut de cartoon-text.js (Luckiest Guy, Rubik...) n'est
		// vendorisée dans ce projet (pas de CDN, voir CLAUDE.md) - sans cette
		// option, le rendu retombait sur la police système générique. "Sora"
		// est déjà vendorisée (voir --font-display, player.css) : réutilisée
		// ici en priorité plutôt que d'ajouter un nouveau fichier de police
		// (voir ANIM_TITLE_FONT_FAMILY/renderAnimTitle ci-dessus).
		await renderAnimTitle(titleHost, t("playerView.death_anim_title"));
		titleHost.classList.add("stamp-in");
		zoom.classList.add("shake-impact");
		playDeathImpactSound();
		// Flash rouge synchronisé avec l'impact (27/09/2026, inspiré du fichier
		// de référence "test1.html" fourni par l'utilisateur - "plus
		// dynamique"). Jamais attendu (fire-and-forget, comme le tremblement
		// d'écran et le son juste au-dessus) : purement décoratif et bref, il
		// ne doit jamais retarder la suite de la séquence - voir
		// .anim-flash-overlay dans player.css, élément PARTAGÉ avec le flash
		// blanc de playRebirthAnimation (jamais actifs en même temps).
		const impactFlash = el("animFlashOverlay");
		if (impactFlash) {
			impactFlash.classList.remove("flash-white");
			impactFlash.style.transition = "none";
			impactFlash.classList.add("flash-red", "active");
			void impactFlash.offsetWidth;
			impactFlash.style.transition = "";
			// Correctif (27/09/2026, relecture indépendante du timing dynamique) :
			// ce minuteur détaché ne retire "active" QUE si l'élément partagé est
			// encore en mode rouge. Sans cette garde, si "Mort du joueur" était
			// interrompue par une exception juste après l'impact (moins de 120 ms),
			// playRebirthAnimation démarrait aussitôt et reprenait l'élément en
			// flash blanc - ce minuteur retirait alors "active" au flash BLANC
			// (mesuré par injection de panne : blanc tenu 114 ms au lieu de 150,
			// fondu démarré 37 ms avant le dézoom). Le flash blanc, une fois posé,
			// appartient entièrement à playRebirthAnimation (retrait normal ou
			// filet de sécurité de son "finally") - aucun risque de flash bloqué.
			setTimeout(() => {
				if (impactFlash.classList.contains("flash-red")) impactFlash.classList.remove("active");
			}, 120);
		}
		await new Promise((r) => setTimeout(r, DEATH_ANIM_IMPACT_MS));
		zoom.classList.remove("shake-impact");

		// ÉTAPE 3 : reste affichée jusqu'au total demandé (DEATH_ANIM_TOTAL_DISPLAY_MS), puis
		// referme automatiquement - refreshPlayer() recharge l'état du joueur
		// (nouvelle main de départ, solde remis à zéro...) pour que l'écran
		// retrouvé juste après reflète déjà sa renaissance.
		const elapsed = DEATH_ANIM_ZOOM_START_DELAY_MS + DEATH_ANIM_DEZOOM_MS + DEATH_ANIM_IMPACT_MS;
		await new Promise((r) => setTimeout(r, Math.max(0, DEATH_ANIM_TOTAL_DISPLAY_MS - elapsed)));
	} catch (err) {
		pushDebugLog("ERREUR", "Animation \"Mort du joueur\" interrompue :", err);
	} finally {
		zoom.classList.remove("shake-impact");
		overlay.classList.remove("active");
		refreshPlayer();
	}
}

// ============================================================
// Animation "Renaissance !" (27/09/2026) - remonté par l'utilisateur :
// "peux-tu faire de même avec la renaissance du joueur... le fond
// dézoome et le texte apparaît comme un coup de tampon, comme pour
// l'exemple précédent." Mécanique et code structurés à l'identique de
// playDeathAnimation ci-dessus (mêmes constantes de timing, même
// technique try/finally, même truc "transition: none" + reflow forcé
// pour un état de départ vraiment instantané) - toutes les leçons de la
// double relecture indépendante de "Mort du joueur" (dézoom qui restait
// invisible, ResizeObserver qui redessinait un ancien titre, écran bloqué
// en cas d'échec de rendu) sont appliquées ICI dès la première version,
// plutôt que de les redécouvrir une seconde fois.
//
// N'est JAMAIS appelée par sa propre file d'attente : contrairement à
// "Mort du joueur" (déclenchée directement par l'événement WebSocket
// "death"), "Renaissance !" est enchaînée par drainDeathAnimQueue()
// ci-dessus, juste après "Mort du joueur" - ce moteur de jeu ne connaît
// qu'un seul événement DEATH pour les deux temps forts (voir le
// commentaire détaillé dans player-view.html). Reste néanmoins une
// fonction totalement autonome et réutilisable en elle-même (aucune
// dépendance à l'état de "Mort du joueur"), au cas où un futur besoin
// voudrait la déclencher seule.
// REBIRTH_ANIM_ZOOM_START_DELAY_MS sert désormais AUSSI de délai de tenue du
// flash blanc masquant l'enchaînement (voir playRebirthAnimation, et
// .anim-flash-overlay dans player.css) - 150ms au lieu de 30ms (second
// retour utilisateur, 27/09/2026, fichier de référence "test1.html" :
// "setTimeout 150ms" avant de révéler la scène suivante). Le dézoom
// (720ms→550ms) reprend la même valeur que "Mort du joueur" ci-dessus, même
// raisonnement (voir son commentaire détaillé) - à garder synchronisée avec
// la transition CSS .rebirth-anim-zoom dans player.css. IMPACT_MS inchangé
// (450ms, synchronisé avec le tremblement d'écran CSS partagé
// deathAnimShake).
const REBIRTH_ANIM_ZOOM_START_DELAY_MS = 150;
const REBIRTH_ANIM_DEZOOM_MS = 550;
const REBIRTH_ANIM_IMPACT_MS = 450;
// Confirmé par la même question posée à l'utilisateur que côté "Mort du
// joueur" ci-dessus (27/09/2026) : "Aussi rapide que la référence" (~1,5s +
// ~1,5s ≈ 3s au total). Écran de renaissance donc ramené à 1,5s (reste
// ~0,35s d'affichage fixe après l'impact).
const REBIRTH_ANIM_TOTAL_DISPLAY_MS = 1500;

async function playRebirthAnimation() {
	const overlay = el("rebirthAnimOverlay");
	const zoom = el("rebirthAnimZoom");
	const avatarCircle = el("rebirthAnimAvatarCircle");
	const titleHost = el("rebirthAnimTitleHost");

	// Avatar RÉEL du joueur (voir buildProfileAvatarHtml, déjà utilisé par
	// l'écran Profil) - jamais un avatar générique. Rendu une seule fois ici
	// : contrairement au titre (cartoon-text.js gère son propre
	// ResizeObserver), un simple <img>/<span> n'a besoin d'aucune précaution
	// particulière pour être remplacé à chaque renaissance.
	avatarCircle.innerHTML = buildProfileAvatarHtml(state.player ? state.player.avatarConfigJson : null);
	avatarCircle.classList.remove("stamp-in");
	avatarCircle.style.opacity = "0";
	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "0";

	// État de départ "zoomed-in" ET ouverture de l'overlay INSTANTANÉS (voir
	// le correctif équivalent de "Mort du joueur", 26/09/2026) : sans le
	// "transition: none" + reflow forcé ci-dessous, la transition CSS
	// prévue pour le DÉZOOM s'appliquerait aussi à cette simple mise en
	// place, rendant le dézoom quasi invisible.
	zoom.style.transition = "none";
	zoom.classList.remove("zoomed-out", "shake-impact");
	zoom.classList.add("zoomed-in");
	void zoom.offsetWidth; // force le calcul du style avant de rétablir la transition
	zoom.style.transition = "";

	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";

	// Flash blanc masquant l'enchaînement mort→renaissance (27/09/2026,
	// inspiré du fichier de référence "test1.html" fourni par l'utilisateur -
	// "plus dynamique"). Affiché INSTANTANÉMENT (même truc "transition: none"
	// + reflow forcé que ci-dessus) pendant que la scène "renaissance" -
	// déjà zoomée sur le cercle doré à cet instant précis - se met en place
	// juste en dessous, invisible sous le flash : masque la coupure plutôt
	// que de laisser apparaître d'un coup le cercle doré grossi. Réutilise
	// le MÊME élément que le flash rouge de "Mort du joueur" (jamais actifs
	// simultanément, voir .anim-flash-overlay dans player.css) - retiré plus
	// bas, synchronisé avec le début du dézoom (voir ÉTAPE 1).
	const flashOverlay = el("animFlashOverlay");
	if (flashOverlay) {
		flashOverlay.classList.remove("flash-red");
		flashOverlay.style.transition = "none";
		flashOverlay.classList.add("flash-white", "active");
		void flashOverlay.offsetWidth;
		flashOverlay.style.transition = "";
	}

	// try/finally englobant TOUTE la séquence (voir le même correctif sur
	// playDeathAnimation) : garantit que l'overlay se referme et que
	// refreshPlayer() est appelé quoi qu'il arrive, même si CartoonText.render
	// échoue.
	try {
		await new Promise((r) => setTimeout(r, REBIRTH_ANIM_ZOOM_START_DELAY_MS));

		// Le flash blanc posé plus haut s'estompe PENDANT que le dézoom
		// démarre (voir son commentaire détaillé ci-dessus) - la scène se
		// révèle donc progressivement plutôt que d'apparaître d'un coup une
		// fois le flash retiré.
		if (flashOverlay) flashOverlay.classList.remove("active");

		// ÉTAPE 1 : dézoom rapide depuis le cercle doré jusqu'à la scène
		// ensoleillée complète.
		zoom.classList.remove("zoomed-in");
		zoom.classList.add("zoomed-out");
		await new Promise((r) => setTimeout(r, REBIRTH_ANIM_DEZOOM_MS));

		// ÉTAPE 2 : l'avatar apparaît dans le cercle ET le titre (multilingue,
		// voir js/vendor/cartoon-text.js) façon "coup de tampon", avec
		// tremblement d'écran + son synchronisés - les deux ensemble, comme
		// sur le modèle fourni.
		titleHost.style.opacity = "";
		await renderAnimTitle(titleHost, t("playerView.rebirth_anim_title"));
		titleHost.classList.add("stamp-in");
		// Correctif (27/09/2026, relecture indépendante) : retirer l'opacité
		// "0" posée EN LIGNE plus haut, exactement comme pour titleHost juste
		// au-dessus - sinon, en mode "animations réduites" (prefers-reduced-
		// motion, voir player.css : animation: none; opacity: 1), ce style en
		// ligne l'emportait sur la règle CSS et l'avatar restait INVISIBLE
		// (cercle doré vide), vérifié dans le navigateur. Sans effet en mode
		// normal : l'animation rebirthAnimAvatarPop (fill "forwards") prend
		// déjà le pas sur tout style en ligne.
		avatarCircle.style.opacity = "";
		avatarCircle.classList.add("stamp-in");
		zoom.classList.add("shake-impact");
		playRebirthChimeSound();
		await new Promise((r) => setTimeout(r, REBIRTH_ANIM_IMPACT_MS));
		zoom.classList.remove("shake-impact");

		// ÉTAPE 3 : reste affichée jusqu'au total demandé
		// (REBIRTH_ANIM_TOTAL_DISPLAY_MS), puis referme automatiquement.
		const elapsed = REBIRTH_ANIM_ZOOM_START_DELAY_MS + REBIRTH_ANIM_DEZOOM_MS + REBIRTH_ANIM_IMPACT_MS;
		await new Promise((r) => setTimeout(r, Math.max(0, REBIRTH_ANIM_TOTAL_DISPLAY_MS - elapsed)));
	} catch (err) {
		pushDebugLog("ERREUR", "Animation \"Renaissance !\" interrompue :", err);
	} finally {
		zoom.classList.remove("shake-impact");
		overlay.classList.remove("active");
		// Filet de sécurité : si une exception a interrompu la séquence AVANT
		// le retrait normal du flash (ÉTAPE 1 ci-dessus), il ne doit jamais
		// rester bloqué visible à l'écran - sans effet si déjà retiré.
		if (flashOverlay) flashOverlay.classList.remove("active");
		refreshPlayer();
	}
}

// Synthèse sonore d'un carillon ascendant (Web Audio API, aucun fichier
// externe - même principe que playDeathImpactSound, réutilise le même
// AudioContext partagé), pensé pour un moment positif (contrairement au
// son sourd de "Mort du joueur") : trois notes brèves montantes.
function playRebirthChimeSound() {
	try {
		if (!mPlayerAudioCtx) mPlayerAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
		if (mPlayerAudioCtx.state === "suspended") mPlayerAudioCtx.resume();
		const ctx = mPlayerAudioCtx;
		const now = ctx.currentTime;
		const notes = [523.25, 659.25, 783.99]; // Do5, Mi5, Sol5 - accord majeur ascendant
		notes.forEach((freq, i) => {
			const start = now + (i * 0.09);
			const osc = ctx.createOscillator();
			const gain = ctx.createGain();
			osc.type = "sine";
			osc.frequency.setValueAtTime(freq, start);
			gain.gain.setValueAtTime(0.0001, start);
			gain.gain.exponentialRampToValueAtTime(0.3, start + 0.02);
			gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.4);
			osc.connect(gain);
			gain.connect(ctx.destination);
			osc.start(start);
			osc.stop(start + 0.4);
		});
	} catch (err) {
		// Ni synthèse audio possible (navigateur très restrictif) : on
		// abandonne silencieusement, jamais bloquant pour l'animation visuelle.
	}
}

// ============================================================
// Animations "Commencez !" (début de tour) / "Stop !" (fin de tour)
// (10/10/2026) - remonté par l'utilisateur, deux images jointes au message
// ("Debut.jpeg"/"Stop.jpeg", même scène de marché en forêt, jour/nuit) :
// "comme pour le texte du joueur qui est mort (et le même temps à peu
// près). Et avec une animation sur le texte et l'image, comme une
// explosion !". Mécanique calquée sur playDeathAnimation/playRebirthAnimation
// ci-dessus (même technique "transition: none" + reflow forcé pour un état
// de départ instantané, même try/finally englobant, même titre rendu par
// js/vendor/cartoon-text.js - jamais un texte codé en dur, pour rester
// multilingue) - voir le commentaire détaillé sur .turnstart-anim-overlay/
// .turnend-anim-overlay dans player.css pour le choix de NE PAS réutiliser
// #animFlashOverlay ici (risque de collision avec une animation de mort/
// renaissance/carré encore en cours pour tel ou tel joueur au même instant,
// ces files d'attente n'étant jamais synchronisées entre elles).
//
// Deux files d'attente INDÉPENDANTES l'une de l'autre (contrairement à
// mort+renaissance, jamais chaînées entre elles ici) - par la même prudence
// défensive que mDeathAnimQueue : un double message WebSocket (rejeu
// réseau, reconnexion...) ne doit jamais faire se chevaucher deux
// animations "Commencez !" (ou deux "Stop !") en plein milieu.
let mTurnStartAnimQueue = [];
let mTurnStartAnimRunning = false;
function enqueueTurnStartAnimation() {
	mTurnStartAnimQueue.push(true);
	if (!mTurnStartAnimRunning) drainTurnStartAnimQueue();
}
async function drainTurnStartAnimQueue() {
	mTurnStartAnimRunning = true;
	try {
		while (mTurnStartAnimQueue.length > 0) {
			mTurnStartAnimQueue.shift();
			try {
				await playTurnStartAnimation();
			} catch (err) {
				pushDebugLog("ERREUR", "Animation \"Commencez !\" (file d'attente) interrompue :", err);
			}
		}
	} finally {
		mTurnStartAnimRunning = false;
	}
}

let mTurnEndAnimQueue = [];
let mTurnEndAnimRunning = false;
function enqueueTurnEndAnimation() {
	mTurnEndAnimQueue.push(true);
	if (!mTurnEndAnimRunning) drainTurnEndAnimQueue();
}
async function drainTurnEndAnimQueue() {
	mTurnEndAnimRunning = true;
	try {
		while (mTurnEndAnimQueue.length > 0) {
			mTurnEndAnimQueue.shift();
			try {
				await playTurnEndAnimation();
			} catch (err) {
				pushDebugLog("ERREUR", "Animation \"Stop !\" (file d'attente) interrompue :", err);
			}
		}
	} finally {
		mTurnEndAnimRunning = false;
	}
}

// Durées calquées sur la transition CSS .turnstart-anim-zoom/.turnend-anim-
// zoom (0,55s, voir player.css) et sur le tremblement/tampon partagés avec
// mort/renaissance (deathAnimShake 450ms, deathAnimStampSlam 380ms) - même
// durée totale d'affichage que ces deux animations (1,5s), demande
// explicite de l'utilisateur ("le même temps à peu près").
const TURNSTART_ANIM_ZOOM_START_DELAY_MS = 50;
const TURNSTART_ANIM_EXPLODE_MS = 550;
const TURNSTART_ANIM_IMPACT_MS = 450;
const TURNSTART_ANIM_TOTAL_DISPLAY_MS = 1500;
const TURNEND_ANIM_ZOOM_START_DELAY_MS = 50;
const TURNEND_ANIM_EXPLODE_MS = 550;
const TURNEND_ANIM_IMPACT_MS = 450;
const TURNEND_ANIM_TOTAL_DISPLAY_MS = 1500;

// Appelée UNIQUEMENT depuis drainTurnStartAnimQueue() ci-dessus. Bascule
// vers l'onglet Cartes à la FIN de l'animation (demande explicite de
// l'utilisateur) - toujours gardée contre un achat/une vente entre-temps
// ouvert sur ce téléphone, même garde que l'ancien code remplacé (voir
// connectPlayerWs) : en pratique, l'entrée dans la file est déjà filtrée par
// cette même garde, mais une modal/un scan pourrait en théorie s'ouvrir
// PENDANT l'animation si une exception interrompait prématurément le verrou
// visuel - prudence inchangée.
async function playTurnStartAnimation() {
	const overlay = el("turnstartAnimOverlay");
	const zoom = el("turnstartAnimZoom");
	const titleHost = el("turnstartAnimTitleHost");

	zoom.style.transition = "none";
	zoom.classList.remove("exploded", "shake-impact");
	zoom.classList.add("collapsed");
	void zoom.offsetWidth; // force le calcul du style "collapsed" avant de rétablir la transition
	zoom.style.transition = "";
	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "0";

	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";

	try {
		await new Promise((r) => setTimeout(r, TURNSTART_ANIM_ZOOM_START_DELAY_MS));

		// ÉTAPE 1 : "explosion" - l'image jaillit d'un point lumineux minuscule
		// jusqu'à occuper tout l'écran (voir la transition CSS avec dépassement,
		// .turnstart-anim-zoom, pour l'effet de jaillissement).
		zoom.classList.remove("collapsed");
		zoom.classList.add("exploded");
		await new Promise((r) => setTimeout(r, TURNSTART_ANIM_EXPLODE_MS));

		// ÉTAPE 2 : le titre (multilingue) apparaît façon "coup de tampon",
		// avec tremblement d'écran synchronisé - même technique que mort/
		// renaissance.
		titleHost.style.opacity = "";
		await renderAnimTitle(titleHost, t("playerView.turnstart_anim_title"));
		titleHost.classList.add("stamp-in");
		zoom.classList.add("shake-impact");
		await new Promise((r) => setTimeout(r, TURNSTART_ANIM_IMPACT_MS));
		zoom.classList.remove("shake-impact");

		// ÉTAPE 3 : reste affichée jusqu'au total demandé, puis referme automatiquement.
		const elapsed = TURNSTART_ANIM_ZOOM_START_DELAY_MS + TURNSTART_ANIM_EXPLODE_MS + TURNSTART_ANIM_IMPACT_MS;
		await new Promise((r) => setTimeout(r, Math.max(0, TURNSTART_ANIM_TOTAL_DISPLAY_MS - elapsed)));
	} catch (err) {
		pushDebugLog("ERREUR", "Animation \"Commencez !\" interrompue :", err);
	} finally {
		zoom.classList.remove("shake-impact");
		overlay.classList.remove("active");
		// Demande explicite de l'utilisateur : "Lorsque l'animation est
		// terminée, le smartphone du joueur affiche l'écran des cartes."
		// state.cardModalItem, pas state.cardModalOffer (10/10/2026, voir le
		// correctif détaillé dans connectPlayerWs plus haut dans ce fichier).
		if (!state.cardModalItem && !state.scanStream) {
			renderMyCards();
			setActiveNav("navBtnCards");
		}
	}
}

// Symétrique de playTurnStartAnimation ci-dessus - bascule vers l'écran
// Profil à la fin (demande explicite de l'utilisateur), pas l'onglet Cartes.
async function playTurnEndAnimation() {
	const overlay = el("turnendAnimOverlay");
	const zoom = el("turnendAnimZoom");
	const titleHost = el("turnendAnimTitleHost");

	zoom.style.transition = "none";
	zoom.classList.remove("exploded", "shake-impact");
	zoom.classList.add("collapsed");
	void zoom.offsetWidth;
	zoom.style.transition = "";
	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "0";

	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";

	try {
		await new Promise((r) => setTimeout(r, TURNEND_ANIM_ZOOM_START_DELAY_MS));

		zoom.classList.remove("collapsed");
		zoom.classList.add("exploded");
		await new Promise((r) => setTimeout(r, TURNEND_ANIM_EXPLODE_MS));

		titleHost.style.opacity = "";
		await renderAnimTitle(titleHost, t("playerView.turnend_anim_title"));
		titleHost.classList.add("stamp-in");
		zoom.classList.add("shake-impact");
		await new Promise((r) => setTimeout(r, TURNEND_ANIM_IMPACT_MS));
		zoom.classList.remove("shake-impact");

		const elapsed = TURNEND_ANIM_ZOOM_START_DELAY_MS + TURNEND_ANIM_EXPLODE_MS + TURNEND_ANIM_IMPACT_MS;
		await new Promise((r) => setTimeout(r, Math.max(0, TURNEND_ANIM_TOTAL_DISPLAY_MS - elapsed)));
	} catch (err) {
		pushDebugLog("ERREUR", "Animation \"Stop !\" interrompue :", err);
	} finally {
		zoom.classList.remove("shake-impact");
		overlay.classList.remove("active");
		// Demande explicite de l'utilisateur : "Lorsque l'animation est
		// terminée, le smartphone du joueur affiche l'écran des profils."
		// state.cardModalItem, pas state.cardModalOffer (10/10/2026, voir le
		// correctif détaillé dans connectPlayerWs plus haut dans ce fichier).
		if (!state.cardModalItem && !state.scanStream) {
			renderProfile();
			showScreen("profileScreen");
			setActiveNav("navBtnProfile");
		}
	}
}

// ============================================================
// Animation "Prison !" (11/10/2026) - dette+smartphone UNIQUEMENT, remonté
// par l'utilisateur (image jointe "13.jpg", prisonnier derrière des
// barreaux) : "Appuie toi sur l'animation de la mort mais remplace
// l'illustration par celle ci-jointe et le texte éditable en html par
// 'Prison !'. Fait un dézoome rapide de la fenêtre et qui montre toute
// l'image en reculant rapidement (effet de dezoome)... à la différence de
// l'animation de la mort, l'image se fige sur l'écran du joueur pendant
// tout le tour suivant, lui empêchant de jouer. L'image disparaitra
// automatiquement à la fin du tour." Entrée calquée EXACTEMENT sur
// playDeathAnimation ci-dessus (mêmes constantes de timing, même technique
// "transition: none" + reflow forcé pour un état de départ instantané, même
// titre rendu par js/vendor/cartoon-text.js) - voir son commentaire
// détaillé plus haut pour le raisonnement complet de chaque correctif déjà
// appliqué ici dès la première version (police préchargée au chargement de
// la page, rendu du titre plafonné dans le temps, etc.).
//
// DIFFÉRENCE VOLONTAIRE avec "Mort du joueur" : aucune fermeture
// automatique après un délai fixe - voir dismissPrisonAnimation()/
// syncPrisonOverlayFromPlayerState() ci-dessous, les deux SEULS points qui
// referment réellement cet overlay (événement WS "turn_ending", ou
// détection que PlayerSelfViewDto.inPrisonThisTurn est repassé à false au
// prochain rafraîchissement périodique - filet de sécurité si ce message WS
// était manqué, même esprit que les nombreux filets de sécurité réseau déjà
// en place ailleurs dans ce fichier, voir refreshPlayer).
//
// mPrisonAnimShown : vrai dès que l'overlay est visible (entrée jouée OU
// restauré directement figé après un rechargement/une reconnexion pendant
// que le joueur est toujours en prison, voir showPrisonOverlayFrozen) - sert
// à la fois de garde anti-double-déclenchement (un message WS "prison"
// rejoué ne doit jamais relancer l'entrée en plein milieu d'un overlay déjà
// figé) et de mémoire pour savoir si une fermeture est nécessaire.
let mPrisonAnimShown = false;

const PRISON_ANIM_ZOOM_START_DELAY_MS = 50;
const PRISON_ANIM_DEZOOM_MS = 550;
const PRISON_ANIM_IMPACT_MS = 450;

// Déclenchée directement par l'événement WebSocket "prison" (voir
// connectPlayerWs plus haut) - jamais via une file d'attente comme "Mort du
// joueur" : un joueur ne peut être envoyé en prison qu'une seule fois par
// entre-deux-tours, et mPrisonAnimShown empêche déjà tout chevauchement en
// cas de double message (reconnexion, rejeu réseau...).
async function playPrisonAnimation() {
	if (mPrisonAnimShown) return; // déjà affichée (entrée déjà jouée, ou restaurée figée après un rechargement) - jamais rejouée par-dessus elle-même
	mPrisonAnimShown = true;

	const overlay = el("prisonAnimOverlay");
	const zoom = el("prisonAnimZoom");
	const titleHost = el("prisonAnimTitleHost");

	// Même truc "transition: none" + reflow forcé que playDeathAnimation
	// (voir son commentaire détaillé plus haut) : l'état de départ
	// "zoomed-in" doit s'appliquer INSTANTANÉMENT, sans quoi la transition
	// CSS prévue pour le dézoom s'appliquerait aussi à cette mise en place.
	zoom.style.transition = "none";
	zoom.classList.remove("zoomed-out", "shake-impact");
	zoom.classList.add("zoomed-in");
	void zoom.offsetWidth; // force le calcul du style avant de rétablir la transition
	zoom.style.transition = "";
	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "0";

	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";

	// Contrairement à playDeathAnimation : PAS de finally qui referme
	// l'overlay - une exception ici (ex. échec de rendu du titre) laisse
	// volontairement l'écran figé (overlay déjà "active" ci-dessus) : mieux
	// vaut un titre manquant qu'un joueur en prison qui retrouverait la main
	// par accident à cause d'une simple erreur de rendu. Voir
	// dismissPrisonAnimation()/syncPrisonOverlayFromPlayerState(), les deux
	// seuls points qui referment réellement cet overlay.
	try {
		await new Promise((r) => setTimeout(r, PRISON_ANIM_ZOOM_START_DELAY_MS));

		// Dézoom rapide (demande explicite : "Fait un dézoome rapide de la
		// fenêtre et qui montre toute l'image en reculant rapidement") - même
		// mécanique que playDeathAnimation ci-dessus.
		zoom.classList.remove("zoomed-in");
		zoom.classList.add("zoomed-out");
		await new Promise((r) => setTimeout(r, PRISON_ANIM_DEZOOM_MS));

		titleHost.style.opacity = "";
		await renderAnimTitle(titleHost, t("playerView.prison_anim_title"));
		titleHost.classList.add("stamp-in");
		zoom.classList.add("shake-impact");
		playDeathImpactSound();
		await new Promise((r) => setTimeout(r, PRISON_ANIM_IMPACT_MS));
		zoom.classList.remove("shake-impact");
		// ÉTAPE 3 : contrairement à "Mort du joueur", AUCUNE fermeture
		// automatique ici - l'overlay reste "active" (pointer-events: auto,
		// écran figé) jusqu'à dismissPrisonAnimation()/
		// syncPrisonOverlayFromPlayerState() ci-dessous.
	} catch (err) {
		pushDebugLog("ERREUR", "Animation \"Prison !\" interrompue (reste affichée, voir le commentaire de tête de fonction) :", err);
	}
}

// Affiche l'overlay DIRECTEMENT dans son état final figé (sans rejouer le
// dézoom/le coup de tampon) - utilisée UNIQUEMENT par
// syncPrisonOverlayFromPlayerState() ci-dessous, pour le cas d'un
// rechargement de page/une reconnexion pendant que le joueur est TOUJOURS en
// prison (son téléphone a par exemple été verrouillé puis rouvert en plein
// milieu du tour de blocage) : le message WebSocket "prison" d'origine, lui,
// n'est jamais rediffusé pour une simple reconnexion - sans cette fonction,
// le joueur retrouverait un écran normal, jouable, alors qu'il devrait
// rester bloqué.
async function showPrisonOverlayFrozen() {
	mPrisonAnimShown = true;
	const overlay = el("prisonAnimOverlay");
	const zoom = el("prisonAnimZoom");
	const titleHost = el("prisonAnimTitleHost");

	zoom.style.transition = "none";
	zoom.classList.remove("zoomed-in", "shake-impact");
	zoom.classList.add("zoomed-out");
	void zoom.offsetWidth;
	zoom.style.transition = "";

	overlay.style.transition = "none";
	overlay.classList.add("active");
	void overlay.offsetWidth;
	overlay.style.transition = "";

	titleHost.classList.remove("stamp-in");
	titleHost.style.opacity = "";
	await renderAnimTitle(titleHost, t("playerView.prison_anim_title"));
	titleHost.classList.add("stamp-in");
}

// Referme l'overlay "Prison !" avec le fondu normal (0,25s, voir
// .prison-anim-overlay dans player.css) - appelée depuis connectPlayerWs sur
// l'événement WS "turn_ending" (fin du tour de blocage, demande explicite :
// "L'image disparaitra automatiquement à la fin du tour") ET depuis
// syncPrisonOverlayFromPlayerState() ci-dessous (filet de sécurité si ce
// message WS était manqué). Idempotente (ne fait rien si l'overlay n'est pas
// affiché) - sans danger d'appel superflu.
function dismissPrisonAnimation() {
	if (!mPrisonAnimShown) return;
	mPrisonAnimShown = false;
	const overlay = el("prisonAnimOverlay");
	if (overlay) overlay.classList.remove("active");
}

// Appelée depuis refreshPlayer() à chaque rafraîchissement (voir plus haut
// dans ce fichier) - synchronise l'overlay "Prison !" sur l'état RÉEL du
// joueur (PlayerSelfViewDto.inPrisonThisTurn, voir Dtos.java côté serveur)
// plutôt que de dépendre uniquement des messages WebSocket "prison"/
// "turn_ending" ponctuels : couvre à la fois (1) un rechargement de page ou
// une reconnexion pendant que le joueur est toujours en prison (le message
// WS d'origine n'a jamais été rediffusé pour cette reconnexion - voir
// showPrisonOverlayFrozen ci-dessus) et (2) un message "turn_ending" manqué
// (accroc réseau documenté ailleurs dans ce fichier comme un risque connu de
// ce mode multi-téléphones) - le rafraîchissement périodique (5s) reste ici
// aussi le filet de sécurité final, même principe que la détection de fin de
// tour déjà en place plus haut dans refreshPlayer().
function syncPrisonOverlayFromPlayerState() {
	if (!state.player) return;
	if (state.player.inPrisonThisTurn && !mPrisonAnimShown) {
		showPrisonOverlayFrozen();
	} else if (!state.player.inPrisonThisTurn && mPrisonAnimShown) {
		dismissPrisonAnimation();
	}
}

if (window.GecoI18n) window.GecoI18n.onChange(startOnce);
setTimeout(startOnce, 1500);
