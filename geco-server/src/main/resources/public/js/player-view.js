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

// Étape 3 (31/08/2026) : décomposition d'une VALEUR (voir
// GameService.computeTradeBalance, jamais une simple donnée locale) en
// jetons physiques faible/moyen/fort - remonté par l'utilisateur : "il faut
// que ce soit cohérent entre les smartphones et l'application [animateur]".
// Portée VERBATIM depuis computeTokenBreakdown() dans app.js (même
// algorithme exact, grosses coupures privilégiées) - jamais réinventée,
// pour qu'un même solde donne TOUJOURS la même décomposition des deux
// côtés, vérifiable par l'animateur comme par le joueur.
function computeTokenBreakdown(pTotalValue, pWeakCoinValue) {
	let units = Math.max(0, Math.round(pTotalValue / (pWeakCoinValue || 1)));
	const strong = Math.floor(units / 4);
	units -= strong * 4;
	const medium = Math.floor(units / 2);
	units -= medium * 2;
	return { weak: units, medium, strong };
}

const params = new URLSearchParams(window.location.search);
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
	scanConfirm: { titleKey: "trade.confirm_title", back: "viewContent" },
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
	el("appHeaderTitle").textContent = t(config.titleKey);
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

async function refreshPlayer() {
	if (!state.gameId || !state.token) {
		el("viewError").classList.remove("hidden");
		el("mobileContainer").classList.add("hidden");
		return;
	}
	try {
		const res = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}`);
		if (!res.ok) throw new Error("not found");
		state.player = await res.json();
		el("viewError").classList.add("hidden");
		// Bouton "Demander un crédit" : monnaie dette uniquement (voir isDebtGame()).
		el("btnOpenCreditRequest").classList.toggle("hidden", !isDebtGame());
		const details = el("playerDetails");
		let detailsHtml = "";
		if ((state.player.curDebt > 0) || (state.player.curInterest > 0))
			detailsHtml += `<p>${t("playerView.current_credit", { debt: state.player.curDebt, interest: state.player.curInterest })}</p>`;
		details.innerHTML = detailsHtml;
		await Promise.all([renderDashboard(), renderProfile()]);
	} catch (err) {
		el("viewError").classList.remove("hidden");
		el("mobileContainer").classList.add("hidden");
		return;
	}
	el("mobileContainer").classList.remove("hidden");
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
	// Le solde en jetons n'a de sens qu'en dette/libre - le troc n'a jamais de
	// jetons par principe (voir docs/10-etape-plugins-troc.md, règle 3).
	el("balanceCard").classList.toggle("hidden", isTrocGame());
	if (!isTrocGame()) {
		el("balanceCardValue").textContent = state.player.tradeBalance;
		// Décomposition en jetons physiques (30/08/2026, remonté par
		// l'utilisateur : "important pour pouvoir vérifier, comparer et
		// s'assurer que tout soit correct... pour que les joueurs comprennent
		// ce qui se passe") - même algorithme que côté animateur (voir
		// computeTokenBreakdown), respecte "Valeur d'une pièce faible".
		const breakdown = computeTokenBreakdown(state.player.tradeBalance, state.player.weakCoinValue);
		el("balanceCardBreakdown").innerHTML = tokenBreakdownHtml(breakdown);
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
		amountEl.textContent = (delta >= 0 ? "+" : "") + delta;
		amountEl.classList.toggle("negative", delta < 0);
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
		el("activityText").innerHTML = t(verbKey, {
			card: `<strong>${escapeHtmlLocal(cardName)}</strong>`,
			name: `<strong>${escapeHtmlLocal(partner)}</strong>`,
			amount: `<strong>${latest.isGoodsTrade ? t("playerView.history_goods_amount_short", { n: latest.buyerWeakGoods + latest.buyerMediumGoods + latest.buyerStrongGoods }) : t("game.transactions_amount", { n: latest.totalCoinsValue })}</strong>`,
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
	el("profileStatus").textContent = state.player.active ? t("playerView.status_active") : t("playerView.status_inactive");
	el("profileAvatarWrapper").innerHTML = buildProfileAvatarHtml(state.player.avatarConfigJson);
	el("statCoins").textContent = isTrocGame() ? "—" : state.player.tradeBalance;
	// Décomposition en jetons physiques (31/08/2026) - même logique que
	// renderDashboard(), voir computeTokenBreakdown().
	if (!isTrocGame()) {
		const breakdown = computeTokenBreakdown(state.player.tradeBalance, state.player.weakCoinValue);
		el("statCoinsBreakdown").innerHTML = tokenBreakdownHtml(breakdown);
	} else {
		el("statCoinsBreakdown").innerHTML = "";
	}
	el("statCards").textContent = state.player.goodsCount || 0;

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
		return order.filter((level) => groups.has(level)).map((level) => {
			const cards = groups.get(level);
			return {
				title: t("playerView.cards_group_value_title", { level: catalogEnumLabel("level", level) }),
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

// Décomposition d'une valeur en jetons physiques (faible/moyen/fort) - copie
// EXACTE de computeTokenBreakdown() dans app.js (côté animateur), remonté
// par l'utilisateur (31/08/2026) : "il faut que ce soit cohérent entre les
// smartphones et l'application" - même algorithme (grosses coupures
// privilégiées), respecte "Valeur d'une pièce faible" (voir
// PlayerSelfViewDto.weakCoinValue, écran Nouvelle partie côté animateur).
// Dupliquée ici comme buildAvatarSVG plus haut : les deux pages sont
// chargées séparément, pas de module JS partagé dans ce projet.
function computeTokenBreakdown(pTotalValue, pWeakCoinValue) {
	let units = Math.max(0, Math.round(pTotalValue / (pWeakCoinValue || 1)));
	const strong = Math.floor(units / 4);
	units -= strong * 4;
	const medium = Math.floor(units / 2);
	units -= medium * 2;
	return { weak: units, medium, strong };
}

// HTML compact de la décomposition (3 pastilles faible/moyen/fort) - voir
// renderDashboard/renderProfile. N'affiche que les dénominations non nulles,
// pour ne pas encombrer l'écran d'un "0 jeton fort" sans intérêt.
function tokenBreakdownHtml(pBreakdown) {
	const parts = [];
	if (pBreakdown.weak > 0) parts.push(`<span class="token-chip token-weak">${pBreakdown.weak} × ${escapeHtmlLocal(t("trade.coin_weak_short"))}</span>`);
	if (pBreakdown.medium > 0) parts.push(`<span class="token-chip token-medium">${pBreakdown.medium} × ${escapeHtmlLocal(t("trade.coin_medium_short"))}</span>`);
	if (pBreakdown.strong > 0) parts.push(`<span class="token-chip token-strong">${pBreakdown.strong} × ${escapeHtmlLocal(t("trade.coin_strong_short"))}</span>`);
	return parts.length > 0 ? parts.join(" ") : `<span class="token-chip">${escapeHtmlLocal(t("trade.coin_none"))}</span>`;
}

// Valeur automatique d'une carte, en jetons, selon son niveau - remonté par
// l'utilisateur (31/08/2026) : "il faut limiter les risques d'erreurs donc
// le nombre de saisies humaines. Tout ce qui peut être automatisé doit
// l'être... la valeur de la carte est définie dans le code", plutôt que de
// laisser le vendeur fixer librement un prix (steppers manuels, retirés en
// dette/libre - toujours utilisés en troc, voir plus bas, cette
// simplification ne concernant que les jetons). Une carte de niveau X vaut
// toujours 3 jetons de LA DÉNOMINATION CORRESPONDANTE (3 faibles pour une
// carte faible, 3 moyens pour une carte moyenne, 3 forts pour une carte
// forte) - tresforte n'ayant pas de 4e dénomination de jeton, 6 jetons forts
// (même valeur totale que 3 jetons d'une dénomination supérieure aurait eu).
const LEVEL_JETON_PRICE = {
	faible: { weak: 3, medium: 0, strong: 0 },
	moyenne: { weak: 0, medium: 3, strong: 0 },
	forte: { weak: 0, medium: 0, strong: 3 },
	tresforte: { weak: 0, medium: 0, strong: 6 },
};

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
	// Troc : pas de valeur en jetons par principe (voir docs/10-etape-
	// plugins-troc.md, règle 3) - garde l'étape manuelle existante
	// (steppers de cartes voulues en retour, librement négocié).
	if (isTrocGame()) {
		renderCardModalPriceStep(item);
	} else {
		el("cardModalBackBody").innerHTML = `<p style="color:#666;font-size:0.85rem;">${escapeHtmlLocal(t("settings.catalog_loading"))}</p>`;
		const p = LEVEL_JETON_PRICE[item.entry.niveau] || { weak: 0, medium: 0, strong: 0 };
		state.cardModalPrice = { ...p };
		generateCardModalQr(item);
	}
}

function closeCardModal() {
	el("cardModalOverlay").classList.remove("active");
	clearCardModalCountdown();
	// Laisse le temps à l'animation de fermeture de se jouer avant de
	// réinitialiser le retournement - même principe que le reste de l'app
	// (voir .stepDone-exit dans player.css).
	setTimeout(() => { el("cardModalFlip").classList.remove("is-flipped"); }, 300);
}

// Étape "prix" au dos de la carte, avant de générer le QR - steppers
// compacts (voir .modal-price-steppers). TROC UNIQUEMENT désormais (voir
// openCardModal) : demande des cartes en retour, librement négocié, comme
// openSellPrice. Le prix en jetons (dette/libre) est automatique, voir
// LEVEL_JETON_PRICE ci-dessus.
function renderCardModalPriceStep(item) {
	const isTroc = isTrocGame();
	const labels = isTroc
		? [t("trade.goods_wanted_weak"), t("trade.goods_wanted_medium"), t("trade.goods_wanted_strong")]
		: [t("trade.coin_weak"), t("trade.coin_medium"), t("trade.coin_strong")];
	const coins = ["weak", "medium", "strong"];
	el("cardModalBackBody").innerHTML = `
		<div class="modal-price-steppers">
			${coins.map((coin, i) => `
			<div class="modal-price-stepper" data-modal-coin="${coin}">
				<span>${escapeHtmlLocal(labels[i])}</span>
				<div class="modal-price-stepper-controls">
					<button type="button" class="modal-stepper-btn" data-modal-delta="-1">−</button>
					<span class="modal-stepper-value" data-modal-value="${coin}">0</span>
					<button type="button" class="modal-stepper-btn" data-modal-delta="1">+</button>
				</div>
			</div>`).join("")}
		</div>
		<button type="button" class="modal-generate-qr-btn" id="cardModalGenerateBtn">${escapeHtmlLocal(t("trade.btn_generate_qr"))}</button>`;

	el("cardModalBackBody").querySelectorAll(".modal-stepper-btn").forEach((btn) => {
		btn.addEventListener("click", (e) => {
			e.stopPropagation(); // ne doit jamais déclencher le retournement de la carte
			const coin = btn.closest("[data-modal-coin]").dataset.modalCoin;
			const delta = parseInt(btn.dataset.modalDelta, 10);
			state.cardModalPrice[coin] = Math.max(0, state.cardModalPrice[coin] + delta);
			el("cardModalBackBody").querySelector(`[data-modal-value="${coin}"]`).textContent = state.cardModalPrice[coin];
		});
	});
	el("cardModalGenerateBtn").addEventListener("click", (e) => {
		e.stopPropagation();
		generateCardModalQr(item);
	});
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
		el("cardModalBackBody").innerHTML = `
			${priceLine}
			<div class="qr-container"><div id="cardModalQrBox"></div></div>
			<p class="qr-instruction">${escapeHtmlLocal(t("trade.qr_instructions"))}</p>
			<div class="qr-timer"><span aria-hidden="true">⏱️</span><span id="cardModalCountdownValue">01:30</span></div>
			<button type="button" class="btn-cancel-link" id="cardModalCancelBtn" style="color:#fff;">${escapeHtmlLocal(t("trade.btn_cancel_sell"))}</button>`;
		// eslint-disable-next-line no-undef
		new QRCode(el("cardModalQrBox"), { text: offer.code, width: 140, height: 140, correctLevel: QRCode.CorrectLevel.M });
		el("cardModalCancelBtn").addEventListener("click", (e) => { e.stopPropagation(); closeCardModal(); });
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
		if (pLimit) entries = entries.slice(0, pLimit);
		// Couleurs de badge tournantes (voir .avatar-badge, mockup de référence)
		// - purement décoratif, un simple repère visuel par position dans la
		// liste plutôt qu'un système de couleur par joueur à faire persister.
		const badgeColors = ["bg-teal", "bg-pink", "bg-purple"];
		list.innerHTML = entries.map((e, i) => {
			const isMe = e.playerId === state.player.id;
			const initial = escapeHtmlLocal((e.playerName || "?").charAt(0).toUpperCase());
			return `
			<li class="leaderboard-item">
				<div class="player-info">
					<div class="avatar-badge ${badgeColors[i % badgeColors.length]} ${isMe ? "me" : ""}">${initial}</div>
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
		const [txs, squares] = await Promise.all([
			fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/transactions`).then((r) => r.json()),
			fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/squares`).then((r) => r.json()),
		]);
		if ((txs.length === 0) && (squares.length === 0)) {
			body.innerHTML = `<li style="color:var(--text-muted);font-size:0.85rem;">${t("playerView.history_empty")}</li>`;
			return;
		}
		const items = [
			...txs.map((tx) => ({ kind: "transaction", timestamp: tx.timestamp, data: tx })),
			...squares.map((sq) => ({ kind: "square", timestamp: sq.timestamp, data: sq })),
		].sort((a, b) => b.timestamp - a.timestamp);
		body.innerHTML = items.map((item) => (item.kind === "transaction"
			? historyTransactionRowHtml(item.data)
			: historySquareRowHtml(item.data))).join("");
	} catch (err) {
		body.innerHTML = `<li style="color:var(--danger)">${t("game.transactions_load_error")}</li>`;
	}
}

function historyTransactionRowHtml(tx) {
	const isSale = tx.sellerPlayerId === state.player.id;
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
	return `
	<li class="leaderboard-item">
		<div class="player-info">
			<div class="avatar-badge bg-purple">⬡</div>
			<div>
				<div class="player-name">${escapeHtmlLocal(t("playerView.history_square_title"))}</div>
				<div style="font-size:0.74rem;color:var(--text-muted);">${escapeHtmlLocal(t("playerView.history_square_detail", { cashed: cashedName, promoted: promotedName }))} · ${escapeHtmlLocal(t("game.transactions_turn_label", { n: sq.turnNumber }))}${breakthroughBadge}</div>
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
		const res = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}/credit-requests`);
		if (res.ok) {
			renderCreditRequestStatus(await res.json());
			return;
		}
	} catch (err) {
		// Erreur réseau (pas un simple 404 "aucune demande") : on retombe quand
		// même sur le formulaire plutôt que de bloquer l'écran - le joueur peut
		// toujours réessayer d'envoyer sa demande.
	}
	renderCreditRequestForm();
}

function renderCreditRequestForm() {
	el("creditRequestBody").innerHTML = `
		<label class="field-label" for="creditRequestAmount" data-i18n="playerView.credit_request_amount_label">Montant souhaité</label>
		<input id="creditRequestAmount" class="field-input" type="number" min="1" value="10">
		<p id="creditRequestError" class="field-error hidden"></p>
		<button type="button" class="btn-primary btn-block" id="btnSubmitCreditRequest">${escapeHtmlLocal(t("playerView.credit_request_submit"))}</button>`;
	el("btnSubmitCreditRequest").addEventListener("click", submitCreditRequest);
}

async function submitCreditRequest() {
	const amount = parseInt(el("creditRequestAmount").value, 10);
	const errEl = el("creditRequestError");
	if (!amount || (amount <= 0)) {
		errEl.textContent = t("playerView.credit_request_invalid_amount");
		errEl.classList.remove("hidden");
		return;
	}
	errEl.classList.add("hidden");
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

	// Étape 3, monnaie libre : contrairement à dette/troc (pas encore de vrai
	// inventaire suivi), le stock de cartes est ici RÉEL et fini (voir le
	// document de cadrage du 28/08/2026 et GameService.
	// dealStartingHandsForLibreIfNeeded) - un joueur ne doit donc pouvoir
	// proposer à la vente QUE ce qu'il possède réellement, jamais l'intégralité
	// du catalogue comme c'était le cas jusqu'ici (un vrai trou : rien
	// n'empêchait de "vendre" une carte jamais possédée, créant une carte
	// fantôme et cassant le stock fixe qu'on vient tout juste de construire).
	let ownedCounts = null;
	if (isLibreGame()) {
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
		// Troc : le "prix" est ce que L'ACHETEUR va donner en échange (des
		// cartes, pas des jetons) - voir offer.weakGoodsWanted&co, posés par
		// le vendeur à la création de l'offre.
		const goodsParts = [];
		if (offer.weakGoodsWanted > 0) goodsParts.push(t("trade.goods_wanted_weak_amount", { n: offer.weakGoodsWanted }));
		if (offer.mediumGoodsWanted > 0) goodsParts.push(t("trade.goods_wanted_medium_amount", { n: offer.mediumGoodsWanted }));
		if (offer.strongGoodsWanted > 0) goodsParts.push(t("trade.goods_wanted_strong_amount", { n: offer.strongGoodsWanted }));
		const goodsText = goodsParts.length > 0 ? goodsParts.join(" + ") : t("trade.price_free");
		infoEl.innerHTML = `
			${cardHtml}
			<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", offer.cardLevel))} · ${escapeHtmlLocal(t("trade.sold_by", { name: offer.sellerPlayerName }))}</span>
			<span class="trade-card-info-price">${escapeHtmlLocal(t("trade.you_will_give", { goods: goodsText }))}</span>`;
		// Pas de lignes de solde en troc : il n'y a pas de jetons à suivre
		// (voir docs/10-etape-plugins-troc.md, règle 3) - seulement des cartes,
		// déjà résumées ci-dessus.
		el("scanConfirmBalanceRows").innerHTML = "";
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
	const offer = state.pendingOffer;
	const btn = el("btnConfirmBuy");
	btn.disabled = true;
	btn.textContent = t("trade.btn_confirming");
	try {
		const res = await fetch(`/api/games/${state.gameId}/trade-offers/${offer.code}/redeem`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ buyerPlayerId: state.player.id, buyerAccessToken: state.token }),
		});
		if (!res.ok) {
			const body = await res.json().catch(() => ({}));
			throw new Error(body.error || body.msg || t("join.generic_error", { status: res.status }));
		}
		// Le prix qui fait foi est celui renvoyé par le serveur (TransactionDto.
		// totalCoinsValue), pas une estimation côté client - même s'ils
		// coïncident presque toujours en pratique.
		const transaction = await res.json();
		const cardName = catalogTextValue(offer.cardName) || offer.cardTypeId;
		if (isTrocGame()) {
			// Pas de solde en jetons à annoncer en troc - juste la confirmation
			// de l'échange (le nouvel inventaire sera visible au rafraîchissement
			// du hub, voir refreshPlayer()).
			showTradeResult(true, t("trade.result_success_title"), t("trade.result_success_body_goods", { name: cardName }));
		} else {
			const newBalance = (state.player.tradeBalance || 0) - transaction.totalCoinsValue;
			showTradeResult(true, t("trade.result_success_title"),
				t("trade.result_success_body_balance", { name: cardName, balance: newBalance }),
				{ newBalance, delta: -transaction.totalCoinsValue });
		}
		refreshPlayer();
	} catch (err) {
		el("scanConfirmError").textContent = err.message;
		el("scanConfirmError").classList.remove("hidden");
	} finally {
		btn.disabled = false;
		btn.textContent = t("trade.btn_confirm_buy");
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
	el("scannerCloseBtn").addEventListener("click", () => showScreen("viewContent"));
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
		// si le visuel/catalogue attendu n'est pas disponible.
	}
	initTradeUI();
	refreshPlayer();
	setInterval(refreshPlayer, 5000);
	connectPlayerWs();
}

// Étape 3 (31/08/2026) : connexion WebSocket - remonté par l'utilisateur en
// creusant l'écran "Vente réussie" (côté VENDEUR) : jusqu'ici, rien ne
// prévenait le vendeur que sa carte avait été vendue - il ne voyait que le
// compte à rebours de son QR arriver à 0, indiscernable d'un QR simplement
// expiré sans avoir jamais été scanné. Même mécanisme que app.js (voir
// connectWs côté animateur) : une seule connexion, reconnexion automatique.
function connectPlayerWs() {
	const proto = location.protocol === "https:" ? "wss" : "ws";
	const ws = new WebSocket(`${proto}://${location.host}/ws`);
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
			playSquareAnimation(msg.payload);
		// Nouveau tour démarré (voir bouton "Démarrer la partie", app.js) -
		// remonté par l'utilisateur (02/09/2026) : "il faut aussi que l'écran
		// des joueurs montre automatiquement l'écran des Cartes". Ne
		// bouscule jamais un achat/une vente EN COURS (modal carte ouverte
		// ou caméra active) - seulement depuis un écran "passif" (accueil,
		// profil, stats, déjà sur Cartes...).
		if ((msg.type === "event") && (msg.payload.type === "TURN") && !state.cardModalOffer && !state.scanStream) {
			renderMyCards();
			setActiveNav("navBtnCards");
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
// Animation "Carré encaissé" (31/08/2026) - remonté par l'utilisateur : les
// carrés se produisaient en silence, sans aucun retour visuel, d'où sa
// confusion initiale ("je n'ai pas fait de carré, pourtant..."). Inspirée
// d'un code de référence fourni (Web Animations API : squash, convergence,
// implosion, flash+étoiles, apparition des 5 nouvelles cartes) - adaptée
// pour utiliser les VRAIES cartes du joueur (résolues depuis le catalogue,
// jamais des cartes génériques) et le fond indigo/violet déjà établi
// ailleurs (voir .square-anim-overlay dans player.css). Dure environ 3,3
// secondes puis referme automatiquement vers "Mes cartes".
async function playSquareAnimation(squareDto) {
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
	el("squareAnimTitle").textContent = t("playerView.square_anim_title");
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
	el("squareAnimSubtitle").textContent = t("playerView.square_anim_detail", { cashed: cashed.label, promoted: promoted.label });
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

if (window.GecoI18n) window.GecoI18n.onChange(startOnce);
setTimeout(startOnce, 1500);
