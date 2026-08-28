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
const SCREENS = ["viewContent", "sellPicker", "sellPrice", "sellQr", "scanCamera", "scanManual", "scanConfirm", "tradeResult"];
function showScreen(id) {
	stopCamera(); // toujours couper la caméra en quittant scanCamera, quel que soit l'écran de destination
	clearQrCountdown();
	SCREENS.forEach((s) => el(s).classList.toggle("hidden", s !== id));
}

// ---------- Consultation (ex-refresh() de l'ancienne version de cette page) ----------
async function refreshPlayer() {
	if (!state.gameId || !state.token) {
		el("viewError").classList.remove("hidden");
		return;
	}
	try {
		const res = await fetch(`/api/games/${state.gameId}/players/by-token/${state.token}`);
		if (!res.ok) throw new Error("not found");
		state.player = await res.json();
		el("viewError").classList.add("hidden");
		el("playerName").textContent = state.player.name;
		el("playerStatus").textContent = state.player.active ? t("playerView.status_active") : t("playerView.status_inactive");
		el("balanceCardValue").textContent = t("trade.balance_value", { n: state.player.tradeBalance });
		const details = el("playerDetails");
		let detailsHtml = "";
		if (state.player.goodsCount > 0) detailsHtml += `<p>${t("playerView.goods_count", { n: state.player.goodsCount })}</p>`;
		if ((state.player.curDebt > 0) || (state.player.curInterest > 0))
			detailsHtml += `<p>${t("playerView.current_credit", { debt: state.player.curDebt, interest: state.player.curInterest })}</p>`;
		details.innerHTML = detailsHtml;
	} catch (err) {
		el("viewError").classList.remove("hidden");
		return;
	}
	// Ne bascule sur le hub que si aucun autre écran d'échange n'est déjà affiché
	// (le rafraîchissement périodique ne doit pas interrompre une vente/un achat en cours).
	if (SCREENS.every((s) => el(s).classList.contains("hidden"))) showScreen("viewContent");
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

	container.innerHTML = CARD_LEVELS.map((level) => {
		const entries = state.cardsCatalog.filter((c) => c.niveau === level);
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
				${entries.map((entry) => sellCardRowHtml(entry)).join("")}
			</div>
		</div>`;
	}).join("");

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

function sellCardRowHtml(entry) {
	const visual = state.visualsCatalog.find((v) => v.id === entry.visualId);
	const thumb = visual
		? `<img src="/cartes/${escapeHtmlLocal(visual.filename)}" class="trade-card-thumb level-${entry.niveau}" onerror="this.outerHTML='<div class=&quot;trade-card-thumb-fallback level-${entry.niveau}&quot;>🖼️</div>'">`
		: `<div class="trade-card-thumb-fallback level-${entry.niveau}">🖼️</div>`;
	return `
	<button type="button" class="trade-card-row" data-id="${escapeHtmlLocal(entry.id)}">
		${thumb}
		<span class="trade-card-name">${escapeHtmlLocal(catalogTextValue(entry.nom) || entry.id)}</span>
	</button>`;
}

function escapeHtmlLocal(s) {
	return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ============================================================
// VENTE : étape 2 - fixer le prix
// ============================================================
function openSellPrice(entry, visual) {
	state.sellSelection = { entry, visual };
	state.sellPrice = { weak: 0, medium: 0, strong: 0 };
	renderSellPriceCardInfo();
	renderPriceSteppers();
	showScreen("sellPrice");
}

function renderSellPriceCardInfo() {
	const { entry, visual } = state.sellSelection;
	const thumb = visual
		? `<img src="/cartes/${escapeHtmlLocal(visual.filename)}" onerror="this.outerHTML='<div class=&quot;trade-card-thumb-fallback&quot;>🖼️</div>'">`
		: `<div class="trade-card-thumb-fallback">🖼️</div>`;
	const html = `
		${thumb}
		<div class="trade-card-info-main">
			<span class="trade-card-info-name">${escapeHtmlLocal(catalogTextValue(entry.nom) || entry.id)}</span>
			<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", entry.niveau))}</span>
		</div>`;
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
async function createOfferAndShowQr() {
	const { entry } = state.sellSelection;
	const btn = el("btnGenerateQr");
	btn.disabled = true;
	try {
		const res = await fetch(`/api/games/${state.gameId}/trade-offers`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({
				sellerPlayerId: state.player.id,
				sellerAccessToken: state.token,
				cardTypeId: entry.id,
				cardLevel: entry.niveau,
				cardName: entry.nom, // table {langue: texte} - l'acheteur affichera dans SA propre langue
				weakCoins: state.sellPrice.weak,
				mediumCoins: state.sellPrice.medium,
				strongCoins: state.sellPrice.strong,
			}),
		});
		if (!res.ok) throw new Error(t("join.generic_error", { status: res.status }));
		const offer = await res.json();
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
	try {
		state.scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
	} catch (err) {
		el("scanCameraError").classList.remove("hidden");
		return;
	}
	const video = el("scanVideo");
	video.srcObject = state.scanStream;
	await video.play();

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
			stopCamera();
			resolveCode(code.data.trim().toUpperCase());
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
	await resolveCode(raw);
}

// ---------- Résolution du code (commune scan/saisie manuelle) ----------
async function resolveCode(code) {
	try {
		const res = await fetch(`/api/games/${state.gameId}/trade-offers/${code}`);
		if (!res.ok) {
			if (el("scanManual").classList.contains("hidden")) {
				// Venait du scan caméra : on ignore et on continue de scanner
				// plutôt que d'interrompre l'utilisateur pour un QR expiré capté
				// par erreur (ex. reflet, ancien QR encore affiché ailleurs).
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
			if (el("scanManual").classList.contains("hidden")) {
				state.scanRAF = requestAnimationFrame(scanTick);
			} else {
				el("manualCodeError").textContent = t("trade.manual_code_own_card");
				el("manualCodeError").classList.remove("hidden");
			}
			return;
		}
		state.pendingOffer = offer;
		renderScanConfirm(offer);
		showScreen("scanConfirm");
	} catch (err) {
		showTradeResult(false, t("trade.result_error_title"), err.message);
	}
}


function renderScanConfirm(offer) {
	const name = catalogTextValue(offer.cardName) || offer.cardTypeId;
	const priceParts = [];
	if (offer.weakCoins > 0) priceParts.push(t("trade.price_weak", { n: offer.weakCoins }));
	if (offer.mediumCoins > 0) priceParts.push(t("trade.price_medium", { n: offer.mediumCoins }));
	if (offer.strongCoins > 0) priceParts.push(t("trade.price_strong", { n: offer.strongCoins }));
	const priceText = priceParts.length > 0 ? priceParts.join(" + ") : t("trade.price_free");

	const infoEl = el("scanConfirmInfo");
	infoEl.className = `trade-card-info level-${offer.cardLevel}`;
	infoEl.innerHTML = `
		<div class="trade-card-thumb-fallback">🖼️</div>
		<div class="trade-card-info-main">
			<span class="trade-card-info-name">${escapeHtmlLocal(name)}</span>
			<span class="trade-card-info-meta">${escapeHtmlLocal(catalogEnumLabel("level", offer.cardLevel))} · ${escapeHtmlLocal(t("trade.sold_by", { name: offer.sellerPlayerName }))}</span>
			<span class="trade-card-info-price">${escapeHtmlLocal(priceText)}</span>
		</div>`;
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
		const newBalance = (state.player.tradeBalance || 0) - transaction.totalCoinsValue;
		showTradeResult(true, t("trade.result_success_title"),
			t("trade.result_success_body_balance", {
				name: catalogTextValue(offer.cardName) || offer.cardTypeId, balance: newBalance,
			}));
		refreshPlayer();
	} catch (err) {
		el("scanConfirmError").textContent = err.message;
		el("scanConfirmError").classList.remove("hidden");
	} finally {
		btn.disabled = false;
		btn.textContent = t("trade.btn_confirm_buy");
	}
}

function showTradeResult(success, title, body) {
	el("tradeResultIcon").textContent = success ? "✅" : "⚠️";
	el("tradeResultTitle").textContent = title;
	el("tradeResultBody").textContent = body;
	showScreen("tradeResult");
}

// ---------- Câblage des boutons (une fois, au chargement) ----------
function initTradeUI() {
	el("btnOpenSell").addEventListener("click", openSellPicker);
	el("btnOpenScan").addEventListener("click", openScan);
	el("btnOpenManualEntry").addEventListener("click", openManualEntry);
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
	el("btnTradeResultBack").addEventListener("click", () => showScreen("viewContent"));
}

// ---------- Démarrage ----------
// Même précaution que sur join.html : attendre la première traduction
// effective avant le premier refresh(), pour ne jamais afficher une clé brute
// le temps que la langue se charge (voir player.js pour la même logique).
let started = false;
function startOnce() {
	if (started) return;
	started = true;
	initTradeUI();
	refreshPlayer();
	setInterval(refreshPlayer, 5000);
}
if (window.GecoI18n) window.GecoI18n.onChange(startOnce);
setTimeout(startOnce, 1500);
