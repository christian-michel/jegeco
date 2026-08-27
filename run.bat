@echo off
REM run.bat - Lance Geconomicus Helper.
REM
REM Par defaut, lance la NOUVELLE interface web (module geco-server) et ouvre
REM automatiquement votre navigateur. Verifie automatiquement que Java 21+ et Maven
REM sont installes (et les installe si besoin via winget), compile le projet s'il ne
REM l'est pas deja, puis lance le jeu. Double-cliquez simplement sur ce fichier :
REM aucune commande a taper.
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

echo ==^> Verification de Java 21...
set "JAVA_OK=0"
where java >nul 2>&1
if %errorlevel%==0 (
	for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
		set "JVER=%%~v"
		set "JVER=!JVER:"=!"
		for /f "delims=. tokens=1" %%m in ("!JVER!") do set "JMAJOR=%%m"
		if !JMAJOR! GEQ 21 set "JAVA_OK=1"
	)
)

if "%JAVA_OK%"=="0" (
	echo ==^> Java 21 non detecte. Installation via winget...
	winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements
	if errorlevel 1 (
		echo.
		echo ERREUR : l'installation automatique de Java a echoue.
		echo Installez-le manuellement depuis https://adoptium.net/ ^(voir docs\02-installation.md^),
		echo puis relancez ce script.
		pause
		exit /b 1
	)
	echo ==^> Java installe. Une nouvelle fenetre va s'ouvrir pour prendre en compte
	echo     la mise a jour du systeme : relancez run.bat une fois dans cette nouvelle fenetre.
	pause
	exit /b 0
)
echo ==^> Java detecte.

echo ==^> Verification de Maven...
where mvn >nul 2>&1
if errorlevel 1 (
	echo ==^> Maven non detecte. Installation via winget...
	winget install --id Apache.Maven -e --accept-source-agreements --accept-package-agreements
	if errorlevel 1 (
		echo.
		echo ERREUR : l'installation automatique de Maven a echoue.
		echo Installez-le manuellement ^(voir docs\02-installation.md^), puis relancez ce script.
		pause
		exit /b 1
	)
	echo ==^> Maven installe. Relancez run.bat une fois dans une nouvelle fenetre
	echo     pour que la mise a jour du systeme soit prise en compte.
	pause
	exit /b 0
)
echo ==^> Maven detecte.

REM On ne construit que le module choisi et sa dependance geco-engine (-pl ... -am) :
REM c'est le strict necessaire pour lancer le jeu, ca evite de telecharger inutilement
REM les dependances de l'autre interface si vous n'en avez pas besoin.
if not exist "%JAR_PATH%" set "FORCE_REBUILD=1"

if "%FORCE_REBUILD%"=="1" (
	echo ==^> Compilation de %INTERFACE_LABEL% ^(peut prendre quelques minutes la premiere fois, connexion internet requise^)...
	call mvn -q -pl %MODULE% -am clean package
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
	java -jar "%JAR_PATH%"
) else (
	REM L'option ci-dessous corrige un bug connu sur certains systemes Linux avec
	REM Swing ; sans effet sur Windows, ajoutee par coherence avec run.sh.
	java -Djavax.accessibility.assistive_technologies= -jar "%JAR_PATH%"
)

pause
