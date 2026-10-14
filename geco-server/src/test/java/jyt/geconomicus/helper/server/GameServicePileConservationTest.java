package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Vérifie, par simulation directe sur GameService (jamais via HTTP - inutile
 * ici, toute la logique testée vit dans cette seule classe), le correctif du
 * 19/09/2026 remonté par l'utilisateur (PDF "Retours - 20260919") : "à partir
 * du 5ème tour, nous avons eu des problèmes avec la pioche : elle semblait
 * manquer de cartes faibles", et le bug associé en troc+smartphone "à sa
 * naissance, le programme ne lui a pas attribué 4 cartes. Il n'avait rien."
 * <p>
 * Cause racine trouvée (voir le commentaire dans GameService.recordEvent,
 * bloc isSmartphoneCardTrackedDeath) : les cartes détenues par un joueur AU
 * MOMENT DE SA MORT disparaissaient purement et simplement du jeu (ni rendues
 * à la pioche partagée, ni conservées) - alors que dealFreshHandForPlayer,
 * appelée dans la foulée pour sa renaissance, RETIRE 4 nouvelles cartes de
 * cette même pioche. Chaque mort était donc une fuite nette d'au moins 4
 * cartes hors circulation, qui finit mécaniquement par épuiser la pioche.
 * <p>
 * Invariant vérifié à chaque étape de la simulation : le nombre TOTAL de
 * cartes dans le jeu (celles dans les pioches partagées + celles détenues par
 * tous les joueurs actifs) ne doit JAMAIS changer, qu'il s'agisse d'une mort/
 * renaissance ou d'un échange/carré - aucune carte ne doit jamais apparaître
 * ni disparaître. C'est aussi la preuve la plus directe qu'aucune carte ne
 * peut se "dupliquer" (remonté par l'utilisateur : "le joueur qui a vendu a
 * conservé la carte après la transaction... il y avait une carte de plus en
 * jeu") : computePlayerCardInventory dérive TOUJOURS l'inventaire depuis
 * l'historique Transaction/CardSquareEvent (jamais un compteur mutable
 * séparé qui pourrait diverger) - ce test confirme empiriquement que ce
 * mécanisme ne peut pas créer de carte fantôme, y compris sous forte charge
 * d'échanges aléatoires (voir testManyRandomTradesNeverDuplicateOrLoseCards).
 * <p>
 * Complété le 20/09/2026 (seconde relecture indépendante) : le même problème
 * existait pour un abandon définitif en cours de partie (EventType.QUIT),
 * qui ne rendait pas non plus les cartes du joueur à la pioche - voir
 * testQuitReturnsHeldCardsToPileWithoutDealingFreshHand.
 */
class GameServicePileConservationTest
{
	private static EntityManagerFactory sEmf;
	private static GameService sService;

	@BeforeAll
	static void setUp()
	{
		sEmf = Persistence.createEntityManagerFactory("geco-server-test"); //$NON-NLS-1$
		sService = new GameService(sEmf);
	}

	@AfterAll
	static void tearDown()
	{
		if (sEmf != null)
			sEmf.close();
	}

	private static final List<String> LEVELS = List.of("faible", "moyenne", "forte", "tresforte"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Catalogue synthétique DÉLIBÉRÉMENT restreint - reproduit les
	 * conditions du retour utilisateur ("avec peu de joueurs/modèles, la
	 * pioche est petite") pour stresser la mécanique de recyclage plutôt que
	 * de la diluer dans un catalogue confortablement large. pModelsPerLevel
	 * doit rester au moins égal à (nbPlayers+1) pour que la toute première
	 * distribution (dealStartingHandsForLibreIfNeeded, qui sélectionne
	 * min(nbPlayers+1, available.size()) modèles) puisse elle-même réunir
	 * assez de cartes "faible" (5 exemplaires × nbModels) pour servir 4
	 * cartes à chaque joueur sans épuiser le sac dès la mise en place - un
	 * scénario différent (et déjà documenté comme cas extrême assumé dans
	 * dealStartingHandsForLibreIfNeeded) de la fuite réparée ici. */
	private Map<String, List<String>> smallCatalog(final int pModelsPerLevel)
	{
		final Map<String, List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : LEVELS)
		{
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < pModelsPerLevel; i++)
				ids.add(level + "_modele_" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	/** Somme les cartes actuellement dans les 4 pioches partagées de la partie. */
	private int totalCardsInPiles(final int gameId)
	{
		final Game game = sService.getGame(gameId);
		final String json = game.getSmartphoneCardPileJson();
		if (json == null)
			return 0;
		try
		{
			final Map<String, Map<String, Integer>> pilesByLevel = new com.fasterxml.jackson.databind.ObjectMapper()
					.readValue(json,
							new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, Integer>>>()
							{
							});
			int total = 0;
			for (final Map<String, Integer> pile : pilesByLevel.values())
				for (final int count : pile.values())
					total += count;
			return total;
		}
		catch (final com.fasterxml.jackson.core.JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}

	/** Somme les cartes actuellement détenues par tous les joueurs actifs. */
	private int totalCardsHeldByPlayers(final int gameId, final List<Integer> playerIds)
	{
		int total = 0;
		for (final int playerId : playerIds)
			for (final int count : sService.computePlayerCardInventory(gameId, playerId).values())
				total += count;
		return total;
	}

	/**
	 * Simule de nombreuses morts/renaissances successives dans une partie
	 * libre + smartphone à la pioche volontairement restreinte, et vérifie
	 * que (a) le total de cartes en circulation (pioches + mains des joueurs)
	 * reste rigoureusement CONSTANT à chaque étape (aucune fuite), et (b)
	 * chaque renaissance redonne bien une main complète de 4 cartes - jamais
	 * une main vide, symptôme observé par l'utilisateur en troc+smartphone
	 * une fois la pioche épuisée par la fuite désormais corrigée.
	 */
	@Test
	void testManyDeathsNeverLeakCardsOutOfCirculation() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimTest", null, "test fuite pioche", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-19", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();

		final int nbPlayers = 5;
		final List<Integer> playerIds = new ArrayList<>();
		for (int i = 0; i < nbPlayers; i++)
			playerIds.add(sService.addPlayer(gameId, "Joueur" + i).getId()); //$NON-NLS-1$

		// Premier tour : déclenche la mise en place (comme le ferait
		// GecoServer sur un événement TURN en mode smartphone, voir sa route
		// POST /api/games/{id}/events).
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final int totalAtStart = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
		assertTrue(totalAtStart > 0, "la mise en place doit avoir distribué des cartes"); //$NON-NLS-1$

		final Random random = new Random(42);
		for (int cycle = 0; cycle < 80; cycle++)
		{
			final int victimId = playerIds.get(random.nextInt(playerIds.size()));
			sService.recordEvent(gameId, "D", victimId, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

			final int totalNow = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
			assertEquals(totalAtStart, totalNow,
					"le nombre total de cartes en circulation doit rester constant après la mort/renaissance #" //$NON-NLS-1$
							+ cycle + " du joueur " + victimId); //$NON-NLS-1$

			final int handSize = sService.computePlayerCardInventory(gameId, victimId).values().stream()
					.mapToInt(Integer::intValue).sum();
			assertEquals(4, handSize,
					"un joueur qui vient de renaître doit recevoir exactement 4 cartes (cycle " + cycle + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Même vérification que testManyDeathsNeverLeakCardsOutOfCirculation, mais
	 * balayée sur toute la plage de joueurs demandée par l'utilisateur (PDF
	 * "Retours - 20260919", consignes de test : "4 à 20 joueurs pour tester
	 * les règles en fonction du nombre de joueurs aussi") et sur une durée
	 * proportionnelle à "12 tours" (une mort simulée par joueur et par tour,
	 * un rythme de mortalité déjà plus élevé que ce qu'observerait une vraie
	 * partie - stress-test volontairement pessimiste).
	 */
	@ParameterizedTest
	@ValueSource(ints = { 4, 8, 12, 20 })
	void testManyDeathsNeverLeakCardsAcrossPlayerCounts(final int nbPlayers) throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimTest" + nbPlayers, null, //$NON-NLS-1$
				"test fuite pioche " + nbPlayers + " joueurs", "2026-09-19", "Ceres", 1, 180, 1.0, false, 0, true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				0.5);
		final int gameId = game.getId();

		final List<Integer> playerIds = new ArrayList<>();
		for (int i = 0; i < nbPlayers; i++)
			playerIds.add(sService.addPlayer(gameId, "Joueur" + i).getId()); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		// Catalogue proportionnel au nombre de joueurs (nbPlayers+1, comme le
		// fait réellement dealStartingHandsForLibreIfNeeded) - reste donc
		// aussi restreint que le permet la mise en place elle-même, quel que
		// soit nbPlayers, pour continuer à stresser le recyclage.
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(nbPlayers + 1));

		final int totalAtStart = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
		assertTrue(totalAtStart > 0, "la mise en place doit avoir distribué des cartes"); //$NON-NLS-1$

		final int nbTurnsSimulated = 12;
		for (int turn = 0; turn < nbTurnsSimulated; turn++)
		{
			for (final int victimId : playerIds)
			{
				sService.recordEvent(gameId, "D", victimId, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

				final int totalNow = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
				assertEquals(totalAtStart, totalNow,
						nbPlayers + " joueurs, tour " + turn + " : le total de cartes en circulation a changé"); //$NON-NLS-1$ //$NON-NLS-2$

				final int handSize = sService.computePlayerCardInventory(gameId, victimId).values().stream()
						.mapToInt(Integer::intValue).sum();
				assertEquals(4, handSize, nbPlayers + " joueurs, tour " + turn //$NON-NLS-1$
						+ " : une renaissance doit toujours redonner 4 cartes"); //$NON-NLS-1$
			}
		}
	}

	/**
	 * Simule de nombreux échanges aléatoires (cartes contre jetons, monnaie
	 * libre + smartphone) entrecoupés de vérifications de carré, et confirme
	 * que le total de cartes en circulation ne bouge jamais et qu'aucun
	 * joueur ne se retrouve avec un compte négatif (signe d'une carte
	 * fantôme vendue deux fois) - la preuve empirique que la duplication
	 * apparente remontée par l'utilisateur ("le vendeur a conservé la
	 * carte") n'est pas un bug du grand livre des transactions lui-même.
	 */
	@Test
	void testManyRandomTradesNeverDuplicateOrLoseCards() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimTest2", null, "test échanges", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-19", "Ceres", 1, 180, 0.4, false, 0, true, 0.4); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();

		final int nbPlayers = 6;
		final List<Integer> playerIds = new ArrayList<>();
		for (int i = 0; i < nbPlayers; i++)
			playerIds.add(sService.addPlayer(gameId, "Joueuse" + i).getId()); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final int totalAtStart = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);

		final Random random = new Random(7);
		int successfulTrades = 0;
		for (int attempt = 0; attempt < 300; attempt++)
		{
			final int sellerId = playerIds.get(random.nextInt(playerIds.size()));
			int buyerId = playerIds.get(random.nextInt(playerIds.size()));
			if (buyerId == sellerId)
				continue;
			final Map<String, Integer> sellerInventory = sService.computePlayerCardInventory(gameId, sellerId);
			final List<String> sellable = sellerInventory.entrySet().stream().filter(e -> e.getValue() > 0)
					.map(Map.Entry::getKey).toList();
			if (sellable.isEmpty())
				continue;
			final String cardTypeId = sellable.get(random.nextInt(sellable.size()));
			final String level = cardTypeId.substring(0, cardTypeId.indexOf('_'));
			try
			{
				sService.recordTransaction(gameId, sellerId, buyerId, cardTypeId, level, 0, 0, 0, 0, 0, 0,
						"nonce-" + attempt, System.currentTimeMillis() + 60_000); //$NON-NLS-1$
				successfulTrades++;
			}
			catch (final IllegalArgumentException expected)
			{
				// Solde insuffisant / rendu de monnaie impossible : un refus normal
				// du moteur, pas une erreur de test - on passe simplement au
				// tirage suivant.
				continue;
			}
			sService.checkAndCashInSquares(gameId, sellerId);
			sService.checkAndCashInSquares(gameId, buyerId);

			final int totalNow = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
			assertEquals(totalAtStart, totalNow,
					"le nombre total de cartes en circulation doit rester constant après l'échange #" + attempt); //$NON-NLS-1$
			for (final int playerId : playerIds)
				for (final int count : sService.computePlayerCardInventory(gameId, playerId).values())
					assertFalse(count < 0, "aucun joueur ne devrait jamais avoir un compte de cartes négatif"); //$NON-NLS-1$
		}
		assertTrue(successfulTrades > 20, "au moins quelques dizaines d'échanges auraient dû réussir sur 300 tirages"); //$NON-NLS-1$
	}

	/**
	 * Vérifie le correctif du 20/09/2026 (trouvé en seconde relecture
	 * indépendante, dans la continuité de l'audit de la fuite de cartes à la
	 * mort ci-dessus) : un abandon définitif (EventType.QUIT, déclenché par
	 * openPlayerQuitDialog() côté app.js) doit, comme une mort, rendre à la
	 * pioche commune toutes les cartes que le joueur détenait - sans quoi
	 * elles restent gelées dans son inventoire (jamais consulté puisqu'il
	 * devient inactif), retirées de la circulation exactement comme l'était
	 * la fuite à la mort. Contrairement à une mort, PAS de renaissance : le
	 * joueur ne doit recevoir aucune nouvelle main après son abandon (voir
	 * Event.java, player.setActive(false) appliqué uniquement pour QUIT).
	 */
	@Test
	void testQuitReturnsHeldCardsToPileWithoutDealingFreshHand() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimTest4", null, "test abandon", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-20", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();

		final int nbPlayers = 4;
		final List<Integer> playerIds = new ArrayList<>();
		for (int i = 0; i < nbPlayers; i++)
			playerIds.add(sService.addPlayer(gameId, "Joueur" + i).getId()); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final int totalAtStart = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
		assertTrue(totalAtStart > 0, "la mise en place doit avoir distribué des cartes"); //$NON-NLS-1$

		final int quitterId = playerIds.get(0);
		final int handSizeBeforeQuit = sService.computePlayerCardInventory(gameId, quitterId).values().stream()
				.mapToInt(Integer::intValue).sum();
		assertEquals(4, handSizeBeforeQuit, "le joueur doit détenir sa main de départ avant d'abandonner"); //$NON-NLS-1$

		sService.recordEvent(gameId, "Q", quitterId, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final int totalAfterQuit = totalCardsInPiles(gameId) + totalCardsHeldByPlayers(gameId, playerIds);
		assertEquals(totalAtStart, totalAfterQuit,
				"l'abandon ne doit ni créer ni détruire de cartes : celles du joueur doivent revenir à la pioche"); //$NON-NLS-1$

		final int handSizeAfterQuit = sService.computePlayerCardInventory(gameId, quitterId).values().stream()
				.mapToInt(Integer::intValue).sum();
		assertEquals(0, handSizeAfterQuit,
				"un joueur qui abandonne ne doit conserver AUCUNE carte (rendues à la pioche, pas de renaissance)"); //$NON-NLS-1$

		for (final int playerId : playerIds)
			for (final int count : sService.computePlayerCardInventory(gameId, playerId).values())
				assertFalse(count < 0, "aucun joueur ne devrait jamais avoir un compte de cartes négatif"); //$NON-NLS-1$
	}

	/**
	 * Vérifie le correctif du classement (PDF "Retours - 20260919" : "mettre
	 * l'affichage du classement en unités monétaires... il est actuellement
	 * en jetons") - un joueur suivi par smartphone avec un solde de jetons
	 * connu doit apparaître dans GameService.computeLeaderboard() converti en
	 * unités monétaires réelles (jetons × weakCoinValue), jamais en jetons
	 * bruts.
	 */
	@Test
	void testLeaderboardConvertsJetonsToMonetaryUnitsForSmartphoneLibre() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimTest3", null, "test classement", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-19", "Ceres", 1, 180, 0.4, false, 0, true, 0.4); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Solo"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));
		// Dotation de départ = 7 unités monétaires, converties en jetons via
		// weakCoinValue=0.4 -> round(7/0.4) = round(17.5) = 18 jetons faibles
		// (voir Game.computeStartingJetonsPerPlayer). Valeur monétaire
		// attendue au classement : 18 * 0.4 = 7.2.
		final List<Dtos.LeaderboardEntryDto> leaderboard = sService.computeLeaderboard(gameId);
		assertEquals(1, leaderboard.size());
		assertEquals(7.2, leaderboard.get(0).value(), 0.01,
				"le classement doit afficher une VALEUR MONÉTAIRE (jetons × weakCoinValue), pas un nombre de jetons bruts"); //$NON-NLS-1$
	}

	/**
	 * BUG TROUVÉ ET CORRIGÉ (14/10/2026, remonté par l'utilisateur, PDF
	 * "Retours - 20261014") : la branche ci-dessus (conversion jetons ->
	 * unités monétaires via weakCoinValue, ajoutée pour la LIBRE le
	 * 19/09/2026) s'appliquait par erreur aussi à la DETTE+smartphone - sa
	 * condition ne testait que {@code Player.startingCardsJson != null},
	 * jamais le système monétaire de la partie. Un crédit de 3 unités
	 * monétaires accordé à un joueur dette+smartphone sur une partie dont
	 * "Valeur d'une pièce faible" valait 0.5 affichait donc "1.5" au
	 * classement, alors qu'en dette+smartphone 1 jeton faible = 1 unité
	 * monétaire TOUJOURS (voir Event.java, cas NEW_CREDIT - jamais mis à
	 * l'échelle par weakCoinValue, contrairement à la libre où ce réglage
	 * convertit une dotation de départ RÉELLE en un nombre de jetons
	 * PHYSIQUES). Corrigé en restreignant cette conversion à
	 * {@code Game.MONEY_LIBRE} explicitement.
	 */
	@Test
	void testLeaderboardDoesNotScaleDebtSmartphoneByWeakCoinValue() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimTest4", null, "test classement dette", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-14", "Ceres", 1, 180, 0.5, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Solo"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));
		// Crédit de 3 unités monétaires (intérêt 1, sans incidence sur ce
		// solde) - voir Event.java, cas NEW_CREDIT : jetonWeak += principal,
		// jamais mis à l'échelle. Valeur attendue au classement : 3 (PAS
		// 3 * 0.5 = 1.5).
		sService.recordEvent(gameId, "N", player.getId(), 3, 1, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final List<Dtos.LeaderboardEntryDto> leaderboard = sService.computeLeaderboard(gameId);
		assertEquals(1, leaderboard.size());
		assertEquals(3.0, leaderboard.get(0).value(), 0.01,
				"le classement dette+smartphone doit afficher le solde RÉEL en unités monétaires (1 jeton = 1 unité), jamais mis à l'échelle par weakCoinValue"); //$NON-NLS-1$
	}

	/**
	 * BUG TROUVÉ ET CORRIGÉ (15/10/2026, seconde relecture indépendante du
	 * correctif du 14/10/2026 ci-dessus, pour le MÊME symptôme utilisateur -
	 * un crédit accordé avant que la partie n'ait jamais démarré, jamais versé
	 * sur le smartphone - reproduit cette fois en HTTP réel plutôt qu'en
	 * relecture de code). Le correctif du 14/10/2026 déplaçait l'appel à
	 * {@code GameService.captureDeckPlayerCountIfNeeded}/
	 * {@code dealStartingHandsForLibreIfNeeded} ("la mise en place") à POST
	 * /api/games/{id}/start - mais cette route HTTP n'est en réalité JAMAIS
	 * appelée par l'interface animateur réelle (voir btnStartGame.onclick
	 * dans app.js, qui enregistre directement un événement TOUR) ; et pour
	 * POST .../events, l'appel restait placé APRÈS {@code recordEvent()} et
	 * limité au type "T" - donc TOUJOURS trop tard pour un crédit (direct ou
	 * via demande+approbation du joueur) enregistré alors qu'aucun événement
	 * TOUR n'a encore jamais eu lieu. Ce test documente l'invariant que
	 * {@code GecoServer} doit désormais garantir (voir
	 * performSmartphoneMiseEnPlaceIfNeeded(), appelée AVANT tout
	 * recordEvent() depuis le 15/10/2026, dans les deux routes concernées) :
	 * la mise en place DOIT avoir eu lieu AVANT le crédit, jamais après -
	 * {@code Event.applyEvent()} (cas NEW_CREDIT) ne recrédite jamais
	 * rétroactivement un événement déjà appliqué, quelle que soit la mise en
	 * place effectuée ensuite.
	 */
	@Test
	void testCreditGrantedBeforeMiseEnPlaceNeverCreditsJetonWeakEvenAfterwards() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimTest5", null, "test ordre mise en place", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-15", "Ceres", 1, 180, 0.5, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Anna"); //$NON-NLS-1$

		// Reproduction du bug : crédit accordé AVANT tout événement TOUR, donc
		// avant toute mise en place (deckPlayerCount/smartphoneCardPileJson
		// encore nuls - isSmartphoneTrackedGame() encore FAUX pour toute la
		// partie, voir Event.java).
		sService.recordEvent(gameId, "N", player.getId(), 3, 1, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		Player reloaded = sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId().equals(player.getId())).findFirst().orElseThrow();
		assertEquals(3, reloaded.getCurDebt(), "la dette doit être enregistrée normalement, quel que soit l'état de la mise en place"); //$NON-NLS-1$
		assertEquals(0, reloaded.getJetonWeak(),
				"BUG CONFIRMÉ (comportement attendu avant tout correctif) : un crédit accordé avant toute mise en place ne peut pas créditer jetonWeak, puisqu'aucun joueur n'a encore startingCardsJson"); //$NON-NLS-1$

		// La partie démarre ENSUITE (premier TOUR, qui déclenche la mise en
		// place côté GecoServer - simulée ici directement) : ceci ne doit
		// JAMAIS rejouer/corriger rétroactivement le crédit déjà appliqué
		// ci-dessus - c'est exactement la perte DÉFINITIVE que le correctif du
		// 15/10/2026 empêche en amont (mise en place désormais appelée AVANT
		// recordEvent, jamais après).
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));
		reloaded = sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId().equals(player.getId())).findFirst().orElseThrow();
		assertEquals(0, reloaded.getJetonWeak(),
				"une mise en place tardive ne doit JAMAIS rejouer un crédit déjà appliqué - confirme que la seule protection valable est d'appeler la mise en place AVANT le premier événement, jamais après (voir GecoServer.performSmartphoneMiseEnPlaceIfNeeded, appelée désormais avant recordEvent() dans les deux routes concernées)"); //$NON-NLS-1$

		// Preuve que l'ORDRE est bien la seule variable en jeu : un DEUXIÈME
		// crédit, accordé maintenant que la mise en place a eu lieu (ordre
		// correct, celui que GecoServer garantit désormais pour TOUT crédit,
		// même le tout premier), crédite bien jetonWeak normalement.
		sService.recordEvent(gameId, "N", player.getId(), 5, 1, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		reloaded = sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId().equals(player.getId())).findFirst().orElseThrow();
		assertEquals(8, reloaded.getCurDebt(), 0.01); //$NON-NLS-1$
		assertEquals(5, reloaded.getJetonWeak(),
				"un crédit accordé APRÈS la mise en place doit bien créditer jetonWeak - seul le crédit accordé AVANT (plus haut) reste perdu, jamais les suivants"); //$NON-NLS-1$
	}
}
