// icons.js — Petit jeu d'icônes SVG en ligne, style trait fin (inspiré de Lucide).
// Choix volontaire : pas de dépendance externe (police d'icônes, CDN) pour rester
// dans l'esprit "aucun outil de build requis" du projet. Chaque icône est une simple
// chaîne SVG, insérée dans les éléments portant l'attribut data-icon="...".

const ICONS = {
	"plus-circle": '<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/>',
	"folder": '<path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6Z"/>',
	"bar-chart": '<path d="M4 20V10M12 20V4M20 20v-7"/>',
	"settings": '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
	"arrow-left": '<path d="M19 12H5M12 19l-7-7 7-7"/>',
	"activity": '<path d="M22 12h-4l-3 8-6-16-3 8H2"/>',
	"users": '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
	"calendar": '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
	"bank": '<path d="M3 21h18M4 21V10M20 21V10M2 10l10-6 10 6M6 10v11M10 10v11M14 10v11M18 10v11"/>',
	"leaf": '<path d="M11 20A7 7 0 0 1 4 13c0-6 7-11 16-11 0 9-5 16-11 16-1.3 0-2.5-.3-3.6-.8Z"/><path d="M4 20c3-4 6-7 12-12"/>',
	"clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/>',
	"credit": '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/>',
	"wifi": '<path d="M5 12.5a11 11 0 0 1 14 0M8.5 16a6 6 0 0 1 7 0"/><circle cx="12" cy="20" r="1"/>',
	"book": '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V4H6.5A2.5 2.5 0 0 0 4 6.5v13Z"/><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>',
	"x": '<path d="M18 6 6 18M6 6l12 12"/>',
};

/**
 * Remplace tous les éléments portant [data-icon] par le SVG correspondant.
 * Appelé une fois au chargement, puis à nouveau après tout rendu dynamique
 * (les nouveaux éléments injectés via innerHTML n'ont pas encore leur SVG).
 */
function renderIcons(root = document) {
	root.querySelectorAll("[data-icon]").forEach((el) => {
		const name = el.getAttribute("data-icon");
		const path = ICONS[name];
		if (!path) return;
		el.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;
		el.removeAttribute("data-icon"); // évite un second rendu si la fonction est rappelée sur le même élément
	});
}
