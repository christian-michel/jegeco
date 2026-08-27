// player.js — Écran mobile "Rejoindre la partie" (étape 3, Phase B).
// Volontairement séparé de app.js (dashboard animateur) : c'est une interface
// distincte, pour un public distinct (les joueurs sur leur propre téléphone).

const IDENTITY_COLORS = ["#7C3AED", "#22C55E", "#0EA5E9", "#F97316", "#EF4444", "#6B7280"];
const SKIN_COLORS = ["#F8D5B0", "#EFC29A", "#D9A066", "#B67D4E", "#8D5A34", "#5C3A21"];
const HAIR_COLORS = ["#1F2430", "#4A2C2A", "#7C4A2D", "#C9A15A", "#B0B0B0", "#E8C4E0"];
const HAIR_STYLES = [
	{ id: "none", label: "🚫" },
	{ id: "short", label: "✂️" },
	{ id: "long", label: "💇" },
	{ id: "curly", label: "➰" },
];
const ACCESSORIES = [
	{ id: "none", label: "🚫" },
	{ id: "glasses", label: "👓" },
	{ id: "hat", label: "🎩" },
];

const state = {
	gameId: null,
	name: "",
	age: 24,
	identityColor: IDENTITY_COLORS[0],
	// Deux façons de choisir un avatar : "gallery" (image toute faite, filtrable
	// par genre/âge/teint) ou "custom" (généré en SVG). Un seul actif à la fois.
	avatarMode: "gallery",
	avatarCustom: { skinColor: SKIN_COLORS[0], hairStyle: "short", hairColor: HAIR_COLORS[0], accessory: "none" },
	avatarGallerySelection: null, // { id, filename } une fois choisi
	activeAvatarTab: "peau",
	galleryFilters: { genre: "", age: "", skin: "" },
};

const el = (id) => document.getElementById(id);

// ---------- Génération de l'avatar en SVG (pas besoin d'illustrations externes) ----------
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

// Construit le HTML d'aperçu (image ou SVG généré) pour la sélection courante.
function renderAvatarPreview() {
	// On garde toujours un SVG de repli à jour (même en mode galerie), utilisé si
	// l'image sélectionnée ne charge pas.
	const fallbackSvg = buildAvatarSVG(state.avatarCustom);
	const previewEl = el("avatarPreview");

	if (state.avatarMode === "gallery" && state.avatarGallerySelection) {
		const entry = state.avatarGallerySelection;
		const img = document.createElement("img");
		img.src = `/avatars/${entry.filename}`;
		img.alt = entry.id;
		// Pas de onerror inline (évite tout souci d'échappement) : géré en JS après coup.
		img.addEventListener("error", () => { previewEl.innerHTML = fallbackSvg; });
		previewEl.innerHTML = "";
		previewEl.appendChild(img);
	} else {
		previewEl.innerHTML = fallbackSvg;
	}
}

// ---------- Galerie d'avatars filtrable ----------
function initAvatarGallery() {
	const catalog = typeof AVATARS_CATALOG !== "undefined" ? AVATARS_CATALOG : [];

	// Le filtre "teint" est peuplé dynamiquement à partir des valeurs réellement
	// présentes dans le catalogue, plutôt que codées en dur - s'adapte
	// automatiquement quand de nouveaux avatars sont ajoutés.
	const skinLabels = [...new Set(catalog.map((a) => a.skinToneLabel))];
	const skinSelect = el("filterSkin");
	skinLabels.forEach((label) => {
		const opt = document.createElement("option");
		opt.value = label;
		opt.textContent = label.charAt(0).toUpperCase() + label.slice(1);
		skinSelect.appendChild(opt);
	});

	function renderGrid() {
		const { genre, age, skin } = state.galleryFilters;
		const filtered = catalog.filter((a) =>
			(!genre || a.genre === genre) && (!age || a.ageCategory === age) && (!skin || a.skinToneLabel === skin));

		const grid = el("avatarGallery");
		grid.innerHTML = "";
		if (filtered.length === 0) {
			grid.innerHTML = '<div class="avatar-gallery-empty">Aucun avatar ne correspond à ces filtres.</div>';
			return;
		}
		for (const entry of filtered) {
			const item = document.createElement("div");
			item.className = "avatar-gallery-item"
				+ (state.avatarGallerySelection && state.avatarGallerySelection.id === entry.id ? " selected" : "");
			// Repli : place un petit avatar généré (dérivé de l'id, stable) tant que la
			// vraie image n'est pas encore disponible - la galerie reste utilisable pour
			// tester le mécanisme de filtre avant même de recevoir les vraies images.
			const fallbackConfig = deriveFallbackAvatarConfig(entry);
			item.innerHTML = `<img src="/avatars/${entry.filename}" alt="${entry.id}" class="gallery-img">`;
			grid.appendChild(item);
			const img = item.querySelector("img");
			img.addEventListener("error", () => {
				img.replaceWith(document.createRange().createContextualFragment(buildAvatarSVG(fallbackConfig)));
			});
			item.addEventListener("click", () => {
				state.avatarGallerySelection = entry;
				state.avatarMode = "gallery";
				renderAvatarPreview();
				renderGrid();
			});
		}
	}

	// Dérive une configuration d'avatar généré "stable" (toujours la même pour un
	// id donné) à partir des métadonnées du catalogue, pour le repli visuel.
	function deriveFallbackAvatarConfig(entry) {
		let hash = 0;
		for (let i = 0; i < entry.id.length; i++) hash = (hash * 31 + entry.id.charCodeAt(i)) >>> 0;
		return {
			skinColor: entry.skinTone || SKIN_COLORS[hash % SKIN_COLORS.length],
			hairStyle: HAIR_STYLES[hash % HAIR_STYLES.length].id,
			hairColor: HAIR_COLORS[(hash >> 2) % HAIR_COLORS.length],
			accessory: "none",
		};
	}

	el("filterGenre").addEventListener("change", (e) => { state.galleryFilters.genre = e.target.value; renderGrid(); });
	el("filterAge").addEventListener("change", (e) => { state.galleryFilters.age = e.target.value; renderGrid(); });
	el("filterSkin").addEventListener("change", (e) => { state.galleryFilters.skin = e.target.value; renderGrid(); });

	renderGrid();

	// Sélectionne automatiquement le premier avatar disponible, pour qu'un aperçu
	// s'affiche dès l'arrivée sur l'écran plutôt qu'un cadre vide.
	if (!state.avatarGallerySelection && catalog.length > 0) {
		state.avatarGallerySelection = catalog[0];
		renderGrid();
	}
	renderAvatarPreview();
}

function initAvatarModeToggle() {
	el("modeGalleryBtn").addEventListener("click", () => {
		state.avatarMode = "gallery";
		el("modeGalleryBtn").classList.add("active");
		el("modeCustomBtn").classList.remove("active");
		el("galleryMode").classList.remove("hidden");
		el("customMode").classList.add("hidden");
		renderAvatarPreview();
	});
	el("modeCustomBtn").addEventListener("click", () => {
		state.avatarMode = "custom";
		el("modeCustomBtn").classList.add("active");
		el("modeGalleryBtn").classList.remove("active");
		el("customMode").classList.remove("hidden");
		el("galleryMode").classList.add("hidden");
		renderAvatarPreview();
	});
}

// ---------- Étape 1 : identité ----------
function initStep1() {
	// Âges de 6 à 99 ans, 24 sélectionné par défaut (cohérent avec l'exemple de la maquette).
	const ageSelect = el("fAge");
	for (let a = 6; a <= 99; a++) {
		const opt = document.createElement("option");
		opt.value = String(a);
		opt.textContent = a + " ans";
		if (a === state.age) opt.selected = true;
		ageSelect.appendChild(opt);
	}

	const colorPicker = el("colorPicker");
	IDENTITY_COLORS.forEach((color, i) => {
		const sw = document.createElement("div");
		sw.className = "swatch" + (i === 0 ? " selected" : "");
		sw.style.background = color;
		sw.style.color = color;
		sw.addEventListener("click", () => {
			state.identityColor = color;
			document.querySelectorAll("#colorPicker .swatch").forEach((s) => s.classList.remove("selected"));
			sw.classList.add("selected");
		});
		colorPicker.appendChild(sw);
	});

	el("btnStep1Next").addEventListener("click", () => {
		const name = el("fPrenom").value.trim();
		if (!name) {
			el("nameError").textContent = "Merci d'indiquer votre prénom.";
			el("nameError").classList.remove("hidden");
			return;
		}
		el("nameError").classList.add("hidden");
		state.name = name;
		state.age = parseInt(el("fAge").value, 10);
		el("step1").classList.add("hidden");
		el("step2").classList.remove("hidden");
		el("joinSubtitle").textContent = "Créez votre avatar";
		renderAvatarPreview();
	});
}

// ---------- Étape 2 : avatar ----------
function renderAvatarOptions() {
	const container = el("avatarOptions");
	container.innerHTML = "";

	function addOption(value, content, isSelected, onClick, isColor) {
		const box = document.createElement("div");
		box.className = "option-swatch" + (isSelected ? " selected" : "");
		if (isColor) box.style.background = content;
		else box.textContent = content;
		box.addEventListener("click", () => {
			onClick(value);
			renderAvatarPreview();
			renderAvatarOptions();
		});
		container.appendChild(box);
	}

	if (state.activeAvatarTab === "peau") {
		SKIN_COLORS.forEach((c) => addOption(c, c, state.avatarCustom.skinColor === c,
			(v) => { state.avatarCustom.skinColor = v; }, true));
	} else if (state.activeAvatarTab === "cheveux") {
		HAIR_STYLES.forEach((h) => addOption(h.id, h.label, state.avatarCustom.hairStyle === h.id,
			(v) => { state.avatarCustom.hairStyle = v; }, false));
		// Séparateur visuel simple entre styles et couleurs (nouvelle ligne via largeur 100%)
		const sep = document.createElement("div");
		sep.style.flexBasis = "100%";
		container.appendChild(sep);
		HAIR_COLORS.forEach((c) => addOption(c, c, state.avatarCustom.hairColor === c,
			(v) => { state.avatarCustom.hairColor = v; }, true));
	} else if (state.activeAvatarTab === "accessoire") {
		ACCESSORIES.forEach((a) => addOption(a.id, a.label, state.avatarCustom.accessory === a.id,
			(v) => { state.avatarCustom.accessory = v; }, false));
	}
}

function initStep2() {
	document.querySelectorAll(".avatar-tab").forEach((tab) => {
		tab.addEventListener("click", () => {
			document.querySelectorAll(".avatar-tab").forEach((t) => t.classList.remove("active"));
			tab.classList.add("active");
			state.activeAvatarTab = tab.dataset.tab;
			renderAvatarOptions();
		});
	});
	renderAvatarOptions();
	initAvatarModeToggle();
	initAvatarGallery();

	el("btnJoin").addEventListener("click", joinGame);
}

// Construit l'objet de configuration d'avatar à envoyer au serveur, selon le mode
// actif - une image de galerie (id + fichier) ou une config générée (SVG).
function buildAvatarConfigForSubmit() {
	if (state.avatarMode === "gallery" && state.avatarGallerySelection) {
		return { type: "gallery", id: state.avatarGallerySelection.id, filename: state.avatarGallerySelection.filename };
	}
	return { type: "custom", ...state.avatarCustom };
}

// ---------- Envoi de l'inscription ----------
async function joinGame() {
	const btn = el("btnJoin");
	btn.disabled = true;
	btn.textContent = "Inscription...";
	try {
		const res = await fetch(`/api/games/${state.gameId}/join`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({
				name: state.name,
				declaredAge: state.age,
				favoriteColor: state.identityColor,
				avatarConfigJson: JSON.stringify(buildAvatarConfigForSubmit()),
			}),
		});
		if (!res.ok) {
			const body = await res.json().catch(() => ({}));
			throw new Error(body.error || `Erreur ${res.status}`);
		}
		// Sauvegarde locale : ce téléphone retient qui il est pour cette partie
		// (utile pour les futures phases : rejoindre à nouveau, échanges...).
		const player = await res.json();
		localStorage.setItem(`geco_player_${state.gameId}`, JSON.stringify(player));

		el("step2").classList.add("hidden");
		el("stepDone").classList.remove("hidden");
		el("joinSubtitle").textContent = "Inscription réussie";
		el("doneTitle").textContent = `Bienvenue, ${state.name} !`;
		el("avatarPreviewDone").innerHTML = "";
		el("avatarPreviewDone").appendChild(el("avatarPreview").firstElementChild.cloneNode(true));
	} catch (err) {
		btn.disabled = false;
		btn.textContent = "Rejoindre la partie !";
		alert("Impossible de rejoindre la partie : " + err.message);
	}
}

// ---------- Vérification de la partie et initialisation ----------
async function init() {
	const params = new URLSearchParams(location.search);
	state.gameId = params.get("gameId");

	if (!state.gameId) {
		el("joinError").classList.remove("hidden");
		el("joinSubtitle").textContent = "Lien invalide";
		return;
	}

	try {
		const res = await fetch(`/api/games/${state.gameId}`);
		if (!res.ok) throw new Error("not found");
		const game = await res.json();
		el("joinSubtitle").textContent = game.description
			? `Rejoindre : ${game.description}`
			: "Bienvenue dans la partie !";
	} catch (err) {
		el("joinError").classList.remove("hidden");
		el("joinSubtitle").textContent = "Partie introuvable";
		return;
	}

	el("step1").classList.remove("hidden");
	initStep1();
	initStep2();
}

init();
