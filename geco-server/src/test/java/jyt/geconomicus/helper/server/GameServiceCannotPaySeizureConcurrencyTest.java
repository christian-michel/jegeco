package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Audit indépendant (25/10/2026, campagne de validation du PDF "Retours -
 * 20261024") de {@code GameService.resolveCannotPaySeizure} (introduite par le
 * commit c71a74c) sous VRAIE concurrence HTTP-like, même méthode que
 * {@code GameServiceConcurrentRedeemTest}/{@code CreditRequestServiceTest} déjà
 * en place dans ce projet - demandée explicitement par la mission d'audit pour
 * vérifier que la nouvelle route de résolution saisie/banqueroute/prison, bien
 * que protégée par {@code withGameLock} (vérifié par lecture de code), reste
 * SÛRE (jamais de carte dupliquée ni perdue, jamais de compte négatif) si
 * l'animateur double-tape le bouton "Valider" ou si une requête relancée par le
 * réseau arrive en double - exactement le même scénario de double-soumission
 * déjà rencontré et corrigé ailleurs dans ce projet pour les demandes de
 * crédit (voir CLAUDE.md, entrée du 20/10/2026).
 * <p>
 * Résultat de cet audit (voir le rapport complet remis à l'agent de contrôle) :
 * {@code resolveCannotPaySeizureUnlocked} clampe toujours sa saisie à
 * l'inventaire RÉEL du joueur relu depuis la base à l'intérieur du verrou de
 * partie - un double appel concurrent ne peut donc jamais faire apparaître ou
 * disparaître de carte au niveau de la pioche/l'inventaire (conservation
 * stricte vérifiée ci-dessous sous 24 appels réellement simultanés). La seule
 * lacune réelle identifiée (documentée, pas corrigée ici : pas une régression
 * de ce commit mais un trait architectural PRÉEXISTANT et partagé par TOUS les
 * types d'événements de cette route générique, pas spécifique à "Ne peut pas
 * payer") est que POST .../events n'a, de façon générale dans toute
 * l'application, aucune déduplication d'un double-clic côté animateur - un
 * double appel à recordEvent("C"/"B"/"P", ...) débiterait deux fois
 * jetonWeak/curDebt au niveau du moteur (Event.applyEvent), comme n'importe
 * quel autre événement enregistré deux fois par erreur. Ce risque est
 * atténué en pratique par l'UI animateur (un seul clic attendu, assistant
 * fermé après soumission) et n'est pas propre à cette fonctionnalité -
 * documenté ici plutôt que "corrigé" pour ne pas élargir la portée de cet
 * audit au-delà de ce qui a été demandé (la route de résolution de pioche
 * elle-même, qui EST bien protégée et conservatrice).
 */
class GameServiceCannotPaySeizureConcurrencyTest
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

	private int sumPiles(final Map<String, Map<String, Integer>> pPilesByLevel)
	{
		return pPilesByLevel.values().stream().mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum())
				.sum();
	}

	/**
	 * 24 appels VRAIMENT simultanés (CyclicBarrier-like via CountDownLatch) de
	 * {@code resolveCannotPaySeizure("C", 2, 1, 1)} pour le MÊME joueur - simule
	 * un double/triple/... clic réseau sur le même bouton "Valider la saisie".
	 * Le joueur ne possède que 9 cartes (2+1+1 = 4 demandées à chaque appel) :
	 * au plus 2 appels peuvent réussir intégralement avant qu'il n'ait plus
	 * assez de cartes du niveau demandé pour un 3e. Peu importe combien
	 * "réussissent" (aucune notion d'échec ici - seizeRandomOwnedCards clampe
	 * silencieusement) : la seule propriété qui compte, et que ce test vérifie
	 * strictement, est la CONSERVATION (pioche + main du joueur, total
	 * invariant, jamais de compte négatif nulle part).
	 */
	@Test
	void testConcurrentSeizureCallsNeverDuplicateOrLoseCards() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimSeizureConc", null, "test concurrence saisie", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-25", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player player = sService.addPlayer(gameId, "VictimeConcurrente"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(20));

		final Map<String, Integer> inventory = new LinkedHashMap<>();
		inventory.put("faible_modele_0", 6); //$NON-NLS-1$
		inventory.put("moyenne_modele_0", 2); //$NON-NLS-1$
		inventory.put("forte_modele_0", 1); //$NON-NLS-1$
		forceInventory(gameId, player.getId(), inventory);

		final int totalBefore = 9 + sumPiles(readPiles(gameId));

		final int threadCount = 24;
		final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch ready = new CountDownLatch(threadCount);
		final CountDownLatch go = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threadCount);
		final AtomicInteger errors = new AtomicInteger(0);
		for (int i = 0; i < threadCount; i++)
		{
			pool.submit(() -> {
				try
				{
					ready.countDown();
					go.await();
					sService.resolveCannotPaySeizure(gameId, player.getId(), "C", 2, 1, 1); //$NON-NLS-1$
				}
				catch (final Throwable t)
				{
					errors.incrementAndGet();
				}
				finally
				{
					done.countDown();
				}
			});
		}
		ready.await(5, TimeUnit.SECONDS);
		go.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS), "tous les threads doivent se terminer dans le délai"); //$NON-NLS-1$
		pool.shutdownNow();

		assertEquals(0, errors.get(), "aucune des 24 résolutions concurrentes ne doit lever d'exception"); //$NON-NLS-1$

		final Map<String, Integer> remaining = sService.computePlayerCardInventory(gameId, player.getId());
		final int remainingTotal = remaining.values().stream().mapToInt(Integer::intValue).sum();
		assertTrue(remainingTotal >= 0, "le joueur ne doit jamais avoir un nombre négatif de cartes"); //$NON-NLS-1$

		final Map<String, Map<String, Integer>> pilesAfter = readPiles(gameId);
		for (final Map<String, Integer> pileOfLevel : pilesAfter.values())
			for (final int count : pileOfLevel.values())
				assertTrue(count >= 0, "aucun compte de pioche ne doit jamais devenir négatif"); //$NON-NLS-1$

		final int totalAfter = remainingTotal + sumPiles(pilesAfter);
		assertEquals(totalBefore, totalAfter,
				"conservation stricte : pioche + main du joueur doit rester invariante sous charge concurrente, même en cas de double-soumission du même bouton"); //$NON-NLS-1$
	}
}
