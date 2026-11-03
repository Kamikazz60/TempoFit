# Suivi — TempoFit

## État (03/11/2026) — version 0.9.4
- Timer, musique, tempo, dossiers, programmes : implémentés, compilés, validés sur téléphone (v0.9.1).
- Signaux plus forts + ducking + Bluetooth (v0.9.1) : validés sur appareil réel.
- Temps avant le départ (v0.9.0), logo (v0.9.1) : compilés ; logo/prépa à confirmer visuellement.
- v0.9.2 : volume des bips divisé par 2 (SIGNAL_GAIN 0,5, slider mini 10 %), décompte 3-2-1 aussi en phase Repos : **compilé uniquement, à valider sur téléphone**.

- v0.9.3 : fichiers WAV régénérés à 65 % du max (−35 %), nouveau choix de tonalité Aiguë / Plus grave dans Réglages (work_grave/rest_grave/finish_grave.wav, aperçu au toucher) : **compilé uniquement, à valider sur téléphone**.

- v0.9.4 : renommage de l'appli en **TempoFit** (libellé, en-tête, notification, README) ; applicationId inchangé pour conserver les données. Description store : `docs/DESCRIPTION_STORE.md`.

- Release signée v0.9.4 compilée et vérifiée (apksigner), testée sur émulateur API 35 ; correctif marge sous la barre d'état (Android 15). Captures : `docs/screenshots/`.
- Publication store perso : **en attente** — l'utilisateur n'a pas encore précisé quel store (adresse / méthode d'envoi).

## Dernière session
Volume ÷2, décompte repos, préparation du dépôt GitHub (.gitignore, README). Fichiers : TrainingService.kt, MainActivity.kt, build.gradle.kts.

## Prochaine tâche exacte
Après validation par l'utilisateur : créer le dépôt GitHub public et pousser.
