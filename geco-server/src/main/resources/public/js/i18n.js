// i18n.js — Internationalisation, fichiers .po (format standard des traducteurs,
// compatible Poedit et autres outils classiques), sans étape de build.
//
// Fonctionnement :
//   - Chaque élément à traduire porte un attribut data-i18n="cle_msgid".
//   - Au chargement, on récupère la langue à utiliser (préférence sauvegardée >
//     langue du navigateur > français par défaut), on charge le fichier .po
//     correspondant (lang/xx.po), on le parse, et on remplace le texte de chaque
//     élément data-i18n par la traduction trouvée (ou on garde le texte français
//     d'origine si la clé est absente - jamais d'écran vide par traduction
//     manquante).
//   - Un petit sélecteur de drapeaux (en haut à droite) permet de changer de
//     langue à la volée, sans recharger la page.
//
// Périmètre actuel (preuve de concept) : la barre latérale de navigation et
// l'écran "Connexion joueurs" sont traduits en français et anglais. Le reste de
// l'application (écran Nouvelle partie, tableau de bord, rapports, écran mobile
// join.html) n'est pas encore couvert - voir docs/08-etape2-i18n.md pour le plan
// d'extension complet (autres écrans + espagnol/italien/portugais/allemand).

(function () {
	"use strict";

	// Remonté par un utilisateur (écran Paramètres) : cette liste n'est plus figée
	// en dur - "fr"/"en" restent connus d'avance (fournis avec l'application),
	// mais les langues personnalisées ajoutées après coup (voir LanguageService
	// côté serveur) viennent s'y ajouter dynamiquement au chargement, voir
	// refreshSupportedLangs(). Les drapeaux/libellés des langues personnalisées
	// sont génériques (🌐 + le code) faute de mieux les connaître à l'avance.
	let SUPPORTED_LANGS = [
		{ code: "fr", flag: "🇫🇷", label: "Français" },
		{ code: "en", flag: "🇬🇧", label: "English" },
	];
	const DEFAULT_LANG = "fr";
	const STORAGE_KEY = "geco_lang";
	// Langue par défaut configurée côté serveur (écran Paramètres) - a la priorité
	// sur la détection navigateur, mais pas sur un choix déjà fait dans CE
	// navigateur (voir detectLang()). "fr" tant que /api/settings n'a pas encore
	// répondu (voir refreshServerDefaultLang(), appelée avant detectLang()).
	let serverDefaultLang = DEFAULT_LANG;

	// Remonté par un utilisateur : table de référence code de langue -> drapeau/
	// libellé, pour qu'une langue tout juste importée (ex. "es.po") affiche
	// automatiquement le bon drapeau plutôt qu'un globe générique. Couvre les
	// codes ISO 639-1 les plus courants, chacun associé à UN pays représentatif -
	// beaucoup de langues sont parlées dans plusieurs pays (l'espagnol dans toute
	// l'Amérique latine, l'arabe dans une vingtaine de pays...), ce choix est donc
	// une convention par défaut, pas une vérité absolue. C'est précisément pour
	// ça qu'un réglage manuel existe (voir l'écran Paramètres, PUT
	// /api/languages/{code}/display) : si cette table se trompe pour un cas
	// précis (ex. "es" pour le Mexique plutôt que l'Espagne), l'animateur peut
	// corriger le drapeau/libellé sans toucher au code.
	const LANGUAGE_COUNTRY_MAP = {
		fr: { flag: "🇫🇷", label: "Français" }, en: { flag: "🇬🇧", label: "English" },
		es: { flag: "🇪🇸", label: "Español" }, de: { flag: "🇩🇪", label: "Deutsch" },
		it: { flag: "🇮🇹", label: "Italiano" }, pt: { flag: "🇵🇹", label: "Português" },
		"pt-br": { flag: "🇧🇷", label: "Português (Brasil)" }, nl: { flag: "🇳🇱", label: "Nederlands" },
		pl: { flag: "🇵🇱", label: "Polski" }, ro: { flag: "🇷🇴", label: "Română" },
		sv: { flag: "🇸🇪", label: "Svenska" }, no: { flag: "🇳🇴", label: "Norsk" },
		nb: { flag: "🇳🇴", label: "Norsk bokmål" }, da: { flag: "🇩🇰", label: "Dansk" },
		fi: { flag: "🇫🇮", label: "Suomi" }, is: { flag: "🇮🇸", label: "Íslenska" },
		el: { flag: "🇬🇷", label: "Ελληνικά" }, tr: { flag: "🇹🇷", label: "Türkçe" },
		ru: { flag: "🇷🇺", label: "Русский" }, uk: { flag: "🇺🇦", label: "Українська" },
		be: { flag: "🇧🇾", label: "Беларуская" }, cs: { flag: "🇨🇿", label: "Čeština" },
		sk: { flag: "🇸🇰", label: "Slovenčina" }, hu: { flag: "🇭🇺", label: "Magyar" },
		bg: { flag: "🇧🇬", label: "Български" }, hr: { flag: "🇭🇷", label: "Hrvatski" },
		sr: { flag: "🇷🇸", label: "Српски" }, bs: { flag: "🇧🇦", label: "Bosanski" },
		sl: { flag: "🇸🇮", label: "Slovenščina" }, mk: { flag: "🇲🇰", label: "Македонски" },
		sq: { flag: "🇦🇱", label: "Shqip" }, lt: { flag: "🇱🇹", label: "Lietuvių" },
		lv: { flag: "🇱🇻", label: "Latviešu" }, et: { flag: "🇪🇪", label: "Eesti" },
		mt: { flag: "🇲🇹", label: "Malti" }, ga: { flag: "🇮🇪", label: "Gaeilge" },
		cy: { flag: "🏴", label: "Cymraeg" }, eu: { flag: "🇪🇸", label: "Euskara" },
		ca: { flag: "🇪🇸", label: "Català" }, gl: { flag: "🇪🇸", label: "Galego" },
		af: { flag: "🇿🇦", label: "Afrikaans" }, sw: { flag: "🇰🇪", label: "Kiswahili" },
		am: { flag: "🇪🇹", label: "አማርኛ" }, ha: { flag: "🇳🇬", label: "Hausa" },
		yo: { flag: "🇳🇬", label: "Yorùbá" }, ig: { flag: "🇳🇬", label: "Igbo" },
		zu: { flag: "🇿🇦", label: "isiZulu" }, xh: { flag: "🇿🇦", label: "isiXhosa" },
		st: { flag: "🇱🇸", label: "Sesotho" }, sn: { flag: "🇿🇼", label: "chiShona" },
		mg: { flag: "🇲🇬", label: "Malagasy" }, rw: { flag: "🇷🇼", label: "Kinyarwanda" },
		so: { flag: "🇸🇴", label: "Soomaali" }, ar: { flag: "🇸🇦", label: "العربية" },
		he: { flag: "🇮🇱", label: "עברית" }, iw: { flag: "🇮🇱", label: "עברית" },
		fa: { flag: "🇮🇷", label: "فارسی" }, ur: { flag: "🇵🇰", label: "اردو" },
		ps: { flag: "🇦🇫", label: "پښتو" }, hi: { flag: "🇮🇳", label: "हिन्दी" },
		bn: { flag: "🇧🇩", label: "বাংলা" }, ta: { flag: "🇱🇰", label: "தமிழ்" },
		te: { flag: "🇮🇳", label: "తెలుగు" }, ml: { flag: "🇮🇳", label: "മലയാളം" },
		mr: { flag: "🇮🇳", label: "मराठी" }, gu: { flag: "🇮🇳", label: "ગુજરાતી" },
		pa: { flag: "🇮🇳", label: "ਪੰਜਾਬੀ" }, ne: { flag: "🇳🇵", label: "नेपाली" },
		si: { flag: "🇱🇰", label: "සිංහල" }, th: { flag: "🇹🇭", label: "ไทย" },
		my: { flag: "🇲🇲", label: "မြန်မာဘာသာ" }, km: { flag: "🇰🇭", label: "ខ្មែរ" },
		lo: { flag: "🇱🇦", label: "ລາວ" }, vi: { flag: "🇻🇳", label: "Tiếng Việt" },
		id: { flag: "🇮🇩", label: "Bahasa Indonesia" }, ms: { flag: "🇲🇾", label: "Bahasa Melayu" },
		tl: { flag: "🇵🇭", label: "Filipino" }, zh: { flag: "🇨🇳", label: "中文" },
		"zh-tw": { flag: "🇹🇼", label: "中文（台灣）" }, ja: { flag: "🇯🇵", label: "日本語" },
		ko: { flag: "🇰🇷", label: "한국어" }, mn: { flag: "🇲🇳", label: "Монгол" },
		ka: { flag: "🇬🇪", label: "ქართული" }, hy: { flag: "🇦🇲", label: "Հայերեն" },
		az: { flag: "🇦🇿", label: "Azərbaycan" }, kk: { flag: "🇰🇿", label: "Қазақ" },
		uz: { flag: "🇺🇿", label: "Oʻzbek" }, ky: { flag: "🇰🇬", label: "Кыргызча" },
		tg: { flag: "🇹🇯", label: "Тоҷикӣ" }, tk: { flag: "🇹🇲", label: "Türkmen" },
	};

	async function refreshSupportedLangs() {
		try {
			const res = await fetch("/api/languages");
			if (!res.ok) return;
			const data = await res.json();
			const overrides = data.overrides || {};
			// Priorité : réglage manuel (écran Paramètres) > table de référence
			// ci-dessus > repli générique (globe + code brut) si le code est
			// inconnu des deux.
			const custom = (data.custom || []).map((code) => {
				const override = overrides[code];
				const known = LANGUAGE_COUNTRY_MAP[code.toLowerCase()];
				return {
					code,
					flag: (override && override.flag) || (known && known.flag) || "🌐",
					label: (override && override.label) || (known && known.label) || code,
				};
			});
			SUPPORTED_LANGS = [
				{ code: "fr", flag: "🇫🇷", label: "Français" },
				{ code: "en", flag: "🇬🇧", label: "English" },
				...custom,
			];
		} catch (err) {
			// Pas grave : on continue avec fr/en, les seules garanties d'être présentes.
			console.warn("Liste des langues personnalisées indisponible.", err);
		}
	}

	async function refreshServerDefaultLang() {
		try {
			const res = await fetch("/api/settings");
			if (!res.ok) return;
			const data = await res.json();
			if (data.defaultLanguage) serverDefaultLang = data.defaultLanguage;
		} catch (err) {
			console.warn("Langue par défaut du serveur indisponible, repli sur le français.", err);
		}
	}

	// --- Détection de la langue à utiliser ---
	function detectLang() {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved && SUPPORTED_LANGS.some((l) => l.code === saved)) return saved;

		// Remonté par un utilisateur : la langue par défaut choisie dans les
		// Paramètres (réglage de l'installation) passe avant la détection
		// automatique du navigateur - mais seulement si aucun choix n'a déjà été
		// fait manuellement dans CE navigateur (voir ci-dessus).
		if (SUPPORTED_LANGS.some((l) => l.code === serverDefaultLang)) return serverDefaultLang;

		// navigator.language est du type "fr-FR", "en-US"... on ne garde que les 2
		// premières lettres, et on ne bascule que si la langue est effectivement
		// couverte - sinon on reste sur le français par défaut, comme demandé.
		const browserLang = (navigator.language || "").slice(0, 2).toLowerCase();
		if (SUPPORTED_LANGS.some((l) => l.code === browserLang)) return browserLang;
		return DEFAULT_LANG;
	}

	// --- Lecteur .po minimal ---
	// Le format .po est une suite de blocs "msgid "..."" / "msgstr "..."". On ignore
	// les commentaires (lignes commençant par #) et les métadonnées d'en-tête
	// (premier bloc, msgid vide). Gère les guillemets échappés (\") et les chaînes
	// réparties sur plusieurs lignes consécutives (concaténation automatique, comme
	// le fait le format .po).
	function parsePo(text) {
		const translations = {};
		const lines = text.split(/\r?\n/);
		let currentMsgid = null;
		let currentMsgstr = null;
		let mode = null; // "msgid" | "msgstr" | null

		function unquote(line) {
			const match = line.match(/"((?:[^"\\]|\\.)*)"/);
			if (!match) return "";
			return match[1].replace(/\\"/g, '"').replace(/\\n/g, "\n");
		}
		function flush() {
			if (currentMsgid !== null && currentMsgid !== "" && currentMsgstr !== null) {
				translations[currentMsgid] = currentMsgstr;
			}
			currentMsgid = null;
			currentMsgstr = null;
		}

		for (const rawLine of lines) {
			const line = rawLine.trim();
			if (!line || line.startsWith("#")) continue;
			if (line.startsWith("msgid ")) {
				flush();
				currentMsgid = unquote(line.slice(6));
				mode = "msgid";
			} else if (line.startsWith("msgstr ")) {
				currentMsgstr = unquote(line.slice(7));
				mode = "msgstr";
			} else if (line.startsWith('"')) {
				// Suite d'une chaîne multi-lignes : on complète le champ en cours.
				const piece = unquote(line);
				if (mode === "msgid") currentMsgid += piece;
				else if (mode === "msgstr") currentMsgstr += piece;
			}
		}
		flush();
		return translations;
	}

	let currentTranslations = {};
	let activeLang = DEFAULT_LANG;

	/** Traduit une clé (utilisable depuis app.js pour du texte généré dynamiquement).
	 * pVars (optionnel) : remplace chaque "{nom}" du texte traduit par la valeur
	 * correspondante - ex. t("newgame.summary_turns_value", {n: 10}) avec un msgstr
	 * "{n} tours" donne "10 tours". */
	function t(key, pVars) {
		let text = currentTranslations[key] || key;
		if (pVars) {
			for (const varName of Object.keys(pVars))
				text = text.replaceAll(`{${varName}}`, pVars[varName]);
		}
		return text;
	}

	const changeListeners = [];

	function applyTranslations() {
		document.querySelectorAll("[data-i18n]").forEach((el) => {
			const key = el.getAttribute("data-i18n");
			const translated = currentTranslations[key];
			if (translated) el.textContent = translated;
			// Si la clé est absente de la traduction, on ne touche pas au texte
			// existant (déjà en français dans le HTML) - jamais d'écran vide.
		});
		// data-i18n-html : mêmes principes, mais pour les textes contenant des balises
		// imbriquées (ex. un lien) - le msgstr du .po contient alors le HTML tel quel,
		// les traducteurs conservent la balise sans y toucher (pratique standard en
		// i18n). Sûr ici car les .po viennent uniquement de nos propres fichiers, jamais
		// d'une saisie utilisateur.
		document.querySelectorAll("[data-i18n-html]").forEach((el) => {
			const key = el.getAttribute("data-i18n-html");
			const translated = currentTranslations[key];
			if (translated) el.innerHTML = translated;
		});
		// data-i18n-placeholder / data-i18n-title : mêmes principes, pour les attributs
		// "placeholder" (champs de saisie) et "title" (infobulles) - ajoutés en
		// traitant l'écran "Nouvelle partie", qui en a besoin pour ses nombreux champs
		// et infobulles (ex. "Facteur carte/monnaie").
		document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
			const key = el.getAttribute("data-i18n-placeholder");
			const translated = currentTranslations[key];
			if (translated) el.setAttribute("placeholder", translated);
		});
		document.querySelectorAll("[data-i18n-title]").forEach((el) => {
			const key = el.getAttribute("data-i18n-title");
			const translated = currentTranslations[key];
			if (translated) el.setAttribute("title", translated);
		});
		// Permet à app.js de réagir après chaque (re)traduction - utile par exemple
		// pour remettre à jour un lien dynamique dont le HTML vient d'être remplacé
		// (voir data-i18n-html ci-dessus, qui écrase tout attribut fixé dynamiquement).
		changeListeners.forEach((cb) => cb());
	}

	async function loadLang(langCode) {
		try {
			// Remonté par un utilisateur (écran Paramètres) : une langue "intégrée"
			// (fr/en) est servie depuis /lang/xx.po (fichier du jar) ; une langue
			// personnalisée ajoutée après coup vit sur le disque et est servie
			// séparément depuis /lang-custom/xx.po (voir LanguageService côté
			// serveur) - on essaie d'abord le chemin intégré, celui-ci en repli.
			let res = await fetch(`/lang/${langCode}.po`);
			if (!res.ok) res = await fetch(`/lang-custom/${langCode}.po`);
			if (!res.ok) throw new Error(`HTTP ${res.status}`);
			const text = await res.text();
			currentTranslations = parsePo(text);
		} catch (err) {
			// Fichier absent ou invalide : on retombe silencieusement sur le français
			// (déjà le texte présent dans le HTML), sans bloquer l'application.
			console.warn(`Traduction "${langCode}" indisponible, repli sur le français.`, err);
			currentTranslations = {};
		}
		activeLang = langCode;
		applyTranslations();
		updateFlagSwitcherUI(langCode);
	}

	function setLang(langCode) {
		localStorage.setItem(STORAGE_KEY, langCode);
		loadLang(langCode);
	}

	// --- Sélecteur de drapeaux (haut à droite) ---
	function renderLangMenu() {
		const menu = document.getElementById("geco-lang-menu");
		if (!menu) return;
		menu.innerHTML = SUPPORTED_LANGS.map((l) => `
			<button type="button" data-lang="${l.code}" style="display:flex;align-items:center;gap:0.5rem;width:100%;
				border:none;background:none;padding:0.5rem 0.6rem;border-radius:8px;cursor:pointer;font-size:0.85rem;text-align:left;">
				<span>${l.flag}</span><span>${l.label}</span>
			</button>`).join("");
		menu.querySelectorAll("[data-lang]").forEach((item) => {
			item.addEventListener("click", () => { setLang(item.dataset.lang); menu.style.display = "none"; });
			item.addEventListener("mouseenter", () => { item.style.background = "#f3f4f6"; });
			item.addEventListener("mouseleave", () => { item.style.background = "none"; });
		});
	}

	function injectFlagSwitcher() {
		if (document.getElementById("geco-lang-switcher")) return;
		const wrap = document.createElement("div");
		wrap.id = "geco-lang-switcher";
		wrap.style.cssText = "position:fixed;top:1rem;right:1.25rem;z-index:9998;";
		wrap.innerHTML = `
			<button id="geco-lang-current" type="button" style="border:1px solid #e5e7eb;background:#fff;
				border-radius:999px;padding:0.35rem 0.7rem;font-size:1rem;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,0.08);">
				🇫🇷
			</button>
			<div id="geco-lang-menu" style="display:none;position:absolute;top:2.4rem;right:0;background:#fff;
				border:1px solid #e5e7eb;border-radius:12px;box-shadow:0 8px 24px rgba(0,0,0,0.12);padding:0.4rem;min-width:150px;"></div>`;
		document.body.appendChild(wrap);
		renderLangMenu();

		const btn = document.getElementById("geco-lang-current");
		const menu = document.getElementById("geco-lang-menu");
		btn.addEventListener("click", () => { menu.style.display = menu.style.display === "none" ? "block" : "none"; });
		document.addEventListener("click", (e) => { if (!wrap.contains(e.target)) menu.style.display = "none"; });
	}

	function updateFlagSwitcherUI(langCode) {
		const btn = document.getElementById("geco-lang-current");
		const lang = SUPPORTED_LANGS.find((l) => l.code === langCode);
		if (btn && lang) btn.textContent = lang.flag;
	}

	// API minimale exposée pour app.js (traduction de texte généré dynamiquement,
	// et connaissance de la langue active pour construire des liens vers la
	// documentation dans la bonne langue).
	window.GecoI18n = {
		t, setLang,
		getSupportedLangs: () => SUPPORTED_LANGS,
		getActiveLang: () => activeLang,
		onChange: (cb) => changeListeners.push(cb),
		// Remonté par un utilisateur (écran Paramètres) : après l'ajout d'une
		// langue personnalisée, l'écran Paramètres appelle ceci pour que la liste
		// (et le sélecteur de drapeaux) la propose sans recharger la page.
		refreshLanguages: async () => {
			await refreshSupportedLangs();
			renderLangMenu();
		},
	};

	document.addEventListener("DOMContentLoaded", async () => {
		// Remonté par un utilisateur : la langue par défaut (Paramètres) et les
		// langues personnalisées doivent être connues AVANT de détecter/charger la
		// langue à utiliser, sinon un premier chargement pourrait ignorer un choix
		// tout juste fait par l'animateur.
		await Promise.all([refreshSupportedLangs(), refreshServerDefaultLang()]);
		injectFlagSwitcher();
		loadLang(detectLang());
	});
})();
