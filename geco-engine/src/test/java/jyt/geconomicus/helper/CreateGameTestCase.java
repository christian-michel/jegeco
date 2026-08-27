package jyt.geconomicus.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Event.EventType;

public class CreateGameTestCase
{
	@Test
	public void testCreateGame()
	{
		// "geco-test" : unité de persistance dédiée aux tests (base H2 en mémoire,
		// isolée), définie dans src/test/resources/META-INF/persistence.xml - à ne
		// pas confondre avec "geco", l'unité de production qui pointe vers ~/geco.h2.
		// Utiliser "geco" ici ferait planter ce test si l'application est déjà
		// lancée (fichier H2 verrouillé), et polluerait les vraies données de
		// l'utilisateur avec une fausse partie à chaque exécution des tests.
		// Remonté par un utilisateur (point 2, "finaliser l'étape 2" - tests
		// automatisés) : nom de base généré via TestPersistence plutôt que le nom
		// fixe du fichier XML, pour ne jamais partager d'état avec les autres
		// classes de test exécutées dans la même JVM par Maven Surefire.
		EntityManagerFactory factory = TestPersistence.newIsolatedFactory();
		EntityManager em = factory.createEntityManager();
		em.getTransaction().begin();
		Game game = new Game(Game.MONEY_DEBT, 10, "toto", "toto@titi.com", "", "today", "Here", 1);
		Player player1 = new Player(game, "Player1");
		Player player2 = new Player(game, "Player2");
		Event event1 = new Event(game, EventType.JOIN, player1);
		event1.applyEvent();
		Event event2 = new Event(game, EventType.JOIN, player2);
		event2.applyEvent();
		Event event3 = new Event(game, EventType.TURN, null);
		event3.applyEvent();
		Event event4 = new Event(game, EventType.NEW_CREDIT, player1);
		event4.setInterest(1);
		event4.setPrincipal(3);
		event4.applyEvent();
		em.persist(game);
		em.getTransaction().commit();

		// Remonté par un utilisateur : ce test n'avait jusqu'ici aucune assertion -
		// il vérifiait seulement que le code s'exécutait sans lever d'exception,
		// pas que le résultat était correct. Quelques vérifications minimales,
		// couvrant le cycle complet joindre -> tour -> crédit -> persistance.
		assertTrue(player1.isActive(), "Un joueur ayant rejoint la partie doit être actif.");
		assertEquals(1, game.getTurnNumber(), "Un seul événement TOUR doit faire passer la partie au tour 1.");
		assertEquals(3, player1.getCurDebt(), "Le principal emprunté doit apparaître comme dette du joueur.");
		assertEquals(1, player1.getCurInterest(), "L'intérêt du crédit doit apparaître comme dû par le joueur.");
		assertNotNull(game.getId(), "La partie doit avoir reçu un identifiant après la persistance.");

		em.close();
		factory.close();
	}
}
