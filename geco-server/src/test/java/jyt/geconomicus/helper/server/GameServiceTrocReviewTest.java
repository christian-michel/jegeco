package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * SECOND AGENT DE REVUE (10/10/2026) - vérification indépendante de la
 * campagne de validation troc+smartphone menée par un premier agent (3
 * parties 4/8/16 joueurs/12 tours, commit 6e27f21 - correctif goodsCount du
 * carré). Conformément à CLAUDE.md ("seconde relecture indépendante... un
 * second agent qui n'a vu ni le raisonnement ni les hypothèses du premier,
 * relit le diff, revérifie les tests, rejoue lui-même des scénarios - jamais
 * seulement ceux du premier agent"), ce fichier construit ses PROPRES
 * scénarios, distincts de ceux du premier agent, pour revérifier
 * EMPIRIQUEMENT (jamais seulement par lecture de code) les points que le
 * rapport du premier agent demandait explicitement de vérifier soi-même :
 * <p>
 * 1. {@link #testSwapRejectedWhenRealCatalogLevelsDifferEvenIfClientLiesAboutThem} -
 * le refus "valeurs différentes" (sécurité déjà corrigée une fois le
 * 18/09/2026, donc particulièrement sensible) : le premier agent n'avait
 * vérifié ce cas QUE par lecture de code, jamais empiriquement (tirage
 * aléatoire n'ayant jamais produit de paire exploitable pendant sa
 * campagne).
 * <p>
 * 2. {@link #testSquareUpdatesGoodsCountToExactlyMatchRealInventoryAcrossTwoSquares} -
 * le correctif goodsCount du commit 6e27f21 lui-même, sur un scénario à DEUX
 * carrés consécutifs (jamais testé par le premier agent, qui n'avait vérifié
 * qu'un seul carré forcé).
 * <p>
 * 3. {@link #testQuitInTrocSmartphoneConservesAllCardsAndReturnsThemToTheSharedPile} -
 * conservation totale à une sortie de joueur (QUIT) en troc+smartphone en
 * cours de partie (point (e) de la mission de revue). Reproduit d'abord en
 * HTTP réel sur le serveur tournant (card-inventory devenu {} mais
 * goodsCount resté à sa valeur d'avant abandon) un NOUVEAU bug, de la même
 * famille que le correctif goodsCount du commit 6e27f21 pour le carré :
 * corrigé dans ce même commit de revue (voir GameService.recordEventUnlocked,
 * cas QUIT).
 * <p>
 * 4. {@link #testWealthReportDoubleCountsInventoryAcrossTwoDeathsForTroc} -
 * vérification chiffrée indépendante du problème de conception signalé
 * (NON corrigé) par le premier agent : la richesse troc rapportée cumule le
 * gain de CHAQUE mort au lieu de ne refléter que l'inventaire final (règle 9
 * de docs/10-etape-plugins-troc.md : "richesse finale = nombre d'objets
 * possédés en fin de partie").
 */
class GameServiceTrocReviewTest
{
	private static EntityManagerFactory sEmf;
	private static GameService sService;
	private static final StatsService sStats = new StatsService();

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

	private Map<String, List<String>> smallCatalog()
	{
		final Map<String, List<String>> catalog = new LinkedHashMap<>();
		catalog.put("faible", List.of("faible_0", "faible_1", "faible_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("moyenne", List.of("moyenne_0", "moyenne_1", "moyenne_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("forte", List.of("forte_0", "forte_1", "forte_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("tresforte", List.of("tresforte_0")); //$NON-NLS-1$ //$NON-NLS-2$
		return catalog;
	}

	/** Prépare une partie TROC+smartphone à 2 joueurs, prête à l'emploi
	 * (mise en place de la pioche + mains de départ réelles). */
	private int[] setUpTrocGameWithTwoPlayers() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_TROC, 12, "ReviewAgentD", null, //$NON-NLS-1$
				"revue indépendante troc+smartphone (10/10/2026)", "2026-10-10", "Ceres", 1, 180, 1.0, false, 4, true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				0.5);
		final int gameId = game.getId();
		final int p0 = sService.addPlayer(gameId, "P0").getId(); //$NON-NLS-1$
		final int p1 = sService.addPlayer(gameId, "P1").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog());

		return new int[] { gameId, p0, p1 };
	}

	/** Injecte directement pCardId×pCount dans la main de pPlayerId, en
	 * contournant toute règle métier - même technique que
	 * GameServiceSquareEncashmentTest.sell (accès direct JPA), utilisée ici
	 * pour construire des scénarios déterministes (carré forcé, mismatch de
	 * niveau) sans dépendre d'un tirage aléatoire de swaps valides, comme le
	 * premier agent a dû le faire.
	 * <p>
	 * pAlsoSyncGoodsCount : quand vrai, goodsCount est avancé du même pCount
	 * en même temps que l'inventaire - nécessaire pour les scénarios qui
	 * vérifient ensuite la justesse du DELTA appliqué par un carré
	 * (checkAndCashInSquaresUnlocked, correctif 6e27f21) : ce correctif
	 * n'ajuste goodsCount que du delta DU CARRÉ lui-même, jamais une
	 * resynchronisation complète depuis l'inventaire - il faut donc partir
	 * d'un goodsCount déjà cohérent AVANT le carré pour que la vérification
	 * APRÈS porte uniquement sur ce que le correctif est censé garantir
	 * (exactement comme si ces cartes avaient été obtenues par un vrai
	 * mécanisme qui, lui, maintient goodsCount - ex. plusieurs swaps réels). */
	private void giveCards(final int pPlayerId, final String pCardId, final int pCount,
			final boolean pAlsoSyncGoodsCount)
	{
		final EntityManager em = sEmf.createEntityManager();
		try
		{
			em.getTransaction().begin();
			final Player player = em.find(Player.class, pPlayerId);
			final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			try
			{
				final Map<String, Integer> hand = mapper.readValue(player.getStartingCardsJson(),
						new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Integer>>()
						{
						});
				hand.merge(pCardId, pCount, Integer::sum);
				player.setStartingCardsJson(mapper.writeValueAsString(hand));
			}
			catch (final com.fasterxml.jackson.core.JsonProcessingException e)
			{
				throw new RuntimeException(e);
			}
			if (pAlsoSyncGoodsCount)
				player.setGoodsCount(player.getGoodsCount() + pCount);
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	private void giveCards(final int pPlayerId, final String pCardId, final int pCount)
	{
		giveCards(pPlayerId, pCardId, pCount, false);
	}

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

	/**
	 * Vérification empirique (pas seulement une lecture de code, contrairement
	 * au premier agent sur ce point précis) de la protection "même valeur"
	 * contre un client malveillant - exactement le type de régression déjà
	 * trouvée et corrigée le 18/09/2026 (voir le commentaire détaillé de
	 * GameService.recordCardSwap, "les deux cartes n'ont pas la même valeur").
	 * <p>
	 * Construit un cas où p0 et p1 possèdent chacun un exemplaire de l'autre
	 * (réciprocité satisfaite - "forte_0" et "faible_0"), mais les deux cartes
	 * ont des niveaux RÉELLEMENT différents dans le catalogue partagé
	 * (pilesByLevel) : forte_0 est "forte", faible_0 est "faible". Le serveur
	 * doit dériver le VRAI niveau de chaque carte depuis la pioche partagée
	 * (findLevelOfCard), jamais faire confiance à un niveau déclaré par le
	 * client - ce test n'a même pas besoin de mentir sur le niveau déclaré
	 * (pCardLevel/pOfferedCardLevel) pour le prouver : la seule divergence de
	 * niveau RÉEL entre les deux cartes suffit déjà à devoir être rejetée.
	 */
	@Test
	void testSwapRejectedWhenRealCatalogLevelsDifferEvenIfClientLiesAboutThem() throws Exception
	{
		final int[] ids = setUpTrocGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p0 = ids[1];
		final int p1 = ids[2];

		// Réciprocité bidirectionnelle satisfaite : p0 détient déjà "faible_0"
		// (ce qu'il recevrait), p1 détient déjà "forte_0" (ce qu'il recevrait) -
		// sans ça, recordCardSwap rejetterait pour la MAUVAISE raison
		// (réciprocité, pas valeur) et ne prouverait rien sur ce test précis.
		giveCards(p0, "forte_0", 1); //$NON-NLS-1$
		giveCards(p0, "faible_0", 1); //$NON-NLS-1$
		giveCards(p1, "faible_0", 1); //$NON-NLS-1$
		giveCards(p1, "forte_0", 1); //$NON-NLS-1$

		// Références AVANT la tentative d'échange - jamais une valeur absolue
		// supposée (ex. "1 exemplaire") : la main de départ réelle
		// (dealStartingHandsForLibreIfNeeded) peut déjà contenir des
		// faible_0/faible_1/faible_2 au hasard, giveCards ci-dessus ne fait
		// qu'AJOUTER à ce qui existe déjà.
		final int p0ForteBefore = sService.computePlayerCardInventory(gameId, p0).getOrDefault("forte_0", 0); //$NON-NLS-1$
		final int p1FaibleBefore = sService.computePlayerCardInventory(gameId, p1).getOrDefault("faible_0", 0); //$NON-NLS-1$

		// p0 propose sa carte "forte_0" (réellement forte), en prétendant NIER
		// la différence de niveau (déclare lui aussi "faible" pour les DEUX
		// cartes, comme le ferait un client modifié) : exactement le scénario
		// du bug du 18/09/2026, reconstruit ici en conditions réelles plutôt
		// que supposé par lecture de code.
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> sService.recordCardSwap(gameId, p0, p1, "forte_0", "faible", "faible_0", "faible", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
						"nonce-mismatch-1", System.currentTimeMillis() + 60_000)); //$NON-NLS-1$
		assertTrue(ex.getMessage().contains("même valeur"), //$NON-NLS-1$
				"le refus doit explicitement porter sur la différence de valeur, message obtenu : " + ex.getMessage()); //$NON-NLS-1$

		// Preuve supplémentaire de conservation : l'échange refusé ne doit avoir
		// RIEN déplacé (aucune Transaction persistée, inventaires inchangés).
		assertEquals(p0ForteBefore, sService.computePlayerCardInventory(gameId, p0).getOrDefault("forte_0", 0), //$NON-NLS-1$
				"p0 doit toujours détenir exactement le même nombre de forte_0 après un échange refusé"); //$NON-NLS-1$
		assertEquals(p1FaibleBefore, sService.computePlayerCardInventory(gameId, p1).getOrDefault("faible_0", 0), //$NON-NLS-1$
				"p1 doit toujours détenir exactement le même nombre de faible_0 après un échange refusé"); //$NON-NLS-1$

		// Contre-épreuve (même paire de joueurs, cette fois une VRAIE paire de
		// même valeur réelle - les deux cartes "faible_0"/"faible_1" sont de
		// niveau "faible" chacune) : doit, elle, réussir - prouve que le refus
		// ci-dessus vient bien de la différence de valeur, pas d'un autre
		// obstacle (nonce, inactivité...) de ce même scénario.
		giveCards(p0, "faible_1", 1); //$NON-NLS-1$
		giveCards(p1, "faible_1", 1); //$NON-NLS-1$
		sService.recordCardSwap(gameId, p0, p1, "faible_1", "faible", "faible_0", "faible", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"nonce-mismatch-control", System.currentTimeMillis() + 60_000); //$NON-NLS-1$
	}

	/**
	 * Revérifie le correctif goodsCount du commit 6e27f21 (GameService.
	 * checkAndCashInSquaresUnlocked), sur un scénario à DEUX carrés
	 * consécutifs (jamais testé par le premier agent, qui n'avait vérifié
	 * qu'un seul carré forcé) - garantit que le delta s'accumule correctement
	 * plutôt que de n'être juste par coïncidence après un seul passage.
	 * <p>
	 * Invariant vérifié à chaque étape : goodsCount (le champ lu par le
	 * tableau de bord animateur) doit TOUJOURS être égal exactement au total
	 * réel de l'inventaire reconstitué (computePlayerCardInventory) - jamais
	 * seulement après le toute premier carré.
	 */
	@Test
	void testSquareUpdatesGoodsCountToExactlyMatchRealInventoryAcrossTwoSquares() throws Exception
	{
		final int[] ids = setUpTrocGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p1 = ids[2];

		final int goodsCountAtStart = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId() == p1).findFirst().orElseThrow().getGoodsCount();
		final int realInventoryAtStart = sService.computePlayerCardInventory(gameId, p1).values().stream()
				.mapToInt(Integer::intValue).sum();
		assertEquals(realInventoryAtStart, goodsCountAtStart,
				"avant tout carré, goodsCount doit déjà refléter la main de départ réelle"); //$NON-NLS-1$

		// Premier carré : 4 "moyenne_0" en main -> encaissé contre 1 carte
		// "forte" (promotion) + 4 nouvelles "moyenne" (remplacement).
		// pAlsoSyncGoodsCount=true : simule que ces 4 cartes ont été obtenues
		// par un mécanisme qui maintient goodsCount à jour au fil de l'eau
		// (ex. plusieurs vrais swaps 1-pour-1, neutres en delta) - nécessaire
		// pour isoler ce que ce test vérifie réellement : le delta appliqué
		// PAR LE CARRÉ lui-même (correctif 6e27f21), jamais une
		// resynchronisation complète que ce correctif ne prétend pas faire.
		giveCards(p1, "moyenne_0", 4, true); //$NON-NLS-1$
		sService.checkAndCashInSquares(gameId, p1);
		final int goodsCountAfterFirstSquare = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId() == p1).findFirst().orElseThrow().getGoodsCount();
		final int realInventoryAfterFirstSquare = sService.computePlayerCardInventory(gameId, p1).values().stream()
				.mapToInt(Integer::intValue).sum();
		assertEquals(realInventoryAfterFirstSquare, goodsCountAfterFirstSquare,
				"après le PREMIER carré, goodsCount doit correspondre exactement à l'inventaire réel (correctif 6e27f21)"); //$NON-NLS-1$

		// Deuxième carré, indépendant : force à nouveau 4 "faible_2" en main -
		// vérifie que le delta du PREMIER carré ne laisse pas goodsCount
		// dériver au second passage (ce que le premier agent n'avait jamais
		// testé, n'ayant forcé qu'un seul carré).
		giveCards(p1, "faible_2", 4, true); //$NON-NLS-1$
		sService.checkAndCashInSquares(gameId, p1);
		final int goodsCountAfterSecondSquare = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId() == p1).findFirst().orElseThrow().getGoodsCount();
		final int realInventoryAfterSecondSquare = sService.computePlayerCardInventory(gameId, p1).values().stream()
				.mapToInt(Integer::intValue).sum();
		assertEquals(realInventoryAfterSecondSquare, goodsCountAfterSecondSquare,
				"après un DEUXIÈME carré indépendant, goodsCount doit toujours correspondre exactement à l'inventaire réel"); //$NON-NLS-1$
	}

	/**
	 * Point (e) de la mission de revue : une sortie de joueur (QUIT) en
	 * troc+smartphone en cours de partie doit rendre intégralement ses cartes
	 * au marché/à la pioche commune - conservation totale (piles + mains de
	 * tous les joueurs) avant/après, et main du joueur sorti vidée.
	 * <p>
	 * Le mécanisme vérifié (GameService.recordEvent, bloc
	 * isSmartphoneCardTrackedLifeEnd) est documenté comme PARTAGÉ par les
	 * trois systèmes monétaires (gated uniquement sur
	 * player.getStartingCardsJson() != null, jamais sur
	 * game.getMoneySystem()) - ce test le revérifie explicitement pour
	 * MONEY_TROC plutôt que de se fier par analogie aux tests déjà existants
	 * en MONEY_LIBRE (GameServicePileConservationTest).
	 */
	@Test
	void testQuitInTrocSmartphoneConservesAllCardsAndReturnsThemToTheSharedPile() throws Exception
	{
		final int[] ids = setUpTrocGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p0 = ids[1];
		final int p1 = ids[2];

		// p1 accumule une main bien chargée et distincte avant de quitter -
		// pour que son retour à la pioche (par modèle) soit facilement
		// vérifiable, distinct de ce que p0 possède.
		giveCards(p1, "forte_1", 2); //$NON-NLS-1$
		giveCards(p1, "moyenne_2", 3); //$NON-NLS-1$

		final int totalBefore = totalCardsInPiles(gameId) + sumAllPlayersInventory(gameId, p0, p1);
		final Map<String, Integer> p1InventoryBeforeQuit = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(p1InventoryBeforeQuit.getOrDefault("forte_1", 0) >= 2, //$NON-NLS-1$
				"préalable du scénario : p1 doit bien détenir ses 2 forte_1 avant de quitter"); //$NON-NLS-1$

		final Map<String, Integer> pilesBeforeQuit = readPiles(gameId);
		final int forte1InPileBeforeQuit = pilesBeforeQuit.getOrDefault("forte_1", 0); //$NON-NLS-1$
		final int moyenne2InPileBeforeQuit = pilesBeforeQuit.getOrDefault("moyenne_2", 0); //$NON-NLS-1$

		sService.recordEvent(gameId, "Q", p1, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		// Conservation totale : exactement le même total après le QUIT.
		final int totalAfter = totalCardsInPiles(gameId) + sumAllPlayersInventory(gameId, p0, p1);
		assertEquals(totalBefore, totalAfter,
				"conservation totale des cartes : le total (pioches + mains) ne doit jamais changer à un QUIT"); //$NON-NLS-1$

		// La main du joueur sorti est vidée.
		final Map<String, Integer> p1InventoryAfterQuit = sService.computePlayerCardInventory(gameId, p1);
		final int p1TotalAfterQuit = p1InventoryAfterQuit.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(0, p1TotalAfterQuit, "la main du joueur qui a quitté doit être entièrement vidée"); //$NON-NLS-1$

		// Les cartes précises qu'il détenait sont bien RENDUES à leur pioche
		// (pas juste "disparues" du total global, vérifié ci-dessus - ici on
		// vérifie qu'elles sont au bon endroit : leur pioche de niveau).
		final Map<String, Integer> pilesAfterQuit = readPiles(gameId);
		assertEquals(forte1InPileBeforeQuit + 2, pilesAfterQuit.getOrDefault("forte_1", 0), //$NON-NLS-1$
				"les 2 forte_1 de p1 doivent être rendues exactement à la pioche forte_1"); //$NON-NLS-1$
		assertEquals(moyenne2InPileBeforeQuit + 3, pilesAfterQuit.getOrDefault("moyenne_2", 0), //$NON-NLS-1$
				"les 3 moyenne_2 de p1 doivent être rendues exactement à la pioche moyenne_2"); //$NON-NLS-1$

		// Le joueur qui a quitté ne doit plus pouvoir échanger (déjà couvert
		// par ailleurs, revérifié ici en passant car directement lié à la
		// conservation : s'il pouvait encore échanger après avoir "rendu" ses
		// cartes, on recréerait de la valeur à partir de rien).
		assertFalse(sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId() == p1).findFirst()
				.orElseThrow().isActive(), "un joueur qui a quitté ne doit plus être actif"); //$NON-NLS-1$

		// BUG TROUVÉ ET CORRIGÉ pendant cette revue (voir GameService.
		// recordEventUnlocked, bloc isSmartphoneCardTrackedLifeEnd, cas QUIT) :
		// goodsCount (lu par le tableau de bord animateur, app.js playersList -
		// jamais filtré aux seuls joueurs actifs, contrairement à
		// statGoods/stat_goods) restait figé sur sa dernière valeur d'avant
		// abandon alors que la main vient d'être vidée ci-dessus - reproduit
		// d'abord en HTTP réel (GET .../card-inventory={} mais goodsCount=4
		// inchangé) avant d'être corrigé. Revérifié ici : doit désormais valoir
		// exactement 0, comme l'inventaire réel.
		assertEquals(0, sService.getGame(gameId).getPlayers().stream().filter(p -> p.getId() == p1).findFirst()
				.orElseThrow().getGoodsCount(), "goodsCount doit être remis à 0 pour un joueur qui a quitté en troc"); //$NON-NLS-1$
	}

	private int sumAllPlayersInventory(final int gameId, final int... playerIds)
	{
		int total = 0;
		for (final int playerId : playerIds)
			total += sService.computePlayerCardInventory(gameId, playerId).values().stream()
					.mapToInt(Integer::intValue).sum();
		return total;
	}

	private Map<String, Integer> readPiles(final int gameId)
	{
		final Game game = sService.getGame(gameId);
		try
		{
			final Map<String, Map<String, Integer>> pilesByLevel = new com.fasterxml.jackson.databind.ObjectMapper()
					.readValue(game.getSmartphoneCardPileJson(),
							new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, Integer>>>()
							{
							});
			final Map<String, Integer> flat = new LinkedHashMap<>();
			for (final Map<String, Integer> pile : pilesByLevel.values())
				flat.putAll(pile);
			return flat;
		}
		catch (final com.fasterxml.jackson.core.JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Vérification chiffrée INDÉPENDANTE (scénario distinct de celui du
	 * premier agent, qui n'en avait donné qu'une preuve textuelle) du problème
	 * de conception signalé mais NON corrigé par le premier agent : la
	 * richesse troc rapportée par StatsService.computeFinalReport (donc
	 * GET /api/games/{id}/report) additionne le gain de CHAQUE
	 * DEATH/QUIT au lieu de ne refléter que l'inventaire final - contredisant
	 * la règle 9 de docs/10-etape-plugins-troc.md ("richesse finale = nombre
	 * d'objets possédés en fin de partie").
	 * <p>
	 * Scénario : p1 meurt UNE FOIS en détenant 4 cartes faibles (valeur 4,
	 * formule troc poids 1/4/16 - voir StatsService.computeCardsGain), renaît
	 * avec une nouvelle main de 4 cartes faibles (valeur 4 à nouveau, AUCUNE
	 * carte supplémentaire acquise après sa renaissance), puis est
	 * "finalisée" par un second DEATH représentant la fin de partie (voir
	 * docs/10-etape-plugins-troc.md, invariant commun : "tous les joueurs
	 * sont comptabilisés à la fin du dernier tour"). Son inventaire RÉEL en
	 * fin de partie est alors de 4 cartes (valeur 4) - mais la richesse
	 * rapportée doit, par construction de addGain (accumulation additive,
	 * jamais un remplacement), valoir 4+4=8, soit le DOUBLE EXACT de la
	 * valeur réelle détenue.
	 */
	@Test
	void testWealthReportDoubleCountsInventoryAcrossTwoDeathsForTroc() throws Exception
	{
		final int[] ids = setUpTrocGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p1 = ids[2];

		// Main de départ réelle de p1 (dealStartingHandsForLibreIfNeeded) :
		// vérifie le préalable du scénario (4 cartes faibles, valeur 4) plutôt
		// que de le supposer.
		final Map<String, Integer> startingHand = sService.computePlayerCardInventory(gameId, p1);
		final int startingHandTotal = startingHand.values().stream().mapToInt(Integer::intValue).sum();
		final boolean allWeak = startingHand.keySet().stream().allMatch(k -> k.startsWith("faible_")); //$NON-NLS-1$
		assertTrue(allWeak && (startingHandTotal == 4),
				"préalable du scénario : la main de départ doit être 4 cartes faibles exactement, obtenu : " //$NON-NLS-1$
						+ startingHand);

		// Première "mort" : meurt avec sa main de départ (4 faibles, valeur 4),
		// renaît avec une NOUVELLE main de 4 faibles (même valeur, par
		// construction de dealFreshHandForPlayer).
		sService.recordEvent(gameId, "D", p1, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		final Map<String, Integer> handAfterRebirth = sService.computePlayerCardInventory(gameId, p1);
		final int handAfterRebirthTotal = handAfterRebirth.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(4, handAfterRebirthTotal,
				"préalable du scénario : la main après renaissance doit elle aussi compter exactement 4 cartes"); //$NON-NLS-1$

		// AUCUNE carte supplémentaire acquise après la renaissance (le joueur
		// ne fait plus rien) : son inventaire réel en fin de partie EST cette
		// main de renaissance, 4 cartes, valeur 4 - jamais 8.
		final int realFinalInventoryValue = handAfterRebirthTotal; // poids 1 pour "faible" dans computeCardsGain

		// "Finalisation" de fin de partie - second DEATH représentant le
		// contrôle final demandé par la règle commune aux trois systèmes
		// (docs/10-etape-plugins-troc.md : "tous les joueurs sont
		// comptabilisés à la fin du dernier tour").
		sService.recordEvent(gameId, "D", p1, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game gameAfter = sService.getGame(gameId);
		final StatsService.FinalReport report = sStats.computeFinalReport(gameAfter, false);
		final int reportedWealth = report.playerWealths().stream().filter(w -> "P1".equals(w.playerName())) //$NON-NLS-1$
				.findFirst().orElseThrow().wealth();

		// DÉSACCORD CONFIRMÉ avec la règle 9 documentée : la richesse rapportée
		// vaut le DOUBLE EXACT de l'inventaire réellement détenu en fin de
		// partie (4+4=8, jamais 4) - comportement actuel non corrigé,
		// volontairement laissé tel quel ici (décision utilisateur en attente,
		// voir CLAUDE.md "Pièges déjà rencontrés" et le rapport du premier
		// agent) : ce test DOCUMENTE le comportement actuel plutôt que
		// d'imposer un correctif non demandé par l'utilisateur. S'il échoue un
		// jour, c'est que quelqu'un a changé ce comportement - à mettre à jour
		// consciemment, pas en CI aveugle.
		assertEquals(2 * realFinalInventoryValue, reportedWealth,
				"comportement actuel CONFIRMÉ (pas encore corrigé, décision utilisateur en attente) : " //$NON-NLS-1$
						+ "la richesse rapportée (" + reportedWealth + ") double l'inventaire réel final (" //$NON-NLS-1$ //$NON-NLS-2$
						+ realFinalInventoryValue + ") en sommant le gain de chaque DEATH au lieu de ne garder " //$NON-NLS-1$
						+ "que le dernier - contredit la règle 9 de docs/10-etape-plugins-troc.md"); //$NON-NLS-1$
		assertFalse(reportedWealth == realFinalInventoryValue,
				"si cette assertion échoue (égalité), c'est que le bug signalé par le premier agent a été " //$NON-NLS-1$
						+ "corrigé entre-temps - mettre à jour ce test en conséquence plutôt que l'ignorer"); //$NON-NLS-1$
	}
}
