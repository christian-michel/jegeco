package jyt.geconomicus.helper.server.plugins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Représentation Java d'un manifeste de plugin "système d'échange" (voir
 * docs/11-plugin-api-contrat.md). Volontairement un modèle hybride :
 * <ul>
 * <li>les champs qu'on doit valider/lire pour le fonctionnement du registre
 * lui-même ({@code id}, {@code apiVersion}, {@code displayName}...) sont
 * typés fortement ;</li>
 * <li>le reste ({@code eventTypes}, {@code playerState}, {@code wizardSteps},
 * {@code extraStats}...) reste en {@link JsonNode} brut plutôt que d'être
 * entièrement modélisé en classes Java, parce que le contrat est encore
 * expérimental - on a déjà trouvé deux cas (calcul de la banque en monnaie
 * dette, formules de DU en monnaie libre) qui ne rentraient pas dans le
 * format simple prévu au départ. Modéliser rigidement maintenant obligerait à
 * réécrire ces classes à chaque ajustement du contrat pendant qu'il se
 * stabilise encore.
 * </ul>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} : un manifeste peut
 * contenir des champs de documentation informels (ex. {@code "note"},
 * {@code "note_eventTypes"} comme dans les manifestes dette/libre) qui ne sont
 * pas destinés à être lus par le code, seulement par les humains qui relisent
 * le fichier - on ne veut pas qu'un champ de commentaire fasse échouer le
 * chargement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest
{
	private String id;
	private int apiVersion;
	private JsonNode displayName;
	private JsonNode shortDescription;
	private boolean hasBank;
	private boolean hasMoneyMass;
	private JsonNode configFields;
	private JsonNode eventTypes;
	private JsonNode playerState;
	private JsonNode deathRebirth;
	private JsonNode wealthFormula;
	private JsonNode extraStats;
	private JsonNode wizardSteps;
	private JsonNode documentation;

	// Emplacement d'où ce manifeste a été chargé (pas dans le JSON lui-même) -
	// utile pour résoudre les chemins relatifs de documentation
	// (ex. "docs/regles.fr.html") et pour les messages d'erreur de validation.
	private String sourceDirectory;

	// Remonté par un utilisateur : l'écran "Nouvelle partie" doit refléter les
	// systèmes activés dans les Paramètres, mais TOUS les plugins chargés ne sont
	// pas encore réellement jouables - le moteur (Event.java/Game.java) ne
	// comprend encore que la monnaie dette et la monnaie libre. Plutôt que de
	// laisser créer une partie dans un système que le reste de l'application ne
	// sait pas faire fonctionner (assistant, tableau de bord, stats), ce champ
	// est positionné par PluginRegistry (pas par le fichier JSON lui-même, qui ne
	// devrait pas pouvoir se déclarer "prêt" tout seul) et distingue "chargé avec
	// succès" de "réellement jouable".
	private boolean engineReady;

	// Idem : reflète les préférences utilisateur (écran Paramètres), pas une
	// propriété du manifeste lui-même - voir PluginPreferences.
	private boolean enabled;

	// Remonté par un utilisateur (écran Paramètres) : les deux systèmes fournis
	// par défaut (dette/libre) ne peuvent jamais être supprimés - positionné par
	// PluginRegistry (voir isBuiltin(String)), pas par le manifeste lui-même
	// pour la même raison qu'engineReady ci-dessus.
	private boolean builtin;

	public String getId()
	{
		return id;
	}

	public void setId(final String pId)
	{
		id = pId;
	}

	public int getApiVersion()
	{
		return apiVersion;
	}

	public void setApiVersion(final int pApiVersion)
	{
		apiVersion = pApiVersion;
	}

	public JsonNode getDisplayName()
	{
		return displayName;
	}

	public void setDisplayName(final JsonNode pDisplayName)
	{
		displayName = pDisplayName;
	}

	public JsonNode getShortDescription()
	{
		return shortDescription;
	}

	public void setShortDescription(final JsonNode pShortDescription)
	{
		shortDescription = pShortDescription;
	}

	public boolean isHasBank()
	{
		return hasBank;
	}

	public void setHasBank(final boolean pHasBank)
	{
		hasBank = pHasBank;
	}

	public boolean isHasMoneyMass()
	{
		return hasMoneyMass;
	}

	public void setHasMoneyMass(final boolean pHasMoneyMass)
	{
		hasMoneyMass = pHasMoneyMass;
	}

	public JsonNode getConfigFields()
	{
		return configFields;
	}

	public void setConfigFields(final JsonNode pConfigFields)
	{
		configFields = pConfigFields;
	}

	public JsonNode getEventTypes()
	{
		return eventTypes;
	}

	public void setEventTypes(final JsonNode pEventTypes)
	{
		eventTypes = pEventTypes;
	}

	public JsonNode getPlayerState()
	{
		return playerState;
	}

	public void setPlayerState(final JsonNode pPlayerState)
	{
		playerState = pPlayerState;
	}

	public JsonNode getDeathRebirth()
	{
		return deathRebirth;
	}

	public void setDeathRebirth(final JsonNode pDeathRebirth)
	{
		deathRebirth = pDeathRebirth;
	}

	public JsonNode getWealthFormula()
	{
		return wealthFormula;
	}

	public void setWealthFormula(final JsonNode pWealthFormula)
	{
		wealthFormula = pWealthFormula;
	}

	public JsonNode getExtraStats()
	{
		return extraStats;
	}

	public void setExtraStats(final JsonNode pExtraStats)
	{
		extraStats = pExtraStats;
	}

	public JsonNode getWizardSteps()
	{
		return wizardSteps;
	}

	public void setWizardSteps(final JsonNode pWizardSteps)
	{
		wizardSteps = pWizardSteps;
	}

	public JsonNode getDocumentation()
	{
		return documentation;
	}

	public void setDocumentation(final JsonNode pDocumentation)
	{
		documentation = pDocumentation;
	}

	public String getSourceDirectory()
	{
		return sourceDirectory;
	}

	public void setSourceDirectory(final String pSourceDirectory)
	{
		sourceDirectory = pSourceDirectory;
	}

	public boolean isEngineReady()
	{
		return engineReady;
	}

	public void setEngineReady(final boolean pEngineReady)
	{
		engineReady = pEngineReady;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(final boolean pEnabled)
	{
		enabled = pEnabled;
	}

	public boolean isBuiltin()
	{
		return builtin;
	}

	public void setBuiltin(final boolean pBuiltin)
	{
		builtin = pBuiltin;
	}
}
