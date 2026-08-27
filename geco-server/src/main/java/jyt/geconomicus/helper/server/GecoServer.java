package jyt.geconomicus.helper.server;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import jyt.geconomicus.helper.Event;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;
import jyt.geconomicus.helper.server.Dtos.AddPlayerRequest;
import jyt.geconomicus.helper.server.Dtos.CreateGameRequest;
import jyt.geconomicus.helper.server.Dtos.EditEventRequest;
import jyt.geconomicus.helper.server.Dtos.EnabledRequest;
import jyt.geconomicus.helper.server.Dtos.EventDto;
import jyt.geconomicus.helper.server.Dtos.GameDetailDto;
import jyt.geconomicus.helper.server.Dtos.GameSummaryDto;
import jyt.geconomicus.helper.server.Dtos.JoinRequest;
import jyt.geconomicus.helper.server.Dtos.PlayerDto;
import jyt.geconomicus.helper.server.Dtos.RecordEventRequest;
import jyt.geconomicus.helper.server.plugins.PluginManifest;
import jyt.geconomicus.helper.server.plugins.PluginPreferences;
import jyt.geconomicus.helper.server.plugins.PluginRegistry;

/**
 * Point d'entrée du serveur web local pour Ğeconomicus Helper (étape 2/3).
 * <p>
 * Sert :
 * <ul>
 * <li>une API REST (/api/...) qui expose le moteur métier de geco-engine ;</li>
 * <li>un canal WebSocket (/ws) qui diffuse en temps réel les événements de jeu à
 *     tous les clients connectés (écran de stats, futurs clients smartphones) ;</li>
 * <li>les fichiers statiques du front HTML/CSS/JS (dossier "public" du classpath).</li>
 * </ul>
 * Usage : {@code java -jar geco-server.jar [port]} (port par défaut : 7000).
 */
public class GecoServer
{
	// Port HTTP par défaut : 7000 (arbitraire, choisi libre pour ne pas entrer en conflit
	// avec les ports standards 8080/3000 souvent déjà utilisés sur les machines de développement).
	private static final int DEFAULT_PORT = 7000;
	// Nom de l'unité de persistance JPA, doit correspondre à persistence.xml (module geco-engine).
	public static final String DB_DEFAULT_NAME = "geco"; //$NON-NLS-1$

	// Connexions WebSocket actives, pour diffuser les mises à jour en temps réel.
	// ConcurrentHashMap.newKeySet() : plusieurs clients peuvent se (dé)connecter en parallèle
	// (un thread Javalin par requête), il faut donc une structure thread-safe.
	private final Set<WsContext> mSessions = ConcurrentHashMap.newKeySet();

	// Le service métier est injecté plutôt qu'instancié ici : ça permet de le tester
	// indépendamment du serveur HTTP (pas besoin de démarrer Javalin pour tester GameService).
	private final GameService mGameService;
	// Service de calcul des statistiques (Phase B) : graphiques de masse monétaire et
	// de répartition des richesses, portés depuis StatsFrame.java (Swing).
	private final StatsService mStatsService = new StatsService();
	// Registre des plugins "système d'échange" (voir docs/11-plugin-api-contrat.md) -
	// chargé une fois au démarrage depuis le disque (jamais depuis le réseau, voir
	// PluginRegistry). Ne fait pour l'instant qu'exposer les manifestes en lecture
	// (GET /api/plugins) : le moteur de jeu ne le consulte pas encore.
	private final PluginRegistry mPluginRegistry = new PluginRegistry();
	// Quels plugins l'animateur a choisi d'activer (écran Paramètres) - voir
	// PluginPreferences. Initialisée dans main() une fois les plugins chargés
	// (a besoin de connaître les ids "prêts par défaut").
	private PluginPreferences mPluginPreferences;
	// Réglages globaux (écran Paramètres) : langue par défaut, son - voir AppSettings.
	private final AppSettings mAppSettings = new AppSettings(Path.of("app-settings.json")); //$NON-NLS-1$
	// Vérification de mise à jour (lecture seule, voir UpdateCheckService).
	private final UpdateCheckService mUpdateCheckService = new UpdateCheckService();
	// Langues personnalisées ajoutées par l'animateur (écran Paramètres) - voir
	// LanguageService. Servies statiquement depuis /lang-custom (voir plus bas),
	// ce service ne fait que lister/écrire les fichiers sur le disque.
	private final LanguageService mLanguageService = new LanguageService(Path.of("lang-custom")); //$NON-NLS-1$
	// Sauvegarde complète de la base (écran Paramètres) - demandé par
	// l'utilisateur pour finaliser l'étape 2. Réutilise l'EntityManagerFactory
	// déjà créée pour GameService plutôt que d'en ouvrir une seconde.
	private final BackupService mBackupService;
	// Étape 3, mode smartphone (écran Paramètres) : les trois tableaux de
	// gestion des visuels - Cartes (catalogue logique des types de carte),
	// Visuels (inventaire des fichiers image de fond de carte) et Avatars
	// (repris du catalogue existant, voir avatars-catalog.js) - voir §5.3 du
	// cahier des charges étape 3 et CatalogService. Catalogues de démonstration
	// PROVISOIRES (mêmes précautions que AVATARS_CATALOG côté front) : à
	// remplacer une fois le vrai catalogue (104 cartes, 4 niveaux de fond, ~100
	// avatars) importé - le format des entrées, lui, ne change pas.
	private final CatalogService mCardCatalogService = new CatalogService(Path.of("catalogs/cartes.json"), //$NON-NLS-1$
			CatalogSeeds::seedCards);
	private final CatalogService mVisualCatalogService = new CatalogService(Path.of("catalogs/visuels.json"), //$NON-NLS-1$
			CatalogSeeds::seedVisuals);
	private final CatalogService mAvatarCatalogService = new CatalogService(Path.of("catalogs/avatars.json"), //$NON-NLS-1$
			CatalogSeeds::seedAvatars);

	public GecoServer(final GameService pGameService)
	{
		mGameService = pGameService;
		mBackupService = new BackupService(pGameService.getEntityManagerFactory(), Path.of("backups-tmp")); //$NON-NLS-1$
	}

	/** Résout un des trois catalogues étape 3 par son nom d'URL ; 404 si inconnu. */
	private CatalogService catalogFor(final String pKind)
	{
		return switch (pKind)
		{
			case "cartes" -> mCardCatalogService; //$NON-NLS-1$
			case "visuels" -> mVisualCatalogService; //$NON-NLS-1$
			case "avatars" -> mAvatarCatalogService; //$NON-NLS-1$
			default -> throw new io.javalin.http.NotFoundResponse("Catalogue inconnu : " + pKind); //$NON-NLS-1$
		};
	}

	public static void main(final String[] pArgs)
	{
		// Port configurable en argument de ligne de commande, pratique pour lancer plusieurs
		// instances en parallèle (ex: tester une partie en monnaie dette et une en monnaie libre).
		final int port = pArgs.length > 0 ? Integer.parseInt(pArgs[0]) : DEFAULT_PORT;

		// Une seule EntityManagerFactory pour toute la durée de vie du serveur (coûteuse à créer,
		// thread-safe en lecture) ; chaque requête HTTP crée ensuite son propre EntityManager
		// (léger, non thread-safe) dans GameService - c'est le pattern JPA standard.
		final EntityManagerFactory emf = Persistence.createEntityManagerFactory(DB_DEFAULT_NAME);
		final GameService gameService = new GameService(emf);
		final GecoServer server = new GecoServer(gameService);

		final Javalin app = Javalin.create(config -> {
			// Sert le front HTML/CSS/JS directement depuis les ressources embarquées dans le jar
			// (src/main/resources/public) : pas de serveur web séparé (nginx, etc.) à installer.
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/"; //$NON-NLS-1$
				staticFiles.directory = "/public"; //$NON-NLS-1$
				staticFiles.location = Location.CLASSPATH;
			});
			// Langues personnalisées (écran Paramètres, voir LanguageService) : servies
			// depuis le disque (pas le jar, qui est en lecture seule une fois empaqueté) -
			// i18n.js essaie d'abord /lang/xx.po (intégré), puis /lang-custom/xx.po en repli.
			// Le dossier doit exister AVANT que Javalin ne configure ce chemin, sinon il
			// refuse de démarrer si le dossier est absent au tout premier lancement.
			try
			{
				java.nio.file.Files.createDirectories(Path.of("lang-custom")); //$NON-NLS-1$
			}
			catch (final java.io.IOException e)
			{
				System.out.println("Impossible de créer le dossier lang-custom : " + e.getMessage()); //$NON-NLS-1$
			}
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/lang-custom"; //$NON-NLS-1$
				staticFiles.directory = "lang-custom"; //$NON-NLS-1$
				staticFiles.location = Location.EXTERNAL;
			});
			config.showJavalinBanner = false;
		});

		server.registerRoutes(app);

		// Chargement des plugins "système d'échange" (voir docs/11-plugin-api-contrat.md).
		// Volontairement lu depuis "./plugins" (le dossier de travail au lancement de
		// run.sh est la racine du projet, voir run.sh) plutôt que depuis une route réseau -
		// décision prise avec l'utilisateur après l'audit sécurité, tant qu'il n'y a aucune
		// authentification dans l'application. Un manifeste invalide n'empêche jamais le
		// démarrage : il est simplement ignoré, avec le motif affiché ci-dessous.
		server.mPluginRegistry.loadAll(Path.of("plugins")); //$NON-NLS-1$
		System.out.println("Plugins charges : " + server.mPluginRegistry.getAll().size()); //$NON-NLS-1$
		for (final var manifest : server.mPluginRegistry.getAll())
			System.out.println("  - " + manifest.getId() + " (" + manifest.getSourceDirectory() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (final String error : server.mPluginRegistry.getLoadErrors())
			System.out.println("  ! " + error); //$NON-NLS-1$
		// Préférences d'activation (écran Paramètres) : par défaut, seuls les
		// systèmes déjà "prêts" (dette/libre) sont activés au tout premier
		// démarrage - voir PluginPreferences.
		server.mPluginPreferences = new PluginPreferences(Path.of("plugins-config.json"), //$NON-NLS-1$
				server.mPluginRegistry.getEngineReadyIds());

		// Ferme proprement la connexion à la base H2 quand le serveur s'arrête (Ctrl+C, kill...).
		Runtime.getRuntime().addShutdownHook(new Thread(emf::close));

		app.start(port);
		System.out.println("Geconomicus Helper Server demarre sur http://localhost:" + port); //$NON-NLS-1$
	}

	private void registerRoutes(final Javalin pApp)
	{
		// --- Plugins (systèmes d'échange) ---

		// Lecture seule pour l'instant : liste les manifestes chargés avec succès au
		// démarrage (voir main()). Pas encore utilisé par le front (écran "Nouvelle
		// partie" reste sur ses deux choix "en dur" dette/libre) - cette route sert de
		// base pour vérifier que le chargement fonctionne avant de brancher le reste.
		pApp.get("/api/plugins", ctx -> { //$NON-NLS-1$
			// Synchronise le champ "enabled" de chaque manifeste avec les préférences
			// actuelles avant de répondre (pas stocké dans le manifeste lui-même, voir
			// PluginManifest.enabled).
			for (final PluginManifest manifest : mPluginRegistry.getAll())
				manifest.setEnabled(mPluginPreferences.isEnabled(manifest.getId()));
			ctx.json(mPluginRegistry.getAll());
		});

		// Active/désactive un plugin pour l'écran "Nouvelle partie" (écran
		// Paramètres). Toujours autorisé, même pour un plugin pas encore
		// "engineReady" : ça permet de vérifier que la sélection fonctionne de bout
		// en bout côté interface, avant même que le moteur ne sache le faire
		// tourner (voir PluginManifest.engineReady et le garde-fou correspondant
		// côté écran "Nouvelle partie").
		pApp.put("/api/plugins/{id}/enabled", ctx -> { //$NON-NLS-1$
			final String pluginId = ctx.pathParam("id"); //$NON-NLS-1$
			if (mPluginRegistry.getById(pluginId) == null)
			{
				ctx.status(404).json(java.util.Map.of("error", "Plugin inconnu : " + pluginId)); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			final EnabledRequest req = ctx.bodyAsClass(EnabledRequest.class);
			mPluginPreferences.setEnabled(pluginId, req.enabled());
			ctx.status(204);
		});

		// Téléchargement d'un plugin (écran Paramètres) : rassemble son dossier
		// (manifest.json + éventuels fragments de documentation, voir
		// docs/11-plugin-api-contrat.md) dans un .zip - jamais de .jar, cohérent
		// avec le choix "aucun code exécutable" déjà retenu pour les plugins. Une
		// simple lecture, sans risque particulier (contrairement à l'upload, voir
		// la route d'ajout plus bas).
		pApp.get("/api/plugins/{id}/download", ctx -> { //$NON-NLS-1$
			final PluginManifest manifest = mPluginRegistry.getById(ctx.pathParam("id")); //$NON-NLS-1$
			if (manifest == null)
			{
				ctx.status(404).json(java.util.Map.of("error", "Plugin inconnu : " + ctx.pathParam("id"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				return;
			}
			final Path dir = Path.of(manifest.getSourceDirectory());
			final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer);
					java.util.stream.Stream<Path> entries = java.nio.file.Files.walk(dir))
			{
				for (final Path file : entries.filter(java.nio.file.Files::isRegularFile).toList())
				{
					zip.putNextEntry(new java.util.zip.ZipEntry(dir.relativize(file).toString()));
					java.nio.file.Files.copy(file, zip);
					zip.closeEntry();
				}
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible de préparer le téléchargement : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			ctx.contentType("application/zip"); //$NON-NLS-1$
			ctx.header("Content-Disposition", "attachment; filename=\"" + manifest.getId() + ".zip\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			ctx.result(buffer.toByteArray());
		});

		// Suppression d'un plugin (écran Paramètres) : jamais autorisée pour les
		// deux systèmes fournis par défaut (voir PluginRegistry.isBuiltin) - le
		// bouton est déjà masqué côté front pour eux, ceci est le garde-fou côté
		// serveur, au cas où l'appel est fait directement.
		pApp.delete("/api/plugins/{id}", ctx -> { //$NON-NLS-1$
			try
			{
				mPluginRegistry.deletePlugin(ctx.pathParam("id")); //$NON-NLS-1$
				// Un plugin peut disparaître sans qu'on efface la préférence
				// d'activation correspondante - inoffensif (elle sera simplement
				// ignorée), mais autant nettoyer pour ne pas laisser une trace
				// orpheline dans plugins-config.json.
				mPluginPreferences.setEnabled(ctx.pathParam("id"), false); //$NON-NLS-1$
				ctx.status(204);
			}
			catch (final IllegalArgumentException e)
			{
				ctx.status(400).json(java.util.Map.of("error", e.getMessage())); //$NON-NLS-1$
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible de supprimer ce plugin : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});

		// Fragment de documentation propre à un système d'échange (écran
		// Documentation, affiché quand une partie est ouverte - voir
		// docs/11-plugin-api-contrat.md, champ "documentation" du manifeste, et
		// docs/12-guide-creer-systeme-echange.md). Repli sur le français si la
		// langue demandée n'a pas de fragment dédié.
		// Précaution de sécurité : "lang" sert à construire un chemin de fichier -
		// validé strictement (deux lettres, éventuellement suivies d'une région)
		// pour ne jamais laisser passer une traversée de répertoire du type
		// "../../../etc/passwd".
		pApp.get("/api/plugins/{id}/docs/{lang}", ctx -> { //$NON-NLS-1$
			final PluginManifest manifest = mPluginRegistry.getById(ctx.pathParam("id")); //$NON-NLS-1$
			if (manifest == null)
			{
				ctx.status(404).json(java.util.Map.of("error", "Plugin inconnu : " + ctx.pathParam("id"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				return;
			}
			final String lang = ctx.pathParam("lang"); //$NON-NLS-1$
			if (!lang.matches("[a-z]{2}(-[a-z]{2})?")) //$NON-NLS-1$
			{
				ctx.status(400).json(java.util.Map.of("error", "Code de langue invalide.")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			final Path docsDir = Path.of(manifest.getSourceDirectory()).resolve("docs"); //$NON-NLS-1$
			Path docPath = docsDir.resolve("regles." + lang + ".html"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!java.nio.file.Files.isRegularFile(docPath))
				docPath = docsDir.resolve("regles.fr.html"); // repli //$NON-NLS-1$
			if (!java.nio.file.Files.isRegularFile(docPath))
			{
				ctx.status(404).json(java.util.Map.of("error", "Aucune documentation disponible pour ce système.")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			try
			{
				ctx.contentType("text/html; charset=utf-8"); //$NON-NLS-1$
				ctx.result(java.nio.file.Files.readString(docPath));
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Documentation illisible : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});

		// --- Paramètres (langue par défaut, son, mise à jour) et langues personnalisées ---

		// Sauvegarde complète de la base de données (écran Paramètres) - demandé
		// par l'utilisateur pour finaliser l'étape 2. Toutes les parties, tous
		// systèmes confondus, dans un seul fichier .zip au format natif H2 (voir
		// BackupService, commande SQL BACKUP - un instantané cohérent, pas une
		// simple copie de fichier qui pourrait capturer un état à moitié écrit).
		pApp.get("/api/backup", ctx -> { //$NON-NLS-1$
			Path backupFile = null;
			try
			{
				backupFile = mBackupService.createBackup();
				ctx.contentType("application/zip"); //$NON-NLS-1$
				ctx.header("Content-Disposition", "attachment; filename=\"" + backupFile.getFileName() + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				ctx.result(java.nio.file.Files.readAllBytes(backupFile));
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible de créer la sauvegarde : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			finally
			{
				// Le fichier temporaire n'a plus d'utilité une fois envoyé (ou en cas
				// d'échec) - on ne laisse jamais s'accumuler des sauvegardes orphelines
				// sur le disque au fil des téléchargements.
				if (backupFile != null)
					try
					{
						java.nio.file.Files.deleteIfExists(backupFile);
					}
					catch (final java.io.IOException e)
					{
						System.out.println("Impossible de nettoyer le fichier de sauvegarde temporaire " + backupFile + " : " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
					}
			}
		});

		pApp.get("/api/settings", ctx -> ctx.json(java.util.Map.of( //$NON-NLS-1$
				"defaultLanguage", mAppSettings.getDefaultLanguage(), //$NON-NLS-1$
				"soundMuted", mAppSettings.isSoundMuted(), //$NON-NLS-1$
				"soundVolume", mAppSettings.getSoundVolume(), //$NON-NLS-1$
				"updateCheckUrl", mAppSettings.getUpdateCheckUrl(), //$NON-NLS-1$
				"protectionEnabled", mAppSettings.isProtectionEnabled(), //$NON-NLS-1$
				"gameMode", mAppSettings.getGameMode(), //$NON-NLS-1$
				"currentVersion", AppVersion.CURRENT))); //$NON-NLS-1$

		pApp.put("/api/settings", ctx -> { //$NON-NLS-1$
			final Dtos.UpdateSettingsRequest req = ctx.bodyAsClass(Dtos.UpdateSettingsRequest.class);
			if (req.defaultLanguage() != null)
				mAppSettings.setDefaultLanguage(req.defaultLanguage());
			mAppSettings.setSoundMuted(req.soundMuted());
			mAppSettings.setSoundVolume(req.soundVolume());
			if (req.updateCheckUrl() != null)
				mAppSettings.setUpdateCheckUrl(req.updateCheckUrl());
			mAppSettings.setProtectionEnabled(req.protectionEnabled());
			if (req.gameMode() != null)
				mAppSettings.setGameMode(req.gameMode());
			ctx.status(204);
		});

		// Étape 3, mode smartphone (écran Paramètres) : les trois tableaux de
		// gestion des visuels - voir CatalogService. "kind" vaut "cartes",
		// "visuels" ou "avatars" ; toute autre valeur renvoie 404 plutôt que de
		// deviner. Édition de métadonnées uniquement (jamais l'image elle-même,
		// voir la zoombox côté front) : PUT applique un patch partiel par id.
		pApp.get("/api/catalogs/{kind}", ctx -> ctx.json(catalogFor(ctx.pathParam("kind")).list())); //$NON-NLS-1$ //$NON-NLS-2$

		pApp.put("/api/catalogs/{kind}/{id}", ctx -> { //$NON-NLS-1$
			try
			{
				final Dtos.CatalogEntryPatch patch = ctx.bodyAsClass(Dtos.CatalogEntryPatch.class);
				ctx.json(catalogFor(ctx.pathParam("kind")).patch(ctx.pathParam("id"), patch.fields())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			catch (final IllegalArgumentException e)
			{
				throw new io.javalin.http.BadRequestResponse(e.getMessage());
			}
		});

		// Vérification de mise à jour (écran Paramètres) : lecture seule, voir
		// UpdateCheckService - ne télécharge et n'installe jamais rien, se contente
		// de comparer un numéro de version et de renvoyer le lien fourni par le
		// serveur distant, pour que l'animateur décide et agisse manuellement.
		pApp.get("/api/updates/check", ctx -> ctx.json(mUpdateCheckService.check(mAppSettings.getUpdateCheckUrl()))); //$NON-NLS-1$

		// Langues personnalisées : le français et l'anglais fournis avec
		// l'application ("intégrés") restent servis tels quels par le serveur de
		// fichiers statiques classique (voir /lang/*.po) - cette route ne liste
		// que celles ajoutées après coup par l'animateur (voir LanguageService),
		// servies séparément depuis /lang-custom/*.po. "overrides" : réglages
		// manuels de drapeau/libellé (voir LanguageService.getOverrides -
		// LANGUAGE_COUNTRY_MAP côté i18n.js fournit la déduction automatique,
		// ceci prend le pas dessus quand elle est présente pour un code donné).
		pApp.get("/api/languages", ctx -> ctx.json(java.util.Map.of( //$NON-NLS-1$
				"builtin", List.of("fr", "en"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"custom", mLanguageService.listCustomLanguageCodes(), //$NON-NLS-1$
				"overrides", mLanguageService.getOverrides()))); //$NON-NLS-1$

		// Définit ou efface (si le corps ne contient ni "flag" ni "label", tous
		// deux vides/absents) le réglage manuel de drapeau/libellé d'une langue -
		// écran Paramètres, pour corriger le cas où la déduction automatique
		// (une langue peut être parlée dans plusieurs pays) ne convient pas.
		pApp.put("/api/languages/{code}/display", ctx -> { //$NON-NLS-1$
			try
			{
				final Dtos.LanguageDisplayRequest req = ctx.bodyAsClass(Dtos.LanguageDisplayRequest.class);
				final boolean isEmpty = ((req.flag() == null) || req.flag().isBlank())
						&& ((req.label() == null) || req.label().isBlank());
				mLanguageService.setOverride(ctx.pathParam("code"), //$NON-NLS-1$
						isEmpty ? null : new LanguageService.LanguageDisplay(req.flag(), req.label()));
				ctx.status(204);
			}
			catch (final IllegalArgumentException e)
			{
				ctx.status(400).json(java.util.Map.of("error", e.getMessage())); //$NON-NLS-1$
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible d'enregistrer ce réglage : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});

		// Ajoute ou remplace un fichier de langue personnalisé (import depuis
		// l'écran Paramètres) - le corps de la requête est le contenu brut du
		// fichier .po, pas du JSON (voir Api.uploadLanguage côté front).
		pApp.post("/api/languages/{code}", ctx -> { //$NON-NLS-1$
			try
			{
				mLanguageService.saveCustomLanguage(ctx.pathParam("code"), ctx.body()); //$NON-NLS-1$
				ctx.status(204);
			}
			catch (final IllegalArgumentException e)
			{
				ctx.status(400).json(java.util.Map.of("error", e.getMessage())); //$NON-NLS-1$
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible d'enregistrer le fichier : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});

		// Supprime une langue personnalisée (écran Paramètres) - jamais autorisé
		// pour le français/anglais (voir LanguageService.deleteCustomLanguage).
		pApp.delete("/api/languages/{code}", ctx -> { //$NON-NLS-1$
			try
			{
				mLanguageService.deleteCustomLanguage(ctx.pathParam("code")); //$NON-NLS-1$
				ctx.status(204);
			}
			catch (final IllegalArgumentException e)
			{
				ctx.status(400).json(java.util.Map.of("error", e.getMessage())); //$NON-NLS-1$
			}
			catch (final java.io.IOException e)
			{
				ctx.status(500).json(java.util.Map.of("error", "Impossible de supprimer le fichier : " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});

		// --- Parties ---

		// Remonté par un utilisateur : protection par code PIN, activable/
		// désactivable globalement (écran Paramètres, AppSettings.protectionEnabled).
		// Une partie créée pendant que la protection est active reçoit un PIN
		// (voir Game.pin) qui doit alors être fourni (en-tête X-Game-Pin) pour
		// toute action d'administration sur CETTE partie précise. Exemptions
		// volontaires : "/join" (auto-inscription d'un nouveau joueur, qui n'a par
		// définition pas encore de jeton - voir Player.accessToken), "/unlock" (la
		// route qui sert justement à vérifier le PIN, elle ne peut pas exiger ce
		// qu'elle valide elle-même), et "/players/by-token/" (un joueur consultant
		// ses propres informations avec SON jeton individuel n'a pas besoin du PIN
		// de l'animateur - accès volontairement plus restreint, voir cette route
		// plus bas, qui ne retourne que les données du joueur concerné).
		final java.util.function.Consumer<io.javalin.http.Context> checkGamePin = ctx -> {
			if (ctx.path().endsWith("/join") || ctx.path().endsWith("/unlock") //$NON-NLS-1$ //$NON-NLS-2$
					|| ctx.path().contains("/players/by-token/")) //$NON-NLS-1$
				return;
			final int id;
			try
			{
				id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			}
			catch (final NumberFormatException e)
			{
				return; // pas un identifiant de partie valide - laisse la route elle-même répondre (404/400)
			}
			final Game game = mGameService.getGame(id);
			if (game == null)
				return; // laisse la route elle-même répondre 404
			final String pin = game.getPin();
			if ((pin == null) || pin.isEmpty())
				return; // partie non protégée (protection désactivée à sa création, ou partie ancienne)
			if (!pin.equals(ctx.header("X-Game-Pin"))) //$NON-NLS-1$
				throw new ForbiddenResponse("Code PIN requis ou incorrect pour cette partie."); //$NON-NLS-1$
		};
		pApp.before("/api/games/{id}", ctx -> checkGamePin.accept(ctx)); //$NON-NLS-1$
		pApp.before("/api/games/{id}/*", ctx -> checkGamePin.accept(ctx)); //$NON-NLS-1$

		// Vérifie un PIN soumis par l'animateur (écran de saisie, une seule fois
		// par appareil/navigateur - voir Api.unlockGame côté front, qui mémorise
		// ensuite le PIN localement pour l'inclure automatiquement dans les
		// requêtes suivantes concernant cette partie).
		pApp.post("/api/games/{id}/unlock", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Dtos.UnlockRequest req = ctx.bodyAsClass(Dtos.UnlockRequest.class);
			if (mGameService.verifyGamePin(id, req.pin()))
				ctx.status(204);
			else
				ctx.status(403).json(java.util.Map.of("error", "Code PIN incorrect.")); //$NON-NLS-1$ //$NON-NLS-2$
		});

		// Liste des parties : utilisé par la vue d'accueil du front (choix/création de partie),
		// équivalent web de ChooseGamesDialog côté Swing.
		pApp.get("/api/games", ctx -> { //$NON-NLS-1$
			final List<Game> games = mGameService.listGames();
			// On mappe vers des DTO plutôt que de sérialiser les entités JPA directement
			// (voir Dtos.java pour le pourquoi : lazy-loading, cycles Game<->Player<->Event).
			ctx.json(games.stream().map(GameSummaryDto::from).toList());
		});

		// Création d'une partie : équivalent web de la boîte de dialogue "nouvelle partie" Swing.
		pApp.post("/api/games", ctx -> { //$NON-NLS-1$
			final CreateGameRequest req = ctx.bodyAsClass(CreateGameRequest.class);
			final Game game = mGameService.createGame(req.moneySystem(), req.nbTurnsPlanned(), req.animatorPseudo(),
					req.animatorEmail(), req.description(), req.curDate(), req.location(), req.moneyCardsFactor(),
					req.turnDurationSeconds(), req.weakCoinValue(), req.tokenPenalty(), req.startingGoods(),
					req.strictTrm());
			// Remonté par un utilisateur : PIN à 6 chiffres généré uniquement si la
			// protection est activée globalement (écran Paramètres) - une partie
			// créée pendant que la protection est désactivée reste accessible sans
			// PIN, même si l'animateur active la protection plus tard (voir
			// GameService.verifyGamePin, qui gère ce cas explicitement).
			if (mAppSettings.isProtectionEnabled())
			{
				final String pin = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000)); //$NON-NLS-1$
				mGameService.setGamePin(game.getId(), pin);
				game.setPin(pin);
			}
			// GameDetailDto plutôt que GameSummaryDto : le front a besoin du PIN tout
			// juste généré pour l'afficher immédiatement à l'animateur (seule
			// occasion pratique de le lui rappeler explicitement - ensuite, il devra
			// le retrouver via l'écran de la partie, où il reste affiché).
			ctx.status(201).json(GameDetailDto.from(game));
		});

		// Comparaison de N parties (dette et/ou libre), joueur par joueur : équivalent web de
		// ChooseGamesDialog + StatsFrame(List<Game>) côté Swing (onglets "Aggrégés standards" /
		// "Aggrégés corrigés"). Exemple : /api/games/compare?ids=3,7,9
		pApp.get("/api/games/compare", ctx -> { //$NON-NLS-1$
			final String idsParam = ctx.queryParam("ids"); //$NON-NLS-1$
			if ((idsParam == null) || idsParam.isBlank())
			{
				ctx.status(400).json(java.util.Map.of("error", "Paramètre 'ids' manquant ou vide")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			final List<Game> games = java.util.Arrays.stream(idsParam.split(",")) //$NON-NLS-1$
					.map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).map(mGameService::getGame)
					.filter(java.util.Objects::nonNull).toList();
			if (games.isEmpty())
			{
				ctx.status(404).json(java.util.Map.of("error", "Aucune des parties demandées n'a été trouvée")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			ctx.json(mStatsService.computeComparison(games));
		});

		// Détail d'une partie (joueurs + journal des événements) : c'est la vue principale
		// pendant le jeu, équivalent web de la fenêtre principale HelperUI.
		pApp.get("/api/games/{id}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			ctx.json(GameDetailDto.from(game));
		});

		// Export d'une seule partie (écran Statistiques ou "Parties récentes") -
		// demandé par l'utilisateur pour finaliser l'étape 2 : un fichier JSON
		// autonome par partie, plus facile à partager ou archiver individuellement
		// qu'une sauvegarde complète de la base (voir /api/backup ci-dessus).
		// Réutilise le même DTO que l'écran "Partie en cours" (GameDetailDto,
		// déjà exhaustif - joueurs, événements, réglages) plutôt que d'en
		// inventer un format d'export séparé à maintenir en double.
		pApp.get("/api/games/{id}/export", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			final String safeDescription = (game.getDescription() == null ? "partie" : game.getDescription()) //$NON-NLS-1$
					.replaceAll("[^a-zA-Z0-9-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
			ctx.contentType("application/json"); //$NON-NLS-1$
			ctx.header("Content-Disposition", //$NON-NLS-1$
					"attachment; filename=\"geconomicus-partie-" + id + "-" + safeDescription + ".json\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			ctx.json(GameDetailDto.from(game));
		});

		// Suppression d'une partie (et de tout ce qui lui est rattaché : joueurs,
		// événements) - proposée depuis l'écran "Parties récentes" du front.
		pApp.delete("/api/games/{id}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			mGameService.deleteGame(id);
			ctx.status(204);
		});

		// Statistiques (Phase B) : masse monétaire dans le temps + répartition des
		// richesses, pour les graphiques du tableau de bord. Calculées à la volée à
		// chaque appel plutôt que mises en cache : une partie de Ğeconomicus a peu
		// d'événements (quelques centaines au plus), le recalcul est quasi instantané.
		pApp.get("/api/games/{id}/stats", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			ctx.json(mStatsService.computeStats(game));
		});

		// Démarre effectivement la partie (bouton "Démarrer la partie") : lance le
		// chrono du premier tour. Le chrono ne démarre plus automatiquement à la
		// création de la partie (remonté par un utilisateur).
		pApp.post("/api/games/{id}/start", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.startGame(id);
			broadcast(id, "game_started", null); //$NON-NLS-1$
			ctx.json(GameDetailDto.from(game));
		});

		// Prolonge le tour en cours (bouton "+30s" du minuteur) : recule l'horodatage de
		// départ du tour, ce qui allonge le temps restant pour tous les clients connectés.
		pApp.post("/api/games/{id}/turn/extend", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final String secondsParam = ctx.queryParam("seconds"); //$NON-NLS-1$
			final int deltaSeconds = secondsParam != null ? Integer.parseInt(secondsParam) : 30;
			final Game game = mGameService.extendCurrentTurn(id, deltaSeconds);
			broadcast(id, "turn_extended", null); //$NON-NLS-1$
			ctx.json(GameDetailDto.from(game));
		});

		// Remonté par un utilisateur : la pause doit être partagée par tous les
		// écrans connectés (tableau de bord ET assistant de fin de tour), pas
		// seulement visuelle sur l'écran qui a cliqué "Pause" - d'où un vrai état
		// stocké côté serveur plutôt qu'un simple indicateur local au navigateur.
		pApp.post("/api/games/{id}/turn/pause", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.pauseTurn(id);
			broadcast(id, "turn_paused", null); //$NON-NLS-1$
			ctx.json(GameDetailDto.from(game));
		});
		pApp.post("/api/games/{id}/turn/resume", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.resumeTurn(id);
			broadcast(id, "turn_resumed", null); //$NON-NLS-1$
			ctx.json(GameDetailDto.from(game));
		});

		// Rapport de fin de partie (Phase D) : statistiques agrégées (moyenne, médiane,
		// écart-type, indice de Gini) et histogramme de répartition finale des richesses.
		pApp.get("/api/games/{id}/report", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			// Remonté par un utilisateur : deux vues possibles (avec/sans la banque
			// comptée comme un joueur de plus) - "sans banque" par défaut si non
			// précisé, pour rester cohérent avec le comportement d'avant cet ajout.
			final boolean includeBank = "true".equals(ctx.queryParam("includeBank")); //$NON-NLS-1$ //$NON-NLS-2$
			ctx.json(mStatsService.computeFinalReport(game, includeBank));
		});

		// Statistiques d'activité par joueur (qui a fait le plus de transactions, le
		// plus emprunté, brassé le plus de volume) + volume global de transactions.
		pApp.get("/api/games/{id}/activity", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			ctx.json(mStatsService.computeActivityReport(game));
		});

		// Suivi de richesse par joueur dans le temps (démonstration du "module
		// Galilée" de la TRM : convergence des comptes vers la moyenne).
		pApp.get("/api/games/{id}/wealth-over-time", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			ctx.json(mStatsService.computeWealthOverTime(game));
		});

		// --- Connectivité (étape 3, Phase A) ---
		// Liste les adresses IP locales de la machine, pour construire les QR codes
		// et URLs que les smartphones utiliseront pour rejoindre la partie. Voir
		// docs/05-etape3-connectivite.md pour le contexte complet. Le port n'est pas
		// renvoyé ici : le front le connaît déjà via window.location.port (même
		// processus Javalin, même port pour l'API et pour lui-même).
		pApp.get("/api/network-info", ctx -> { //$NON-NLS-1$
			ctx.json(NetworkUtils.listLocalAddresses());
		});

		// --- Joueurs ---

		// Ajout d'un joueur en cours de partie. On diffuse immédiatement l'info aux autres clients
		// connectés (broadcast) : c'est ce qui permettra, à l'étape 3, à l'écran de l'animateur de
		// voir apparaître un joueur qui vient de rejoindre depuis son smartphone.
		pApp.post("/api/games/{id}/players", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final AddPlayerRequest req = ctx.bodyAsClass(AddPlayerRequest.class);
			final Player player = mGameService.addPlayer(id, req.name());
			broadcast(id, "player_added", PlayerDto.from(player, 0)); //$NON-NLS-1$
			ctx.status(201).json(PlayerDto.from(player, 0));
		});

		// Remonté par un utilisateur : un joueur consultant SES PROPRES
		// informations avec son jeton individuel (voir Player.accessToken) - accès
		// volontairement restreint à un seul joueur, jamais aux autres ni aux
		// actions d'administration (contrairement au PIN de partie). Base pour
		// l'écran joueur de l'étape 3 - fonctionnel dès maintenant en lecture
		// seule, même sans cet écran encore construit.
		pApp.get("/api/games/{id}/players/by-token/{token}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final String token = ctx.pathParam("token"); //$NON-NLS-1$
			final Game game = mGameService.getGame(id);
			if (game == null)
			{
				ctx.status(404);
				return;
			}
			final Player player = game.getPlayers().stream()
					.filter(p -> (p.getAccessToken() != null) && p.getAccessToken().equals(token)).findFirst()
					.orElse(null);
			if (player == null)
			{
				ctx.status(404).json(java.util.Map.of("error", "Jeton inconnu.")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			ctx.json(PlayerDto.from(player, 0));
		});

		// Suppression totale d'un joueur (et de ses événements associés), avec recalcul
		// intégral de l'état de la partie. Diffusé avec le détail complet de la partie,
		// puisque la suppression peut affecter la masse monétaire et d'autres joueurs.
		pApp.delete("/api/games/{id}/players/{playerId}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final int playerId = Integer.parseInt(ctx.pathParam("playerId")); //$NON-NLS-1$
			mGameService.deletePlayer(id, playerId);
			final Game updated = mGameService.getGame(id);
			broadcast(id, "game_recomputed", GameDetailDto.from(updated)); //$NON-NLS-1$
			ctx.status(204);
		});

		// Renommage d'un joueur (icône crayon de la liste des joueurs) : le nouveau nom
		// est répercuté partout puisque toutes les vues lisent directement le nom stocké
		// côté serveur (pas de copie locale à synchroniser côté client).
		pApp.put("/api/games/{id}/players/{playerId}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final int playerId = Integer.parseInt(ctx.pathParam("playerId")); //$NON-NLS-1$
			final AddPlayerRequest req = ctx.bodyAsClass(AddPlayerRequest.class);
			try
			{
				mGameService.renamePlayer(id, playerId, req.name());
				final Game updated = mGameService.getGame(id);
				broadcast(id, "game_recomputed", GameDetailDto.from(updated)); //$NON-NLS-1$
				ctx.status(200).json(GameDetailDto.from(updated));
			}
			catch (final GameService.DuplicatePlayerNameException e)
			{
				ctx.status(409).json(java.util.Map.of("error", //$NON-NLS-1$
						"Ce prénom est déjà pris dans cette partie, choisissez-en un autre.")); //$NON-NLS-1$
			}
		});

		// Auto-inscription depuis le smartphone du joueur (étape 3, Phase B) : équivalent
		// de la route ci-dessus, mais avec les infos saisies par le joueur lui-même
		// (âge déclaré, couleur, avatar) et une vérification de nom dupliqué - deux
		// téléphones pourraient saisir le même prénom sans le savoir, contrairement à
		// l'animateur qui voit déjà la liste des joueurs à l'écran.
		pApp.post("/api/games/{id}/join", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final JoinRequest req = ctx.bodyAsClass(JoinRequest.class);
			if (req.name() == null || req.name().isBlank())
			{
				ctx.status(400).json(java.util.Map.of("error", "Le prénom est obligatoire.")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			try
			{
				final Player player = mGameService.joinAsPlayer(id, req.name().trim(), req.declaredAge(),
						req.favoriteColor(), req.avatarConfigJson());
				final PlayerDto dto = PlayerDto.from(player, 0);
				broadcast(id, "player_added", dto); //$NON-NLS-1$
				ctx.status(201).json(dto);
			}
			catch (final GameService.DuplicatePlayerNameException e)
			{
				ctx.status(409).json(java.util.Map.of("error", //$NON-NLS-1$
						"Ce prénom est déjà pris dans cette partie, choisissez-en un autre.")); //$NON-NLS-1$
			}
		});

		// --- Evenements (credit, remboursement, mort, nouveau tour, etc.) ---

		// Enregistrement d'un événement de jeu : c'est LA route la plus utilisée pendant une partie
		// (chaque action de l'animateur/banquier). req.type() attend le code à une lettre défini
		// dans EventTypeConverter (ex: "J" pour JOIN, "C" pour NEW_CREDIT...).
		pApp.post("/api/games/{id}/events", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final RecordEventRequest req = ctx.bodyAsClass(RecordEventRequest.class);
			final Event event = mGameService.recordEvent(id, req.type(), req.playerId(), req.principal(),
					req.interest(), req.weakCards(), req.mediumCards(), req.strongCards(),
					req.counterpartyPlayerId(), req.goodsFromPlayer(), req.goodsFromCounterparty(),
					req.weakCoins(), req.mediumCoins(), req.strongCoins());
			broadcast(id, "event", EventDto.from(event)); //$NON-NLS-1$
			ctx.status(201).json(EventDto.from(event));
		});

		// Suppression d'un événement, avec recalcul intégral de l'état de la partie
		// (voir GameService.deleteEvent). Diffusé via WebSocket avec le détail complet
		// de la partie (pas juste l'événement supprimé) puisque dettes/masse monétaire
		// peuvent avoir changé en cascade pour d'autres joueurs.
		pApp.delete("/api/games/{id}/events/{eventId}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final int eventId = Integer.parseInt(ctx.pathParam("eventId")); //$NON-NLS-1$
			mGameService.deleteEvent(id, eventId);
			final Game updated = mGameService.getGame(id);
			broadcast(id, "game_recomputed", GameDetailDto.from(updated)); //$NON-NLS-1$
			ctx.status(204);
		});

		// Édition d'un événement (principal/intérêt/date), avec le même recalcul intégral.
		pApp.put("/api/games/{id}/events/{eventId}", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final int eventId = Integer.parseInt(ctx.pathParam("eventId")); //$NON-NLS-1$
			final EditEventRequest req = ctx.bodyAsClass(EditEventRequest.class);
			java.util.Date tstamp = null;
			if (req.tstamp() != null && !req.tstamp().isBlank())
			{
				tstamp = java.util.Date.from(java.time.Instant.parse(req.tstamp()));
			}
			mGameService.editEvent(id, eventId, req.principal(), req.interest(), tstamp);
			final Game updated = mGameService.getGame(id);
			broadcast(id, "game_recomputed", GameDetailDto.from(updated)); //$NON-NLS-1$
			ctx.status(200).json(GameDetailDto.from(updated));
		});

		// Annule la dernière action enregistrée (équivalent touche [z] de l'app Swing).
		// Renvoie 204 si une action a bien été annulée, 404 s'il n'y avait rien à annuler
		// (partie vide de tout événement).
		pApp.post("/api/games/{id}/undo", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			final boolean undone = mGameService.undoLastEvent(id);
			if (!undone)
			{
				ctx.status(404);
				return;
			}
			final Game updated = mGameService.getGame(id);
			broadcast(id, "game_recomputed", GameDetailDto.from(updated)); //$NON-NLS-1$
			ctx.status(200).json(GameDetailDto.from(updated));
		});

		// Suggestion des joueurs qui devraient mourir ce tour-ci (portage de
		// l'algorithme de l'app Swing originale - voir GameService.suggestDeaths).
		// Lecture seule, appelée typiquement à l'ouverture de l'assistant de fin de
		// tour, avant que l'animateur ne fasse sa propre sélection.
		pApp.get("/api/games/{id}/suggested-deaths", ctx -> { //$NON-NLS-1$
			final int id = Integer.parseInt(ctx.pathParam("id")); //$NON-NLS-1$
			ctx.json(mGameService.suggestDeaths(id));
		});

		// --- WebSocket : diffusion temps reel (ecran de stats, futurs clients smartphones) ---
		// On se contente ici d'enregistrer/désenregistrer la session dans mSessions ; c'est la
		// méthode broadcast() ci-dessous qui envoie effectivement les messages. Le client (app.js)
		// n'a pas besoin d'envoyer de messages au serveur pour l'instant, seulement de recevoir.
		pApp.ws("/ws", ws -> { //$NON-NLS-1$
			ws.onConnect(mSessions::add);
			ws.onClose(mSessions::remove);
		});
	}

	/**
	 * Diffuse un evenement a tous les clients WebSocket connectes. C'est ce mecanisme
	 * qui, a l'etape 3, permettra a chaque smartphone connecte de voir en temps reel
	 * les actions des autres joueurs et de l'animateur.
	 */
	private void broadcast(final int pGameId, final String pType, final Object pPayload)
	{
		final WsBroadcastMessage msg = new WsBroadcastMessage(pType, pGameId, pPayload);
		for (final WsContext session : mSessions)
		{
			session.send(msg);
		}
	}

	public record WsBroadcastMessage(String type, int gameId, Object payload)
	{
	}
}
