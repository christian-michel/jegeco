package jyt.geconomicus.helper.server.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Charge et valide les manifestes de plugins "système d'échange" (voir
 * docs/11-plugin-api-contrat.md) depuis un dossier sur le disque de la
 * machine hôte - jamais depuis une route réseau, décision prise avec
 * l'utilisateur suite à l'audit sécurité : tant qu'il n'y a aucune
 * authentification dans l'application, accepter des fichiers uploadés par le
 * réseau serait un risque, même pour un format purement déclaratif (JSON, pas
 * de code exécutable).
 * <p>
 * Un manifeste invalide ne bloque jamais le démarrage du serveur : il est
 * ignoré et l'erreur est consignée dans {@link #getLoadErrors()}, sur le même
 * principe défensif déjà appliqué côté web (voir initChartZoomButtons() dans
 * app.js) - un plugin mal écrit par la communauté ne doit jamais empêcher les
 * autres, ni le reste de l'application, de fonctionner.
 * <p>
 * Ce registre ne fait, pour l'instant, que charger et exposer les manifestes
 * (voir GET /api/plugins) - il n'est pas encore consulté par le moteur de jeu
 * (Event.java/Game.java restent, à ce stade, sur MONEY_DEBT/MONEY_LIBRE en
 * dur). Le brancher au moteur est l'étape suivante, une fois ce socle
 * vérifié.
 */
public class PluginRegistry
{
	// Un id de plugin doit rester simple et prévisible : il sert potentiellement
	// de préfixe de clé i18n, de segment d'URL, de nom de fichier - on
	// n'autorise donc que des minuscules, chiffres et tirets, commençant par une
	// lettre.
	private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9-]*$"); //$NON-NLS-1$

	// Codes d'événements réservés au socle commun du moteur (voir le contrat) :
	// aucun plugin n'a le droit de redéfinir l'un de ces codes, pour préserver
	// les invariants de comparabilité (tours, morts/renaissances identiques
	// partout).
	private static final List<String> RESERVED_EVENT_CODES = List
			.of("JOIN", "TURN", "DEATH", "QUIT", "END", "MM_CHANGE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	// Remonté par un utilisateur : l'écran "Nouvelle partie" ne doit proposer que
	// des systèmes réellement jouables - le moteur (Event.java/Game.java) sait
	// maintenant aussi faire tourner le troc (assistant, tableau de bord,
	// statistiques - voir docs/10-etape-plugins-troc.md) en plus de la dette et
	// de la libre.
	private static final List<String> ENGINE_READY_IDS = List.of("dette", "libre", "troc"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	// Remonté par un utilisateur (écran Paramètres) : les deux systèmes fournis
	// par défaut avec l'application ne peuvent jamais être supprimés (bouton
	// supprimer masqué côté front, et refusé ici même en cas d'appel direct à
	// la route) - contrairement à ENGINE_READY_IDS ci-dessus, cette liste ne
	// grandira jamais avec le troc ou de futurs plugins communautaires, même
	// une fois "prêts" : seuls dette/libre sont protégés.
	private static final List<String> BUILTIN_IDS = List.of("dette", "libre"); //$NON-NLS-1$ //$NON-NLS-2$

	public boolean isBuiltin(final String pId)
	{
		return BUILTIN_IDS.contains(pId);
	}

	private final Map<String, PluginManifest> plugins = new LinkedHashMap<>();
	private final List<String> loadErrors = new ArrayList<>();

	/**
	 * Scanne {@code pPluginsDir}/&#42;/manifest.json et charge chaque manifeste
	 * trouvé. Peut être appelée plusieurs fois (ex. rechargement à chaud
	 * ultérieur) : repart de zéro à chaque appel.
	 */
	public void loadAll(final Path pPluginsDir)
	{
		plugins.clear();
		loadErrors.clear();

		if ((pPluginsDir == null) || !Files.isDirectory(pPluginsDir))
		{
			loadErrors.add("Dossier de plugins introuvable : " + pPluginsDir); //$NON-NLS-1$
			return;
		}

		final ObjectMapper mapper = new ObjectMapper();
		try (Stream<Path> entries = Files.list(pPluginsDir))
		{
			entries.filter(Files::isDirectory).sorted().forEach(pluginDir -> loadOne(pluginDir, mapper));
		}
		catch (final IOException e)
		{
			loadErrors.add("Impossible de lister le dossier de plugins " + pPluginsDir + " : " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private void loadOne(final Path pPluginDir, final ObjectMapper pMapper)
	{
		final Path manifestPath = pPluginDir.resolve("manifest.json"); //$NON-NLS-1$
		final String dirLabel = pPluginDir.getFileName().toString();
		if (!Files.isRegularFile(manifestPath))
		{
			// Pas forcément une erreur : un dossier sans manifest.json est
			// simplement ignoré (ex. un dossier de sauvegarde, un .DS_Store...).
			return;
		}

		final PluginManifest manifest;
		try
		{
			manifest = pMapper.readValue(manifestPath.toFile(), PluginManifest.class);
		}
		catch (final IOException e)
		{
			loadErrors.add(String.format("Plugin \"%s\" : manifest.json illisible ou mal formé (%s).", dirLabel, //$NON-NLS-1$
					e.getMessage()));
			return;
		}
		manifest.setSourceDirectory(pPluginDir.toString());

		final List<String> issues = validate(manifest);
		if (!issues.isEmpty())
		{
			loadErrors.add(String.format("Plugin \"%s\" ignoré - %s", dirLabel, String.join(" ; ", issues))); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}

		if (plugins.containsKey(manifest.getId()))
		{
			loadErrors.add(String.format(
					"Plugin \"%s\" ignoré : un autre plugin utilise déjà l'id \"%s\" (chargé depuis %s).", dirLabel, //$NON-NLS-1$
					manifest.getId(), plugins.get(manifest.getId()).getSourceDirectory()));
			return;
		}

		manifest.setEngineReady(ENGINE_READY_IDS.contains(manifest.getId()));
		manifest.setBuiltin(isBuiltin(manifest.getId()));
		plugins.put(manifest.getId(), manifest);
	}

	/**
	 * Valide un manifeste déjà désérialisé. Retourne la liste des problèmes
	 * trouvés (vide si le manifeste est valide). Volontairement permissif sur
	 * tout ce qui n'a pas encore d'usage concret côté moteur (configFields,
	 * playerState, deathRebirth...) - seuls les points qui garantissent les
	 * invariants de comparabilité et la sécurité du chargement sont vérifiés
	 * pour l'instant.
	 */
	private List<String> validate(final PluginManifest pManifest)
	{
		final List<String> issues = new ArrayList<>();

		if ((pManifest.getId() == null) || !VALID_ID.matcher(pManifest.getId()).matches())
			issues.add("\"id\" manquant ou invalide (minuscules/chiffres/tirets uniquement, doit commencer par une lettre)."); //$NON-NLS-1$

		if (pManifest.getApiVersion() != 1)
			issues.add("\"apiVersion\" doit valoir 1 (seule version du contrat supportée pour l'instant)."); //$NON-NLS-1$

		if ((pManifest.getDisplayName() == null) || !pManifest.getDisplayName().hasNonNull("fr") //$NON-NLS-1$
				|| pManifest.getDisplayName().get("fr").asText().isBlank()) //$NON-NLS-1$
			issues.add("\"displayName.fr\" manquant ou vide."); //$NON-NLS-1$

		if ((pManifest.getWizardSteps() == null) || !pManifest.getWizardSteps().isArray()
				|| pManifest.getWizardSteps().isEmpty())
			issues.add("\"wizardSteps\" manquant ou vide : un système doit décrire au moins une étape d'assistant."); //$NON-NLS-1$

		if (pManifest.getWealthFormula() == null)
			issues.add("\"wealthFormula\" manquant : chaque système doit pouvoir produire une richesse comparable (voir le contrat)."); //$NON-NLS-1$

		if ((pManifest.getEventTypes() != null) && pManifest.getEventTypes().isArray())
		{
			final List<String> seenCodes = new ArrayList<>();
			for (final JsonNode eventType : pManifest.getEventTypes())
			{
				final JsonNode codeNode = eventType.get("code"); //$NON-NLS-1$
				if ((codeNode == null) || codeNode.asText().isBlank())
				{
					issues.add("un \"eventTypes\" sans \"code\"."); //$NON-NLS-1$
					continue;
				}
				final String code = codeNode.asText();
				if (RESERVED_EVENT_CODES.contains(code))
					issues.add(String.format("le code d'événement \"%s\" est réservé au socle commun, un plugin ne peut pas le redéfinir.", //$NON-NLS-1$
							code));
				else if (seenCodes.contains(code))
					issues.add(String.format("le code d'événement \"%s\" est déclaré plusieurs fois dans ce même manifeste.", code)); //$NON-NLS-1$
				else
					seenCodes.add(code);
			}
		}

		return issues;
	}

	public Collection<PluginManifest> getAll()
	{
		return plugins.values();
	}

	/**
	 * Supprime un plugin (dossier + manifest.json) du disque - jamais autorisé
	 * pour les systèmes fournis par défaut (voir {@link #isBuiltin(String)}).
	 * Retire aussi l'entrée en mémoire, sans repasser par {@link #loadAll(Path)}
	 * (inutile de revalider tous les autres plugins pour une simple suppression).
	 */
	public void deletePlugin(final String pId) throws IOException
	{
		if (isBuiltin(pId))
			throw new IllegalArgumentException(
					"Le système \"" + pId + "\" est fourni par défaut et ne peut pas être supprimé."); //$NON-NLS-1$ //$NON-NLS-2$
		final PluginManifest manifest = plugins.get(pId);
		if (manifest == null)
			throw new IllegalArgumentException("Plugin inconnu : " + pId); //$NON-NLS-1$
		final Path dir = Path.of(manifest.getSourceDirectory());
		try (Stream<Path> entries = Files.walk(dir))
		{
			// Trie du plus profond au moins profond (reverseOrder sur les chemins déjà
			// triés en profondeur par Files.walk) : un dossier doit être vide avant de
			// pouvoir être supprimé, donc on efface toujours les fichiers avant leur
			// dossier parent.
			for (final Path p : entries.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.delete(p);
		}
		plugins.remove(pId);
	}

	/** Ids des plugins "prêts" (voir {@link PluginManifest#isEngineReady()}) - sert
	 * à initialiser {@link PluginPreferences} par défaut au tout premier démarrage. */
	public java.util.Set<String> getEngineReadyIds()
	{
		final java.util.Set<String> ids = new java.util.HashSet<>();
		for (final PluginManifest manifest : plugins.values())
			if (manifest.isEngineReady())
				ids.add(manifest.getId());
		return ids;
	}

	public PluginManifest getById(final String pId)
	{
		return plugins.get(pId);
	}

	/** Erreurs rencontrées au dernier {@link #loadAll(Path)} - jamais fatales, juste consignées. */
	public List<String> getLoadErrors()
	{
		return loadErrors;
	}
}
