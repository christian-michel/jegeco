// avatars-catalog.js — Catalogue des avatars disponibles, avec leurs caractéristiques
// pour le système de filtre (genre / âge / teint).
//
// Format d'une entrée :
//   { id, filename, genre, ageCategory, skinTone, skinToneLabel }
//   - genre        : "homme" | "femme" | "neutre"
//   - ageCategory  : "enfant" | "adulte" | "senior"
//   - skinTone     : code hex utilisé pour la pastille du filtre (purement visuel)
//   - skinToneLabel: libellé affiché ("claire" / "mate" / "foncée"...)
//
// Les fichiers images doivent être déposés dans ce même dossier
// (public/avatars/), sous le nom exact indiqué par "filename". Une image
// manquante affiche un repli automatique (avatar généré, voir player.js) plutôt
// que de casser la galerie.
//
// ⚠️ Entrées ci-dessous PROVISOIRES (dossier vide au démarrage) : à remplacer une
// fois vos vraies images reçues. Le format d'entrée, lui, ne change pas.

const AVATARS_CATALOG = [
	{ id: "demo1", filename: "avatar_demo1.png", genre: "femme", ageCategory: "adulte", skinTone: "#F1C27D", skinToneLabel: "claire" },
	{ id: "demo2", filename: "avatar_demo2.png", genre: "homme", ageCategory: "adulte", skinTone: "#C68642", skinToneLabel: "mate" },
	{ id: "demo3", filename: "avatar_demo3.png", genre: "femme", ageCategory: "enfant", skinTone: "#8D5524", skinToneLabel: "foncée" },
	{ id: "demo4", filename: "avatar_demo4.png", genre: "homme", ageCategory: "senior", skinTone: "#F1C27D", skinToneLabel: "claire" },
	{ id: "demo5", filename: "avatar_demo5.png", genre: "neutre", ageCategory: "adulte", skinTone: "#C68642", skinToneLabel: "mate" },
	{ id: "demo6", filename: "avatar_demo6.png", genre: "femme", ageCategory: "senior", skinTone: "#8D5524", skinToneLabel: "foncée" },
];
