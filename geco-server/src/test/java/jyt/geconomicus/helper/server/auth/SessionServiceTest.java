package jyt.geconomicus.helper.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Vérifie SessionService seul (pas de JPA impliqué, voir sa javadoc). */
class SessionServiceTest
{
	@Test
	void testCreateSessionThenResolveReturnsTheSameAnimatorId()
	{
		final SessionService service = new SessionService();
		final String token = service.createSession(42);

		assertEquals(42, service.resolveSession(token));
	}

	@Test
	void testResolveUnknownOrNullTokenReturnsNull()
	{
		final SessionService service = new SessionService();

		assertNull(service.resolveSession("jeton-inconnu"));
		assertNull(service.resolveSession(null));
	}

	@Test
	void testInvalidateSessionMakesTheTokenUnresolvableAfterwards()
	{
		final SessionService service = new SessionService();
		final String token = service.createSession(7);
		assertEquals(7, service.resolveSession(token));

		service.invalidateSession(token);

		assertNull(service.resolveSession(token));
	}

	@Test
	void testEachSessionGetsAUniqueToken()
	{
		final SessionService service = new SessionService();
		final String token1 = service.createSession(1);
		final String token2 = service.createSession(1);

		assertNotEquals(token1, token2, "deux connexions du même animateur doivent recevoir des jetons distincts"); //$NON-NLS-1$
	}
}
