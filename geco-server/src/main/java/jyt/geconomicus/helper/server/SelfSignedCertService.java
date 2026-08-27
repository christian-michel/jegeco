package jyt.geconomicus.helper.server;

import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Étape 3 (achat de cartes par QR/caméra, §5.1 du cahier des charges) : génère
 * un certificat HTTPS auto-signé si aucun n'existe déjà sur le disque de
 * l'installation, pour que {@code getUserMedia} (accès caméra, exigé par les
 * navigateurs en contexte sécurisé uniquement) fonctionne sur le réseau
 * local/partage de connexion, pas seulement sur {@code localhost} - voir
 * {@code docs/05-etape3-connectivite.md}, qui prévoyait déjà ce besoin.
 * <p>
 * Remonté par un utilisateur le 27/08/2026 : le certificat doit être généré
 * automatiquement (pas de manipulation OpenSSL demandée à l'animateur). Choix
 * de Bouncy Castle plutôt que d'exiger `openssl` installé sur la machine
 * (Windows/macOS/Linux) : une seule dépendance Java portable, cohérente avec
 * l'esprit "aucune installation externe" déjà en place pour le reste de
 * l'application.
 * <p>
 * ⚠️ Un certificat auto-signé n'est reconnu par AUCUNE autorité de
 * certification : chaque téléphone affichera un avertissement de sécurité la
 * première fois qu'il visite l'adresse HTTPS (normal, à accepter manuellement
 * - "Continuer quand même"/"Avancé" selon le navigateur). Ça reste néanmoins
 * suffisant pour que le navigateur classe la page en "contexte sécurisé" et
 * autorise l'accès caméra, ce qui est le seul but recherché ici - ce n'est
 * PAS une protection contre l'interception du trafic (inutile dans ce
 * contexte : un jeu pédagogique sur un réseau local de confiance, pas un
 * service exposé sur internet).
 */
final class SelfSignedCertService
{
	static
	{
		Security.addProvider(new BouncyCastleProvider());
	}

	private SelfSignedCertService()
	{
		// Classe utilitaire, jamais instanciée.
	}

	/**
	 * Génère le certificat + clé privée (fichiers PEM) si absents, couvrant
	 * "localhost"/"127.0.0.1" ainsi que toutes les adresses IP locales
	 * fournies (voir {@link NetworkUtils#listLocalAddresses()}) comme noms
	 * alternatifs (SAN) - sans ça, le navigateur refuserait le certificat dès
	 * qu'on y accède via l'IP du partage de connexion plutôt que "localhost".
	 * Ne fait rien si les deux fichiers existent déjà (générés lors d'un
	 * lancement précédent) : un certificat valide 10 ans n'a pas besoin
	 * d'être régénéré à chaque démarrage.
	 */
	static void ensureCertificate(final Path pCertPath, final Path pKeyPath, final List<String> pLocalIps)
			throws Exception
	{
		if (Files.isRegularFile(pCertPath) && Files.isRegularFile(pKeyPath))
			return;
		if (pCertPath.getParent() != null)
			Files.createDirectories(pCertPath.getParent());

		final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA"); //$NON-NLS-1$
		kpg.initialize(2048);
		final KeyPair kp = kpg.generateKeyPair();

		final X500Name subject = new X500Name("CN=Geconomicus (certificat local auto-signe)"); //$NON-NLS-1$
		final BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
		// Marge d'un jour dans le passé : les horloges des téléphones/PC ne sont
		// pas toujours parfaitement synchronisées, éviter un rejet "pas encore
		// valide" pour quelques minutes de décalage.
		final Date notBefore = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
		final Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000); // 10 ans

		final JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial, notBefore,
				notAfter, subject, kp.getPublic());

		final List<GeneralName> sans = new ArrayList<>();
		sans.add(new GeneralName(GeneralName.dNSName, "localhost")); //$NON-NLS-1$
		sans.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1")); //$NON-NLS-1$
		for (final String ip : pLocalIps)
			if ((ip != null) && !ip.isBlank())
				sans.add(new GeneralName(GeneralName.iPAddress, ip));

		builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray(new GeneralName[0])));
		builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
		builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

		final ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(kp.getPrivate()); //$NON-NLS-1$
		final X509CertificateHolder holder = builder.build(signer);
		final X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);

		try (JcaPEMWriter w = new JcaPEMWriter(new FileWriter(pCertPath.toFile())))
		{
			w.writeObject(cert);
		}
		try (JcaPEMWriter w = new JcaPEMWriter(new FileWriter(pKeyPath.toFile())))
		{
			w.writeObject(kp.getPrivate());
		}
	}
}
