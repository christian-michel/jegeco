package jyt.geconomicus.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Event.EventType;

/**
 * Vérifie le comportement du moteur spécifique au troc : dotation de départ,
 * échange bien-contre-bien entre deux joueurs, renaissance avec la dotation
 * de départ - voir docs/10-etape-plugins-troc.md pour les règles telles que
 * validées avec l'utilisateur (uniquement des transactions d'échange, jamais
 * de don sans contrepartie, aucune monnaie ni jeton).
 */
public class BarterMoneySystemTest
{
	private EntityManagerFactory mFactory;
	private EntityManager mEm;
	private Game mGame;
	private Player mAramis;
	private Player mDartagnan;

	@BeforeEach
	public void setUp()
	{
		mFactory = TestPersistence.newIsolatedFactory();
		mEm = mFactory.createEntityManager();
		mEm.getTransaction().begin();
		mGame = new Game(Game.MONEY_TROC, 10, "animateur", "a@b.c", "partie de test", "today", "ici", 1);
		mAramis = new Player(mGame, "Aramis");
		mDartagnan = new Player(mGame, "Dartagnan");
		new Event(mGame, EventType.JOIN, mAramis).applyEvent();
		new Event(mGame, EventType.JOIN, mDartagnan).applyEvent();
	}

	@AfterEach
	public void tearDown()
	{
		mEm.getTransaction().rollback();
		mEm.close();
		mFactory.close();
	}

	@Test
	public void testPlayerStartsWithConfiguredStartingGoods()
	{
		// Dotation par défaut du moteur (Game.startingGoods) : 4, conformément à la
		// règle 1 validée avec l'utilisateur.
		assertEquals(4, mAramis.getGoodsCount());
		assertEquals(4, mDartagnan.getGoodsCount());
	}

	@Test
	public void testGoodsTradeTransfersGoodsBothWays()
	{
		// Aramis donne 2 biens, reçoit 1 en retour (négociation libre, aucune
		// valeur imposée - voir règle 6). Départ : 4 chacun.
		final Event trade = new Event(mGame, EventType.GOODS_TRADE, mAramis);
		trade.setCounterpartyPlayer(mDartagnan);
		trade.setGoodsFromPlayer(2);
		trade.setGoodsFromCounterparty(1);
		trade.applyEvent();

		assertEquals(3, mAramis.getGoodsCount(), "Aramis : 4 - 2 (donnés) + 1 (reçus) = 3.");
		assertEquals(5, mDartagnan.getGoodsCount(), "Dartagnan : 4 - 1 (donnés) + 2 (reçus) = 5.");
	}

	@Test
	public void testTotalGoodsInCirculationIsConservedAcrossTrades()
	{
		// Un échange ne crée ni ne détruit de biens - invariant important à
		// vérifier, puisque le troc n'a (contrairement aux deux autres systèmes)
		// aucune notion de masse monétaire globale à surveiller pour détecter une
		// incohérence : ce total est la seule grandeur globale qui doit rester
		// stable d'un échange à l'autre.
		final int totalBefore = mAramis.getGoodsCount() + mDartagnan.getGoodsCount();

		final Event trade = new Event(mGame, EventType.GOODS_TRADE, mAramis);
		trade.setCounterpartyPlayer(mDartagnan);
		trade.setGoodsFromPlayer(3);
		trade.setGoodsFromCounterparty(1);
		trade.applyEvent();

		final int totalAfter = mAramis.getGoodsCount() + mDartagnan.getGoodsCount();
		assertEquals(totalBefore, totalAfter, "Le nombre total de biens en circulation entre les deux joueurs ne doit jamais changer suite à un échange.");
	}

	@Test
	public void testDeathResetsGoodsToStartingAllotment()
	{
		// Aramis accumule des biens avant de mourir...
		final Event trade = new Event(mGame, EventType.GOODS_TRADE, mDartagnan);
		trade.setCounterpartyPlayer(mAramis);
		trade.setGoodsFromPlayer(1);
		trade.setGoodsFromCounterparty(3);
		trade.applyEvent();
		assertEquals(2, mAramis.getGoodsCount(), "Aramis : 4 - 3 (donnés) + 1 (reçus) = 2, avant sa mort.");

		new Event(mGame, EventType.DEATH, mAramis).applyEvent();

		// Règle 1 : à la renaissance, le joueur repart avec la dotation de départ,
		// jamais avec ce qu'il possédait avant sa mort.
		assertEquals(4, mAramis.getGoodsCount(), "À la renaissance, Aramis doit repartir avec la dotation de départ (4), pas avec ce qu'il possédait avant.");
	}

	@Test
	public void testMoneyMassIsNeverUsedInBarterSystem()
	{
		// Le troc n'a ni monnaie ni banque - la masse monétaire (héritage des deux
		// autres systèmes) doit rester à 0 quoi qu'il se passe.
		final Event trade = new Event(mGame, EventType.GOODS_TRADE, mAramis);
		trade.setCounterpartyPlayer(mDartagnan);
		trade.setGoodsFromPlayer(1);
		trade.setGoodsFromCounterparty(1);
		trade.applyEvent();
		new Event(mGame, EventType.TURN, null).applyEvent();
		new Event(mGame, EventType.DEATH, mAramis).applyEvent();

		assertEquals(0, mGame.getMoneyMass(), "La masse monétaire doit rester à 0 en troc, quels que soient les événements survenus.");
	}
}
