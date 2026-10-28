@echo off
rem Nyx — lanceur desktop (v0.3.3)
rem Le repertoire de donnees vit dans %USERPROFILE%\.nyx-messenger
setlocal
set DIR=%~dp0
if not exist "%DIR%runtime\bin\java.exe" (
  echo [Nyx] runtime Java introuvable — reinstallez l'application.
  pause
  exit /b 1
)
if not exist "%DIR%tor\tor.exe" (
  echo [Nyx] tor.exe introuvable — reinstallez l'application.
  pause
  exit /b 1
)
rem v0.3.10 — camera du PC en visio (ffmpeg embarque). ABSENT : repli
rem honnete sur le partage d'ecran (l'app reste pleinement fonctionnelle).
if not exist "%DIR%ffmpeg\ffmpeg.exe" (
  echo [Nyx] attention : ffmpeg.exe introuvable — la visio utilisera le
  echo partage d'ecran au lieu de la camera du PC. Reinstallez pour la camera.
)
cd /d "%DIR%"
rem v0.3.2 — SANS argument : javaw (subsystem GUI) → AUCUNE fenetre console
rem qui traine derriere l'application ; le journal applicatif reste
rem consultable dans l'application (Reglages > Diagnostic > Exporter le
rem journal — meme format que l'export Android).
rem AVEC argument (ex. --selftest) : java.exe console → sortie visible,
rem code de retour exploitable (pipeline de build / diagnostics terrain).
if "%~1"=="" (
  if exist "%DIR%runtime\bin\javaw.exe" (
    start "" "%DIR%runtime\bin\javaw.exe" -Xmx1g -jar "%DIR%nyx-messenger.jar"
  ) else (
    "%DIR%runtime\bin\java.exe" -Xmx1g -jar "%DIR%nyx-messenger.jar"
  )
) else (
  "%DIR%runtime\bin\java.exe" -Xmx1g -jar "%DIR%nyx-messenger.jar" %*
)
endlocal
