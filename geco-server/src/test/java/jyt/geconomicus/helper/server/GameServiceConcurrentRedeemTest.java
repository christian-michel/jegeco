package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Régression du 27/09/2026 : rachats smartphone SIMULTANÉS (voir
 * GameService.mGameLocks/withGameLock et le correctif de recordTransaction).
 * <p>
 * Contexte : retour utilisateur sur une vraie partie libre + smartphone
 * (2 joueurs, 8 tours) - "Montre mécanique x4" jamais encaissée, trois modèles
 * "Très forte" à x5. L'enquête a trouvé DEUX causes distinctes :
 * <ol>
 * <li>La situation exacte de la capture d'écran s'explique SANS concurrence,
 * par l'épuisement des petites pioches (2 joueurs = 3 modèles x 5 exemplaires
 * par niveau) : décision de règle en attente, hors du périmètre de ce test.</li>
 * <li>Le risque "Connu, non corrigé" de CLAUDE.md (validation puis écriture
 * sans verrou) a été REPRODUIT en HTTP réel sur le jar non corrigé (84fd145,
 * 100 manches de 4 rachats simultanés pour un même acheteur) : somme des
 * jetons dérivant dans 52 manches sur 100 (jusqu'à +31 073 unités créées de
 * rien), modèles jusqu'à 22 exemplaires au lieu de 5, carrés encaissés deux
 * fois. Avec le verrou : 0 dérive, conservation exacte. Les mêmes 100 manches
 * tirées SÉQUENTIELLEMENT sur le jar non corrigé ne dérivent jamais - c'est
 * donc bien la concurrence qui cassait les invariants.</li>
 * </ol>
 * Ce test rejoue la séquence de la route /trade-offers/{code}/redeem
 * (recordTransaction puis checkAndCashInSquares vendeur ET acheteur)
 * directement sur GameService, depuis plusieurs threads relâchés au même
 * instant (CyclicBarrier) - vérifié échouant sur le code non corrigé.
 */
class GameServiceConcurrentRedeemTest
{
	private static EntityManagerFactory sEmf;
	private static GameService sService;
	private static final List<String> LEVELS = List.of("faible", "moyenne", "forte", "tresforte"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

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

	private static Map<String, List<String>> catalog()
	{
		final Map<String, List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : LEVELS)
		{
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < 12; i++)
				ids.add(level + "_m" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	private static String levelOf(final String pCardId)
	{
		return pCardId.substring(0, pCardId.indexOf('_'));
	}

	private static int[] newLibreGameWithPlayers(final int pNbPlayers, final String pName) throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "A", null, pName, "2026-09-27", "X", 1, 3600, 1.0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				false, 4, true, 0.5);
		final int[] ids = new int[pNbPlayers + 1];
		ids[0] = game.getId();
		for (int i = 0; i < pNbPlayers; i++)
			ids[i + 1] = sService.addPlayer(game.getId(), "J" + i).getId(); //$NON-NLS-1$
		sService.recordEvent(game.getId(), "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(game.getId());
		sService.dealStartingHandsForLibreIfNeeded(game.getId(), catalog());
		return ids;
	}

	private static int totalMoney(final int pGameId)
	{
		int total = 0;
		for (final Player p : sService.getGame(pGameId).getPlayers())
			total += p.getJetonWeak() + (2 * p.getJetonMedium()) + (4 * p.getJetonStrong());
		return total;
	}

	/** Cartes par modèle : pioche partagée + mains de tous les joueurs (doit rester à 5 partout). */
	private static Map<String, Integer> cardsPerModel(final int pGameId, final int[] pIds) throws Exception
	{
		final Map<String, Map<String, Integer>> piles = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
				sService.getGame(pGameId).getSmartphoneCardPileJson(),
				new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, Integer>>>()
				{
				});
		final Map<String, Integer> perModel = new LinkedHashMap<>();
		for (final Map<String, Integer> pile : piles.values())
			pile.forEach((k, v) -> perModel.merge(k, v, Integer::sum));
		for (int i = 1; i < pIds.length; i++)
			sService.computePlayerCardInventory(pGameId, pIds[i]).forEach((k, v) -> perModel.merge(k, v, Integer::sum));
		return perModel;
	}

	@Test
	void concurrentRedeemsForSameBuyerNeverCreateMoneyNorCards() throws Exception
	{
		final int[] ids = newLibreGameWithPlayers(3, "concurrent-redeem"); //$NON-NLS-1$
		final int gameId = ids[0];
		// Assez de jetons pour que la plupart des achats passent (le but est la
		// course, pas le manque d'argent).
		for (int i = 1; i < ids.length; i++)
			sService.recordEvent(gameId, "W", ids[i], 0, 0, 0, 0, 0, null, 0, 0, 400, 0, 0, 0, 0, 0); //$NON-NLS-1$
		final int money = totalMoney(gameId);
		final Random random = new Random(20260927);
		final AtomicInteger nonce = new AtomicInteger();
		final AtomicInteger accepted = new AtomicInteger();
		final ExecutorService pool = Executors.newFixedThreadPool(4);
		try
		{
			for (int round = 0; round < 40; round++)
			{
				final int buyer = ids[1 + random.nextInt(3)];
				final Map<String, Integer> buyerInv = sService.computePlayerCardInventory(gameId, buyer);
				// Jusqu'à 4 achats simultanés, sur les modèles que l'acheteur détient
				// déjà le plus (thésaurisation : provoque des carrés concurrents).
				final List<Object[]> purchases = new ArrayList<>();
				for (int i = 1; i < ids.length; i++)
				{
					if (ids[i] == buyer)
						continue;
					final List<String> cards = new ArrayList<>(sService.computePlayerCardInventory(gameId, ids[i]).keySet());
					cards.sort((a, b) -> buyerInv.getOrDefault(b, 0) - buyerInv.getOrDefault(a, 0));
					for (int c = 0; (c < 2) && (c < cards.size()); c++)
						purchases.add(new Object[] { ids[i], cards.get(c) });
				}
				if (purchases.isEmpty())
					continue;
				final CyclicBarrier barrier = new CyclicBarrier(purchases.size());
				final List<Future<?>> futures = new ArrayList<>();
				for (final Object[] purchase : purchases)
					futures.add(pool.submit(() -> {
						barrier.await(10, TimeUnit.SECONDS);
						final int seller = (Integer) purchase[0];
						final String card = (String) purchase[1];
						try
						{
							sService.recordTransaction(gameId, seller, buyer, card, levelOf(card), 1, 0, 0, 0, 0, 0,
									"cr-" + nonce.incrementAndGet(), System.currentTimeMillis() + 60_000); //$NON-NLS-1$
							accepted.incrementAndGet();
						}
						catch (final IllegalArgumentException refused)
						{
							// refus légitime (solde, rendu de monnaie...) - sans effet sur les invariants
						}
						// Même enchaînement que la route HTTP /redeem.
						sService.checkAndCashInSquares(gameId, seller);
						sService.checkAndCashInSquares(gameId, buyer);
						return null;
					}));
				for (final Future<?> f : futures)
					f.get(60, TimeUnit.SECONDS);
				assertEquals(money, totalMoney(gameId), "jetons créés ou détruits à la manche " + round); //$NON-NLS-1$
				for (final Map.Entry<String, Integer> e : cardsPerModel(gameId, ids).entrySet())
					assertEquals(5, e.getValue(), "modèle " + e.getKey() + " : cartes créées ou perdues à la manche " + round); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		assertTrue(accepted.get() > 20, "trop peu d'achats acceptés pour que le test soit probant : " + accepted.get()); //$NON-NLS-1$
	}

	@Test
	void sellerCannotSellTheSameCopyTwiceThroughTwoQrCodes() throws Exception
	{
		final int[] ids = newLibreGameWithPlayers(3, "double-qr"); //$NON-NLS-1$
		final int gameId = ids[0];
		for (int i = 1; i < ids.length; i++)
			sService.recordEvent(gameId, "W", ids[i], 0, 0, 0, 0, 0, null, 0, 0, 100, 0, 0, 0, 0, 0); //$NON-NLS-1$
		final Map<String, Integer> sellerInv = sService.computePlayerCardInventory(gameId, ids[1]);
		final String card = sellerInv.keySet().iterator().next();
		final int owned = sellerInv.get(card);
		final long expires = System.currentTimeMillis() + 60_000;
		// Autant de QR que d'exemplaires détenus : tous acceptés.
		for (int i = 0; i < owned; i++)
			sService.recordTransaction(gameId, ids[1], ids[2 + (i % 2)], card, levelOf(card), 1, 0, 0, 0, 0, 0, "dq-" + i, //$NON-NLS-1$
					expires);
		// Un QR de plus pour la même carte (écran de vente rouvert) : refusé.
		assertThrows(IllegalArgumentException.class, () -> sService.recordTransaction(gameId, ids[1], ids[2], card,
				levelOf(card), 1, 0, 0, 0, 0, 0, "dq-extra", expires)); //$NON-NLS-1$
		assertEquals(5, cardsPerModel(gameId, ids).get(card));
	}

	@Test
	void priceAndRecordedLevelComeFromThePileNotFromTheClient() throws Exception
	{
		final int[] ids = newLibreGameWithPlayers(2, "level-spoof"); //$NON-NLS-1$
		final int gameId = ids[0];
		for (int i = 1; i < ids.length; i++)
			sService.recordEvent(gameId, "W", ids[i], 0, 0, 0, 0, 0, null, 0, 0, 100, 0, 0, 0, 0, 0); //$NON-NLS-1$
		final String faibleCard = sService.computePlayerCardInventory(gameId, ids[1]).keySet().iterator().next();
		final int buyerBefore = sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId() == ids[2])
				.findFirst().orElseThrow().getJetonWeak();
		final Game game = sService.getGame(gameId);
		final int expectedPrice = (int) Math.round(game.cardPriceInDU("faible") * game.computeCurrentDU()); //$NON-NLS-1$
		// Le client prétend "tresforte" pour une carte faible.
		final var tx = sService.recordTransaction(gameId, ids[1], ids[2], faibleCard, "tresforte", 1, 0, 0, 0, 0, 0, //$NON-NLS-1$
				"spoof-1", System.currentTimeMillis() + 60_000); //$NON-NLS-1$
		final int buyerAfter = sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId() == ids[2])
				.findFirst().orElseThrow().getJetonWeak();
		assertEquals("faible", tx.getCardLevel()); //$NON-NLS-1$
		assertEquals(expectedPrice, buyerBefore - buyerAfter);
	}
}
