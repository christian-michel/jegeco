@echo off
REM run.bat - Lance Geconomicus Helper.
REM
REM Par defaut, lance la NOUVELLE interface web (module geco-server) et ouvre
REM automatiquement votre navigateur. Double-cliquez simplement sur ce fichier :
REM aucune commande a taper.
REM
REM ============================================================
REM  JDK/MAVEN PORTABLES (sans droits admin, sans toucher a votre
REM  installation Java existante) - remonte par un utilisateur (poste de
REM  travail professionnel : pas de droits d'installation, une autre
REM  version de Java deja presente, a ne surtout pas perturber) :
REM
REM  Si un dossier "jdk-portable" existe A COTE de ce script (meme dossier),
REM  son java.exe est utilise EXCLUSIVEMENT - jamais le Java systeme, jamais
REM  de modification du PATH ni du registre. Meme principe pour un dossier
REM  "maven-portable" a cote de ce script.
REM
REM  Pour l'obtenir : telechargez l'archive .zip (PAS l'installeur .msi) de
REM  Temurin JDK 21 sur https://adoptium.net/ (bouton "Other platforms and
REM  versions" si besoin), decompressez-la, renommez le dossier obtenu en
REM  "jdk-portable" et placez-le a cote de ce script. Decompresser un zip ne
REM  demande aucun droit admin. Meme principe pour Maven (archive .zip sur
REM  https://maven.apache.org/download.cgi), dossier a renommer
REM  "maven-portable".
REM
REM  Si vous avez deja un fichier geco-server\target\geco-server.jar deja
REM  compile (ex. construit sur un autre poste puis copie ici via
REM  git/Cloud/e-mail), Maven n'est meme pas necessaire : seul un JDK/JRE
REM  portable suffit pour LANCER le jeu (voir plus bas, l'etape de
REM  compilation est entierement sautee si le jar existe deja).
REM ============================================================
REM
REM Options (combinables) :
REM   run.bat --classic    lance l'ancienne interface Swing (module geco-app) a la place
REM   run.bat --rebuild    force une recompilation meme si le jar existe deja

setlocal enabledelayedexpansion

REM On se place a la racine du projet (dossier contenant ce script), pour que ca
REM fonctionne quel que soit l'endroit d'ou il est lance (double-clic, raccourci...).
cd /d "%~dp0"

set "JAR_PATH=geco-server\target\geco-server.jar"
set "MODULE=geco-server"
set "INTERFACE_LABEL=l'interface web (nouvelle version)"
set "FORCE_REBUILD=0"

for %%a in (%*) do (
	if /i "%%a"=="--classic" (
		set "JAR_PATH=geco-app\target\gecohelper.jar"
		set "MODULE=geco-app"
		set "INTERFACE_LABEL=l'interface Swing (ancienne version)"
	)
	if /i "%%a"=="--swing" (
		set "JAR_PATH=geco-app\target\gecohelper.jar"
		set "MODULE=geco-app"
		set "INTERFACE_LABEL=l'interface Swing (ancienne version)"
	)
	if /i "%%a"=="--rebuild" set "FORCE_REBUILD=1"
)

REM --- 1. JDK portable ? (voir l'explication en tete de fichier) ---
REM Priorite absolue : si present, utilise SEULEMENT celui-ci pour tout le
REM reste du script (java ET javac/Maven, via JAVA_HOME positionne
REM UNIQUEMENT pour ce script - "setlocal" en tete garantit qu'il disparait
REM avec la fenetre, jamais persiste sur le systeme).
set "JAVA_EXE=java"
if exist "%~dp0jdk-portable\bin\java.exe" (
	echo ==^> JDK portable detecte ^(jdk-portable\^) - utilise en priorite, votre Java systeme n'est pas touche.
	set "JAVA_HOME=%~dp0jdk-portable"
	set "JAVA_EXE=%~dp0jdk-portable\bin\java.exe"
	set "PATH=%~dp0jdk-portable\bin;%PATH%"
)

REM --- 2. Verification de Java 21 (systeme, seulement si pas de portable) ---
echo ==^> Verification de Java 21...
set "JAVA_OK=0"
if not "%JAVA_EXE%"=="java" (
	set "JAVA_OK=1"
	echo ==^> Java detecte : "!JAVA_EXE!" ^(portable^)
) else (
	where java >nul 2>&1
	if %errorlevel%==0 (
		for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
			set "JVER=%%~v"
			set "JVER=!JVER:"=!"
			for /f "delims=. tokens=1" %%m in ("!JVER!") do set "JMAJOR=%%m"
			if !JMAJOR! GEQ 21 set "JAVA_OK=1"
		)
	)
)

if "%JAVA_OK%"=="0" (
	echo.
	echo Java 21 n'est pas detecte, et aucun droit d'installation n'est suppose ^(voir
	echo l'explication en tete de ce fichier^). Deux options :
	echo.
	echo   1. RECOMMANDE, sans droits admin : telechargez l'archive .zip ^(pas le .msi^)
	echo      de Temurin JDK 21 sur https://adoptium.net/, decompressez-la, renommez
	echo      le dossier obtenu en "jdk-portable" et placez-le a cote de ce script
	echo      ^(meme dossier que run.bat^). Relancez ensuite run.bat.
	echo.
	echo   2. Si vous avez les droits d'installation : winget install --id EclipseAdoptium.Temurin.21.JDK
	echo.
	pause
	exit /b 1
)

REM --- 3. La compilation est-elle seulement necessaire ? ---
REM Remonte par un utilisateur : si le jar est deja present ^(ex. compile sur
REM un autre poste puis copie ici^), Maven n'est meme pas requis - on saute
REM entierement sa verification, qui pourrait sinon echouer ^(ou tenter une
REM installation^) inutilement sur un poste sans droits.
if not exist "%JAR_PATH%" set "FORCE_REBUILD=1"

if "%FORCE_REBUILD%"=="1" (
	REM --- 3bis. Maven portable ? (meme principe que le JDK ci-dessus) ---
	set "MVN_CMD=mvn"
	if exist "%~dp0maven-portable\bin\mvn.cmd" (
		echo ==^> Maven portable detecte ^(maven-portable\^) - utilise en priorite.
		set "MVN_CMD=%~dp0maven-portable\bin\mvn.cmd"
	) else (
		where mvn >nul 2>&1
		if errorlevel 1 (
			echo.
			echo Maven n'est pas detecte, et le jar n'existe pas encore ^(compilation
			echo necessaire^). Deux options ^(voir aussi l'explication en tete de fichier^) :
			echo.
			echo   1. RECOMMANDE, sans droits admin : telechargez l'archive .zip de Maven
			echo      sur https://maven.apache.org/download.cgi, decompressez-la, renommez
			echo      le dossier obtenu en "maven-portable" et placez-le a cote de ce script.
			echo.
			echo   2. Recuperez un fichier geco-server\target\geco-server.jar deja compile
			echo      ^(construit sur un autre poste ou vous avez deja Maven, puis transmis
			echo      ici via git/Cloud/e-mail - pas besoin de cle USB^) et placez-le au bon
			echo      endroit dans ce dossier de projet : Maven ne sera alors plus necessaire
			echo      du tout pour simplement LANCER le jeu.
			echo.
			echo   3. Si vous avez les droits d'installation : winget install --id Apache.Maven
			echo.
			pause
			exit /b 1
		)
	)

	echo ==^> Compilation de %INTERFACE_LABEL% ^(peut prendre quelques minutes la premiere fois, connexion internet requise^)...
	call "!MVN_CMD!" -q -pl %MODULE% -am clean package
	if errorlevel 1 (
		echo.
		echo ERREUR de compilation. Voir le detail ci-dessus.
		pause
		exit /b 1
	)
	echo ==^> Compilation terminee.
) else (
	echo ==^> Le projet est deja compile ^(%INTERFACE_LABEL%^).
	echo     Pour recompiler apres une mise a jour du code : run.bat --rebuild
)

echo ==^> Lancement de %INTERFACE_LABEL%...

if "%MODULE%"=="geco-server" (
	echo ==^> Ouverture automatique de http://localhost:7000 dans votre navigateur...
	start "" cmd /c "timeout /t 2 >nul && start http://localhost:7000"
	"!JAVA_EXE!" -jar "%JAR_PATH%"
) else (
	REM L'option ci-dessous corrige un bug connu sur certains systemes Linux avec
	REM Swing ; sans effet sur Windows, ajoutee par coherence avec run.sh.
	"!JAVA_EXE!" -Djavax.accessibility.assistive_technologies= -jar "%JAR_PATH%"
)

pause
