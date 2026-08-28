package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * Un événement de jeu : arrivée d'un joueur, nouveau tour, crédit, remboursement,
 * saisie, mort/renaissance, etc. Chaque action de l'animateur/banquier pendant la
 * partie se traduit par un Event, appliqué via {@link #applyEvent()} puis persisté.
 * C'est ce journal d'événements qui permet de reconstituer et comparer les parties
 * a posteriori (courbes, histogrammes).
 * @author jytou
 *
 * NOTE DE MIGRATION (2026) : logique inchangée depuis la version originale de jytou
 * (https://gitlab.com/jytou/geconomicus_helper). Seules les annotations jakarta.persistence/jakarta.xml.bind
 * ont été mises à jour (voir docs/03-architecture-technique.md).
 */
@Entity
public class Event implements Serializable
{
	// Use event types with different starting characters for persistence
	public enum EventType
	{
		// A player joins the game
		JOIN(Messages.getString("BaseMessage.Event.NewPlayer")), //$NON-NLS-1$
		// A turn just finished - initiate a new turn
		TURN(Messages.getString("BaseMessage.Event.NewTurn")), //$NON-NLS-1$
		// A player takes a new credit
		NEW_CREDIT(Messages.getString("BaseMessage.Event.NewCredit")), //$NON-NLS-1$
		// A player pays back only the interest
		INTEREST_ONLY(Messages.getString("BaseMessage.Event.ReimburseInterestOnly")), //$NON-NLS-1$
		// A player reimbursed his credit (partially or in full)
		REIMB_CREDIT(Messages.getString("BaseMessage.Event.ReimburseCredit")), //$NON-NLS-1$
		// A player is defaulting on his debt but can still play after having been seized
		CANNOT_PAY(Messages.getString("BaseMessage.Event.DefaultOk")), //$NON-NLS-1$
		// A player cannot pay and doesn't have enough cards to continue playing: he skis a turn
		BANKRUPT(Messages.getString("BaseMessage.Event.DefaultBankrupt")), //$NON-NLS-1$
		// A player doesn't have enough cards to cover his default: he goes to prison
		PRISON(Messages.getString("BaseMessage.Event.DefaultPrison")), //$NON-NLS-1$
		// A player quits the game (can be used in the middle of the game but also used to do the assessment at the end of the game)
		QUIT(Messages.getString("BaseMessage.Event.QuitGame")), //$NON-NLS-1$
		// The money mass changes unexpectedly (should mostly not happen at all - only for very exceptional cases)
		MM_CHANGE(Messages.getString("BaseMessage.Event.MoneyMassChange")), //$NON-NLS-1$
		// End of the game
		END(Messages.getString("BaseMessage.Event.EndGame")), //$NON-NLS-1$
		// A player dies - assessment of his possessions
		DEATH(Messages.getString("BaseMessage.Event.DeathRebirth")), //$NON-NLS-1$
		// A technological breakthrough. Note that this event MUST have a player attached to it: the player that caused the breakthrough
		XTECHNOLOGICAL_BREAKTHROUGH(Messages.getString("BaseMessage.Event.TechnologicalBreakthrough")), //$NON-NLS-1$
		// The bank invests money and/or cards
		SIDE_INVESTMENT(Messages.getString("BaseMessage.Event.BankInvestment")), //$NON-NLS-1$
		// Final assessment of the investments of the bank at the end of the game
		ASSESSMENT_FINAL(Messages.getString("BaseMessage.Event.BankAssessment")), //$NON-NLS-1$
		// Troc (voir plugins/troc/manifest.json) : échange bien-contre-bien entre
		// deux joueurs, librement négocié, sans valeur imposée. Uniquement des
		// transactions d'échange - jamais de don sans contrepartie, jamais de
		// monnaie ni de jeton (retour utilisateur : les échanges de service et le
		// temps de vie ont été retirés après un premier essai).
		// Nommé "GOODS_TRADE" plutôt que "TRADE_GOODS" (le code du manifeste) pour
		// que sa première lettre (G) ne collisionne pas avec un type existant -
		// EventTypeConverter dérive le code persisté de la première lettre du nom
		// de l'enum, ce qui devient une vraie limite si de futurs plugins
		// communautaires ajoutent beaucoup de types d'événements (toutes les
		// lettres disponibles finiraient par être prises) - à revoir alors, pas
		// urgent tant qu'il n'y a qu'une poignée de systèmes.
		GOODS_TRADE(Messages.getString("BaseMessage.Event.GoodsTrade")), //$NON-NLS-1$
		// Étape 3, mode smartphone, monnaie libre uniquement (voir §5.1 du cahier
		// des charges et la conversation du 28/08/2026 avec l'utilisateur) : point
		// de contrôle LÉGER du solde en jetons d'un joueur, posé à chaque fin de
		// tour pour TOUS les joueurs actifs - pas seulement ceux qui meurent,
		// contrairement à DEATH/QUIT. Volontairement SANS AUCUN EFFET sur la masse
		// monétaire ni sur les cartes (voir applyEvent() ci-dessous, cas no-op) :
		// ce n'est qu'un point de mesure pour la courbe de richesse en continu
		// (StatsService.computeWealthOverTime), jamais un mouvement d'argent réel -
		// contrairement à DEATH/QUIT, qui EUX retirent réellement la valeur
		// saisie de la masse monétaire (le joueur sort du jeu). Remonté par
		// l'utilisateur : avant cette étape, seuls les joueurs mourant un tour
		// donné avaient leur solde numériquement enregistré - la courbe restait
		// plate entre deux morts pour tous les autres, faute de mieux (voir le
		// commentaire historique sur StatsService.computeWealthOverTime).
		WEALTH_CHECKPOINT(Messages.getString("BaseMessage.Event.WealthCheckpoint")); //$NON-NLS-1$

		private String description;
		EventType(String pDescription)
		{
			description = pDescription;
		}

		public String getDescription()
		{
			return description;
		}
	}; 

	// IDs are generated automatically by EclipseLink.
	@TableGenerator(
		name="evtGen",
		table="ID_GEN",
		pkColumnName="GEN_KEY",
		valueColumnName="GEN_VALUE",
		pkColumnValue="EVT_ID",
		allocationSize=1
	)
	@XmlTransient
	@GeneratedValue(strategy=GenerationType.TABLE, generator="evtGen")
	@Id
	private Integer id;

	// The timestamp for this event
	@Temporal(TemporalType.TIMESTAMP)
	private Date tstamp;

	// The event type - translated into a String in the dabatase
	@Column
	@Convert(converter = EventTypeConverter.class)
	private EventType evt;

	// The game in which this event occurred
	@XmlIDREF
	@ManyToOne
	@JoinColumn(nullable=false)
	private Game game;

	// The player that triggered this event. It may be null if it is a game event.
	@XmlIDREF
	@JoinColumn(nullable=true)
	private Player player;

	// Troc uniquement (voir plugins/troc/manifest.json) : le second joueur d'un
	// échange GOODS_TRADE ("player" ci-dessus est l'"initiator", ce champ-ci le
	// "counterparty"). Null pour tous les autres types d'événements, qui
	// n'impliquent qu'un seul joueur à la fois.
	@XmlIDREF
	@JoinColumn(nullable=true)
	private Player counterpartyPlayer;

	// The two next are only for the debt-money system
	// The interest due/reimbursed by the player during this event
	private int interest = 0;
	// The principal of the credit due/reimbursed by the player during this event
	private int principal = 0;

	// Troc uniquement : nombre d'objets donnés par chaque côté d'un GOODS_TRADE
	// (un objet compte pour 1 quel que soit son niveau, voir la règle 7 de
	// docs/10-etape-plugins-troc.md). Retour utilisateur : uniquement des
	// transactions d'échange - jamais de don sans contrepartie, jamais de
	// monnaie ni de jeton de temps (les échanges de service ont été retirés
	// après un premier essai).
	private int goodsFromPlayer = 0;
	private int goodsFromCounterparty = 0;

	// The three next are only for the Free Currency system: the coins that are left when a player dies/quits the game
	private int weakCoins = 0;
	private int mediumCoins = 0;
	private int strongCoins = 0;
	// The cards that a player has left in his hands, or that are seized by the banker during a default.
	private int weakCards = 0;
	private int mediumCards = 0;
	private int strongCards = 0;

	// Used by EclipseLink to instantiate empty objects.
	@SuppressWarnings("unused")
	private Event()
	{
		super();
	}

	/**
	 * Creates a new event for this game
	 * @param pGame
	 * @param pEventType
	 * @param pPlayer can be <code>null</code> if the event is a global event (new turn, etc.)
	 */
	public Event(Game pGame, EventType pEventType, Player pPlayer)
	{
		super();
		game = pGame;
		evt = pEventType;
		player = pPlayer;
		game.addEvent(this);
		tstamp = new Date();
	}

	public EventType getEvt()
	{
		return evt;
	}

	public void setEvt(EventType pEvt)
	{
		evt = pEvt;
	}

	public Date getTstamp()
	{
		return tstamp;
	}

	public int getInterest()
	{
		return interest;
	}

	public void setInterest(int pInterest)
	{
		interest = pInterest;
	}

	public int getPrincipal()
	{
		return principal;
	}

	public void setPrincipal(int pPrincipal)
	{
		principal = pPrincipal;
	}

	public int getWeakCards()
	{
		return weakCards;
	}

	public void setWeakCards(int pWeakCardrs)
	{
		weakCards = pWeakCardrs;
	}

	public int getMediumCards()
	{
		return mediumCards;
	}

	public void setMediumCards(int pMediumCards)
	{
		mediumCards = pMediumCards;
	}

	public int getStrongCards()
	{
		return strongCards;
	}

	public void setStrongCards(int pStrongCards)
	{
		strongCards = pStrongCards;
	}

	@XmlTransient
	public Integer getId()
	{
		return id;
	}

	public void setPlayer(Player pPlayer)
	{
		player = pPlayer;
	}

	@XmlTransient
	public Player getPlayer()
	{
		return player;
	}

	public void setCounterpartyPlayer(final Player pCounterpartyPlayer)
	{
		counterpartyPlayer = pCounterpartyPlayer;
	}

	@XmlTransient
	public Player getCounterpartyPlayer()
	{
		return counterpartyPlayer;
	}

	public int getGoodsFromPlayer()
	{
		return goodsFromPlayer;
	}

	public void setGoodsFromPlayer(final int pGoodsFromPlayer)
	{
		goodsFromPlayer = pGoodsFromPlayer;
	}

	public int getGoodsFromCounterparty()
	{
		return goodsFromCounterparty;
	}

	public void setGoodsFromCounterparty(final int pGoodsFromCounterparty)
	{
		goodsFromCounterparty = pGoodsFromCounterparty;
	}

	// Accesseur manquant jusqu'ici (le champ game n'était utilisé qu'en interne par le
	// constructeur) - nécessaire pour vérifier côté serveur web qu'un événement à
	// éditer/supprimer appartient bien à la partie demandée, avant toute modification.
	@XmlTransient
	public Game getGame()
	{
		return game;
	}

	/**
	 * Applies this event to the current game.<br>
	 * It adds seized values or interest gained, increments or decrements the money owed by a player,
	 * adjusts the current money mass, etc.
	 */
	public void applyEvent()
	{
		switch (evt)
		{
		case BANKRUPT:
		case PRISON:
		case CANNOT_PAY:
		case REIMB_CREDIT:
			// Money and/or cards get taken from a player
			game.seizeValues(weakCards, mediumCards, strongCards);
			game.gainInterest(interest);
			// and then it's just like quitting: don't break here!

		case QUIT:
		case DEATH:
		{
			// A player has finished playing - do an inventory of what he has left
			// take that into account in the total money mass
			// Remonté par un utilisateur : mode "strict TRM" (voir Game.isStrictTrm,
			// réglable uniquement à la création d'une partie en monnaie libre) - dans
			// ce mode, la masse monétaire ne doit jamais diminuer à la sortie d'un
			// joueur : ce qu'il possédait reste compté dans la masse globale (juste
			// devenu inaccessible aux joueurs vivants), on saute donc ce retrait.
			// N'affecte jamais la monnaie dette/le troc (seule la monnaie libre a ce
			// réglage) ni les cas REIMB_CREDIT/CANNOT_PAY/BANKRUPT/PRISON (propres à
			// la monnaie dette, qui n'a pas ce mode).
			final boolean strictTrmExit = (game.getMoneySystem() == Game.MONEY_LIBRE) && game.isStrictTrm();
			if (!strictTrmExit)
				game.changeMoneyMass(-interest-principal-(weakCoins + 2 * mediumCoins + 4 * strongCoins) * game.getMoneyCardsFactor());
			if (EventType.REIMB_CREDIT.equals(evt))
			{
				player.setCurDebt(player.getCurDebt() - principal);
				player.setCurInterest(player.getCurInterest() - interest);
			}
			else
			// If it's anything else, the debt is wiped out
			{
				player.setCurDebt(0);
				player.setCurInterest(0);
			}
			player.setVisitedBank(true);
			if ((game.getMoneySystem() == Game.MONEY_LIBRE) && EventType.DEATH.equals(evt))
			{
				if (strictTrmExit)
				// Remonté par un utilisateur : en mode strict TRM, le nouveau-né reçoit
				// une création monétaire fraîche égale au DU du moment (pas un bonus
				// fixe indépendant de l'état de la partie) - même formule que celle
				// affichée à l'écran (voir computeCurrentDU() côté client), portée ici
				// côté moteur puisqu'elle doit influer sur la masse monétaire elle-même.
				{
					int nbActivePlayers = 0;
					for (Player p2 : game.getPlayers())
						if (p2.isActive())
							nbActivePlayers++;
					final int du = nbActivePlayers > 0
							? game.getMoneyMass() / (7 * nbActivePlayers * game.getMoneyCardsFactor()) : 0;
					game.changeMoneyMass(du);
				}
				else
				// adjust money mass
					game.changeMoneyMass(8 * game.getMoneyCardsFactor());
			}
			if ((game.getMoneySystem() == Game.MONEY_TROC) && EventType.DEATH.equals(evt))
			// Renaissance : dotation de départ, comme au premier tour - voir règle 1
			// de docs/10-etape-plugins-troc.md ("chaque joueur commence [et renaît]
			// avec 4 cartes"). L'inventaire du joueur mourant (goodsFromPlayer,
			// transmis via ce même événement DEATH) a déjà été pris en compte pour
			// les statistiques avant ce point - voir StatsService, pas ce moteur.
				player.setGoodsCount(game.getStartingGoods());
			break;
		}
		case INTEREST_ONLY:
			// The bank is grabbing some interest only
			game.gainInterest(interest);
			game.changeMoneyMass(-interest);
			// Bug trouvé lors d'un retour utilisateur (présent dans le code original,
			// pas introduit par la refonte web) : le compteur d'intérêts du joueur
			// n'était jamais décrémenté ici, contrairement à REIMB_CREDIT qui le fait
			// bien - un joueur remboursant ses intérêts continuait donc d'apparaître
			// comme les devant toujours en intégralité.
			player.setCurInterest(Math.max(0, player.getCurInterest() - interest));
			player.setVisitedBank(true);
			break;
		case MM_CHANGE:
			game.changeMoneyMass(principal);
			break;
		case NEW_CREDIT:
			player.setCurDebt(player.getCurDebt() + principal);
			player.setCurInterest(player.getCurInterest() + interest);
			player.setVisitedBank(true);
			game.changeMoneyMass(principal);
			break;
		case JOIN:
			player.setActive(true);
			if (game.getMoneySystem() == Game.MONEY_LIBRE)
			// Add player's DU to money mass
				game.changeMoneyMass(7 * game.getMoneyCardsFactor());
			break;
		case TURN:
			// All players that have debt need to go to the bank
			for (Player player : game.getPlayers())
				if (player.getCurDebt() > 0)
					player.setVisitedBank(false);
			game.incTurnNumber();
			if (game.getMoneySystem() == Game.MONEY_LIBRE)
			// The money mass is going towards the average
			// Note that we don't have the actual data of how much money each player is giving away
			// We can deal with an average here.
			{
				int nbPlayers = 0;
				for (Player player : game.getPlayers())
					if (player.isActive())
						nbPlayers++;
				final int target = 7 * game.getMoneyCardsFactor() * nbPlayers;
				final int currentMM = game.getMoneyMass();
				game.changeMoneyMass((target - currentMM) / 2);
			}
			// Remonté par un utilisateur : le troc ne gère plus de jetons de temps
			// (retirés après un premier essai) - rien à faire ici pour lui, les
			// joueurs se contentent de troquer des biens d'un tour sur l'autre.
			break;
		case END:
		case XTECHNOLOGICAL_BREAKTHROUGH:
			// Nothing to do here
			break;
		case WEALTH_CHECKPOINT:
			// Volontairement AUCUN effet sur l'état du jeu (voir le commentaire sur
			// ce type d'événement ci-dessus) : ni masse monétaire, ni cartes, ni
			// saisie - un simple point de mesure pour StatsService.
			// computeWealthOverTime, jamais un mouvement d'argent réel comme le
			// sont DEATH/QUIT juste au-dessus.
			break;
		case SIDE_INVESTMENT:
			// The bank invests some money and cards
			game.investMoney(interest);
			game.investCards(weakCards + 2 * mediumCards + 4 * strongCards);
			break;
		case ASSESSMENT_FINAL:
			// The final assessment from the bank
			game.gainInterest(interest);
			game.seizeValues(weakCards, mediumCards, strongCards);
			break;
		case GOODS_TRADE:
			// Troc, règle 3 : échange bien-contre-bien, librement négocié, sans
			// limite liée au temps. "player" = initiator, "counterpartyPlayer" =
			// counterparty (voir plugins/troc/manifest.json, roles).
			player.setGoodsCount(player.getGoodsCount() - goodsFromPlayer + goodsFromCounterparty);
			counterpartyPlayer.setGoodsCount(counterpartyPlayer.getGoodsCount() - goodsFromCounterparty + goodsFromPlayer);
			break;
		default:
			// This should not happen
			throw new RuntimeException("Unexpected event " + evt.description); //$NON-NLS-1$
		}
		// This is a special case that cannot go into the switch
		if (EventType.QUIT.equals(evt))
			player.setActive(false);
	}

	@Override
	public String toString()
	{
		// To be used only in the CLI version
		return "#" + getId() + " - " + getTstamp().toString() + " - player " + (getPlayer() == null ? "" : " - player " + getPlayer().getName()) + " - " + getEvt().getDescription() + " - " + details();  //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
	}

	/**
	 * This method should only be used in the CLI version.
	 * @return
	 */
	public String details()
	{
		final StringBuilder sb = new StringBuilder();
		if (principal > 0)
			sb.append("principal: ").append(principal); //$NON-NLS-1$

		if (interest > 0)
		{
			if (sb.length() > 0)
				sb.append(" - "); //$NON-NLS-1$
			sb.append("interest: ").append(interest); //$NON-NLS-1$
		}
		if (weakCoins + mediumCoins + strongCoins > 0)
		{
			if (sb.length() > 0)
				sb.append(" - "); //$NON-NLS-1$
			sb.append("had: ").append(weakCoins).append(", ").append(mediumCoins).append(", ").append(strongCoins).append(" (total: ").append(weakCoins + mediumCoins * 2 + strongCoins * 4).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		}
		if (weakCards + mediumCards + strongCards > 0)
		{
			if (sb.length() > 0)
				sb.append(" - "); //$NON-NLS-1$
			sb.append("seized: ").append(weakCards).append(", ").append(mediumCards).append(", ").append(strongCards).append(" (total: ").append(weakCards + mediumCards * 2 + strongCards * 4).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		}
		return sb.toString();
	}

	public int getWeakCoins()
	{
		return weakCoins;
	}

	public void setWeakCoins(int pWeakCoins)
	{
		weakCoins = pWeakCoins;
	}

	public int getMediumCoins()
	{
		return mediumCoins;
	}

	public void setMediumCoins(int pMediumCoins)
	{
		mediumCoins = pMediumCoins;
	}

	public int getStrongCoins()
	{
		return strongCoins;
	}

	public void setStrongCoins(int pStrongCoins)
	{
		strongCoins = pStrongCoins;
	}

	// This is for JAXB
	public void setTstamp(Date pTstamp)
	{
		tstamp = pTstamp;
	}

	/**
	 * Clones an event into another game. Useful for importing events into a game.
	 * @param pGame
	 * @return the cloned event for pGame
	 * @throws PlayerNotFoundException if no player with the same name is found
	 */
	public Event cloneFor(Game pGame) throws PlayerNotFoundException
	{
		Player otherPlayer = null;
		if (player != null)
		{
			boolean found = false;
			for (Player gamePlayer : pGame.getPlayers())
			{
				if (gamePlayer.getName().equals(player.getName()))
				{
					otherPlayer = gamePlayer;
					found = true;
					break;
				}
			}
			if (!found)
				throw new PlayerNotFoundException(player.getName());
		}
		final Event event = new Event(pGame, evt, otherPlayer);
		event.setInterest(interest);
		event.setPrincipal(principal);
		event.setTstamp(tstamp);
		event.setWeakCards(weakCards);
		event.setMediumCards(mediumCards);
		event.setStrongCards(strongCards);
		event.setWeakCoins(weakCoins);
		event.setMediumCoins(mediumCoins);
		event.setStrongCoins(strongCoins);
		return event;
	}
}
