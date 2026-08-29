package jyt.geconomicus.helper.server;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import jyt.geconomicus.helper.CardSquareEvent;
import jyt.geconomicus.helper.Event;
import jyt.geconomicus.helper.Event.EventType;
import jyt.geconomicus.helper.EventTypeConverter;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;
import jyt.geconomicus.helper.PlayerNotFoundException;
import jyt.geconomicus.helper.Transaction;

/**
 * Couche de service qui encapsule les opérations JPA sur Game/Player/Event.
 * Utilisée par les routes REST de {@link GecoServer}. C'est le pendant, côté web,
 * de ce que HelperUI fait directement sur un EntityManager dans l'app Swing : la
 * logique métier elle-même (Game/Player/Event) reste strictement identique.
 *
 * Choix d'implémentation : chaque méthode ouvre et referme son propre EntityManager
 * (pattern "un EntityManager par requête/transaction courte"), plutôt que d'en garder
 * un seul ouvert en permanence. C'est le pattern recommandé en environnement multi-thread :
 * un EntityManager n'est pas thread-safe, et Javalin traite les requêtes HTTP sur des
 * threads séparés (un par requête).
 */
public class GameService
{
	private final EntityManagerFactory mEntityManagerFactory;

	public GameService(final EntityManagerFactory pEntityManagerFactory)
	{
		mEntityManagerFactory = pEntityManagerFactory;
	}

	/**
	 * Exposé pour {@link BackupService}, qui a besoin d'émettre une requête SQL
	 * native (BACKUP TO) - pas de raison de dupliquer la connexion à la base
	 * plutôt que de réutiliser celle déjà créée pour GameService.
	 */
	public EntityManagerFactory getEntityManagerFactory()
	{
		return mEntityManagerFactory;
	}

	public List<Game> listGames()
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.createNamedQuery("Game.findAll", Game.class).getResultList();
		}
		finally
		{
			em.close();
		}
	}

	public Game getGame(final int pId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.find(Game.class, pId);
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Définit le PIN d'une partie - appelé juste après sa création si la
	 * protection par code est activée (voir AppSettings.protectionEnabled et
	 * GecoServer, route POST /api/games). Une transaction séparée de
	 * {@link #createGame}, qui a déjà validé la sienne à ce stade.
	 */
	public void setGamePin(final int pGameId, final String pPin)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			em.getTransaction().begin();
			game.setPin(pPin);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Vérifie qu'un PIN correspond bien à celui d'une partie - voir GecoServer,
	 * route POST /api/games/{id}/unlock et le filtre appliqué aux autres routes
	 * /api/games/{id}/*. Une partie sans PIN (protection désactivée à sa
	 * création, ou partie créée avant l'ajout de cette fonctionnalité) accepte
	 * toujours l'accès - jamais bloquée après coup par un changement de réglage
	 * global.
	 */
	public boolean verifyGamePin(final int pGameId, final String pSubmittedPin)
	{
		final Game game = getGame(pGameId);
		if (game == null)
			return false;
		final String pin = game.getPin();
		if ((pin == null) || pin.isEmpty())
			return true; // partie non protégée : toujours accessible
		return pin.equals(pSubmittedPin);
	}

	public Game createGame(final int pMoneySystem, final int pNbTurnsPlanned, final String pAnimatorPseudo,
			final String pAnimatorEmail, final String pDescription, final String pCurDate, final String pLocation,
			final int pMoneyCardsFactor, final int pTurnDurationSeconds, final double pWeakCoinValue,
			final boolean pTokenPenalty, final int pStartingGoods, final boolean pStrictTrm)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = new Game(pMoneySystem, pNbTurnsPlanned, pAnimatorPseudo, pAnimatorEmail, pDescription,
					pCurDate, pLocation, pMoneyCardsFactor);
			if (pWeakCoinValue > 0)
				game.setWeakCoinValue(pWeakCoinValue);
			game.setTokenPenalty(pTokenPenalty);
			// Remonté par un utilisateur : mode "strict TRM", propre à la monnaie
			// libre et réglable uniquement ici, à la création - jamais modifiable en
			// cours de route (voir Game.strictTrm, Event.applyEvent pour son effet).
			// Sans effet pour les deux autres systèmes, même si transmis à true par
			// erreur : Event.applyEvent() le vérifie toujours en même temps que
			// MONEY_LIBRE.
			game.setStrictTrm(pStrictTrm);
			if (pTurnDurationSeconds > 0)
				game.setTurnDurationSeconds(pTurnDurationSeconds);
			// Troc uniquement (voir plugins/troc/manifest.json) : dotation de départ en
			// objets. 0 garde la valeur par défaut du moteur (voir Game.startingGoods).
			if (pStartingGoods > 0)
				game.setStartingGoods(pStartingGoods);
			// Remonté par l'utilisateur : le chrono ne doit PAS démarrer à la création de
			// la partie, seulement quand l'animateur clique explicitement sur "Démarrer la
			// partie" (voir startGame() ci-dessous). turnStartedAt reste donc à null ici.
			em.getTransaction().begin();
			em.persist(game);
			em.getTransaction().commit();
			return game;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Supprime une partie et tout ce qui lui est rattaché (joueurs, événements) -
	 * le cascade REMOVE déjà configuré sur Game.players/events (voir Game.java) s'en
	 * charge automatiquement, pas besoin de les supprimer un par un ici.
	 */
	public void deleteGame(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				return; // déjà supprimée / inexistante : rien à faire, pas une erreur
			em.getTransaction().begin();
			em.remove(game);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	public Player addPlayer(final int pGameId, final String pName)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			em.getTransaction().begin();
			final Player player = new Player(game, pName);
			// Étape 3 : jeton d'accès individuel désormais généré pour TOUT
			// joueur, systématiquement - plus seulement si la partie est
			// protégée par PIN (comme à l'étape 2). Depuis l'étape 3, ce jeton
			// n'est plus qu'une sécurité optionnelle : c'est aussi l'identifiant
			// qui permet au joueur d'atteindre son espace personnel
			// (player-view.html - consultation, vente, achat), indispensable
			// que la protection par PIN soit activée ou non. Remonté par un
			// utilisateur (27/08/2026) : sans ce correctif, un joueur inscrit
			// dans une partie non protégée par PIN (le cas par défaut) n'avait
			// tout simplement aucun moyen d'atteindre son propre espace.
			player.setAccessToken(java.util.UUID.randomUUID().toString());
			final Event joinEvent = new Event(game, EventType.JOIN, player);
			joinEvent.applyEvent();
			em.persist(player);
			em.persist(joinEvent);
			em.getTransaction().commit();
			return player;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Auto-inscription d'un joueur depuis son propre smartphone (étape 3, Phase B) :
	 * équivalent de {@link #addPlayer}, mais avec les informations supplémentaires
	 * saisies par le joueur lui-même sur l'écran "Créez votre avatar" (âge déclaré,
	 * couleur d'identité, configuration de l'avatar), et une vérification de nom
	 * dupliqué - plus nécessaire quand c'est l'animateur qui saisit un nom lui-même
	 * en le voyant déjà à l'écran, mais utile ici où deux téléphones pourraient
	 * saisir le même prénom sans le savoir.
	 */
	public Player joinAsPlayer(final int pGameId, final String pName, final Integer pDeclaredAge,
			final String pFavoriteColor, final String pAvatarConfigJson) throws DuplicatePlayerNameException
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final boolean nameTaken = game.getPlayers().stream()
					.anyMatch(p -> p.getName().equalsIgnoreCase(pName));
			if (nameTaken)
				throw new DuplicatePlayerNameException(pName);

			em.getTransaction().begin();
			final Player player = new Player(game, pName);
			player.setDeclaredAge(pDeclaredAge);
			player.setFavoriteColor(pFavoriteColor);
			player.setAvatarConfigJson(pAvatarConfigJson);
			// Étape 3 : voir le même correctif dans addPlayer() ci-dessus - jeton
			// généré systématiquement, plus seulement si la partie est protégée
			// par PIN (indispensable pour atteindre player-view.html).
			player.setAccessToken(java.util.UUID.randomUUID().toString());
			final Event joinEvent = new Event(game, EventType.JOIN, player);
			joinEvent.applyEvent();
			em.persist(player);
			em.persist(joinEvent);
			em.getTransaction().commit();
			return player;
		}
		finally
		{
			em.close();
		}
	}

	/** Levée quand un joueur tente de s'auto-inscrire avec un nom déjà pris dans la partie. */
	public static class DuplicatePlayerNameException extends Exception
	{
		public DuplicatePlayerNameException(final String pName)
		{
			super(pName);
		}
	}

	/**
	 * Enregistre un nouvel événement (crédit, remboursement, mort, nouveau tour, etc.)
	 * et déclenche son application sur l'état du jeu (mêmes règles que Swing/CreditActionDialog).
	 */
	public Event recordEvent(final int pGameId, final String pEventTypeChar, final Integer pPlayerId,
			final int pPrincipal, final int pInterest, final int pWeakCards, final int pMediumCards,
			final int pStrongCards, final Integer pCounterpartyPlayerId, final int pGoodsFromPlayer,
			final int pGoodsFromCounterparty, final int pWeakCoins, final int pMediumCoins, final int pStrongCoins,
			final int pWeakGoodsFromCounterparty, final int pMediumGoodsFromCounterparty,
			final int pStrongGoodsFromCounterparty)
			throws PlayerNotFoundException
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			// Le type d'événement est transmis par le front sous forme d'un caractère unique
			// (ex: "C" pour NEW_CREDIT) plutôt que le nom complet de l'enum : ça reprend exactement
			// le même codage que celui déjà utilisé pour la persistance (voir EventTypeConverter),
			// et ça garde les URLs/payloads courts, cohérent avec les raccourcis clavier historiques
			// de l'app Swing (une touche = un type d'événement).
			final EventType type = EventTypeConverter.getEventType(pEventTypeChar);
			if (type == null)
				throw new IllegalArgumentException("Unknown event type: " + pEventTypeChar); //$NON-NLS-1$
			Player player = null;
			if (pPlayerId != null)
			{
				player = em.find(Player.class, pPlayerId);
				// Correctif sécurité (audit) : sans cette vérification, n'importe qui pouvait
				// enregistrer un événement (crédit, mort, remboursement...) pour un joueur
				// appartenant à une AUTRE partie que celle de l'URL, simplement en devinant un
				// identifiant de joueur valide - un vrai risque une fois plusieurs parties
				// jouées en même temps sur le même réseau (étape 3). Même principe déjà
				// appliqué à deleteEvent()/editEvent() ci-dessous pour les événements.
				if ((player == null) || !player.getGame().equals(game))
					throw new PlayerNotFoundException(String.valueOf(pPlayerId));
			}
			// Troc uniquement (voir plugins/troc/manifest.json) : le second joueur d'un
			// échange GOODS_TRADE - même vérification de propriété que pour "player"
			// ci-dessus, pour la même raison.
			Player counterpartyPlayer = null;
			if (pCounterpartyPlayerId != null)
			{
				counterpartyPlayer = em.find(Player.class, pCounterpartyPlayerId);
				if ((counterpartyPlayer == null) || !counterpartyPlayer.getGame().equals(game))
					throw new PlayerNotFoundException(String.valueOf(pCounterpartyPlayerId));
			}
			em.getTransaction().begin();
			final Event event = new Event(game, type, player);
			event.setPrincipal(pPrincipal);
			event.setInterest(pInterest);
			event.setWeakCards(pWeakCards);
			event.setMediumCards(pMediumCards);
			event.setStrongCards(pStrongCards);
			// Remonté par un utilisateur : distinct des cartes ci-dessus - nécessaire
			// pour que StatsService.computeGain() calcule correctement la richesse
			// d'un joueur en monnaie libre à sa mort/sortie (jetons ET cartes comptent
			// séparément, voir la formule de computeGain). Sans ce champ, jusqu'ici
			// jamais renseigné par aucun appelant, la partie "jetons" de la richesse
			// d'un joueur mourant en monnaie libre était silencieusement ignorée.
			event.setWeakCoins(pWeakCoins);
			event.setMediumCoins(pMediumCoins);
			event.setStrongCoins(pStrongCoins);
			event.setCounterpartyPlayer(counterpartyPlayer);
			event.setGoodsFromPlayer(pGoodsFromPlayer);
			event.setGoodsFromCounterparty(pGoodsFromCounterparty);
			// Détail par niveau donné par la CONTREPARTIE d'un GOODS_TRADE (troc) -
			// remonté par un utilisateur (28/08/2026) : ces trois paramètres
			// existaient déjà dans la signature de cette méthode, mais n'étaient
			// encore jamais appliqués à l'objet Event avant persistance - un oubli
			// qui, combiné à l'appel de route qui ne les transmettait pas non plus
			// (voir GecoServer.java), rendait le suivi par niveau des biens
			// troqués totalement silencieux (toujours 0, quoi que saisisse
			// l'animateur).
			event.setWeakGoodsFromCounterparty(pWeakGoodsFromCounterparty);
			event.setMediumGoodsFromCounterparty(pMediumGoodsFromCounterparty);
			event.setStrongGoodsFromCounterparty(pStrongGoodsFromCounterparty);
			// applyEvent() contient toute la logique métier (calcul de la dette, de la masse
			// monétaire, etc.) : elle est strictement identique à celle utilisée par l'app Swing,
			// puisqu'il s'agit du même code dans geco-engine.
			event.applyEvent();
			em.persist(event);
			// Un nouveau tour commence : on relance le minuteur partagé par tous les clients
			// connectés (voir GecoServer, canal WebSocket) à partir de maintenant.
			if (type == EventType.TURN)
				game.setTurnStartedAt(new java.util.Date());
			em.getTransaction().commit();
			return event;
		}
		finally
		{
			// Le "finally" garantit la fermeture de l'EntityManager même en cas d'exception
			// levée par applyEvent() ou par une violation de contrainte SQL.
			em.close();
		}
	}

	/**
	 * Enregistre une transaction individuelle carte-contre-jetons entre deux
	 * joueurs (étape 3, mode smartphone) - voir Transaction.java pour le
	 * raisonnement complet et sa portée volontairement limitée à ce stade.
	 * Ne modifie aucun état du moteur (contrairement à recordEvent ci-dessus) :
	 * ce n'est qu'un journal, à ce stade purement déclaratif - c'est au joueur
	 * (ou à un futur écran animateur) de refléter le changement de main dans
	 * son inventaire, cette méthode ne fait qu'en garder la trace.
	 */
	public Transaction recordTransaction(final int pGameId, final int pSellerPlayerId, final int pBuyerPlayerId,
			final String pCardTypeId, final String pCardLevel, final int pWeakCoins, final int pMediumCoins,
			final int pStrongCoins, final int pBuyerWeakGoods, final int pBuyerMediumGoods,
			final int pBuyerStrongGoods, final String pNonce, final long pExpiresAtEpochMs)
			throws PlayerNotFoundException
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			// Même vérification de propriété que pour recordEvent() ci-dessus :
			// un joueur ne peut être ni vendeur ni acheteur dans une transaction
			// d'une AUTRE partie que celle de l'URL.
			final Player seller = em.find(Player.class, pSellerPlayerId);
			if ((seller == null) || !seller.getGame().equals(game))
				throw new PlayerNotFoundException(String.valueOf(pSellerPlayerId));
			final Player buyer = em.find(Player.class, pBuyerPlayerId);
			if ((buyer == null) || !buyer.getGame().equals(game))
				throw new PlayerNotFoundException(String.valueOf(pBuyerPlayerId));
			if (seller.equals(buyer))
				throw new IllegalArgumentException("Le vendeur et l'acheteur ne peuvent pas être le même joueur."); //$NON-NLS-1$
			// Protection anti-rejeu du QR autonome (voir Transaction.java) : le
			// nonce est généré côté client par le vendeur, jamais par le serveur -
			// on se contente donc ici de vérifier qu'il n'a encore jamais servi,
			// et que le délai annoncé au vendeur (~90s, affiché en compte à
			// rebours) n'est pas dépassé.
			if ((pNonce == null) || pNonce.isBlank())
				throw new IllegalArgumentException("Nonce manquant."); //$NON-NLS-1$
			final long nonceCount = em.createQuery("SELECT COUNT(t) FROM Transaction t WHERE t.nonce = :nonce", //$NON-NLS-1$
					Long.class).setParameter("nonce", pNonce).getSingleResult(); //$NON-NLS-1$
			if (nonceCount > 0)
				throw new IllegalArgumentException("Ce QR code a déjà été utilisé."); //$NON-NLS-1$
			if (System.currentTimeMillis() > pExpiresAtEpochMs)
				throw new IllegalArgumentException("Ce QR code a expiré, demandez-en un nouveau au vendeur."); //$NON-NLS-1$
			em.getTransaction().begin();
			final Transaction transaction = new Transaction(game, seller, buyer, pCardTypeId, pCardLevel, pWeakCoins,
					pMediumCoins, pStrongCoins, pBuyerWeakGoods, pBuyerMediumGoods, pBuyerStrongGoods, pNonce);
			em.persist(transaction);
			// Troc uniquement (voir Transaction.isGoodsTrade()) : contrairement aux
			// jetons (dette/libre - jamais un solde stocké sur Player, toujours
			// recalculé depuis l'historique, voir GameService.computeTradeBalance),
			// les biens troc SONT suivis en direct sur Player.weakGoods/
			// mediumGoods/strongGoods (voir Event.applyEvent(), cas GOODS_TRADE,
			// et le correctif du 28/08/2026 qui a comblé ce même suivi pour
			// l'échange classique). Une transaction smartphone doit donc mettre à
			// jour ces champs elle aussi, sous peine du même bug déjà corrigé pour
			// le dialogue animateur : un suivi par niveau qui ne bouge jamais.
			// Le vendeur perd la carte de niveau pCardLevel (représentée comme un
			// bien de CE niveau) et reçoit les biens donnés par l'acheteur ;
			// l'inverse pour l'acheteur.
			if (game.getMoneySystem() == Game.MONEY_TROC)
			{
				applyGoodsLevelDelta(seller, pCardLevel, -1);
				applyGoodsLevelDelta(seller, "faible", pBuyerWeakGoods); //$NON-NLS-1$
				applyGoodsLevelDelta(seller, "moyenne", pBuyerMediumGoods); //$NON-NLS-1$
				applyGoodsLevelDelta(seller, "forte", pBuyerStrongGoods); //$NON-NLS-1$
				applyGoodsLevelDelta(buyer, pCardLevel, 1);
				applyGoodsLevelDelta(buyer, "faible", -pBuyerWeakGoods); //$NON-NLS-1$
				applyGoodsLevelDelta(buyer, "moyenne", -pBuyerMediumGoods); //$NON-NLS-1$
				applyGoodsLevelDelta(buyer, "forte", -pBuyerStrongGoods); //$NON-NLS-1$
				seller.setGoodsCount(seller.getGoodsCount() - 1 + pBuyerWeakGoods + pBuyerMediumGoods + pBuyerStrongGoods);
				buyer.setGoodsCount(buyer.getGoodsCount() + 1 - pBuyerWeakGoods - pBuyerMediumGoods - pBuyerStrongGoods);
			}
			final boolean isLibre = game.getMoneySystem() == Game.MONEY_LIBRE;
			final int sellerId = seller.getId();
			final int buyerId = buyer.getId();
			em.getTransaction().commit();
			// Encaissement automatique des carrés (voir checkAndCashInSquares) -
			// APRÈS le commit ci-dessus et EN DEHORS de cette transaction/cet
			// EntityManager (checkAndCashInSquares gère les siens, une seule
			// EntityManager ne pouvant pas imbriquer plusieurs transactions
			// actives) - monnaie libre uniquement pour l'instant (voir la
			// question posée à l'utilisateur le 28/08/2026 : troc/dette pas
			// encore concernés par un vrai inventaire suivi). Le vendeur ET
			// l'acheteur sont vérifiés : l'un des deux peut avoir complété un
			// carré par cet échange précis.
			if (isLibre)
			{
				checkAndCashInSquares(pGameId, sellerId);
				checkAndCashInSquares(pGameId, buyerId);
			}
			return transaction;
		}
		finally
		{
			em.close();
		}
	}

	// Applique un delta (positif ou négatif) au champ de biens correspondant
	// au niveau donné - voir recordTransaction ci-dessus (troc uniquement).
	// "tresforte" volontairement absent : aucune carte de ce niveau ne peut
	// aujourd'hui être échangée par smartphone (le sélecteur de prix ne
	// propose que faible/moyenne/forte, voir player-view.js) - à réviser si
	// ça change un jour.
	private void applyGoodsLevelDelta(final Player pPlayer, final String pLevel, final int pDelta)
	{
		if (pDelta == 0)
			return;
		switch (pLevel)
		{
			case "faible": //$NON-NLS-1$
				pPlayer.setWeakGoods(pPlayer.getWeakGoods() + pDelta);
				break;
			case "moyenne": //$NON-NLS-1$
				pPlayer.setMediumGoods(pPlayer.getMediumGoods() + pDelta);
				break;
			case "forte": //$NON-NLS-1$
				pPlayer.setStrongGoods(pPlayer.getStrongGoods() + pDelta);
				break;
			default:
				// Niveau inconnu/non pris en charge (ex. "tresforte") : ignoré
				// plutôt que de faire échouer toute la transaction pour un champ
				// d'affichage secondaire (le suivi par niveau resterait alors
				// simplement légèrement imprécis pour ce cas rare).
				break;
		}
	}

	/**
	 * Liste les transactions individuelles d'une partie, plus récentes en
	 * premier - utilisé par l'écran de statistiques/historique (voir
	 * renderTransactionsPanel côté web) et par l'historique joueur du mode
	 * smartphone (§5.1, écran "Historique").
	 */
	public List<Transaction> listTransactions(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.createQuery(
					"SELECT t FROM Transaction t WHERE t.game.id = :gameId ORDER BY t.tstamp DESC", //$NON-NLS-1$
					Transaction.class)
					.setParameter("gameId", pGameId) //$NON-NLS-1$
					.getResultList();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Solde en jetons d'un joueur, DÉRIVÉ de l'historique des transactions
	 * individuelles carte-contre-jetons (étape 3, mode smartphone) - voir
	 * Transaction.java. Volontairement distinct de curDebt/curInterest (le
	 * système dette/libre "classique", qui ne connaît pas ce mécanisme) :
	 * ce solde ne reflète QUE les ventes/achats de cartes par QR, pas une
	 * dotation initiale ni les intérêts de crédit - remonté un utilisateur
	 * (28/08/2026) souhaitant afficher "solde avant/après" sur les écrans
	 * d'achat, sur le modèle de son mockup de référence. N'invente aucune
	 * donnée : un joueur qui n'a encore fait aucun échange a un solde de 0,
	 * affiché tel quel plutôt que de simuler une dotation de départ que le
	 * jeu ne définit pas encore pour ce mode.
	 */
	public int computeTradeBalance(final int pGameId, final int pPlayerId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Player player = em.find(Player.class, pPlayerId);
			// Dotation de départ (voir dealStartingHandsForLibreIfNeeded) : 1
			// jeton faible + 1 moyen + 1 fort = valeur 7, une CONSTANTE fixée
			// par les règles (geconomicus.glibre.org/libre_money.html) - jamais
			// stockée séparément, juste ajoutée dès que ce joueur a bien reçu
			// sa mise en place (startingCardsJson non nul, posé au même
			// moment). Avant ce correctif (28/08/2026), tout joueur partait
			// systématiquement de 0, ignorant cette dotation initiale.
			final int startingValue = ((player != null) && (player.getStartingCardsJson() != null)) ? 7 : 0;
			final List<Transaction> txs = em.createQuery(
					"SELECT t FROM Transaction t WHERE t.game.id = :gameId AND (t.seller.id = :pid OR t.buyer.id = :pid)", //$NON-NLS-1$
					Transaction.class)
					.setParameter("gameId", pGameId).setParameter("pid", pPlayerId) //$NON-NLS-1$ //$NON-NLS-2$
					.getResultList();
			int balance = startingValue;
			for (final Transaction t : txs)
			{
				if (t.getSeller().getId().equals(pPlayerId))
					balance += t.totalCoinsValue();
				if (t.getBuyer().getId().equals(pPlayerId))
					balance -= t.totalCoinsValue();
			}
			return balance;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Inventaire de cartes d'un joueur, DÉRIVÉ de l'historique des transactions
	 * smartphone (même principe que {@link #computeTradeBalance} pour les
	 * jetons) - construit à la demande de l'utilisateur (28/08/2026, mockup de
	 * référence "Mes cartes") : n'invente aucune donnée, un joueur qui n'a
	 * encore rien acheté a un inventaire vide. Comme convenu explicitement
	 * avec l'utilisateur, ne couvre QUE ce qui a été échangé par smartphone
	 * depuis ce correctif - tout échange antérieur reste invisible, faute
	 * d'avoir jamais été enregistré individuellement avant l'étape 3.
	 */
	public java.util.Map<String, Integer> computePlayerCardInventory(final int pGameId, final int pPlayerId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final java.util.Map<String, Integer> inventory = new java.util.LinkedHashMap<>();
			// Point de départ : la dotation initiale (voir
			// dealStartingHandsForLibreIfNeeded/Player.startingCardsJson) -
			// avant ce correctif (28/08/2026), cet inventaire partait toujours
			// de zéro, ignorant les 4 cartes reçues à la mise en place.
			final Player player = em.find(Player.class, pPlayerId);
			if ((player != null) && (player.getStartingCardsJson() != null))
			{
				try
				{
					final java.util.Map<String, Integer> startingHand = new com.fasterxml.jackson.databind.ObjectMapper()
							.readValue(player.getStartingCardsJson(),
									new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Integer>>()
									{
									});
					inventory.putAll(startingHand);
				}
				catch (final com.fasterxml.jackson.core.JsonProcessingException e)
				{
					// Donnée corrompue (ne devrait jamais arriver) : on continue avec
					// un inventaire vide plutôt que de faire échouer tout l'écran.
				}
			}
			final List<Transaction> txs = em.createQuery(
					"SELECT t FROM Transaction t WHERE t.game.id = :gameId AND (t.seller.id = :pid OR t.buyer.id = :pid)", //$NON-NLS-1$
					Transaction.class)
					.setParameter("gameId", pGameId).setParameter("pid", pPlayerId) //$NON-NLS-1$ //$NON-NLS-2$
					.getResultList();
			for (final Transaction t : txs)
			{
				if (t.getBuyer().getId().equals(pPlayerId))
					inventory.merge(t.getCardTypeId(), 1, Integer::sum);
				if (t.getSeller().getId().equals(pPlayerId))
					inventory.merge(t.getCardTypeId(), -1, Integer::sum);
			}
			// Historique des carrés déjà encaissés (voir CardSquareEvent,
			// checkAndCashInSquares) : les 4 cartes défaussées quittent
			// l'inventaire, la carte promue et les 4 cartes de remplacement y
			// entrent - un carré n'est PAS une Transaction (pas d'échange entre
			// deux joueurs, une interaction avec la pioche partagée), d'où ce
			// second journal, rejoué séparément ici.
			final List<CardSquareEvent> squareEvents = em.createQuery(
					"SELECT s FROM CardSquareEvent s WHERE s.game.id = :gameId AND s.player.id = :pid", //$NON-NLS-1$
					CardSquareEvent.class)
					.setParameter("gameId", pGameId).setParameter("pid", pPlayerId) //$NON-NLS-1$ //$NON-NLS-2$
					.getResultList();
			for (final CardSquareEvent square : squareEvents)
			{
				inventory.merge(square.getCashedCardTypeId(), -4, Integer::sum);
				inventory.merge(square.getPromotedCardTypeId(), 1, Integer::sum);
				try
				{
					final java.util.List<String> replenished = new com.fasterxml.jackson.databind.ObjectMapper()
							.readValue(square.getReplenishedCardIdsJson(),
									new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<String>>()
									{
									});
					for (final String cardId : replenished)
						inventory.merge(cardId, 1, Integer::sum);
				}
				catch (final com.fasterxml.jackson.core.JsonProcessingException e)
				{
					// Donnée corrompue (ne devrait jamais arriver) : on ignore ce
					// carré précis plutôt que de faire échouer tout l'inventaire.
				}
			}
			// Ne devrait normalement jamais arriver (on ne peut pas vendre une carte
			// qu'on n'a pas), mais on nettoie par sécurité plutôt que d'afficher un
			// nombre négatif absurde - ex. cartes détenues avant l'usage du
			// smartphone, vendues ensuite via lui (voir la limite assumée
			// ci-dessus).
			inventory.values().removeIf(v -> v <= 0);
			return inventory;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Classement des joueurs actifs d'une partie par richesse - demandé par
	 * l'utilisateur (28/08/2026, mockup de référence "Classement de la
	 * partie"). Formule volontairement simple et cohérente avec ce que
	 * chaque joueur voit déjà sur son propre espace : {@link #computeTradeBalance}
	 * (jetons issus des échanges smartphone) en dette/libre, valeur pondérée
	 * ×4 par niveau des biens en troc (voir Transaction.totalGoodsValue et
	 * StatsService.computeGain, cas MONEY_TROC) - PAS une reconstitution
	 * complète de la richesse totale du jeu (dotation de départ, DU perçus
	 * au fil des tours...), qui demanderait de rejouer tout l'historique
	 * d'événements plutôt que les seules transactions.
	 */
	public List<Dtos.LeaderboardEntryDto> computeLeaderboard(final int pGameId)
	{
		final Game game = getGame(pGameId);
		if (game == null)
			return List.of();
		final List<Dtos.LeaderboardEntryDto> entries = new java.util.ArrayList<>();
		for (final Player p : game.getPlayers())
		{
			if (!p.isActive())
				continue;
			final int value = (game.getMoneySystem() == Game.MONEY_TROC)
					? p.getWeakGoods() + (4 * p.getMediumGoods()) + (16 * p.getStrongGoods())
					: computeTradeBalance(pGameId, p.getId());
			entries.add(new Dtos.LeaderboardEntryDto(p.getId(), p.getName(), value, 0));
		}
		entries.sort((a, b) -> Integer.compare(b.value(), a.value()));
		final List<Dtos.LeaderboardEntryDto> ranked = new java.util.ArrayList<>();
		for (int i = 0; i < entries.size(); i++)
		{
			final Dtos.LeaderboardEntryDto e = entries.get(i);
			ranked.add(new Dtos.LeaderboardEntryDto(e.playerId(), e.playerName(), e.value(), i + 1));
		}
		return ranked;
	}

	/**
	 * Étape 3, monnaie libre, mode smartphone : capture, UNE SEULE FOIS, le
	 * nombre de joueurs actifs au moment de la mise en place (premier tour
	 * démarré) - voir Game.deckPlayerCount pour le raisonnement complet
	 * (dimensionnement du futur paquet de cartes, document de cadrage du
	 * 28/08/2026 et geconomicus.glibre.org/rules.html). Appelée depuis la
	 * route POST /events juste après l'enregistrement réussi d'un événement
	 * TURN, uniquement si le mode smartphone est actif - vérifié côté
	 * appelant, qui a accès aux réglages globaux (AppSettings), contrairement
	 * à GameService qui n'y touche jamais directement.
	 * <p>
	 * Idempotent et sans effet en dehors du tout premier tour : rien ne se
	 * passe si rappelée par erreur, si la partie n'est pas en monnaie libre,
	 * ou si ce nombre a déjà été capturé - jamais recalculé une fois posé,
	 * exactement comme une vraie mise en place ne se refait pas en cours de
	 * partie.
	 */
	public void captureDeckPlayerCountIfNeeded(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				return;
			if ((game.getMoneySystem() != Game.MONEY_LIBRE) || (game.getTurnNumber() != 1)
					|| (game.getDeckPlayerCount() != null))
				return;
			final long activeCount = game.getPlayers().stream().filter(Player::isActive).count();
			em.getTransaction().begin();
			game.setDeckPlayerCount((int) activeCount);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Étape 3, monnaie libre, mode smartphone : mise en place complète, une
	 * fois {@link #captureDeckPlayerCountIfNeeded} passée - voir le document
	 * de cadrage du 28/08/2026, geconomicus.glibre.org/libre_money.html et
	 * docs/04-etape3-catalogue-cartes.md (modèle SIMPLIFIÉ, confirmé par
	 * l'utilisateur : pas de rotation complexe des 4 niveaux - les 4 pioches
	 * (faible/moyenne/forte/tresforte) sont TOUTES préparées dès le départ,
	 * même si seule "faible" est distribuée aux joueurs à cet instant. Les
	 * trois autres attendent sur la table, prêtes à être piochées lors d'un
	 * futur "carré" (voir {@link #checkAndCashInSquares}).
	 * <p>
	 * Sélectionne N+1 modèles PAR NIVEAU (N = {@code Game.deckPlayerCount},
	 * plafonné au nombre de modèles réellement disponibles au catalogue pour
	 * ce niveau), mint 5 exemplaires de chacun, distribue 4 cartes de niveau
	 * faible au hasard à chaque joueur actif, et donne à chacun sa dotation
	 * de départ en jetons - 1 faible + 1 moyen + 1 fort (valeur 7), une
	 * CONSTANTE fixée par les règles ("distribuer les trois couleurs à
	 * chaque joueur (1 billet de chacune)"), jamais stockée séparément :
	 * {@link #computeTradeBalance} l'ajoute simplement dès que {@code
	 * Player.startingCardsJson} n'est plus nul (voir ce champ).
	 * <p>
	 * Idempotent : ne fait rien si déjà effectuée ({@code
	 * Game.smartphoneCardPileJson} déjà renseigné) ou si les conditions ne
	 * sont pas réunies (partie pas en monnaie libre, ou
	 * {@code deckPlayerCount} pas encore capturé).
	 *
	 * @param pAvailableCardIdsByLevel pour chaque niveau ("faible", "moyenne",
	 *            "forte", "tresforte"), les identifiants de cartes
	 *            disponibles au catalogue - fourni par l'appelant
	 *            (GecoServer, qui a accès au catalogue ; GameService, lui, ne
	 *            le consulte jamais directement, voir la règle déjà en place
	 *            pour AppSettings).
	 */
	public void dealStartingHandsForLibreIfNeeded(final int pGameId,
			final java.util.Map<String, List<String>> pAvailableCardIdsByLevel)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				return;
			if ((game.getMoneySystem() != Game.MONEY_LIBRE) || (game.getDeckPlayerCount() == null)
					|| (game.getSmartphoneCardPileJson() != null))
				return; // déjà fait, ou conditions non réunies - jamais recalculé

			final int nbPlayers = game.getDeckPlayerCount();
			final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			// Une pioche par niveau, toutes préparées maintenant (voir le
			// commentaire de tête de méthode - modèle simplifié).
			final java.util.Map<String, java.util.Map<String, Integer>> pilesByLevel = new java.util.LinkedHashMap<>();
			java.util.List<String> faibleBag = null; // conservé à part : c'est le seul qu'on distribue tout de suite
			for (final String level : java.util.List.of("faible", "moyenne", "forte", "tresforte")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			{
				final List<String> available = pAvailableCardIdsByLevel.getOrDefault(level, List.of());
				if (available.isEmpty())
				{
					pilesByLevel.put(level, new java.util.LinkedHashMap<>());
					continue;
				}
				final int nbModels = Math.min(nbPlayers + 1, available.size());
				final java.util.List<String> shuffledModels = new java.util.ArrayList<>(available);
				java.util.Collections.shuffle(shuffledModels);
				final java.util.List<String> selectedModels = shuffledModels.subList(0, nbModels);
				final java.util.List<String> bag = new java.util.ArrayList<>();
				for (final String modelId : selectedModels)
					for (int i = 0; i < 5; i++)
						bag.add(modelId);
				java.util.Collections.shuffle(bag);
				if ("faible".equals(level)) //$NON-NLS-1$
					faibleBag = bag; // distribué juste après, voir plus bas
				final java.util.Map<String, Integer> pile = new java.util.LinkedHashMap<>();
				for (final String modelId : selectedModels)
					pile.put(modelId, 0); // tous les modèles sélectionnés apparaissent, même à 0
				for (final String cardId : bag)
					pile.merge(cardId, 1, Integer::sum);
				pilesByLevel.put(level, pile);
			}

			em.getTransaction().begin();
			final List<Player> activePlayers = game.getPlayers().stream().filter(Player::isActive).toList();
			if (faibleBag != null)
			{
				// Distribue 4 cartes de niveau faible par joueur, en les retirant
				// de la pioche "faible" déjà préparée ci-dessus (pilesByLevel).
				final java.util.Map<String, Integer> faiblePile = pilesByLevel.get("faible"); //$NON-NLS-1$
				int bagIndex = 0;
				for (final Player player : activePlayers)
				{
					final java.util.Map<String, Integer> hand = new java.util.LinkedHashMap<>();
					for (int i = 0; (i < 4) && (bagIndex < faibleBag.size()); i++)
					{
						final String cardId = faibleBag.get(bagIndex++);
						hand.merge(cardId, 1, Integer::sum);
						faiblePile.merge(cardId, -1, Integer::sum); // retiré de la pioche, remis en main du joueur
					}
					writeJsonQuietly(mapper, hand, player::setStartingCardsJson);
				}
			}
			writeJsonQuietly(mapper, pilesByLevel, game::setSmartphoneCardPileJson);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	// Ordre fixe des niveaux (modèle SIMPLIFIÉ confirmé par l'utilisateur, voir
	// docs/04-etape3-catalogue-cartes.md : pas de rotation des 4 niveaux - un
	// carré fait toujours progresser vers le niveau immédiatement supérieur
	// dans CET ordre fixe, jusqu'à "tresforte" qui reste le sommet).
	private static final java.util.List<String> LEVEL_ORDER = java.util.List.of("faible", "moyenne", "forte", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"tresforte"); //$NON-NLS-1$

	/**
	 * Étape 3, monnaie libre, mode smartphone : détecte et encaisse
	 * AUTOMATIQUEMENT tous les "carrés" (4 cartes identiques) actuellement
	 * réunis par un joueur - confirmé explicitement par l'utilisateur
	 * (encaissement automatique, sans action du joueur). Appelée après
	 * chaque transaction smartphone (voir {@link #recordTransaction}), pour
	 * le vendeur ET l'acheteur : l'un des deux peut avoir complété un carré.
	 * <p>
	 * Reproduit "il pioche une carte de valeur supérieure, se défausse de son
	 * carré dans la pioche du paquet correspondant et pioche quatre nouvelles
	 * cartes de ce même paquet" (rules.html) : les 4 cartes retournent dans
	 * LEUR pioche de niveau, 1 carte est piochée dans la pioche du niveau
	 * supérieur, et 4 nouvelles cartes sont piochées dans la pioche du niveau
	 * d'origine pour reconstituer la main - jamais depuis le catalogue
	 * complet (GameService n'y touche jamais directement), toujours depuis
	 * {@code Game.smartphoneCardPileJson}, qui encode déjà à quel niveau
	 * appartient chaque modèle "en jeu" pour CETTE partie précise (les clés
	 * de chaque sous-pioche).
	 * <p>
	 * Rupture technologique (voir docs/04-etape3-catalogue-cartes.md, modèle
	 * simplifié confirmé par l'utilisateur - PAS de réordonnancement des 4
	 * niveaux) : la toute première fois qu'un carré fait entrer une carte de
	 * niveau "tresforte" en jeu pour cette partie (peu importe quel joueur),
	 * un événement XTECHNOLOGICAL_BREAKTHROUGH est enregistré en plus, pour
	 * que le calcul de richesse déjà existant (StatsService, currentFactor
	 * *= 2) en tienne compte - réutilise TEL QUEL ce mécanisme déjà présent
	 * dans le moteur (confirmé par l'utilisateur comme suffisant), plutôt que
	 * d'implémenter un réordonnancement complexe des couleurs.
	 * <p>
	 * Boucle tant qu'un carré existe : un joueur pourrait, en théorie, réunir
	 * plusieurs carrés d'un coup (les 4 cartes de remplacement d'un premier
	 * carré pourraient elles-mêmes compléter un second, si le hasard fait
	 * qu'il les tirait déjà identiques à 3 cartes déjà en main) - la boucle
	 * protège contre ce cas, même rare.
	 */
	public void checkAndCashInSquares(final int pGameId, final int pPlayerId)
	{
		while (true)
		{
			final EntityManager em = mEntityManagerFactory.createEntityManager();
			try
			{
				final Game game = em.find(Game.class, pGameId);
				if ((game == null) || (game.getSmartphoneCardPileJson() == null))
					return; // pas (encore) de mise en place - rien à faire

				final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				final java.util.Map<String, java.util.Map<String, Integer>> pilesByLevel;
				try
				{
					pilesByLevel = mapper.readValue(game.getSmartphoneCardPileJson(),
							new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, Integer>>>()
							{
							});
				}
				catch (final com.fasterxml.jackson.core.JsonProcessingException e)
				{
					return; // donnée corrompue : on abandonne silencieusement plutôt que de planter
				}

				// Cherche un modèle réuni en 4 exemplaires ou plus dans
				// l'inventaire ACTUEL (déjà rejoué : dotation + transactions +
				// carrés précédents) - le premier trouvé, peu importe l'ordre.
				final java.util.Map<String, Integer> inventory = computePlayerCardInventory(pGameId, pPlayerId);
				String squareCardId = null;
				String squareLevel = null;
				for (final java.util.Map.Entry<String, Integer> e : inventory.entrySet())
				{
					final String level = findLevelOfCard(pilesByLevel, e.getKey());
					if ((e.getValue() >= 4) && (level != null))
					{
						squareCardId = e.getKey();
						squareLevel = level;
						break;
					}
				}
				if (squareCardId == null)
					return; // rien à encaisser, on s'arrête là

				final int levelIndex = LEVEL_ORDER.indexOf(squareLevel);
				if ((levelIndex < 0) || (levelIndex >= LEVEL_ORDER.size() - 1))
					return; // déjà au niveau le plus haut (tresforte) - pas de niveau supérieur dans ce modèle simplifié

				final String nextLevel = LEVEL_ORDER.get(levelIndex + 1);
				final java.util.Map<String, Integer> samePile = pilesByLevel.get(squareLevel);
				final java.util.Map<String, Integer> nextPile = pilesByLevel.get(nextLevel);
				if ((samePile == null) || (nextPile == null) || samePile.isEmpty() || nextPile.isEmpty())
					return; // pioche absente/vide (ne devrait pas arriver si la mise en place a bien eu lieu)

				// Remet les 4 cartes défaussées dans LEUR pioche.
				samePile.merge(squareCardId, 4, Integer::sum);

				// Pioche 1 carte au hasard dans le niveau supérieur.
				final String promotedCardId = pickRandomAvailable(nextPile);
				if (promotedCardId == null)
					return; // pioche supérieure épuisée (cas limite, ne devrait pas arriver avec 5×(N+1) exemplaires)
				nextPile.merge(promotedCardId, -1, Integer::sum);

				// Pioche 4 nouvelles cartes dans LE MÊME niveau que celui
				// défaussé (peut inclure à nouveau le modèle qu'on vient de
				// rendre, ou d'autres - un vrai tirage au hasard).
				final java.util.List<String> replenished = new java.util.ArrayList<>();
				for (int i = 0; i < 4; i++)
				{
					final String cardId = pickRandomAvailable(samePile);
					if (cardId == null)
						break; // pioche épuisée en cours de route - on s'arrête là plutôt que de planter
					samePile.merge(cardId, -1, Integer::sum);
					replenished.add(cardId);
				}

				// Rupture technologique : la toute première fois qu'une carte
				// "tresforte" entre en jeu pour CETTE partie (voir le
				// raisonnement complet ci-dessus).
				final boolean isFirstBreakthrough = "tresforte".equals(nextLevel) //$NON-NLS-1$
						&& (em.createQuery(
								"SELECT COUNT(s) FROM CardSquareEvent s WHERE s.game.id = :gameId AND s.promotedLevel = :lvl", //$NON-NLS-1$
								Long.class)
								.setParameter("gameId", pGameId).setParameter("lvl", "tresforte") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
								.getSingleResult() == 0);

				final Player player = em.find(Player.class, pPlayerId);
				em.getTransaction().begin();
				writeJsonQuietly(mapper, pilesByLevel, game::setSmartphoneCardPileJson);
				final CardSquareEvent squareEvent = new CardSquareEvent(game, player, squareCardId, squareLevel,
						promotedCardId, nextLevel, toJsonQuietly(mapper, replenished), isFirstBreakthrough);
				em.persist(squareEvent);
				em.getTransaction().commit();

				if (isFirstBreakthrough)
				{
					// recordEvent() gère sa propre transaction séparément, d'où cet
					// appel après le commit ci-dessus plutôt que dans la même
					// transaction (une seule EntityManager ne peut pas imbriquer
					// deux transactions actives à la fois).
					try
					{
						recordEvent(pGameId, "X", pPlayerId, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
					}
					catch (final PlayerNotFoundException ignored)
					{
						// Ne devrait jamais arriver : ce joueur vient d'être résolu
						// avec succès juste au-dessus.
					}
				}
			}
			finally
			{
				em.close();
			}
			// On reboucle : les 4 cartes de remplacement pourraient, en théorie,
			// compléter immédiatement un second carré (voir le commentaire de
			// tête de méthode) - la boucle s'arrêtera d'elle-même dès que
			// computePlayerCardInventory ne trouve plus aucun modèle à 4+.
		}
	}

	// Dans quel niveau (clé de pPilesByLevel) ce modèle de carte apparaît-il -
	// null si aucun (carte hors du sous-ensemble "en jeu" pour cette partie,
	// ne devrait normalement pas arriver pour une carte réellement possédée).
	private String findLevelOfCard(final java.util.Map<String, java.util.Map<String, Integer>> pPilesByLevel,
			final String pCardTypeId)
	{
		for (final java.util.Map.Entry<String, java.util.Map<String, Integer>> e : pPilesByLevel.entrySet())
			if (e.getValue().containsKey(pCardTypeId))
				return e.getKey();
		return null;
	}

	// Tire un modèle au hasard parmi ceux ENCORE disponibles (count > 0) dans
	// cette pioche - null si la pioche est entièrement épuisée.
	private String pickRandomAvailable(final java.util.Map<String, Integer> pPile)
	{
		final java.util.List<String> available = pPile.entrySet().stream().filter(e -> e.getValue() > 0)
				.map(java.util.Map.Entry::getKey).toList();
		if (available.isEmpty())
			return null;
		return available.get(new java.util.Random().nextInt(available.size()));
	}

	// Comme writeJsonQuietly, mais renvoie la chaîne au lieu de l'appliquer à
	// un setter - nécessaire ici car CardSquareEvent construit sa chaîne JSON
	// dans son CONSTRUCTEUR (pas de setter à appeler après coup).
	private String toJsonQuietly(final com.fasterxml.jackson.databind.ObjectMapper pMapper, final Object pValue)
	{
		try
		{
			return pMapper.writeValueAsString(pValue);
		}
		catch (final com.fasterxml.jackson.core.JsonProcessingException e)
		{
			throw new RuntimeException(e); // ne devrait jamais arriver en pratique
		}
	}

	// Sérialise pValue en JSON et le passe à pSetter - ne devrait jamais
	// échouer (structures toujours sérialisables, Map<String,Integer>), mais
	// une exception vérifiée (JsonProcessingException) doit bien être traitée
	// quelque part plutôt que de forcer chaque appelant à le refaire.
	private void writeJsonQuietly(final com.fasterxml.jackson.databind.ObjectMapper pMapper,
			final Object pValue, final java.util.function.Consumer<String> pSetter)
	{
		try
		{
			pSetter.accept(pMapper.writeValueAsString(pValue));
		}
		catch (final com.fasterxml.jackson.core.JsonProcessingException e)
		{
			throw new RuntimeException(e); // ne devrait jamais arriver en pratique
		}
	}

	/**
	 * Historique des transactions d'UN joueur précis (achats et ventes), plus
	 * récentes en premier - voir "Historique" côté espace joueur (mockup de
	 * référence du 28/08/2026). Distinct de {@link #listTransactions}
	 * (l'historique COMPLET d'une partie, réservé à l'animateur/protégé par
	 * le PIN) : un joueur sur son téléphone ne connaît jamais ce PIN, cette
	 * méthode ne renvoie donc que SES propres transactions.
	 */
	public List<Transaction> listPlayerTransactions(final int pGameId, final int pPlayerId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.createQuery(
					"SELECT t FROM Transaction t WHERE t.game.id = :gameId AND (t.seller.id = :pid OR t.buyer.id = :pid) ORDER BY t.tstamp DESC", //$NON-NLS-1$
					Transaction.class)
					.setParameter("gameId", pGameId).setParameter("pid", pPlayerId) //$NON-NLS-1$ //$NON-NLS-2$
					.getResultList();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Supprime un événement et recalcule intégralement l'état de la partie à partir
	 * des événements restants (dette de chaque joueur, masse monétaire, numéro de
	 * tour...). Ce recalcul complet est nécessaire : un événement au milieu de
	 * l'historique peut avoir des conséquences en cascade (ex. supprimer un crédit
	 * change la dette de tous les remboursements suivants). {@link Game#recomputeAll}
	 * fait exactement ce que faisait le menu "Recalcul des événements" de l'app
	 * Swing originale : réinitialise tout à zéro puis rejoue chaque événement restant
	 * dans l'ordre.
	 */
	public void deleteEvent(final int pGameId, final int pEventId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final Event toRemove = em.find(Event.class, pEventId);
			if (toRemove == null || !toRemove.getGame().equals(game))
				throw new IllegalArgumentException("Event not found in this game: " + pEventId); //$NON-NLS-1$

			em.getTransaction().begin();
			game.getEvents().remove(toRemove); // orphanRemoval=true : supprime aussi la ligne en base
			game.recomputeAll(null);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Modifie le principal/intérêt/date d'un événement existant, puis recalcule
	 * intégralement l'état de la partie (même principe que {@link #deleteEvent}).
	 * Volontairement limité à ces trois champs, qui sont les seuls saisissables
	 * lors de la création d'un événement dans l'interface web actuelle - pas
	 * d'édition des cartes/jetons, non exposés à la création non plus.
	 */
	public void editEvent(final int pGameId, final int pEventId, final int pPrincipal, final int pInterest,
			final java.util.Date pTstamp)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final Event event = em.find(Event.class, pEventId);
			if (event == null || !event.getGame().equals(game))
				throw new IllegalArgumentException("Event not found in this game: " + pEventId); //$NON-NLS-1$

			em.getTransaction().begin();
			event.setPrincipal(pPrincipal);
			event.setInterest(pInterest);
			if (pTstamp != null)
				event.setTstamp(pTstamp);
			game.recomputeAll(null);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Annule la dernière action enregistrée (équivalent de la touche [z] de l'app
	 * Swing originale) : supprime l'événement le plus récent de la partie (par
	 * horodatage) et recalcule l'état. Rappelable plusieurs fois de suite pour
	 * annuler successivement plusieurs actions.
	 */
	/**
	 * Portage fidèle de l'algorithme "createDeathSchedule" + "suggestDeaths" de
	 * l'app Swing originale (HelperUI.java, code source retrouvé sur le dépôt
	 * GitHub/GitLab du projet original) : calcule combien de joueurs devraient
	 * mourir à ce tour-ci pour que tous les joueurs actifs vivent au moins une
	 * renaissance avant la fin de la partie prévue, puis en suggère une sélection
	 * aléatoire parmi ceux qui n'ont encore jamais connu la mort. Se "rebase"
	 * automatiquement (sans rattrapage forcé) si l'animateur s'est écarté des
	 * suggestions précédentes - c'est la partie la plus subtile de l'algorithme
	 * original, portée telle quelle plutôt que simplifiée.
	 * <p>
	 * Lecture seule : ne modifie rien, se contente de calculer une suggestion.
	 */
	public List<String> suggestDeaths(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			if (game.getTurnNumber() == 0)
				return List.of(); // pas de suggestion avant que la partie n'ait commencé

			// Joueurs actifs n'ayant encore jamais connu la mort (reconstruit depuis
			// zéro à chaque appel, comme le fait l'original).
			final java.util.Set<Integer> nonDeadPlayers = new java.util.HashSet<>();
			for (final Player p : game.getPlayers())
				if (p.isActive())
					nonDeadPlayers.add(p.getId());
			final List<Event> sortedEvents = new java.util.ArrayList<>(game.getEvents());
			sortedEvents.sort(java.util.Comparator.comparing(Event::getTstamp));
			for (final Event e : sortedEvents)
				if (e.getEvt() == EventType.DEATH && e.getPlayer() != null)
					nonDeadPlayers.remove(e.getPlayer().getId());

			final List<Integer> deathSchedule = computeDeathSchedule(game, sortedEvents);

			final List<String> chosen = new java.util.ArrayList<>();
			if (!nonDeadPlayers.isEmpty() && game.getTurnNumber() <= deathSchedule.size())
			{
				final int curTarget = deathSchedule.get(game.getTurnNumber() - 1);
				final List<Integer> pool = new java.util.ArrayList<>(nonDeadPlayers);
				final java.util.Random rand = new java.util.Random();
				while (chosen.size() < curTarget && !pool.isEmpty())
				{
					final int idx = pool.size() == 1 ? 0 : rand.nextInt(pool.size());
					final Integer playerId = pool.remove(idx);
					for (final Player p : game.getPlayers())
						if (p.getId().equals(playerId))
						{
							chosen.add(p.getName());
							break;
						}
				}
			}
			java.util.Collections.sort(chosen);
			return chosen;
		}
		finally
		{
			em.close();
		}
	}

	/** Voir {@link #suggestDeaths} - fonction d'interpolation linéaire "rebornFunction" de l'original. */
	private int rebornFunction(final double pNbPlayers, final double pNbTurns, final double pReferenceTurn,
			final double pRenewedAtReferenceTurn, final double pCurrentTurn)
	{
		return (int) Math.round((pCurrentTurn - pReferenceTurn) * (pNbPlayers - pRenewedAtReferenceTurn)
				/ (pNbTurns - pReferenceTurn) + pRenewedAtReferenceTurn);
	}

	/** Voir {@link #suggestDeaths} - portage de "createDeathSchedule" de l'original. */
	private List<Integer> computeDeathSchedule(final Game pGame, final List<Event> pSortedEvents)
	{
		final List<Integer> deathSchedule = new java.util.ArrayList<>();
		int t0 = 1;
		int p0 = 0;
		int sinceLastReference = 0;
		int t = 1;
		for (final Event event : pSortedEvents)
		{
			if (event.getEvt() == EventType.TURN)
			{
				t++;
			}
			else if (event.getEvt() == EventType.JOIN || event.getEvt() == EventType.QUIT)
			{
				t0 = t;
				p0 += sinceLastReference;
				sinceLastReference = 0;
			}
			else if (event.getEvt() == EventType.DEATH)
			{
				sinceLastReference++;
				while (deathSchedule.size() < t)
					deathSchedule.add(0);
				deathSchedule.set(t - 1, deathSchedule.get(t - 1) + 1);
			}
		}
		final int nbPlayers = (int) pGame.getPlayers().stream().filter(Player::isActive).count();
		if (pGame.getTurnNumber() > t0)
			if (sinceLastReference != rebornFunction(nbPlayers, pGame.getNbTurnsPlanned(), t0, p0,
					pGame.getTurnNumber()))
			{
				// L'animateur s'est écarté du plan : on "rebase" à partir de maintenant,
				// sans essayer de rattraper le retard/l'avance de façon abrupte.
				t0 = pGame.getTurnNumber();
				p0 += sinceLastReference;
				sinceLastReference = 0;
			}
		int curRenewed = p0;
		for (int i = t0; i < pGame.getNbTurnsPlanned(); i++)
		{
			while (deathSchedule.size() < i)
				deathSchedule.add(0);
			final int target = rebornFunction(nbPlayers, pGame.getNbTurnsPlanned(), t0, p0, i + 1);
			final int diff = target - curRenewed;
			deathSchedule.set(i - 1, diff);
			curRenewed += diff;
		}
		return deathSchedule;
	}

	public boolean undoLastEvent(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final Event last = game.getEvents().stream().max(java.util.Comparator.comparing(Event::getTstamp)
					.thenComparing(Event::getId)).orElse(null);
			if (last == null)
				return false; // rien à annuler

			em.getTransaction().begin();
			game.getEvents().remove(last);
			game.recomputeAll(null);
			em.getTransaction().commit();
			return true;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Supprime totalement un joueur et tous ses événements associés (crédits,
	 * remboursements, morts...), puis recalcule l'état de la partie. Reprend le
	 * comportement du manuel original : "il est possible de supprimer totalement
	 * un joueur (et ses actions associées)". Un joueur n'a pas de relation directe
	 * vers ses propres événements dans le modèle de données (seul Event->Player
	 * existe) : on retire donc d'abord ses événements un par un de la liste de la
	 * partie avant de retirer le joueur lui-même, pour ne pas laisser d'événement
	 * orphelin en base.
	 */
	public void deletePlayer(final int pGameId, final int pPlayerId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final Player player = em.find(Player.class, pPlayerId);
			// Correctif sécurité (audit) : voir la vérification identique dans
			// recordEvent() plus haut - sans elle, un identifiant de joueur d'une autre
			// partie était accepté silencieusement (sans effet réel ici de par la
			// structure des collections JPA, mais à corriger quand même : mieux vaut une
			// erreur claire qu'un comportement accidentellement inoffensif).
			if ((player == null) || !player.getGame().equals(game))
				throw new IllegalArgumentException("Player not found: " + pPlayerId); //$NON-NLS-1$

			em.getTransaction().begin();
			game.getEvents().removeIf(e -> player.equals(e.getPlayer()));
			game.getPlayers().remove(player);
			game.recomputeAll(null);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Renomme un joueur. Vérifie l'unicité du nom dans la partie (comme à
	 * l'auto-inscription) : deux joueurs de même nom seraient impossibles à
	 * distinguer dans l'historique. Pas de recalcul nécessaire, le nom n'entrant
	 * dans aucun calcul financier.
	 */
	public void renamePlayer(final int pGameId, final int pPlayerId, final String pNewName)
			throws DuplicatePlayerNameException
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			final Player player = em.find(Player.class, pPlayerId);
			// Correctif sécurité (audit) : c'était ici le plus exploitable des trois -
			// sans cette vérification, n'importe qui pouvait renommer N'IMPORTE QUEL
			// joueur de N'IMPORTE QUELLE partie, juste en devinant un identifiant de
			// joueur valide (aucun lien nécessaire avec pGameId). Voir la même
			// vérification dans recordEvent()/deletePlayer() ci-dessus.
			if ((player == null) || !player.getGame().equals(game))
				throw new IllegalArgumentException("Player not found: " + pPlayerId); //$NON-NLS-1$
			final boolean nameTaken = game.getPlayers().stream()
					.anyMatch(p -> !p.equals(player) && p.getName().equalsIgnoreCase(pNewName));
			if (nameTaken)
				throw new DuplicatePlayerNameException(pNewName);

			em.getTransaction().begin();
			player.setName(pNewName);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Prolonge le tour en cours de {@code pDeltaSeconds} secondes (bouton "+30s" du
	 * minuteur), en reculant l'horodatage de départ du tour d'autant. Comme le calcul
	 * du temps restant se fait toujours par différence avec {@code turnStartedAt},
	 * cette prolongation est immédiatement visible par tous les clients connectés
	 * (aucun état de minuteur séparé à synchroniser).
	 */
	/**
	 * Démarre effectivement la partie (bouton "Démarrer la partie") : lance le
	 * chrono du premier tour. Distinct d'un "Nouveau tour" classique - ne fait PAS
	 * avancer {@code turnNumber} ni n'enregistre d'événement TURN, puisque le
	 * premier tour n'a pas encore été joué, il ne fait que commencer.
	 */
	public Game startGame(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			em.getTransaction().begin();
			game.setTurnStartedAt(new java.util.Date());
			em.getTransaction().commit();
			return game;
		}
		finally
		{
			em.close();
		}
	}

	public Game extendCurrentTurn(final int pGameId, final int pDeltaSeconds)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			em.getTransaction().begin();
			// Remonté par un utilisateur : "+30s" doit ajouter du temps au compte à
			// rebours affiché, que la partie soit en pause ou non - si elle est en
			// pause, le temps restant figé (pausedRemainingSeconds) est directement
			// augmenté plutôt que de décaler turnStartedAt (qui ne pilote plus
			// l'affichage tant qu'on est en pause).
			if (game.getPausedRemainingSeconds() != null)
			{
				game.setPausedRemainingSeconds(game.getPausedRemainingSeconds() + pDeltaSeconds);
			}
			else
			{
				final java.util.Date base = game.getTurnStartedAt() == null ? new java.util.Date()
						: game.getTurnStartedAt();
				game.setTurnStartedAt(new java.util.Date(base.getTime() - pDeltaSeconds * 1000L));
			}
			em.getTransaction().commit();
			return game;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Met le chrono du tour en cours en pause, partagée par tous les écrans
	 * connectés (contrairement à l'ancienne pause purement visuelle côté client).
	 * Calcule le temps restant au moment de l'appel et le fige dans
	 * {@code pausedRemainingSeconds} - tant que ce champ n'est pas null, le temps
	 * restant affiché (et le déclenchement automatique de l'assistant à 0) ne
	 * doit plus être recalculé depuis {@code turnStartedAt}.
	 */
	public Game pauseTurn(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			if (game.getPausedRemainingSeconds() == null && game.getTurnStartedAt() != null)
			{
				final long elapsedSeconds = (System.currentTimeMillis() - game.getTurnStartedAt().getTime()) / 1000L;
				final int remaining = (int) Math.max(0, game.getTurnDurationSeconds() - elapsedSeconds);
				em.getTransaction().begin();
				game.setPausedRemainingSeconds(remaining);
				em.getTransaction().commit();
			}
			return game;
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Relance le chrono après une pause : reconstruit {@code turnStartedAt} de
	 * façon à ce que le temps restant affiché reprenne exactement là où il avait
	 * été figé, puis efface {@code pausedRemainingSeconds}.
	 */
	public Game resumeTurn(final int pGameId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Game game = em.find(Game.class, pGameId);
			if (game == null)
				throw new IllegalArgumentException("Game not found: " + pGameId); //$NON-NLS-1$
			if (game.getPausedRemainingSeconds() != null)
			{
				em.getTransaction().begin();
				final int remaining = game.getPausedRemainingSeconds();
				final long newStart = System.currentTimeMillis() - (game.getTurnDurationSeconds() - remaining) * 1000L;
				game.setTurnStartedAt(new java.util.Date(newStart));
				game.setPausedRemainingSeconds(null);
				em.getTransaction().commit();
			}
			return game;
		}
		finally
		{
			em.close();
		}
	}
}
