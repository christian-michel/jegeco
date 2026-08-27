package jyt.geconomicus.helper.server;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Contenu de démonstration des trois catalogues étape 3 (mode smartphone,
 * écran Paramètres) - voir {@link CatalogService}. Utilisé UNIQUEMENT au tout
 * premier lancement (si le fichier JSON correspondant n'existe pas encore) :
 * une fois le catalogue édité ou remplacé par l'animateur, ce contenu n'est
 * plus jamais relu.
 * <p>
 * ⚠️ Entrées PROVISOIRES, même principe que AVATARS_CATALOG côté front
 * (dossier d'images vide au départ) : à remplacer une fois les vraies images
 * reçues (104 illustrations de cartes, 4 fonds, ~100 avatars) - le format
 * d'entrée, lui, ne change pas. Les "niveau" utilisés reprennent les 4 niveaux
 * confirmés avec l'utilisateur le 27/08/2026 (faible/moyenne/forte/tresforte,
 * conformes aux règles officielles à 4 paquets de cartes) - voir le point
 * encore ouvert dans le cahier des charges (§5.1) sur l'impact de ce 4ᵉ
 * niveau dans le MOTEUR de jeu (StatsService/Event.java), qui reste, lui, une
 * décision séparée non traitée ici : ces catalogues ne font que décrire des
 * visuels/métadonnées, ils ne changent aucune règle.
 * <p>
 * Multilinguisme (remonté par un utilisateur le 27/08/2026) : deux familles de
 * champs bien distinctes, pour rester cohérent avec le reste de l'app (voir
 * i18n.js) sans pour autant passer par les fichiers .po pour du contenu de
 * DONNÉES (par opposition au texte fixe de l'interface) :
 * <ul>
 * <li>les champs à valeurs fixes (niveau, secteur, règle, genre, tranche
 *     d'âge) restent un simple code ("faible", "alimentation"...), traduit à
 *     l'affichage via des clés .po dédiées (catalog.level.*, catalog.sector.*,
 *     catalog.rule.*, catalog.avatar_genre.*, catalog.avatar_age.*) - un
 *     ensemble fermé et connu à l'avance, comme le reste de l'interface ;</li>
 * <li>les champs de texte libre affichés au joueur (nom d'une carte,
 *     étiquette d'un visuel) sont une {@code Map<code langue, texte>}
 *     directement dans le catalogue (ex. {"fr":"Blé","en":"Wheat"}) plutôt
 *     qu'une clé .po : l'animateur doit pouvoir les éditer directement depuis
 *     la zoombox (§5.3) sans toucher aux fichiers de langue de l'application,
 *     et pour n'importe quelle langue installée (pas seulement fr/en).</li>
 * </ul>
 */
final class CatalogSeeds
{
	private CatalogSeeds()
	{
		// Classe utilitaire, jamais instanciée.
	}

	static List<LinkedHashMap<String, Object>> seedCards()
	{
		return List.of(
				card("carte_001", "faible", "alimentation", name("Blé", "Wheat"), "aucune", "visuel_001"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				card("carte_002", "faible", "alimentation", name("Lait", "Milk"), "aucune", "visuel_002"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				card("carte_003", "moyenne", "agriculture", name("Vache", "Cow"), "aucune", "visuel_003"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				card("carte_004", "forte", "ressources", name("Bois", "Wood"), "aucune", "visuel_004"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				card("carte_005", "tresforte", "ressources", name("Cuivre", "Copper"), "aucune", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Petite table {code langue -> texte} pour un champ multilingue de catalogue (nom/étiquette). */
	private static LinkedHashMap<String, Object> name(final String pFr, final String pEn)
	{
		final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
		m.put("fr", pFr); //$NON-NLS-1$
		m.put("en", pEn); //$NON-NLS-1$
		return m;
	}

	private static LinkedHashMap<String, Object> card(final String pId, final String pNiveau, final String pSecteur,
			final LinkedHashMap<String, Object> pNom, final String pRegle, final String pVisualId)
	{
		final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
		m.put("id", pId); //$NON-NLS-1$
		m.put("niveau", pNiveau); //$NON-NLS-1$
		m.put("secteur", pSecteur); //$NON-NLS-1$
		m.put("nom", pNom); //$NON-NLS-1$
		m.put("regle", pRegle); //$NON-NLS-1$
		m.put("visualId", pVisualId); //$NON-NLS-1$
		return m;
	}

	static List<LinkedHashMap<String, Object>> seedVisuals()
	{
		return List.of(
				visual("visuel_001", "carte_001.png", "faible", name("Épi de blé doré", "Golden wheat ear")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				visual("visuel_002", "carte_002.png", "faible", name("Bouteille de lait", "Bottle of milk")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				visual("visuel_003", "carte_003.png", "moyenne", name("Vache dans un pré", "Cow in a meadow")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				visual("visuel_004", "carte_004.png", "forte", name("Tas de rondins", "Pile of logs")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				visual("visuel_005", "carte_005.png", "tresforte", name("Lingot de cuivre", "Copper ingot"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static LinkedHashMap<String, Object> visual(final String pId, final String pFilename, final String pNiveau,
			final LinkedHashMap<String, Object> pEtiquette)
	{
		final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
		m.put("id", pId); //$NON-NLS-1$
		m.put("filename", pFilename); //$NON-NLS-1$
		m.put("niveau", pNiveau); //$NON-NLS-1$
		m.put("etiquette", pEtiquette); //$NON-NLS-1$
		return m;
	}

	// Reprend telles quelles les entrées de démonstration déjà présentes dans
	// public/avatars/avatars-catalog.js (même id/filename/genre/ageCategory/
	// skinTone/skinToneLabel) : une seule source de vérité pour le contenu de
	// démonstration, même si les DEUX fichiers coexistent encore aujourd'hui
	// (voir avatars-catalog.js pour la galerie d'inscription joueur, qui n'est
	// pas encore branchée sur ce catalogue serveur - unification prévue plus
	// tard, voir §5.2 du cahier des charges).
	static List<LinkedHashMap<String, Object>> seedAvatars()
	{
		return List.of(
				avatar("demo1", "avatar_demo1.png", "femme", "adulte", "#F1C27D", "claire"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				avatar("demo2", "avatar_demo2.png", "homme", "adulte", "#C68642", "mate"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				avatar("demo3", "avatar_demo3.png", "femme", "enfant", "#8D5524", "foncée"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				avatar("demo4", "avatar_demo4.png", "homme", "senior", "#F1C27D", "claire"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				avatar("demo5", "avatar_demo5.png", "neutre", "adulte", "#C68642", "mate"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				avatar("demo6", "avatar_demo6.png", "femme", "senior", "#8D5524", "foncée")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	private static LinkedHashMap<String, Object> avatar(final String pId, final String pFilename, final String pGenre,
			final String pAgeCategory, final String pSkinTone, final String pSkinToneLabel)
	{
		final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
		m.put("id", pId); //$NON-NLS-1$
		m.put("filename", pFilename); //$NON-NLS-1$
		m.put("genre", pGenre); //$NON-NLS-1$
		m.put("ageCategory", pAgeCategory); //$NON-NLS-1$
		m.put("skinTone", pSkinTone); //$NON-NLS-1$
		m.put("skinToneLabel", pSkinToneLabel); //$NON-NLS-1$
		return m;
	}
}
