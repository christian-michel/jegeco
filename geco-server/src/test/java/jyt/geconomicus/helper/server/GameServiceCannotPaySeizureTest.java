package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Vérifie, par simulation directe sur GameService (jamais via HTTP), les
 * règles de saisie/banqueroute/prison remontées par l'utilisateur (24/10/2026,
 * PDF "Retours - 20261024", pages 4-6), en dette+smartphone :
 * <ul>
 * <li>page 4 : la "Valeur que la banque décide de saisir" (formule vérifiée
 * côté client dans app.js, computeSeizureTargetPrefill - pas testable ici en
 * Java, voir ce fichier pour les exemples chiffrés 3+1+2=6 et 3+2=5) ;</li>
 * <li>pages 5-6 : les effets RÉELS de pioche (GameService.resolveCannotPaySeizure,
 * ce fichier) - cartes précises retirées au joueur et remises dans la pioche
 * partagée, nouvelles cartes distribuées en banqueroute/à la libération de
 * prison.</li>
 * </ul>
 */
class GameServiceCannotPaySeizureTest
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

	private Map<String, java.util.List<String>> smallCatalog(final int pModelsPerLevel)
	{
		final Map<String, java.util.List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : java.util.List.of("faible", "moyenne", "forte", "tresforte")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		{
			final java.util.List<String> ids = new java.util.ArrayList<>();
			for (int i = 0; i < pModelsPerLevel; i++)
				ids.add(level + "_modele_" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	/** Force directement l'inventaire d'un joueur (contournant le tirage
	 * aléatoire habituel) - utilisé UNIQUEMENT pour mettre en place un
	 * scénario de test précis (le joueur possède exactement ceci), jamais
	 * pour reproduire la logique métier elle-même. Rend d'abord à la pioche
	 * partagée les cartes RÉELLEMENT détenues par le joueur avant cet appel
	 * (sa dotation de départ, voir dealStartingHandsForLibreIfNeeded) - sans
	 * ça, ces cartes disparaîtraient purement et simplement du total
	 * compté par les tests de conservation ci-dessous, pour une raison
	 * propre à CE test (pas un vrai bug de l'application). Le niveau de
	 * chaque carte se déduit de son identifiant (convention de
	 * smallCatalog() : "<niveau>_modele_<n>"), jamais de la pioche
	 * (findLevelOfCard est privée côté GameService). */
	private void forceInventory(final int pGameId, final int pPlayerId, final Map<String, Integer> pInventory)
			throws Exception
	{
		final Map<String, Integer> previouslyHeld = sService.computePlayerCardInventory(pGameId, pPlayerId);
		final EntityManager em = sEmf.createEntityManager();
		try
		{
			em.getTransaction().begin();
			final Game game = em.find(Game.class, pGameId);
			final Map<String, Map<String, Integer>> pilesByLevel = new ObjectMapper().readValue(
					game.getSmartphoneCardPileJson(), new TypeReference<Map<String, Map<String, Integer>>>()
					{
					});
			for (final Map.Entry<String, Integer> e : previouslyHeld.entrySet())
			{
				final String level = e.getKey().split("_modele_")[0]; //$NON-NLS-1$
				pilesByLevel.get(level).merge(e.getKey(), e.getValue(), Integer::sum);
			}
			// Symétrique : la NOUVELLE main forcée est réputée "piochée" depuis la
			// pioche partagée (sinon ses cartes seraient comptées deux fois - une
			// fois chez le joueur, une fois toujours dans la pioche).
			for (final Map.Entry<String, Integer> e : pInventory.entrySet())
			{
				final String level = e.getKey().split("_modele_")[0]; //$NON-NLS-1$
				pilesByLevel.get(level).merge(e.getKey(), -e.getValue(), Integer::sum);
			}
			game.setSmartphoneCardPileJson(new ObjectMapper().writeValueAsString(pilesByLevel));

			final Player player = em.find(Player.class, pPlayerId);
			player.setStartingCardsJson(new ObjectMapper().writeValueAsString(pInventory));
			player.setCardInventoryResetAt(new java.util.Date());
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	private Map<String, Map<String, Integer>> readPiles(final int pGameId) throws Exception
	{
		final Game game = sService.getGame(pGameId);
		return new ObjectMapper().readValue(game.getSmartphoneCardPileJson(),
				new TypeReference<Map<String, Map<String, Integer>>>()
				{
				});
	}

	private int sumPile(final Map<String, Integer> pPile)
	{
		return pPile.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Page 6, cas 1 : saisie simple qui couvre la cible ET laisse au moins 4
	 * cartes au joueur - continue de jouer normalement, les cartes précises
	 * saisies sont remises dans la pioche partagée (jamais perdues).
	 */
	@Test
	void testSimpleSeizureReturnsExactCardsToSharedPile() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimSeizure1", null, "test saisie simple", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-24", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Victime1"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		// Inventaire forcé : 6 faibles, 2 moyennes, 1 forte (9 cartes au total,
		// largement >= 4 une fois la saisie effectuée).
		final Map<String, Integer> inventory = new LinkedHashMap<>();
		inventory.put("faible_modele_0", 6); //$NON-NLS-1$
		inventory.put("moyenne_modele_0", 2); //$NON-NLS-1$
		inventory.put("forte_modele_0", 1); //$NON-NLS-1$
		forceInventory(gameId, player.getId(), inventory);

		final int totalBefore = sumInventory(inventory) + sumPiles(readPiles(gameId));

		// Saisie demandée : 2 faibles + 1 moyenne + 1 forte (= 2+2+4 = 8 en
		// valeur, sans importance ici, seul le nombre de cartes compte pour ce
		// test de conservation).
		sService.recordEvent(gameId, "C", player.getId(), 0, 0, 2, 1, 1, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.resolveCannotPaySeizure(gameId, player.getId(), "C", 2, 1, 1); //$NON-NLS-1$

		final Map<String, Integer> remaining = sService.computePlayerCardInventory(gameId, player.getId());
		final int remainingTotal = remaining.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(5, remainingTotal, "5 cartes doivent rester au joueur (9 - 2 - 1 - 1 = 5 saisies)"); //$NON-NLS-1$

		final int totalAfter = remainingTotal + sumPiles(readPiles(gameId));
		assertEquals(totalBefore, totalAfter,
				"aucune carte ne doit disparaître ni apparaître : les 4 cartes saisies doivent être remises dans la pioche partagée"); //$NON-NLS-1$
	}

	/**
	 * Page 6, cas 2 : la saisie couvre la cible mais laisse MOINS de 4 cartes
	 * au joueur - banqueroute personnelle : ses cartes restantes sont AUSSI
	 * saisies (il se retrouve à 0 carte AVANT la nouvelle dotation), puis il
	 * reçoit IMMÉDIATEMENT 4 nouvelles cartes faibles tirées de la pioche.
	 */
	@Test
	void testBankruptcySeizesRemainingCardsThenDealsFourFreshWeakCards() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimSeizure2", null, "test banqueroute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-24", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Victime2"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		// Inventaire forcé : seulement 3 cartes faibles au total - une saisie
		// de 2 en laisse 1, strictement moins de 4 -> banqueroute.
		final Map<String, Integer> inventory = new LinkedHashMap<>();
		inventory.put("faible_modele_0", 3); //$NON-NLS-1$
		forceInventory(gameId, player.getId(), inventory);

		sService.recordEvent(gameId, "B", player.getId(), 0, 0, 2, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.resolveCannotPaySeizure(gameId, player.getId(), "B", 2, 0, 0); //$NON-NLS-1$

		final Map<String, Integer> after = sService.computePlayerCardInventory(gameId, player.getId());
		final int afterTotal = after.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(4, afterTotal,
				"le joueur en banqueroute doit se retrouver avec exactement 4 cartes (sa nouvelle dotation), aucune ancienne carte conservée"); //$NON-NLS-1$
		assertTrue(after.keySet().stream().allMatch(id -> id.startsWith("faible_")), //$NON-NLS-1$
				"les 4 nouvelles cartes doivent toutes être de valeur FAIBLE"); //$NON-NLS-1$
	}

	/**
	 * Page 6, cas 3 : la saisie ne suffit jamais (même en prenant toutes les
	 * cartes) - prison : TOUTES les cartes du joueur sont remises dans la
	 * pioche IMMÉDIATEMENT (il se retrouve à 0 carte pendant tout le tour de
	 * blocage), puis il reçoit 4 nouvelles cartes faibles à sa LIBÉRATION
	 * (voir GameService.clearExpiredPrisonStatus, appelée à l'annonce de fin
	 * de tour - jamais avant).
	 */
	@Test
	void testPrisonSeizesEverythingImmediatelyThenDealsFourFreshCardsOnRelease() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimSeizure3", null, "test prison", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-24", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "Victime3"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final Map<String, Integer> inventory = new LinkedHashMap<>();
		inventory.put("faible_modele_0", 2); //$NON-NLS-1$
		inventory.put("moyenne_modele_0", 1); //$NON-NLS-1$
		forceInventory(gameId, player.getId(), inventory);

		final int totalBefore = sumInventory(inventory) + sumPiles(readPiles(gameId));

		sService.recordEvent(gameId, "P", player.getId(), 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.resolveCannotPaySeizure(gameId, player.getId(), "P", 0, 0, 0); //$NON-NLS-1$

		final Map<String, Integer> duringPrison = sService.computePlayerCardInventory(gameId, player.getId());
		assertEquals(0, duringPrison.values().stream().mapToInt(Integer::intValue).sum(),
				"pendant la prison, le joueur ne doit plus avoir AUCUNE carte"); //$NON-NLS-1$
		final int totalDuringPrison = sumPiles(readPiles(gameId));
		assertEquals(totalBefore, totalDuringPrison,
				"toutes les cartes saisies par la prison doivent être remises dans la pioche partagée, aucune carte perdue"); //$NON-NLS-1$

		final Player reloaded = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId().equals(player.getId())).findFirst().orElseThrow();
		final int turnNumberAtPrisonTime = sService.getGame(gameId).getTurnNumber();
		assertEquals(Integer.valueOf(turnNumberAtPrisonTime + 1), reloaded.getPrisonUntilTurn(),
				"le joueur doit être bloqué pour le PROCHAIN tour entier"); //$NON-NLS-1$

		// Le tour suivant (celui pour lequel il était bloqué) s'achève : sa
		// prison se lève et il reçoit 4 nouvelles cartes faibles.
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.clearExpiredPrisonStatus(gameId);

		final Player afterRelease = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId().equals(player.getId())).findFirst().orElseThrow();
		assertNull(afterRelease.getPrisonUntilTurn(), "la prison doit être levée une fois le tour de blocage achevé"); //$NON-NLS-1$
		final Map<String, Integer> afterReleaseInventory = sService.computePlayerCardInventory(gameId, player.getId());
		assertEquals(4, afterReleaseInventory.values().stream().mapToInt(Integer::intValue).sum(),
				"le joueur libéré doit recevoir exactement 4 nouvelles cartes"); //$NON-NLS-1$
		assertTrue(afterReleaseInventory.keySet().stream().allMatch(id -> id.startsWith("faible_")), //$NON-NLS-1$
				"les 4 nouvelles cartes de libération doivent toutes être de valeur FAIBLE"); //$NON-NLS-1$
	}

	private int sumInventory(final Map<String, Integer> pInventory)
	{
		return pInventory.values().stream().mapToInt(Integer::intValue).sum();
	}

	private int sumPiles(final Map<String, Map<String, Integer>> pPilesByLevel)
	{
		return pPilesByLevel.values().stream().mapToInt(this::sumPile).sum();
	}
}
