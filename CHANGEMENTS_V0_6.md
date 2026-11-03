# v0.6 — Correctifs ciblés

- Réglages : clés LazyColumn distinctes pour les dossiers et les programmes (collision « renfo » corrigée).
- Changement de morceau : retour automatique au tempo 100 %, y compris via les commandes casque et la lecture automatique.
- Cadran BPM : temporisation de 350 ms avant application au moteur audio pour éviter une reconfiguration à chaque mouvement du doigt. La hauteur reste fixée à 1.0.
- Données existantes : mêmes identifiants de package et de préférences ; ne pas désinstaller la version précédente avant la mise à jour.

## Limites et test
Code non compilé dans cet environnement. Tester sur téléphone le réglage progressif du tempo avec plusieurs MP3, l'enchaînement automatique et l'ouverture des Réglages. Si une coupure persiste, relever Logcat avec `AudioTrack`, `MediaCodec`, `ExoPlayer` et `PlaybackException` pour diagnostiquer le périphérique audio.
