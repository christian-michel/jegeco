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
}
