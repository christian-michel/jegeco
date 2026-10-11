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

	// États possibles de Request.status() - voir tryClaim()/markResolved() plus
	// bas pour le détail des transitions (pending -> processing -> approved/declined).
	private static final String STATUS_PENDING = "pending"; //$NON-NLS-1$
	private static final String STATUS_PROCESSING = "processing"; //$NON-NLS-1$
	private static final String STATUS_APPROVED = "approved"; //$NON-NLS-1$
	private static final String STATUS_DECLINED = "declined"; //$NON-NLS-1$

	/** Crée une nouvelle demande, toujours "pending" à la création. */
	int create(final int pGameId, final int pPlayerId, final String pPlayerName, final int pRequestedPrincipal)
	{
		evictOldResolved();
		final int id = mNextId.getAndIncrement();
		mRequests.put(id, new Request(id, pGameId, pPlayerId, pPlayerName, pRequestedPrincipal,
				System.currentTimeMillis(), STATUS_PENDING));
		return id;
	}

	/** Demandes "pending" d'une partie, plus récentes en premier - pour l'écran animateur. */
	java.util.List<Request> listPending(final int pGameId)
	{
		return mRequests.values().stream().filter(r -> (r.gameId() == pGameId) && STATUS_PENDING.equals(r.status()))
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

	/**
	 * BUG TROUVÉ ET CORRIGÉ (11/10/2026, seconde relecture indépendante du
	 * commit 220b6c2, confirmé par un vrai test de concurrence HTTP - deux
	 * appels POST .../approve envoyés en parallèle pour la MÊME demande) :
	 * avant ce correctif, GecoServer.java vérifiait le statut "pending" de la
	 * demande (via {@link #get}) PUIS, seulement après, appelait
	 * GameService.recordEvent (lui-même protégé par son propre verrou de
	 * partie, voir GameService.withGameLock) et enfin {@link #markResolved}
	 * - un contrôle-puis-écriture (TOCTOU) SANS AUCUN verrou entre les deux,
	 * exactement le même type de bug que celui déjà trouvé et corrigé le
	 * 27/09/2026 pour les échanges troc par QR code (voir CLAUDE.md, section
	 * "Pièges déjà rencontrés" - "+31073 unités monétaires créées de rien,
	 * mesuré en HTTP réel"). Reproduit ici à l'identique : deux requêtes
	 * POST /approve quasi simultanées pour la même demande passaient TOUTES
	 * LES DEUX le contrôle "pending" (aucune n'avait encore eu le temps de
	 * marquer la demande comme traitée), chacune appelait ensuite
	 * recordEvent("N", ...) - credit accordé DEUX FOIS pour une seule
	 * demande (mesuré : +20 au lieu de +10, pour un joueur dont le solde
	 * était à 0 avant le test). GameService.withGameLock protège bien contre
	 * une incohérence sur LA PARTIE pendant que recordEvent s'exécute, mais
	 * ne protège pas CreditRequestService, qui est un état séparé, en
	 * mémoire, propre à ce panneau.
	 * <p>
	 * Corrigé en rendant la transition "pending" → en cours de traitement
	 * ATOMIQUE via {@link java.util.concurrent.ConcurrentHashMap#computeIfPresent}
	 * (déjà le mécanisme utilisé ailleurs dans cette classe) : {@link #tryClaim}
	 * ci-dessous doit être appelé AVANT tout traitement, et renvoie false si
	 * un autre appel a déjà "gagné" la demande entre-temps - l'appelant doit
	 * alors refuser la requête (HTTP 400) sans jamais appeler recordEvent.
	 * <p>
	 * Tente de "réserver" atomiquement une demande encore "pending" pour la
	 * traiter. Renvoie {@code true} seulement pour l'appelant qui a
	 * réellement gagné la course - tout appel concurrent ultérieur (même
	 * demande, déjà "processing"/"approved"/"declined") renvoie
	 * {@code false} et ne doit RIEN exécuter d'autre. Appelé par GecoServer
	 * AVANT GameService.recordEvent, jamais après.
	 */
	boolean tryClaim(final int pRequestId)
	{
		// computeIfPresent() s'exécute de façon atomique sur UNE entrée du
		// ConcurrentHashMap - deux threads concurrents sur le MÊME id s'y
		// exécutent en série, jamais en parallèle, donc un seul des deux peut
		// lire "pending" et y substituer "processing" ; l'autre lit déjà
		// "processing" (ou un statut final) et repart bredouille. "claimed"
		// est un tableau d'un élément (effectivement final) pour pouvoir être
		// affecté depuis l'intérieur du lambda, computeIfPresent ne renvoyant
		// que la nouvelle valeur de l'entrée, pas un booléen de succès.
		final boolean[] claimed = { false };
		mRequests.computeIfPresent(pRequestId, (id, r) -> {
			if (!STATUS_PENDING.equals(r.status()))
				return r; // déjà pris par un autre appel, ou déjà traité - inchangé
			claimed[0] = true;
			return new Request(r.id(), r.gameId(), r.playerId(), r.playerName(), r.requestedPrincipal(),
					r.createdAtEpochMs(), STATUS_PROCESSING);
		});
		return claimed[0];
	}

	/**
	 * Remet une demande "processing" à "pending" (voir {@link #tryClaim}) -
	 * utilisé quand recordEvent échoue APRÈS la réservation (ex. joueur
	 * supprimé entre-temps) : la demande reste traitable plutôt que bloquée
	 * indéfiniment sur un statut intermédiaire que l'écran animateur ne
	 * reconnaît pas.
	 */
	void revertToPending(final int pRequestId)
	{
		mRequests.computeIfPresent(pRequestId, (id, r) -> STATUS_PROCESSING.equals(r.status())
				? new Request(r.id(), r.gameId(), r.playerId(), r.playerName(), r.requestedPrincipal(),
						r.createdAtEpochMs(), STATUS_PENDING)
				: r);
	}

	/**
	 * Marque une demande comme traitée (approuvée ou refusée) - appelé APRÈS
	 * {@link #tryClaim} a réussi (donc après que recordEvent a lui-même
	 * réussi, pour "approved"). Fonctionne aussi bien depuis "processing" que
	 * depuis "pending" (robuste si jamais appelé sans passer par tryClaim,
	 * mais GecoServer doit désormais toujours passer par tryClaim en
	 * premier).
	 */
	void markResolved(final int pRequestId, final boolean pApproved)
	{
		mRequests.computeIfPresent(pRequestId, (id, r) -> new Request(r.id(), r.gameId(), r.playerId(), r.playerName(),
				r.requestedPrincipal(), r.createdAtEpochMs(), pApproved ? STATUS_APPROVED : STATUS_DECLINED));
	}

	/** Nettoyage paresseux (pas de tâche de fond) : à chaque création, on purge les demandes déjà traitées depuis trop longtemps. */
	private void evictOldResolved()
	{
		final long now = System.currentTimeMillis();
		// "processing" compte comme "pas encore résolue" ici au même titre que
		// "pending" - ne jamais purger une demande dont le traitement est en
		// cours (fenêtre normalement très brève, le temps d'un recordEvent).
		mRequests.values().removeIf(r -> !STATUS_PENDING.equals(r.status()) && !STATUS_PROCESSING.equals(r.status())
				&& ((now - r.createdAtEpochMs()) > RESOLVED_RETENTION_MS));
	}
}
