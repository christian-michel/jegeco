package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * BUG TROUVÉ ET CORRIGÉ (audit indépendant du 20/10/2026 du correctif
 * ea7c0d3 "remboursement de crédit" - voir CreditRequestService.
 * createIfNoPending pour le détail complet du raisonnement, et
 * GecoServer.java, route POST /api/games/{id}/credit-requests, pour son
 * appelant) : rien n'empêchait jusqu'ici un même joueur de déposer PLUSIEURS
 * demandes "pending" simultanées (double-tap sur "Demander un crédit", ou
 * simple relance réseau) - seul {@code curDebt > 0} était vérifié côté
 * GecoServer, qui ne redevient vrai qu'APRÈS approbation d'une demande, donc
 * ne protège en rien contre deux demandes encore "pending" en parallèle.
 * Reproduit en HTTP réel (deux POST simultanés, Promise.all) avant ce
 * correctif : les deux demandes étaient créées, toutes deux approuvables
 * séparément au tableau de bord animateur, menant à un crédit DOUBLE si
 * l'animateur approuvait les deux sans s'en apercevoir (mesuré :
 * curDebt=6/curInterest=2 au lieu de 3/1 pour un joueur n'ayant jamais
 * demandé qu'un seul principal de 3).
 * <p>
 * Ce test exerce directement {@link CreditRequestService} (classe du même
 * package, méthodes package-private) - pas besoin de démarrer un serveur
 * HTTP réel pour vérifier cette logique, qui ne dépend d'aucun état JPA.
 */
class CreditRequestServiceTest
{
	@Test
	void testCreateIfNoPendingRefusesSecondRequestForSamePlayer()
	{
		final CreditRequestService service = new CreditRequestService();
		final Integer first = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(first, "la première demande doit toujours réussir"); //$NON-NLS-1$

		final Integer second = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNull(second, "une seconde demande PENDANT que la première est encore pending doit être refusée"); //$NON-NLS-1$

		// Une seule demande "pending" réellement visible au tableau de bord animateur.
		assertEquals(1, service.listPending(1).size());
	}

	@Test
	void testCreateIfNoPendingAllowsNewRequestAfterResolution()
	{
		final CreditRequestService service = new CreditRequestService();
		final Integer first = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(first);

		// Refusée par l'animateur (même séquence que GecoServer : tryClaim PUIS
		// markResolved, voir son commentaire détaillé pour le raisonnement).
		assertTrue(service.tryClaim(first));
		service.markResolved(first, false);

		// Le joueur doit pouvoir redéposer une demande après un refus.
		final Integer second = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(second, "une nouvelle demande doit être possible après le refus de la précédente"); //$NON-NLS-1$
	}

	@Test
	void testCreateIfNoPendingAllowsNewRequestAfterApproval()
	{
		final CreditRequestService service = new CreditRequestService();
		final Integer first = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(first);
		assertTrue(service.tryClaim(first));
		service.markResolved(first, true);

		// Approuvée : GecoServer bloquerait normalement une redemande via
		// curDebt > 0, mais CE service, lui, ne connaît rien de la dette - il
		// doit redevenir disponible (la règle "déjà endetté" est une
		// responsabilité SÉPARÉE, vérifiée côté GecoServer avant d'appeler
		// cette méthode, voir son commentaire).
		final Integer second = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(second, "ce service seul ne doit pas bloquer après une approbation - c'est le rôle de curDebt côté GecoServer"); //$NON-NLS-1$
	}

	@Test
	void testCreateIfNoPendingIsIndependentPerPlayerAndPerGame()
	{
		final CreditRequestService service = new CreditRequestService();
		final Integer alice = service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
		final Integer bruno = service.createIfNoPending(1, 11, "Bruno", 3); //$NON-NLS-1$
		final Integer aliceOtherGame = service.createIfNoPending(2, 10, "Alice", 3); //$NON-NLS-1$
		assertNotNull(alice);
		assertNotNull(bruno, "un AUTRE joueur de la même partie doit pouvoir déposer sa propre demande"); //$NON-NLS-1$
		assertNotNull(aliceOtherGame, "le même nom de joueur dans une AUTRE partie n'a rien à voir (id de partie différent)"); //$NON-NLS-1$
		assertEquals(3, service.listPending(1).size() + service.listPending(2).size());
	}

	/**
	 * Vérifie en conditions de concurrence RÉELLE (threads relâchés au même
	 * instant via {@link CyclicBarrier}, même méthode déjà utilisée dans ce
	 * module pour GameServiceConcurrentRedeemTest - voir son commentaire de
	 * tête pour le raisonnement complet sur pourquoi un test purement
	 * séquentiel ne suffit PAS à révéler ce genre de bug) qu'une seule des N
	 * tentatives concurrentes de demande de crédit pour le MÊME joueur
	 * réussit jamais - reproduit le scénario exact du double-tap/de la
	 * relance réseau avant le correctif (toutes auraient réussi).
	 */
	@Test
	void testCreateIfNoPendingUnderRealConcurrencyOnlyOneWins() throws Exception
	{
		final CreditRequestService service = new CreditRequestService();
		final int threadCount = 30;
		final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		final CyclicBarrier barrier = new CyclicBarrier(threadCount);
		try
		{
			final List<Future<Integer>> futures = IntStream.range(0, threadCount).mapToObj(i -> pool.submit(() -> {
				barrier.await();
				return service.createIfNoPending(1, 10, "Alice", 3); //$NON-NLS-1$
			})).collect(Collectors.toList());

			final AtomicInteger successCount = new AtomicInteger(0);
			for (final Future<Integer> f : futures)
			{
				final Integer result = f.get(10, TimeUnit.SECONDS);
				if (result != null)
					successCount.incrementAndGet();
			}

			assertEquals(1, successCount.get(),
					"une seule des " + threadCount + " tentatives concurrentes doit réussir à créer une demande"); //$NON-NLS-1$ //$NON-NLS-2$
			assertEquals(1, service.listPending(1).size(),
					"une seule demande 'pending' doit être visible au tableau de bord animateur, jamais " + threadCount); //$NON-NLS-1$
		}
		finally
		{
			pool.shutdownNow();
		}
	}
}
