package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import jakarta.persistence.Column;
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
 * Étape 3, mode smartphone : une transaction individuelle carte-contre-jetons
 * entre deux joueurs (§5.1 du cahier des charges étape 3, "modèle de
 * transaction individuelle" - brique demandée explicitement par
 * l'utilisateur : "je souhaite enregistrer les cartes, les jetons, les
 * personnes, le tour et le temps").
 * <p>
 * Volontairement séparée d'{@link Event} : {@code Event} reste le journal
 * d'événements de l'ANIMATEUR (crédits, morts, tours...), déjà utilisé par
 * les deux systèmes de monnaie et par l'app Swing - une {@code Transaction}
 * est initiée par un JOUEUR depuis son smartphone, un usage assez différent
 * pour mériter sa propre table plutôt que de surcharger {@code Event} d'un
 * énième cas particulier. Elle ne modifie d'ailleurs aucun état du moteur
 * (contrairement à {@code Event.applyEvent()}) : à ce stade, elle ne fait que
 * consigner l'échange pour permettre des statistiques ou une exploitation des
 * données a posteriori (courbe de richesse en continu, notamment - voir
 * StatsService.computeWealthOverTime, dont la portée actuelle est limitée aux
 * évaluations Mort/Fin de partie faute de ce journal).
 * <p>
 * ⚠️ Portée actuelle volontairement limitée (voir cahier des charges §5.1,
 * "modèle de données oui, comportement non - pas pour cette étape") :
 * <ul>
 * <li>{@code cardTypeId} référence le catalogue LOGIQUE des types de carte
 *     (ex. "carte_001", voir CatalogService côté serveur web), pas encore un
 *     identifiant d'EXEMPLAIRE unique en jeu - cette table ne sait donc pas
 *     encore répondre à "quel exemplaire précis du Blé a changé de main",
 *     seulement "un Blé a changé de main". Le passage à un identifiant
 *     d'exemplaire (nécessaire pour la vraie traçabilité MDBC) est un second
 *     temps, une fois ce premier journal en usage réel.</li>
 * <li>Les jetons sont comptés par niveau (comme {@code Event.weakCoins}/
 *     {@code mediumCoins}/{@code strongCoins}), pas encore identifiables un
 *     par un non plus, pour la même raison.</li>
 * <li>Aucune vérification serveur qu'une offre de vente a réellement été
 *     scannée (pas de notion de "listing" temporaire côté serveur pour
 *     l'instant) : le prix et le type de carte transitent tels quels depuis
 *     le QR code affiché par le vendeur. À revoir si un abus est constaté une
 *     fois le vrai flux d'achat/vente construit côté écrans joueur.</li>
 * </ul>
 */
@Entity
public class Transaction implements Serializable
{
	@TableGenerator(
		name="txGen",
		table="ID_GEN",
		pkColumnName="GEN_KEY",
		valueColumnName="GEN_VALUE",
		pkColumnValue="TX_ID",
		allocationSize=1
	)
	@XmlTransient
	@GeneratedValue(strategy=GenerationType.TABLE, generator="txGen")
	@Id
	private Integer id;

	// Identifiant unique et stable de cette transaction, distinct de l'id
	// technique JPA ci-dessus - préparation MDBC (cahier des charges §5.1,
	// "cartes et jetons uniques et identifiables") : un UUID généré une fois
	// à la création, jamais réutilisé, pour pouvoir être référencé/exposé
	// (ex. reçu affiché au joueur) sans dépendre du détail d'implémentation
	// qu'est l'id auto-incrémenté de la base.
	private String uuid;

	// Protection anti-rejeu du QR code autonome (voir §5.1, flux achat/vente) :
	// un code court, généré côté CLIENT par le vendeur au moment où il affiche
	// son QR (pas d'appel serveur pour le créer - décision prise avec
	// l'utilisateur le 27/08/2026 pour privilégier la rapidité), embarqué tel
	// quel dans le QR. Le serveur refuse toute transaction dont le nonce a déjà
	// servi (voir GameService.recordTransaction) : ça ne protège pas contre
	// une capture d'écran réutilisée AVANT le premier scan légitime, mais ça
	// empêche un même QR d'être validé deux fois - le compromis rapidité/
	// robustesse choisi pour cette étape.
	@Column(unique=true)
	private String nonce;

	@XmlIDREF
	@ManyToOne
	@JoinColumn(nullable=false)
	private Game game;

	// Le joueur qui VEND la carte et REÇOIT les jetons.
	@XmlIDREF
	@ManyToOne
	@JoinColumn(nullable=false)
	private Player seller;

	// Le joueur qui ACHÈTE la carte et PAIE les jetons.
	@XmlIDREF
	@ManyToOne
	@JoinColumn(nullable=false)
	private Player buyer;

	// Numéro de tour au moment de la transaction (voir Game.getTurnNumber()) -
	// dénormalisé ici plutôt que recalculé après coup : le tour "au moment de
	// l'échange" doit rester figé même si la partie a avancé depuis.
	private int turnNumber;

	@Temporal(TemporalType.TIMESTAMP)
	private Date tstamp;

	// Référence au catalogue LOGIQUE des types de carte (voir CatalogService/
	// "cartes.json" côté serveur web, ex. "carte_001") - PAS une clé étrangère
	// JPA : ce catalogue vit hors base de données, dans un simple fichier
	// JSON (voir le raisonnement dans CatalogService.java côté geco-server).
	private String cardTypeId;

	// Niveau de la carte au moment de la transaction (faible/moyenne/forte/
	// tresforte) - dénormalisé : si le catalogue reclasse ce type de carte à
	// un autre niveau plus tard, l'historique déjà enregistré ne doit pas
	// changer rétroactivement (même principe que "principal"/"interest" figés
	// sur chaque Event plutôt que recalculés depuis l'état courant du joueur).
	private String cardLevel;

	// Jetons payés par l'acheteur, par niveau - même convention qu'Event
	// (weakCoins/mediumCoins/strongCoins), pas encore des jetons identifiables
	// un par un (voir la portée limitée en tête de fichier).
	private int weakCoins;
	private int mediumCoins;
	private int strongCoins;

	// Troc uniquement (voir Game.MONEY_TROC) : cartes données par l'ACHETEUR
	// en échange, par niveau - jamais de jetons en troc (règle 3 de
	// docs/10-etape-plugins-troc.md, "jamais de monnaie ni de jeton d'aucune
	// sorte"), donc weakCoins/mediumCoins/strongCoins ci-dessus restent
	// toujours à 0 pour ces transactions. Ajoutés le 28/08/2026, en même
	// temps que la reprise du système de valeur ×4 par niveau (voir
	// StatsService.computeGain, cas MONEY_TROC) - une transaction troc doit
	// pouvoir alimenter le même calcul de richesse.
	private int buyerWeakGoods;
	private int buyerMediumGoods;
	private int buyerStrongGoods;

	// Utilisé par EclipseLink pour instancier des objets vides.
	@SuppressWarnings("unused")
	private Transaction()
	{
		super();
	}

	/** Constructeur pour une transaction monnaie (dette/libre) : carte contre jetons. */
	public Transaction(final Game pGame, final Player pSeller, final Player pBuyer, final String pCardTypeId,
			final String pCardLevel, final int pWeakCoins, final int pMediumCoins, final int pStrongCoins,
			final String pNonce)
	{
		this(pGame, pSeller, pBuyer, pCardTypeId, pCardLevel, pWeakCoins, pMediumCoins, pStrongCoins, 0, 0, 0, pNonce);
	}

	/**
	 * Constructeur complet - carte contre jetons (dette/libre, buyerWeak/Medium/
	 * StrongGoods valent alors 0) OU carte contre cartes (troc, weakCoins/
	 * mediumCoins/strongCoins valent alors 0). Jamais les deux à la fois : un
	 * système de jeu utilise l'un ou l'autre, jamais un mélange (voir
	 * Game.moneySystem).
	 */
	public Transaction(final Game pGame, final Player pSeller, final Player pBuyer, final String pCardTypeId,
			final String pCardLevel, final int pWeakCoins, final int pMediumCoins, final int pStrongCoins,
			final int pBuyerWeakGoods, final int pBuyerMediumGoods, final int pBuyerStrongGoods, final String pNonce)
	{
		super();
		uuid = UUID.randomUUID().toString();
		nonce = pNonce;
		game = pGame;
		seller = pSeller;
		buyer = pBuyer;
		turnNumber = pGame.getTurnNumber();
		tstamp = new Date();
		cardTypeId = pCardTypeId;
		cardLevel = pCardLevel;
		weakCoins = pWeakCoins;
		mediumCoins = pMediumCoins;
		strongCoins = pStrongCoins;
		buyerWeakGoods = pBuyerWeakGoods;
		buyerMediumGoods = pBuyerMediumGoods;
		buyerStrongGoods = pBuyerStrongGoods;
	}

	public String getNonce()
	{
		return nonce;
	}

	public Integer getId()
	{
		return id;
	}

	public String getUuid()
	{
		return uuid;
	}

	public Game getGame()
	{
		return game;
	}

	public Player getSeller()
	{
		return seller;
	}

	public Player getBuyer()
	{
		return buyer;
	}

	public int getTurnNumber()
	{
		return turnNumber;
	}

	public Date getTstamp()
	{
		return tstamp;
	}

	public String getCardTypeId()
	{
		return cardTypeId;
	}

	public String getCardLevel()
	{
		return cardLevel;
	}

	public int getWeakCoins()
	{
		return weakCoins;
	}

	public int getMediumCoins()
	{
		return mediumCoins;
	}

	public int getStrongCoins()
	{
		return strongCoins;
	}

	public int getBuyerWeakGoods()
	{
		return buyerWeakGoods;
	}

	public int getBuyerMediumGoods()
	{
		return buyerMediumGoods;
	}

	public int getBuyerStrongGoods()
	{
		return buyerStrongGoods;
	}

	/** Vrai si cette transaction est un échange troc (carte contre cartes), pas un achat en jetons. */
	public boolean isGoodsTrade()
	{
		return (buyerWeakGoods > 0) || (buyerMediumGoods > 0) || (buyerStrongGoods > 0);
	}

	/** Valeur totale en jetons payée, dans la même convention que le reste du moteur (1/2/4). */
	public int totalCoinsValue()
	{
		return weakCoins + 2 * mediumCoins + 4 * strongCoins;
	}

	/**
	 * Valeur totale des cartes données par l'acheteur en troc, dans la même
	 * convention ×4 par niveau que {@link jyt.geconomicus.helper.server.
	 * StatsService#computeGain} (voir docs/10-etape-plugins-troc.md, mise à
	 * jour du 28/08/2026).
	 */
	public int totalGoodsValue()
	{
		return buyerWeakGoods + (4 * buyerMediumGoods) + (16 * buyerStrongGoods);
	}
}
