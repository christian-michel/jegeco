package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.xml.bind.annotation.XmlIDREF;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Étape 3, les trois systèmes monétaires + smartphone : "échange avec la
 * pioche" - demandé par l'utilisateur (18/10/2026, suite à la campagne de
 * test 2/6/16 joueurs, voir docs/14-reflexion-echange-pioche.md pour le
 * document de cadrage validé) : un joueur rend une carte à la pioche
 * partagée de SON niveau et en reçoit une autre, tirée au hasard parmi les
 * AUTRES modèles de ce même niveau encore en stock - jamais son propre
 * modèle, jamais de jetons/valeur monétaire (carte contre carte, comme le
 * troc). Pensé pour débloquer la progression quand un modèle manquant à un
 * joueur n'est détenu par AUCUN autre joueur (uniquement dans la pioche,
 * normalement inaccessible hors carré/renaissance) - voir la campagne de
 * test du 18/10/2026 dans docs/03-architecture-technique.md pour les chiffres
 * qui ont motivé cette fonctionnalité.
 * <p>
 * Volontairement séparée de {@link Transaction} (jamais un échange entre
 * deux joueurs) et de {@link CardSquareEvent} (jamais un carré - une seule
 * carte rendue, une seule reçue, le même niveau des deux côtés, aucune
 * promotion) : une interaction DIRECTE du joueur avec la banque/pioche
 * PARTAGÉE, qui mérite son propre journal append-only, au même titre que les
 * deux autres. {@link jyt.geconomicus.helper.server.GameService#computePlayerCardInventory}
 * rejoue les TROIS journaux ensemble pour reconstituer l'inventaire réel
 * d'un joueur.
 */
@Entity
public class PileExchangeEvent implements Serializable
{
	@TableGenerator(
		name="pxGen",
		table="ID_GEN",
		pkColumnName="GEN_KEY",
		valueColumnName="GEN_VALUE",
		pkColumnValue="PX_ID",
		allocationSize=1
	)
	@XmlTransient
	@GeneratedValue(strategy=GenerationType.TABLE, generator="pxGen")
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

	// Le modèle rendu à la pioche de ce niveau (celui que le joueur
	// détenait et a choisi d'échanger).
	private String givenCardTypeId;

	// Le niveau des DEUX cartes (identique des deux côtés par construction -
	// un échange avec la pioche ne change jamais de niveau, contrairement à
	// un carré) - résolu côté SERVEUR depuis Game.smartphoneCardPileJson,
	// jamais déclaré par le client (même principe que recordTransaction/
	// recordCardSwap depuis le correctif du 27/09/2026).
	private String level;

	// Le modèle reçu en retour, tiré au hasard dans la pioche de ce niveau,
	// TOUJOURS différent de givenCardTypeId (voir GameService.exchangeWithPile
	// - le joueur ne peut jamais retomber sur sa propre carte).
	private String receivedCardTypeId;

	@SuppressWarnings("unused")
	private PileExchangeEvent()
	{
		super();
	}

	public PileExchangeEvent(final Game pGame, final Player pPlayer, final String pGivenCardTypeId,
			final String pLevel, final String pReceivedCardTypeId)
	{
		super();
		game = pGame;
		player = pPlayer;
		turnNumber = pGame.getTurnNumber();
		tstamp = new Date();
		givenCardTypeId = pGivenCardTypeId;
		level = pLevel;
		receivedCardTypeId = pReceivedCardTypeId;
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

	public String getGivenCardTypeId()
	{
		return givenCardTypeId;
	}

	public String getLevel()
	{
		return level;
	}

	public String getReceivedCardTypeId()
	{
		return receivedCardTypeId;
	}
}
