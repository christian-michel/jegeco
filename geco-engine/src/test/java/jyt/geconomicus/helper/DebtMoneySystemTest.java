package jyt.geconomicus.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Event.EventType;

/**
 * Vérifie le comportement du moteur (Event.applyEvent) spécifique à la
 * monnaie dette : crédits, remboursements, saisies - voir
 * docs/12-guide-creer-systeme-echange.md pour le contexte de cette suite de
 * tests (point 2 de la liste "à finaliser avant l'étape 3").
 */
public class DebtMoneySystemTest
{
	private EntityManagerFactory mFactory;
	private EntityManager mEm;
	private Game mGame;
	private Player mPlayer1;

	@BeforeEach
	public void setUp()
	{
		mFactory = TestPersistence.newIsolatedFactory();
		mEm = mFactory.createEntityManager();
		mEm.getTransaction().begin();
		mGame = new Game(Game.MONEY_DEBT, 10, "animateur", "a@b.c", "partie de test", "today", "ici", 1);
		mPlayer1 = new Player(mGame, "Aramis");
		new Event(mGame, EventType.JOIN, mPlayer1).applyEvent();
	}

	@AfterEach
	public void tearDown()
	{
		mEm.getTransaction().rollback();
		mEm.close();
		mFactory.close();
	}

	@Test
	public void testNewCreditIncreasesDebtAndMoneyMass()
	{
		final int massBefore = mGame.getMoneyMass();
		final Event credit = new Event(mGame, EventType.NEW_CREDIT, mPlayer1);
		credit.setPrincipal(3);
		credit.setInterest(1);
		credit.applyEvent();

		assertEquals(3, mPlayer1.getCurDebt(), "Le principal emprunté doit s'ajouter à la dette du joueur.");
		assertEquals(1, mPlayer1.getCurInterest(), "L'intérêt dû doit s'ajouter à l'intérêt du joueur.");
		assertEquals(massBefore + 3, mGame.getMoneyMass(),
				"La masse monétaire augmente du principal accordé (l'intérêt n'est pas encore de la monnaie en circulation).");
	}

	@Test
	public void testFullRepaymentClearsDebtAndInterest()
	{
		final Event credit = new Event(mGame, EventType.NEW_CREDIT, mPlayer1);
		credit.setPrincipal(5);
		credit.setInterest(2);
		credit.applyEvent();

		final Event repayment = new Event(mGame, EventType.REIMB_CREDIT, mPlayer1);
		repayment.setPrincipal(5);
		repayment.setInterest(2);
		repayment.applyEvent();

		assertEquals(0, mPlayer1.getCurDebt(), "Un remboursement complet doit ramener la dette à 0.");
		assertEquals(0, mPlayer1.getCurInterest(), "Un remboursement complet doit ramener l'intérêt dû à 0.");
	}

	@Test
	public void testDeathDoesNotChangeMoneyMassInDebtSystem()
	{
		// Contrairement à la monnaie libre (où mourir déclenche un bonus de masse
		// monétaire), la monnaie dette n'a pas cette règle - vérifie qu'un décès
		// sans crédit en cours n'a aucun effet sur la masse monétaire globale.
		final int massBefore = mGame.getMoneyMass();
		new Event(mGame, EventType.DEATH, mPlayer1).applyEvent();
		assertEquals(massBefore, mGame.getMoneyMass(),
				"Un décès en monnaie dette ne doit jamais, à lui seul, modifier la masse monétaire.");
	}

	@Test
	public void testTurnMarksIndebtedPlayersAsNotVisitedBank()
	{
		final Event credit = new Event(mGame, EventType.NEW_CREDIT, mPlayer1);
		credit.setPrincipal(4);
		credit.setInterest(0);
		credit.applyEvent();
		assertTrue(mPlayer1.isVisitedBank(), "Juste après avoir emprunté, le joueur est considéré à jour (vient de passer à la banque).");

		new Event(mGame, EventType.TURN, null).applyEvent();

		assertTrue(!mPlayer1.isVisitedBank(),
				"Au tour suivant, un joueur qui a une dette en cours doit être marqué comme devant repasser à la banque.");
	}

	/**
	 * BUG TROUVÉ ET CORRIGÉ (18/09/2026, seconde relecture indépendante de la
	 * dette+smartphone introduite par ba1e8d8, confirmé en rejouant le
	 * scénario en direct via l'API REST) : REIMB_CREDIT débitait jetonWeak sur
	 * weakCards - un champ que les deux seuls points d'entrée réels de cet
	 * événement (bouton rapide "Rembourser le crédit" et la boîte de dialogue
	 * générique principal/intérêt, voir app.js openPlayerEventDialog) ne
	 * remplissent JAMAIS. Un joueur en dette+smartphone remboursait donc son
	 * crédit sans que son solde physique de jetons ne bouge d'un jeton -
	 * argent gratuit. Ce test simule le suivi par smartphone en donnant à
	 * mPlayer1 une pioche de départ (startingCardsJson non nul, seule
	 * condition vérifiée par Event.isSmartphoneTrackedGame) - jamais un
	 * réglage global.
	 */
	@Test
	public void testSmartphoneTrackedFullRepaymentDebitsJetonWeak()
	{
		mPlayer1.setStartingCardsJson("{}"); // simule une pioche de départ réelle (mode smartphone)

		final Event credit = new Event(mGame, EventType.NEW_CREDIT, mPlayer1);
		credit.setPrincipal(10);
		credit.setInterest(1);
		credit.applyEvent();
		assertEquals(10, mPlayer1.getJetonWeak(),
				"Un crédit accordé doit créditer jetonWeak du principal emprunté (jamais de l'intérêt, pas encore en circulation).");

		final Event repayment = new Event(mGame, EventType.REIMB_CREDIT, mPlayer1);
		repayment.setPrincipal(10);
		repayment.setInterest(1);
		repayment.applyEvent();

		assertEquals(0, mPlayer1.getCurDebt(), "Un remboursement complet doit ramener la dette à 0.");
		assertEquals(0, mPlayer1.getCurInterest(), "Un remboursement complet doit ramener l'intérêt dû à 0.");
		assertEquals(0, mPlayer1.getJetonWeak(),
				"Un remboursement complet (principal+intérêt) doit débiter EXACTEMENT ce même montant du solde "
						+ "physique de jetons - sinon le joueur garde ses jetons tout en effaçant sa dette.");
	}

	/**
	 * Même correctif que ci-dessus, cas d'un remboursement PARTIEL (l'autre
	 * branche de REIMB_CREDIT dans Event.applyEvent, qui ne remet pas
	 * jetonWeak à 0 mais doit tout de même le débiter du montant réellement
	 * remboursé).
	 */
	@Test
	public void testSmartphoneTrackedPartialRepaymentDebitsJetonWeakByExactAmount()
	{
		mPlayer1.setStartingCardsJson("{}");

		final Event credit = new Event(mGame, EventType.NEW_CREDIT, mPlayer1);
		credit.setPrincipal(10);
		credit.setInterest(2);
		credit.applyEvent();
		assertEquals(10, mPlayer1.getJetonWeak());

		final Event partialRepayment = new Event(mGame, EventType.REIMB_CREDIT, mPlayer1);
		partialRepayment.setPrincipal(4);
		partialRepayment.setInterest(1);
		partialRepayment.applyEvent();

		assertEquals(6, mPlayer1.getCurDebt(), "Un remboursement partiel ne réduit la dette que du principal remboursé.");
		assertEquals(1, mPlayer1.getCurInterest(), "Un remboursement partiel ne réduit l'intérêt dû que de l'intérêt remboursé.");
		assertEquals(5, mPlayer1.getJetonWeak(),
				"jetonWeak doit être débité EXACTEMENT du montant remboursé (principal+intérêt = 5), jamais plus, jamais moins.");
	}
}
