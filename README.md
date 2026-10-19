# TapisSync

Appli Android qui récupère tes séances de course planifiées dans Garmin Connect et pilote
la vitesse d'un tapis de course Bluetooth (FTMS), comme le Reebok Z-Tech AC.

## Ce que fait l'appli

- Connexion à Garmin Connect dans la page de connexion de l'appli Garmin mobile (double authentification comprise).
  L'appli ne voit pas ton mot de passe : elle garde seulement un jeton d'accès, valable environ 30 jours sans utilisation.
- Liste des séances planifiées (de 3 jours avant à 3 semaines après aujourd'hui) et de « Mes entraînements ».
- Conversion de chaque étape en vitesse tapis, avec l'explication affichée avant le départ :
  - plage d'allure Garmin : milieu de la plage par défaut, réglable du bout lent au bout rapide ;
  - correction en % si le tapis et la montre ne mesurent pas pareil ;
  - vitesses par défaut pour les étapes sans allure (échauffement, récupération, cible cardio…).
- Pilotage du tapis : démarrage, vitesse à chaque étape, pause, ajustement ±0,1 km/h, arrêt.
  La consigne est vérifiée quelques secondes après chaque changement et renvoyée si besoin.
- Annonces vocales et bips à chaque étape, fenêtre incrustée pour courir devant une vidéo.
- Capteur de foulée simulé (Bluetooth RSC) : la montre reçoit la vitesse et la distance du tapis.
  Sur Garmin : Capteurs et accessoires › Ajouter › Foulée, puis vitesse et distance sur « Toujours » ou « Intérieur ».
- Écran Diagnostic : services Bluetooth du tapis et journal à partager en cas de souci.

## Compiler

Gradle 8.14 et le SDK Android 35 :

```
gradle testReleaseUnitTest assembleRelease
```

L'APK signée est dans `app/build/outputs/apk/release/`. La clé de signature est dans `keystore/`
(mot de passe `tapissync`) : garde-la pour que les mises à jour s'installent par-dessus.

Sans environnement Android, pousse le projet sur GitHub : le workflow `.github/workflows/build.yml`
compile l'APK dans l'onglet Actions.

## Limites connues

- L'accès à Garmin Connect n'est pas officiel. Si Garmin change sa connexion, il faudra adapter l'appli.
- Sur les étapes à la distance, l'appli compte la distance du tapis ; la montre compte la sienne,
  les changements d'étape peuvent donc se décaler de quelques secondes entre les deux.
