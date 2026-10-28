// Nyx desktop — installateur Windows 1-clic (miroir de l'installateur
// nyx-admin : payload zip EMBARQUÉ, SHA-256 compilé en dur vérifié à
// l'extraction, anti Zip-Slip, contrôles des fichiers essentiels,
// raccourcis Bureau + Menu Démarrer, désinstalleur préservant les données).
package main

import (
        "archive/zip"
        "crypto/sha256"
        "embed"
        "encoding/hex"
        "flag"
        "fmt"
        "io"
        "os"
        "os/exec"
        "path/filepath"
        "strings"
)

//go:embed payload.zip
var embedded embed.FS

// SHA de référence — injecté par ldflags au build :
//
//      -ldflags "-X main.payloadSHA=<sha256 hex du zip>"
var payloadSHA = "INJECTED_AT_BUILD"

// Version affichée (injectable : -X main.version=…).
var version = "0.3.3"

const appDirName = "NyxMessenger"

func main() {
        silent := flag.Bool("silent", false, "installation silencieuse (aucune question)")
        dirFlag := flag.String("dir", "", "répertoire d'installation (défaut : %LOCALAPPDATA%\\NyxMessenger)")
        flag.Parse()

        fmt.Printf("[nyx] installateur Nyx Desktop v%s\n", version)

        // 1. Lecture du payload embarqué + vérification d'intégrité.
        data, err := embedded.ReadFile("payload.zip")
        if err != nil {
                fatal("payload embarqué illisible : %v", err)
        }
        sum := sha256.Sum256(data)
        got := hex.EncodeToString(sum[:])
        if payloadSHA != "INJECTED_AT_BUILD" && got != payloadSHA {
                fatal("intégrité du payload INVALIDE (attendu %s, obtenu %s) — l'installateur est altéré", payloadSHA, got)
        }
        fmt.Printf("[nyx] payload : %d octets — SHA-256 vérifié (%.12s…)\n", len(data), got)

        // 2. Destination.
        dest := *dirFlag
        if dest == "" {
                localAppData := os.Getenv("LOCALAPPDATA")
                if localAppData == "" {
                        home, _ := os.UserHomeDir()
                        localAppData = filepath.Join(home, "AppData", "Local")
                }
                dest = filepath.Join(localAppData, appDirName)
        }
        if _, err := os.Stat(dest); err == nil {
                fmt.Printf("[nyx] le répertoire %s existe déjà — mise à jour par-dessus (données préservées)\n", dest)
        }

        // 3. Extraction anti Zip-Slip + fichiers essentiels.
        if err := extractZip(data, dest); err != nil {
                fatal("extraction impossible : %v", err)
        }
        for _, essential := range []string{
                filepath.Join("nyx-messenger", "nyx-messenger.jar"),
                filepath.Join("nyx-messenger", "nyx-messenger.bat"),
                filepath.Join("nyx-messenger", "runtime", "bin", "java.exe"),
                filepath.Join("nyx-messenger", "runtime", "bin", "libgcc_s_seh-1.dll"),
                filepath.Join("nyx-messenger", "runtime", "bin", "libwinpthread-1.dll"),
                filepath.Join("nyx-messenger", "tor", "tor.exe"),
                // v0.3.3 — geoip de l'expert bundle (torrc les référence ; un tor
                // privé de geoip bootstrap mais avec des avertissements bruités).
                filepath.Join("nyx-messenger", "tor", "geoip"),
                filepath.Join("nyx-messenger", "tor", "geoip6"),
                // v0.3.10 — CAMÉRA DU PC PAR DÉFAUT en visio (demande opérateur
                // session 80 : « le pc fait un partage d'écran plutot que
                // d'envoyer le flux caméra. Je veux par défaut le flux caméra mais
                // la possibilité de partager l'écran ») : ffmpeg embarqué capture
                // la webcam par dshow. Son absence = repli silencieux sur l'écran
                // = exactement ce dont l'opérateur s'est plaint : INSTALLATION
                // BLOQUÉE (le build vérifie déjà sa présence dans le zip).
                filepath.Join("nyx-messenger", "ffmpeg", "ffmpeg.exe"),
        } {
                if _, err := os.Stat(filepath.Join(dest, essential)); err != nil {
                        fatal("fichier essentiel absent après extraction : %s", essential)
                }
        }
        fmt.Printf("[nyx] extraction : OK (fichiers essentiels vérifiés)\n")

        // 4. Désinstalleur (les données %%USERPROFILE%%\\.nyx-messenger sont PRÉSERVÉES).
        uninst := filepath.Join(dest, "desinstaller.bat")
        if err := os.WriteFile(uninst, []byte(desinstallerBat()), 0644); err != nil {
                fmt.Printf("[nyx] (avertissement) désinstalleur non écrit : %v\n", err)
        }

        // 5. Raccourcis (PowerShell). v0.3.2 — correctif retour terrain
        // « pas de raccourci sur le bureau » : le dossier est résolu PAR
        // L'API SHELL ([Environment]::GetFolderPath → SHGetKnownFolderPath)
        // et non plus par les variables d'environnement, qui mentent :
        //   - %OneDrive% désigne la RACINE du dossier synchronisé, pas le
        //     Bureau (redirection « Known Folder Move » → %OneDrive%\Desktop) ;
        //   - %USERPROFILE%\Desktop n'existe pas sur Windows localisé
        //     (fr-FR : le dossier physique s'appelle « Bureau »).
        exeDir := filepath.Join(dest, "nyx-messenger")
        createShortcut(exeDir, "desktop")
        createShortcut(exeDir, "startmenu")

        fmt.Printf("[nyx] installation TERMINÉE : %s\n", exeDir)
        fmt.Printf("[nyx] lancez : %s\n", filepath.Join(exeDir, "nyx-messenger.bat"))

        // 6. Lancement proposé (sauf -silent).
        if !*silent {
                var answer string
                fmt.Print("[nyx] lancer Nyx maintenant ? [O/n] ")
                if _, err := fmt.Scanln(&answer); err == nil {
                        ans := strings.ToLower(strings.TrimSpace(answer))
                        if ans == "" || ans == "o" || ans == "oui" || ans == "y" {
                                launch(filepath.Join(exeDir, "nyx-messenger.bat"))
                        }
                }
        }
}

func extractZip(data []byte, dest string) error {
        zr, err := zip.NewReader(strings.NewReader(string(data)), int64(len(data)))
        if err != nil {
                return err
        }
        for _, f := range zr.File {
                name := filepath.Clean(f.Name)
                if strings.Contains(name, "..") {
                        return fmt.Errorf("entrée suspecte (Zip-Slip) : %s", f.Name)
                }
                target := filepath.Join(dest, name)
                if f.FileInfo().IsDir() {
                        if err := os.MkdirAll(target, 0755); err != nil {
                                return err
                        }
                        continue
                }
                if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
                        return err
                }
                rc, err := f.Open()
                if err != nil {
                        return err
                }
                out, err := os.Create(target)
                if err != nil {
                        rc.Close()
                        return err
                }
                if _, err := io.Copy(out, rc); err != nil {
                        out.Close()
                        rc.Close()
                        return err
                }
                out.Close()
                rc.Close()
        }
        return nil
}

func createShortcut(exeDir, where string) {
        bat := filepath.Join(exeDir, "nyx-messenger.bat")
        ico := filepath.Join(exeDir, "nyx.ico")
        folder, label := "Desktop", "desktop"
        if where == "startmenu" {
                folder, label = "Programs", "startmenu"
        }
        // Icône réelle si livrée (sinon dégradation propre : icône du lanceur).
        iconPart := ""
        if _, err := os.Stat(ico); err == nil {
                iconPart = "$s.IconLocation='" + psQuote(ico) + "';"
        }
        // Le chemin du .lnk est résolu DANS PowerShell par l'API shell — il
        // couvre redirection OneDrive, dossiers localisés et déplacements
        // personnalisés d'un seul coup. Codes de sortie : 2 = dossier
        // introuvable, 3 = .lnk absent après Save, 0 = créé (chemin en sortie).
        ps := "$dir=[Environment]::GetFolderPath('" + folder + "');" +
                "if(-not $dir){exit 2};" +
                "$lnk=Join-Path $dir 'Nyx Messagerie.lnk';" +
                "$s=(New-Object -COM WScript.Shell).CreateShortcut($lnk);" +
                "$s.TargetPath='" + psQuote(bat) + "';" +
                "$s.WorkingDirectory='" + psQuote(exeDir) + "';" +
                iconPart +
                "$s.Description='Nyx - messagerie anonyme sur Tor';" +
                "$s.Save();" +
                "if(Test-Path -LiteralPath $lnk){Write-Output $lnk; exit 0}else{exit 3}"
        cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
        if out, err := cmd.Output(); err != nil {
                fmt.Printf("[nyx] (avertissement) raccourci %s non créé : %v\n", label, err)
        } else {
                fmt.Printf("[nyx] raccourci %s créé : %s\n", label, strings.TrimSpace(string(out)))
        }
}

/** Échappe une valeur pour une chaîne simple-quote PowerShell ('' par '). */
func psQuote(s string) string {
        return strings.ReplaceAll(s, "'", "''")
}

func launch(bat string) {
        // Sur Linux (smoke test) ce n'est qu'un affichage ; sur Windows : start.
        if isWindows() {
                exec.Command("cmd", "/C", "start", "", bat).Start()
        } else {
                fmt.Printf("[nyx] (smoke test) lancerait : %s\n", bat)
        }
}

func isWindows() bool {
        return strings.Contains(strings.ToLower(os.Getenv("OS")), "windows") ||
                strings.Contains(strings.ToLower(os.Getenv("GOOS")), "windows")
}

func desinstallerBat() string {
        return `@echo off
rem Nyx — desinstalleur : supprime l'APPLICATION, PRESERVE les donnees
rem (%USERPROFILE%\.nyx-messenger : identite, conversations, fichiers).
setlocal
set APPDIR=%~dp0
echo Suppression de %APPDIR% ...
timeout /t 3 /nobreak >nul
cd /d %USERPROFILE%
rmdir /s /q "%APPDIR%"
echo Desinstalle. Vos donnees restent dans %%USERPROFILE%%\.nyx-messenger
echo (supprimez ce dossier pour un effacement TOTAL du compte local).
pause
`
}

func fatal(format string, args ...interface{}) {
        fmt.Printf("[nyx] ERREUR : "+format+"\n", args...)
        os.Exit(1)
}
