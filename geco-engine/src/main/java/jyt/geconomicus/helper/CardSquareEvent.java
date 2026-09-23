package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.xml.bind.annotation.XmlIDREF;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Étape 3, monnaie libre, mode smartphone : encaissement d'un "carré" (4
 * cartes identiques réunies par un joueur) - remonté par l'utilisateur
 * (28/08/2026, document de cadrage + geconomicus.glibre.org/rules.html) :
 * "il pioche une carte de valeur supérieure, se défausse de son carré dans
 * la pioche du paquet correspondant et pioche quatre nouvelles cartes de ce
 * même paquet."
 * <p>
 * Volontairement séparée de {@link Transaction} : un carré est une
 * interaction du joueur avec la BANQUE/PIOCHE PARTAGÉE (voir
 * Game.smartphoneCardPileJson), pas un échange entre deux joueurs - le
 * schéma de Transaction (un vendeur, un acheteur, une seule carte contre un
 * prix) ne représente pas du tout ce mouvement (4 cartes rendues, 5 cartes
 * reçues, dont 4 d'un même niveau et 1 d'un niveau supérieur). Journal
 * append-only au même titre que Transaction : {@link
 * jyt.geconomicus.helper.server.GameService#computePlayerCardInventory}
 * rejoue les deux journaux ensemble pour reconstituer l'inventaire réel d'un
 * joueur.
 */
@Entity
public class CardSquareEvent implements Serializable
{
	@TableGenerator(
		name="sqGen",
		table="ID_GEN",
		pkColumnName="GEN_KEY",
		valueColumnName="GEN_VALUE",
		pkColumnValue="SQ_ID",
		allocationSize=1
	)
	@XmlTransient
	@GeneratedValue(strategy=GenerationType.TABLE, generator="sqGen")
	@Id
	private Integer id;

	@ManyToOne
	@JoinColumn(name="GAME_ID")
	@XmlIDREF
	private Game game;

	@ManyToOne
	@JoinColumn(name="PLAYER_ID")
	@XmlIDREF
	private Player player;

	@Temporal(TemporalType.TIMESTAMP)
	private Date tstamp;

	private int turnNumber;

	// Le modèle et le niveau des 4 cartes défaussées (rendues à la pioche de
	// CE niveau).
	private String cashedCardTypeId;
	private String cashedLevel;

	// Le modèle et le niveau de LA carte piochée en récompense (niveau
	// immédiatement supérieur à cashedLevel).
	private String promotedCardTypeId;
	private String promotedLevel;

	// Les 4 nouvelles cartes piochées dans la pioche de cashedLevel pour
	// remplacer celles défaussées - JSON, liste de cardTypeId (généralement
	// pas tous identiques, un tirage au hasard dans la pioche).
	@Lob
	private String replenishedCardIdsJson;

	// Vrai si CET encaissement est celui qui a fait entrer en jeu le niveau
	// "en attente" pour la première fois de la partie (rupture
	// technologique) - voir GameService, qui enregistre alors AUSSI un
	// Event de type XTECHNOLOGICAL_BREAKTHROUGH pour que le calcul de
	// richesse existant (StatsService, currentFactor *= 2) en tienne compte.
	// Se déclenche UNE SEULE FOIS par partie (jamais aux occurrences
	// suivantes) - volontairement DÉCOUPLÉ de triggeredRevolution ci-dessous
	// (22/09/2026), qui lui se répète : un doublement du facteur de richesse
	// à CHAQUE révolution aurait fait croître le graphique de richesse de
	// façon exponentielle et non maîtrisée sur une partie longue, un effet
	// non demandé par l'utilisateur (qui a seulement demandé la rotation des
	// PRIX des cartes, pas une réévaluation de la courbe de richesse).
	private boolean triggeredBreakthrough;

	// Vrai si CET encaissement est un carré au niveau physique le plus haut
	// ("tresforte") qui a RÉELLEMENT bouclé vers "faible" (jamais si le
	// repli "aucun autre modèle disponible" s'est déclenché à la place -
	// voir GameService.checkAndCashInSquares) - ajouté le 22/09/2026, suite
	// utilisateur : "il peut être intéressant de mettre en place une
	// rotation des valeurs, d'autant que cela est conforme à la règle du
	// jeu" (voir geconomicus.glibre.org/rules.html, "révolution
	// économique"). Contrairement à triggeredBreakthrough, se répète à
	// CHAQUE fois (pas seulement la première) - c'est ce qui fait tourner
	// Game.revolutionCount, donc le prix de chaque niveau (voir
	// Game.cardPriceInDU). Purement informatif pour l'UI (déclenche
	// l'animation "RÉVOLUTION !" côté client) - la vraie donnée de jeu reste
	// Game.revolutionCount, jamais recalculée séparément ailleurs.
	private boolean triggeredRevolution;

	// Valeur de Game.revolutionCount immédiatement APRÈS cet encaissement
	// (donc après incrémentation si triggeredRevolution est vrai) - capturée
	// ici pour que l'animation puisse afficher "Révolution n°X" sans avoir à
	// relire Game séparément (évite une course avec un encaissement suivant
	// qui aurait déjà incrémenté le compteur avant que le client ne lise
	// cet événement).
	private int revolutionCountAfter;

	@SuppressWarnings("unused")
	private CardSquareEvent()
	{
		super();
	}

	public CardSquareEvent(final Game pGame, final Player pPlayer, final String pCashedCardTypeId,
			final String pCashedLevel, final String pPromotedCardTypeId, final String pPromotedLevel,
			final String pReplenishedCardIdsJson, final boolean pTriggeredBreakthrough,
			final boolean pTriggeredRevolution, final int pRevolutionCountAfter)
	{
		super();
		game = pGame;
		player = pPlayer;
		turnNumber = pGame.getTurnNumber();
		tstamp = new Date();
		cashedCardTypeId = pCashedCardTypeId;
		cashedLevel = pCashedLevel;
		promotedCardTypeId = pPromotedCardTypeId;
		promotedLevel = pPromotedLevel;
		replenishedCardIdsJson = pReplenishedCardIdsJson;
		triggeredBreakthrough = pTriggeredBreakthrough;
		triggeredRevolution = pTriggeredRevolution;
		revolutionCountAfter = pRevolutionCountAfter;
	}

	public Integer getId()
	{
		return id;
	}

	public Game getGame()
	{
		return game;
	}

	public Player getPlayer()
	{
		return player;
	}

	public Date getTstamp()
	{
		return tstamp;
	}

	public int getTurnNumber()
	{
		return turnNumber;
	}

	public String getCashedCardTypeId()
	{
		return cashedCardTypeId;
	}

	public String getCashedLevel()
	{
		return cashedLevel;
	}

	public String getPromotedCardTypeId()
	{
		return promotedCardTypeId;
	}

	public String getPromotedLevel()
	{
		return promotedLevel;
	}

	public String getReplenishedCardIdsJson()
	{
		return replenishedCardIdsJson;
	}

	public boolean isTriggeredBreakthrough()
	{
		return triggeredBreakthrough;
	}

	public boolean isTriggeredRevolution()
	{
		return triggeredRevolution;
	}

	public int getRevolutionCountAfter()
	{
		return revolutionCountAfter;
	}
}
