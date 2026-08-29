package jyt.geconomicus.helper.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Étape 3, monnaie dette, mode smartphone : demandes de crédit initiées par
 * un JOUEUR depuis son téléphone, validées par l'ANIMATEUR - remonté par
 * l'utilisateur (28/08/2026) : "il faut que les joueurs qui souhaitent faire
 * un crédit se déplacent jusqu'à la banque ou à l'animateur pour contracter
 * un crédit... Lorsque l'animateur valide quelque chose, le profil du joueur
 * est instantanément mis à jour."
 * <p>
 * Différent du mécanisme de crédit existant (voir GameService.recordEvent,
 * type "N"/NEW_CREDIT) : celui-ci reste la SEULE façon d'enregistrer
 * réellement un crédit dans le moteur (aucun changement ici) - cette classe
 * n'ajoute qu'une couche de DEMANDE/NOTIFICATION par-dessus, à la demande
 * explicite d'un joueur, que l'animateur peut ensuite approuver (ce qui
 * appelle recordEvent normalement) ou refuser. Volontairement EN MÉMOIRE
 * (pas de table JPA), même raisonnement que {@link TradeOfferService} : une
 * demande en attente est une donnée de très courte durée de vie (le temps
 * que l'animateur la traite), la perdre lors d'un redémarrage serveur
 * (rarissime en pleine partie) n'a aucune conséquence grave - le joueur
 * referait simplement sa demande.
 */
final class CreditRequestService
{
	record Request(int id, int gameId, int playerId, String playerName, int requestedPrincipal, long createdAtEpochMs,
			String status) // "pending" / "approved" / "declined"
	{
	}

	private final AtomicInteger mNextId = new AtomicInteger(1);
	private final Map<Integer, Request> mRequests = new ConcurrentHashMap<>();

	// Durée de rétention d'une demande déjà TRAITÉE (approuvée/refusée) avant
	// nettoyage paresseux - le temps que le joueur voie le résultat sur son
	// téléphone (rafraîchi toutes les 5s, voir player-view.js), largement
	// suffisant. Les demandes "pending" ne sont, elles, jamais purgées par
	// cette limite (seul l'animateur les fait disparaître, en les traitant).
	private static final long RESOLVED_RETENTION_MS = 10 * 60 * 1000;

	/** Crée une nouvelle demande, toujours "pending" à la création. */
	int create(final int pGameId, final int pPlayerId, final String pPlayerName, final int pRequestedPrincipal)
	{
		evictOldResolved();
		final int id = mNextId.getAndIncrement();
		mRequests.put(id, new Request(id, pGameId, pPlayerId, pPlayerName, pRequestedPrincipal,
				System.currentTimeMillis(), "pending")); //$NON-NLS-1$
		return id;
	}

	/** Demandes "pending" d'une partie, plus récentes en premier - pour l'écran animateur. */
	java.util.List<Request> listPending(final int pGameId)
	{
		return mRequests.values().stream().filter(r -> (r.gameId() == pGameId) && "pending".equals(r.status())) //$NON-NLS-1$
				.sorted((a, b) -> Long.compare(b.createdAtEpochMs(), a.createdAtEpochMs())).toList();
	}

	/** Dernière demande d'UN joueur (n'importe quel statut) - pour que son téléphone affiche l'état. */
	Request findLatestForPlayer(final int pGameId, final int pPlayerId)
	{
		return mRequests.values().stream().filter(r -> (r.gameId() == pGameId) && (r.playerId() == pPlayerId))
				.max((a, b) -> Long.compare(a.createdAtEpochMs(), b.createdAtEpochMs())).orElse(null);
	}

	Request get(final int pRequestId)
	{
		return mRequests.get(pRequestId);
	}

	/** Marque une demande comme traitée (approuvée ou refusée) - ne peut être appelé qu'une fois par demande. */
	void markResolved(final int pRequestId, final boolean pApproved)
	{
		mRequests.computeIfPresent(pRequestId, (id, r) -> new Request(r.id(), r.gameId(), r.playerId(), r.playerName(),
				r.requestedPrincipal(), r.createdAtEpochMs(), pApproved ? "approved" : "declined")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Nettoyage paresseux (pas de tâche de fond) : à chaque création, on purge les demandes déjà traitées depuis trop longtemps. */
	private void evictOldResolved()
	{
		final long now = System.currentTimeMillis();
		mRequests.values().removeIf(r -> !"pending".equals(r.status()) //$NON-NLS-1$
				&& ((now - r.createdAtEpochMs()) > RESOLVED_RETENTION_MS));
	}
}
