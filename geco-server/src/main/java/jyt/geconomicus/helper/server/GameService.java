package jyt.geconomicus.helper.server;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import jyt.geconomicus.helper.Event;
import jyt.geconomicus.helper.Event.EventType;
import jyt.geconomicus.helper.EventTypeConverter;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;
import jyt.geconomicus.helper.PlayerNotFoundException;

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
			// Remonté par un utilisateur : jeton d'accès individuel, uniquement si la
			// protection par code est active pour CETTE partie (signalée par la
			// présence d'un PIN, voir Game.pin) - jamais pour une partie non protégée.
			if ((game.getPin() != null) && !game.getPin().isEmpty())
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
			// Remonté par un utilisateur : voir le même commentaire dans addPlayer()
			// ci-dessus - jeton généré uniquement si cette partie est protégée.
			if ((game.getPin() != null) && !game.getPin().isEmpty())
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
			final int pGoodsFromCounterparty, final int pWeakCoins, final int pMediumCoins, final int pStrongCoins)
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
