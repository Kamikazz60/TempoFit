# TempoFit — instructions du projet
(Règles générales : skill developpement-pro.)

## Présentation
Appli Android (Kotlin, Compose, Media3) : lecteur MP3 + timer d'entraînement par intervalles. Livrable : APK debug.

## Environnement (vérifié le 03/11/2026)
- PC Windows 10, projet : `C:\Users\Surface\Desktop\Programme IA\MusicTraining`
- Build : `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug` (le Java 26 système est trop récent)
- SDK : `%LOCALAPPDATA%\Android\Sdk` (platforms 35, build-tools 35)
- Aucun téléphone en USB ; transfert de l'APK par câble ou serveur HTTP temporaire (pare-feu Windows à ouvrir) ; Drive refuse les APK.
- GitHub : `gh` connecté (compte Kamikazz60).

## Architecture
`MainActivity.kt` (UI Compose, préférences `music_training_v5`), `TrainingService.kt` (timer, sons SoundPool, ducking via focus audio, `SIGNAL_GAIN`), `MusicService.kt` (ExoPlayer/MediaSession). Sons dans `assets/*.wav` (générés : ondes carrées 2–2,8 kHz). Logo : vecteurs dans `res/drawable`.

## Signature release
Clé hors projet : `%USERPROFILE%\.tempofit-signing\` (`tempofit-release.jks` + `keystore.properties`, jamais dans git). **À sauvegarder par l'utilisateur : sans elle, plus de mise à jour possible.** Build : `.\gradlew.bat assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` (le lint vital plante en interne). Émulateur : AVD `Medium_Phone_API_35` (lent au premier démarrage).

## Particularités
- Bips en USAGE_MEDIA (sinon non diffusés sur Bluetooth).
- Ne pas désinstaller l'ancienne version avant mise à jour (données internes) ; incrémenter versionCode à chaque APK.
