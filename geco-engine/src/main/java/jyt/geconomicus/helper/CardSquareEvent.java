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
	private boolean triggeredBreakthrough;

	@SuppressWarnings("unused")
	private CardSquareEvent()
	{
		super();
	}

	public CardSquareEvent(final Game pGame, final Player pPlayer, final String pCashedCardTypeId,
			final String pCashedLevel, final String pPromotedCardTypeId, final String pPromotedLevel,
			final String pReplenishedCardIdsJson, final boolean pTriggeredBreakthrough)
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
}
