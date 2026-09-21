package jyt.geconomicus.helper;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Un compte animateur du serveur - permet à un même serveur d'héberger
 * plusieurs animateurs indépendants, chacun avec son propre profil et ses
 * propres parties (voir {@link Game#getOwner()}).
 *
 * Ajouté le 21/09/2026 (demande utilisateur : "proposer une version serveur
 * capable de gérer le multi session avec plusieurs animateurs qui ont chacuns
 * leur profil et leurs parties"). Volontairement placé ici, dans geco-engine,
 * aux côtés de Game/Player plutôt que dans geco-server : même convention déjà
 * en place pour les autres champs propres au web (Player.accessToken,
 * Game.smartphoneCardPileJson...) - un seul schéma de base partagé entre web
 * et Swing (voir CLAUDE.md, "Base de données"). L'app Swing (geco-app) n'a
 * - pour l'instant - aucune notion de compte et continue de fonctionner
 * exactement comme avant, sans jamais lire ni écrire cette table : rien de ce
 * qui suit ne change son comportement.
 *
 * Toute la LOGIQUE (création de compte, vérification du mot de passe,
 * migration des parties orphelines...) vit côté geco-server, dans
 * jyt.geconomicus.helper.server.auth.AnimatorService - cette classe ne porte
 * que les données, comme Game/Player.
 */
@Entity
public class Animator implements Serializable
{
	@TableGenerator(
		name = "animatorGen",
		table = "ID_GEN",
		pkColumnName = "GEN_KEY",
		valueColumnName = "GEN_VALUE",
		pkColumnValue = "ANIMATOR_ID",
		allocationSize = 1
	)
	@XmlTransient
	@GeneratedValue(strategy = GenerationType.TABLE, generator = "animatorGen")
	@Id
	private Integer id;

	// Identifiant de connexion, unique - distinct du nom affiché ci-dessous
	// (modifiable librement, lui, sans casser les parties déjà créées qui
	// pointent vers ce compte par son id).
	@Column(unique = true, nullable = false)
	private String login;

	// Nom affiché dans l'interface animateur (tableau de bord, liste des
	// parties...) - distinct du login, purement cosmétique.
	private String displayName;

	// Hash du mot de passe - JAMAIS le mot de passe en clair. Voir
	// jyt.geconomicus.helper.server.auth.PasswordHasher (PBKDF2WithHmacSHA256,
	// natif au JDK - pas de nouvelle dépendance) pour le format exact du
	// contenu de ce champ ("pbkdf2:<itérations>:<sel en Base64>:<hash en
	// Base64>").
	@Column(nullable = false)
	private String passwordHash;

	/**
	 * ADMIN : gère les réglages partagés par tout le serveur (catalogues de
	 * cartes/visuels/avatars, plugins activés, comptes animateurs) - voir la
	 * feuille de route multi-session pour le détail complet du découpage des
	 * droits, encore à construire au moment où ce champ est ajouté (seul le
	 * modèle de données est posé dans cette première étape).
	 * ANIMATEUR : peut créer/gérer ses propres parties, sans accès à ces
	 * réglages partagés.
	 */
	public enum Role
	{
		ADMIN,
		ANIMATEUR
	}

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Temporal(TemporalType.TIMESTAMP)
	private Date createdAt;

	public Animator()
	{
		// Constructeur vide requis par JPA.
	}

	public Animator(final String pLogin, final String pDisplayName, final String pPasswordHash, final Role pRole)
	{
		login = pLogin;
		displayName = pDisplayName;
		passwordHash = pPasswordHash;
		role = pRole;
		createdAt = new Date();
	}

	@XmlTransient
	public Integer getId()
	{
		return id;
	}

	public String getLogin()
	{
		return login;
	}

	public void setLogin(final String pLogin)
	{
		login = pLogin;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public void setDisplayName(final String pDisplayName)
	{
		displayName = pDisplayName;
	}

	public String getPasswordHash()
	{
		return passwordHash;
	}

	public void setPasswordHash(final String pPasswordHash)
	{
		passwordHash = pPasswordHash;
	}

	public Role getRole()
	{
		return role;
	}

	public void setRole(final Role pRole)
	{
		role = pRole;
	}

	public Date getCreatedAt()
	{
		return createdAt;
	}
}
