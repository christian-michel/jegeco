package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlID;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * A Ğeconomicus game. It is only one single game in one single currency. Thus a full "game" consists generally of two
 * games: one in Debt-Money and one in Libre Currency.
 * @author jytou
 *
 * NOTE DE MIGRATION (2026) : cette classe est le cœur du moteur métier, inchangée dans sa logique depuis la
 * version originale de jytou (https://gitlab.com/jytou/geconomicus_helper). Seules les annotations de
 * persistance/sérialisation ont été renommées de javax.persistence/javax.xml.bind vers jakarta.persistence/
 * jakarta.xml.bind (retirés du JDK depuis Java 11). Voir docs/03-architecture-technique.md pour le détail.
 */
@XmlRootElement
@Entity
@NamedQuery(name="Game.findAll", query="SELECT c FROM Game c order by c.id") 
public class Game implements Serializable
{
	// Ids are automatically generated
	@TableGenerator(
		name="gameGen",
		table="ID_GEN",
		pkColumnName="GEN_KEY",
		valueColumnName="GEN_VALUE",
		pkColumnValue="GAME_ID",
		allocationSize=1
	)
	@XmlTransient
	@GeneratedValue(strategy=GenerationType.TABLE, generator="gameGen")
	@Id
	private Integer id;
	// Pseudo of the animator of the game
	private String animatorPseudo;
	// Emais of the animator of the game
	private String animatorEmail;
	// Some extra description/comments on the game, such as banker name or any extra precision
	@Lob
	private String description;

	// The money system types that can be used for the money system used in this game
	public final static int MONEY_LIBRE = 0;
	public final static int MONEY_DEBT = 1;
	// Troc (voir plugins/troc/manifest.json, docs/10-etape-plugins-troc.md) :
	// échange direct de biens et de services, sans monnaie ni banque. Premier
	// système ajouté depuis l'architecture à plugins - garde le champ moneySystem
	// existant (int) plutôt que de le remplacer par l'id de plugin (String) pour
	// l'instant, afin de limiter le risque sur les deux systèmes déjà en
	// production ; à revoir si de futurs systèmes se multiplient.
	public final static int MONEY_TROC = 2;

	// The money system for this game
	private int moneySystem;
	// The number of turns planned for this game - or number of "rounds" if you prefer
	private int nbTurnsPlanned;
	// The current turn number (it is actually the number of turns that have actually been played
	// so this starts at 0, and is equal to nbTurnsPlanned at the end of the game.
	private int turnNumber;

	// Phase C (étape 2 web) : durée du tour en secondes, et horodatage de début du
	// tour en cours, pour permettre à plusieurs clients web de partager un compte à
	// rebours synchronisé (chacun calcule le temps restant à partir de la même
	// référence serveur, plutôt que de faire tourner un minuteur local indépendant
	// qui dériverait d'un client à l'autre).
	// Valeur par défaut (300s = 5 min) pour rester compatible avec les parties créées
	// avant cet ajout, et avec l'app Swing qui ne renseigne pas ce champ.
	@Column(name = "turn_duration_seconds")
	private int turnDurationSeconds = 300;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "turn_started_at")
	private Date turnStartedAt;

	// Remonté par un utilisateur : la pause du chrono doit être partagée entre
	// TOUS les écrans connectés (tableau de bord ET assistant de fin de tour), pas
	// seulement visuelle sur l'écran qui a cliqué "Pause". null = chrono actif
	// (temps restant calculé depuis turnStartedAt comme d'habitude) ; une valeur
	// = chrono figé à ce nombre de secondes restantes.
	@Column(name = "paused_remaining_seconds")
	private Integer pausedRemainingSeconds;
	// The current total money mass (used in Debt Money only)
	private int moneyMass;
	// The estimate of seized values by the banker so far
	private int seizedValues;
	// The interest gained by the banker so far
	private int interestGained;
	// When the bank turns to an investment bank, the number of cards that the bank has invested
	// This number is actually subtracted from the bank's current seized gains.
	private int cardsInvestBank;
	// The money invested by the bank. Again this is subtracted from the gained interest
	private int moneyInvestBank;
	// How many coins/bank notes you need to pay for a low value card. Normally, this is 1 but can also be 2 depending on the animator.
	private Integer moneyCardsFactor;

	// Nouveau champ, ajouté suite à un retour utilisateur : "la valeur d'une pièce
	// faible", généralement 1 en monnaie dette et en monnaie libre. N'existait pas
	// du tout dans le moteur jusqu'ici (seul moneyCardsFactor existait). Reste un
	// entier pour l'instant : le manuel original mentionne aussi des parties jouées
	// avec des jetons faibles de 0,5 - non géré ici, ça demanderait de revoir tous
	// les calculs qui manipulent ces valeurs comme des entiers, pas juste ce champ.
	private double weakCoinValue = 1;

	// Nouveau champ : "pénalité d'un jeton", propre à la monnaie libre (règles
	// officielles : geconomicus.glibre.org/libre_money.html). Un jeton faible est
	// retiré au joueur lors de la rotation de la monnaie - facultatif, certaines
	// parties ne l'appliquent pas et se contentent de diviser la valeur par deux.
	private boolean tokenPenalty;

	// Remonté par un utilisateur : mode "strict TRM", propre à la monnaie libre,
	// réglable uniquement à la création de la partie (jamais modifiable en cours
	// de route - changer les règles économiques en plein jeu n'aurait pas de
	// sens). Par défaut désactivé : le comportement historique du jeu (celui
	// utilisé depuis le début, voir Event.applyEvent) reste inchangé. Une fois
	// activé, la masse monétaire ne peut plus jamais diminuer à la mort/sortie
	// d'un joueur - ce qu'il possédait reste compté (juste inaccessible aux
	// vivants), et sa renaissance crée de la monnaie fraîche à hauteur du DU du
	// moment, plutôt qu'un bonus fixe indépendant de l'état de la partie.
	private boolean strictTrm;

	// Remonté par un utilisateur : code PIN de cette partie, uniquement généré
	// quand la protection par code est activée (voir AppSettings.protectionEnabled
	// côté serveur - un réglage global, pas propre à une partie). Null quand la
	// protection est désactivée ou pour une partie créée avant l'ajout de cette
	// fonctionnalité (compatibilité ascendante : ces parties restent accessibles
	// sans PIN, jamais bloquées après coup). Protège les actions d'administration
	// de CETTE partie (tableau de bord, assistant, gestion des joueurs) - voir
	// GecoServer, vérification du PIN sur les routes /api/games/{id}/*.
	// Distinct du jeton individuel de chaque joueur (voir Player.accessToken),
	// qui donne un accès plus restreint, propre à un seul joueur.
	private String pin;

	// Troc uniquement (voir plugins/troc/manifest.json, configFields) : dotation
	// de départ et de renaissance en objets. 4 par défaut. Retour utilisateur :
	// uniquement des transactions d'échange - jamais de don sans contrepartie,
	// jamais de monnaie ni de jeton (les échanges de service et le temps de vie
	// ont été retirés après un premier essai).
	private int startingGoods = 4;
	// Current date
	@XmlID
	private String curdate;
	// Location where the game took place
	private String location;

	// The players in this game, ordered by player name
	@XmlElementWrapper
	@XmlElement(name="player")
	@OneToMany(mappedBy="game",cascade={CascadeType.PERSIST, CascadeType.REMOVE},orphanRemoval=true)
	@OrderBy("active,name")
	private Collection<Player> players = new ArrayList<Player>();

	// All events, sorted by time
	@XmlElementWrapper
	@XmlElement(name="event")
	@OneToMany(mappedBy="game",cascade={CascadeType.PERSIST, CascadeType.REMOVE},orphanRemoval=true)
	@OrderBy("tstamp")
	private Collection<Event> events = new ArrayList<Event>();

	// This is necessary for EclipseLink to instantiate an empty object
	@SuppressWarnings("unused")
	private Game()
	{
		super();
	}

	/**
	 * Create a new game with the following parameters. This is only to be used for a new game,
	 * since old games are loaded by EclipseLink.
	 * @param pMoneySystem
	 * @param pNbTurnsPlanned
	 * @param pAnimatorPseudo
	 * @param pAnimatorEmail
	 * @param pDescription
	 * @param pCurDate
	 * @param pLocation
	 * @param pMoneyCardsFactor
	 */
	public Game(int pMoneySystem, int pNbTurnsPlanned, String pAnimatorPseudo, String pAnimatorEmail, String pDescription, String pCurDate, String pLocation, int pMoneyCardsFactor)
	{
		super();
		moneySystem = pMoneySystem;
		nbTurnsPlanned = pNbTurnsPlanned;
		animatorPseudo = pAnimatorPseudo;
		animatorEmail = pAnimatorEmail;
		description = pDescription;
		curdate = pCurDate;
		location = pLocation;
		turnNumber = 0;
		moneyCardsFactor = pMoneyCardsFactor;
	}

	@XmlTransient
	public Integer getId()
	{
		return id;
	}

	public int getTurnNumber()
	{
		return turnNumber;
	}

	public int getTurnDurationSeconds()
	{
		return turnDurationSeconds;
	}

	public void setTurnDurationSeconds(final int pTurnDurationSeconds)
	{
		turnDurationSeconds = pTurnDurationSeconds;
	}

	public Date getTurnStartedAt()
	{
		return turnStartedAt;
	}

	public void setTurnStartedAt(final Date pTurnStartedAt)
	{
		turnStartedAt = pTurnStartedAt;
	}

	public Integer getPausedRemainingSeconds()
	{
		return pausedRemainingSeconds;
	}

	public void setPausedRemainingSeconds(final Integer pPausedRemainingSeconds)
	{
		pausedRemainingSeconds = pPausedRemainingSeconds;
	}

	public void incTurnNumber()
	{
		turnNumber++;
	}

	public void setId(Integer pId)
	{
		id = pId;
	}

	@XmlTransient
	public String getCurdate()
	{
		return curdate;
	}

	public void setCurdate(String pCurdate)
	{
		curdate = pCurdate;
	}

	public String getLocation()
	{
		return location;
	}

	public void setLocation(String pLocation)
	{
		location = pLocation;
	}

	public void addPlayer(Player pPlayer)
	{
		players.add(pPlayer);
	}

	public void addEvent(Event pEvent)
	{
		events.add(pEvent);
	}

	public Collection<Player> getPlayers()
	{
		return players;
	}

	public Collection<Event> getEvents()
	{
		return events;
	}

	public int getMoneyMass()
	{
		return moneyMass;
	}

	public int getInterestGained()
	{
		return interestGained;
	}

	/**
	 * The bank has gained some new interest: it is added to the current gains.
	 * @param pInterest
	 */
	public void gainInterest(int pInterest)
	{
		interestGained += pInterest;
	}

	/**
	 * The bank has seized cards of those values, which are added to the currently seized values.
	 * @param pLow
	 * @param pMedium
	 * @param pStrong
	 */
	public void seizeValues(int pLow, int pMedium, int pStrong)
	{
		seizedValues += pLow + 2 * pMedium + 4 * pStrong;
	}

	public int getSeizedValues()
	{
		return seizedValues;
	}

	/**
	 * The money mass has changed by some offset (which can be positive or negative).
	 * @param pOffset
	 */
	public void changeMoneyMass(int pOffset)
	{
		moneyMass += pOffset;
	}

	public String getAnimatorPseudo()
	{
		return animatorPseudo;
	}

	public String getAnimatorEmail()
	{
		return animatorEmail;
	}

	public String getDescription()
	{
		return description;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		// This should be only used in command line.
		return "#" + getId() + //$NON-NLS-1$
		       " - " + getCurdate() + //$NON-NLS-1$
		       " - " + getLocation() + //$NON-NLS-1$
		       " \nanimated by " + getAnimatorPseudo() + " (" + getAnimatorEmail() + ")" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		       " in " + (moneySystem == MONEY_DEBT ? "Debt-Money" : "Libre Money") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		       " \nturn " + getTurnNumber() + " / " + getNbTurnsPlanned() + //$NON-NLS-1$ //$NON-NLS-2$
		       " - MM " + getMoneyMass() + //$NON-NLS-1$
		       " - gained " + getInterestGained() + //$NON-NLS-1$
		       " - seized " + getSeizedValues(); //$NON-NLS-1$
	}

	/**
	 * Reinitializes the game from the beginning and applies all events one after the other.
	 * This way, this method may correct some inconsistencies in the computed values.
	 * This method can also be used to "play the game again" and grab some historical information
	 * in the process. Very useful to make historical graphs.
	 * @param pEventListener an event listener that will get notified every time an event is triggered
	 */
	public void recomputeAll(IEventListener pEventListener)
	{
		// Reinitialize everything
		for (Player player : players)
		{
			player.setCurDebt(0);
			player.setCurInterest(0);
			player.setActive(false);
			player.setVisitedBank(true);
		}
		interestGained = 0;
		moneyMass = 0;
		seizedValues = 0;
		// Correctif (retour utilisateur) : ces deux champs sont ré-incrémentés à chaque
		// événement SIDE_INVESTMENT rejoué (voir Event#applyEvent -> investMoney/investCards)
		// mais n'étaient jamais remis à zéro ici, contrairement aux 3 champs ci-dessus.
		// Conséquence : chaque appel à recomputeAll() (courbes, stats, mais aussi
		// GameService#deleteEvent/editEvent qui persistent le résultat) doublait ces
		// compteurs - un bug latent hérité tel quel de l'app Swing d'origine, qui ne
		// s'en servait jamais pour ses propres statistiques et ne l'a donc jamais vu.
		cardsInvestBank = 0;
		moneyInvestBank = 0;
		turnNumber = 0;

		// Iterate on events
		for (Event event : events)
		{
			event.applyEvent();
			if (pEventListener != null)
				pEventListener.eventApplied(event);
		}
	}

	public int getMoneySystem()
	{
		return moneySystem;
	}

	public int getNbTurnsPlanned()
	{
		return nbTurnsPlanned;
	}

	public int getCardsInvestBank()
	{
		return cardsInvestBank;
	}

	public void setCardsInvestBank(int pCardsInvestBank)
	{
		cardsInvestBank = pCardsInvestBank;
	}

	public int getMoneyInvestBank()
	{
		return moneyInvestBank;
	}

	public void setMoneyInvestBank(int pMoneyInvestBank)
	{
		moneyInvestBank = pMoneyInvestBank;
	}

	/**
	 * Removes an event. Useful to undo an event.
	 * @param pToUndo
	 * @param pRecompute if true, all current values are computed from scratch, otherwise nothing is refreshed.
	 *                   This may be useful if a bunch of events need to be cancelled, and you only want to recompute
	 *                   everything once after the event cancellations.
	 */
	public void removeEvent(Event pToUndo, boolean pRecompute)
	{
		events.remove(pToUndo);
		if (pRecompute)
			recomputeAll(null);
	}

	/**
	 * The bank invests some money into trading cards.
	 * @param pMoney
	 */
	public void investMoney(int pMoney)
	{
		moneyMass += pMoney;
		moneyInvestBank += pMoney;
		interestGained -= pMoney;
	}

	/**
	 * The bank invests some cards into trading.
	 * @param pTotal
	 */
	public void investCards(int pTotal)
	{
		cardsInvestBank += pTotal;
		seizedValues -= pTotal;
	}

	public int getMoneyCardsFactor()
	{
		if (moneyCardsFactor == null)
			moneyCardsFactor = 1;// default
		return moneyCardsFactor;
	}

	// These are only for JAXB
	public void setAnimatorEmail(String pAnimatorEmail)
	{
		animatorEmail = pAnimatorEmail;
	}
	public void setAnimatorPseudo(String pAnimatorPseudo)
	{
		animatorPseudo = pAnimatorPseudo;
	}
	public void setDescription(String pDescription)
	{
		description = pDescription;
	}
	public void setInterestGained(int pInterestGained)
	{
		interestGained = pInterestGained;
	}
	public void setMoneyMass(int pMoneyMass)
	{
		moneyMass = pMoneyMass;
	}
	public void setMoneySystem(int pMoneySystem)
	{
		moneySystem = pMoneySystem;
	}
	public void setNbTurnsPlanned(int pNbTurnsPlanned)
	{
		nbTurnsPlanned = pNbTurnsPlanned;
	}
	public void setSeizedValues(int pSeizedValues)
	{
		seizedValues = pSeizedValues;
	}
	public void setTurnNumber(int pTurnNumber)
	{
		turnNumber = pTurnNumber;
	}

	/**
	 * Should normally not be called but can be used exceptionally when a player was mistakenly added
	 * and the user wants to totally get rid of that player.
	 * @param pPlayer
	 */
	public void removePlayer(Player pPlayer)
	{
		players.remove(pPlayer);
	}

	public void setMoneyCardsFactor(int pMoneyCardsFactor)
	{
		moneyCardsFactor = pMoneyCardsFactor;
	}

	public double getWeakCoinValue()
	{
		return weakCoinValue;
	}

	public void setWeakCoinValue(final double pWeakCoinValue)
	{
		weakCoinValue = pWeakCoinValue;
	}

	public boolean isTokenPenalty()
	{
		return tokenPenalty;
	}

	public void setTokenPenalty(final boolean pTokenPenalty)
	{
		tokenPenalty = pTokenPenalty;
	}

	public boolean isStrictTrm()
	{
		return strictTrm;
	}

	public void setStrictTrm(final boolean pStrictTrm)
	{
		strictTrm = pStrictTrm;
	}

	public String getPin()
	{
		return pin;
	}

	public void setPin(final String pPin)
	{
		pin = pPin;
	}

	public int getStartingGoods()
	{
		return startingGoods;
	}

	public void setStartingGoods(final int pStartingGoods)
	{
		startingGoods = pStartingGoods;
	}
}
