# Cadence — application Android de vélo de route

Application native Android (Kotlin + Jetpack Compose) d'enregistrement de
sorties, de recommandation de parcours et de navigation, avec export vers un
compteur **iGPSPORT BSC200S**.

Tout fonctionne **sans compte et sans serveur** : les sorties restent dans une
base SQLite sur le téléphone.

---

## 1. Obtenir l'APK

**👉 Guide détaillé : [COMMENT-OBTENIR-L-APK.md](COMMENT-OBTENIR-L-APK.md)**

Je n'ai pas pu produire l'APK moi-même : l'environnement où j'ai travaillé
n'atteint ni dl.google.com ni Maven Central, donc ni SDK Android ni plugin
Android Gradle. Trois chemins existent pour l'obtenir, du plus simple au plus
classique.

### A. GitHub compile à ta place — rien à installer

Le projet contient `.github/workflows/build-apk.yml`. Crée un dépôt sur GitHub,
envoie-y le contenu de ce dossier, et l'APK est produit automatiquement : il
apparaît dans l'onglet *Actions* (artefact `cadence-apk`) et dans *Releases*,
d'où tu peux le télécharger directement depuis le téléphone.

### B. Une commande, sans Android Studio (Linux, macOS)

```bash
./build-apk.sh
```

Le script installe le SDK Android dans `~/Android/sdk` et Gradle dans
`~/.cadence-build`, compile, et dépose `cadence-debug.apk` à la racine.
Pré-requis : un JDK 17 ou plus récent.

### C. Android Studio (tous systèmes)

*File* → *Open* → le dossier `cadence` → laisse Gradle se synchroniser →
*Build* → *Build APK(s)*. Accepte les mises à jour d'AGP et de Kotlin si elles
sont proposées, ce sont des montées de version sûres.

En ligne de commande une fois le SDK installé :

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/
```

### Ce qui a été vérifié, et ce qui ne l'a pas été

3 137 lignes — modèle, routage, génération de boucles, scoring, export
GPX/TCX/FIT, statistiques, navigation — sont passées au compilateur Kotlin
2.0.21 avec des stubs d'API fidèles : **0 erreur, 0 avertissement**. Les
couches Compose, Room, Bluetooth et MapLibre n'ont pas pu l'être, faute
d'artefacts téléchargeables. Une erreur résiduelle y reste possible ; le
guide explique quoi faire le cas échéant.

## 2. Ce que fait l'application

### Enregistrement de sortie
- Service de premier plan : l'enregistrement continue **écran éteint**, avec
  une notification qui affiche distance, temps et vitesse moyenne, et des
  boutons pause / reprise / fin.
- **Filtrage GPS** : rejet des points à précision dégradée, des sauts
  impossibles et du bruit à l'arrêt. Sans ça une sortie de 100 km est
  surestimée de plusieurs kilomètres.
- **Altimètre barométrique** : si le téléphone a un baromètre, le dénivelé est
  calculé dessus et recalé sur l'altitude GPS. Bien plus juste que le GPS seul.
- **Pause automatique** : le compteur s'arrête au feu rouge et repart tout seul.
- **Capteurs Bluetooth** : cardiofréquencemètre, capteur de puissance, capteur
  de vitesse/cadence. Profils BLE standard, donc compatible Garmin, Wahoo,
  Polar, Magene, iGPSPORT…
- **Puissance estimée sans capteur** : à partir de la vitesse, de la pente et
  du poids total, via un modèle physique. Les calories en découlent.
- Métriques temps réel : vitesse, moyenne, distance, temps en mouvement, D+,
  pente instantanée, cardio, puissance, cadence, calories.

### Recommandation de parcours — le cœur de ta demande
Tu donnes un point de départ, une distance visée, un type de relief et un style
de routes ; l'application propose des **boucles** classées.

Comment ça marche :
1. Le moteur **BRouter** génère des boucles (`engineMode=4`). Piège : son
   paramètre `roundTripDistance` est un **rayon**, pas une longueur. Le
   générateur part de `distance / 2π` puis **converge par itérations
   proportionnelles** jusqu'à tomber à ±8 % de la distance demandée.
2. Plusieurs directions de départ sont explorées (réparties sur 360°) pour
   proposer des boucles vraiment différentes, puis les doublons sont écartés
   (comparaison de traces rééchantillonnées, dans les deux sens de parcours).
3. Chaque candidate est **notée sur 5 critères** : correspondance à la
   distance, correspondance au relief, qualité du revêtement, tranquillité des
   routes, adéquation à ton niveau. Le détail est affiché dans l'app pour que
   tu comprennes le classement.
4. Le revêtement et le trafic viennent des **tags OpenStreetMap** que BRouter
   renvoie segment par segment (`surface`, `highway`, `smoothness`,
   `estimated_traffic_class`) — aucune requête supplémentaire.
5. La **durée est estimée par un modèle physique** (résistance au roulement,
   gravité, traînée aérodynamique), pas par une vitesse moyenne forfaitaire.
   Un 80 km avec 1 500 m de D+ ne coûte pas le même temps qu'un 80 km plat, et
   pas le même temps selon le niveau.

### Niveau du cycliste
Cinq niveaux (Débutant → Compétiteur), chacun avec sa puissance d'endurance en
W/kg, son SCx, sa vitesse de descente confortable et son dénivelé tolérable par
kilomètre. L'application **estime ton niveau à partir de ton historique**
(vitesse moyenne, volume hebdomadaire, plus longue sortie, dénivelé avalé) et
te propose de mettre le profil à jour.

### Navigation guidée
- Carte vectorielle MapLibre, tracé du parcours et trace parcourue.
- Détection de sortie de parcours (45 m, confirmée sur 3 positions).
- Prochaine consigne, distance jusqu'au virage, distance et dénivelé restants.
- **Annonces vocales** en français à 300 m, 100 m et au virage.
- Les consignes sont déduites de la géométrie aux points de décision signalés
  par BRouter, ce qui reste juste quelle que soit la version du serveur.

### Historique et statistiques
- Sorties groupées par mois, détail complet avec carte et profil altimétrique.
- **Records personnels** sur 1 / 5 / 10 / 20 / 40 / 50 / 100 / 160 km, calculés
  par fenêtre glissante sur la trace.
- Volumes hebdomadaires et mensuels, totaux annuels et cumulés.
- **Charge d'entraînement** : moyennes exponentielles 7 j / 42 j et ratio, avec
  un conseil en clair.
- Puissance normalisée (modèle de Coggan) quand la puissance est disponible.

---

## 3. Transfert vers l'iGPSPORT BSC200S

C'est le point où il faut être honnête sur ce qui est garanti et ce qui ne
l'est pas.

### Ce qui est sûr
Le manuel du BSC200S indique que l'application iGPSPORT accepte le **GPX** et
le **TCX**. Le chemin qui fonctionne de façon fiable sur Android est un chemin
« tiré » et non « poussé » :

1. Dans Cadence, ouvre le parcours → *Envoyer vers mon compteur* → choisis le
   format → *Générer le fichier*. Le fichier est écrit dans **Téléchargements**.
2. Ouvre l'application **iGPSPORT Ride**.
3. *Ma page* → *Mes parcours* → **+** → *Importer un parcours*.
4. Choisis le fichier dans Téléchargements.
5. Ouvre le parcours → **Envoyer vers l'appareil**.

Le nom de fichier est volontairement austère (ASCII, tirets, 28 caractères max)
parce que les compteurs iGPSPORT documentent cette limite.

### Ce qui est proposé mais peut échouer
Le bouton **Partager** envoie le fichier directement à l'application iGPSPORT.
Sur certaines versions Android, celle-ci répond *« Non-GPX file is not
supported »* — c'est un défaut connu de leur application, pas du fichier. Si ça
arrive, repasse par le chemin ci-dessus.

### Les trois formats
| Format | Trace | Consignes de virage | Recommandation |
|---|---|---|---|
| **GPX** | oui | non (waypoints, souvent ignorés) | **Commence par celui-ci** |
| **TCX** | oui | oui (`CoursePoint`) | À tester sur ton compteur |
| **FIT** | oui | oui (`course_point`) | Encodeur binaire complet, à tester |

L'encodeur FIT est écrit à la main (profil Course du SDK Garmin : `file_id`,
`course`, `lap`, `event`, `record`, `course_point`, CRC-16 FIT, coordonnées en
semicercles, époque 1989). iGPSPORT documente le FIT pour ses modèles iGS60 /
iGS618 / iGS620 ; pour le BSC200S ce n'est pas écrit noir sur blanc, d'où le
« à tester ».

**Le BSC200S n'a pas de fond de carte** : il affiche la trace seule et alerte
des virages à l'approche. C'est pour ça que l'application garde la carte
détaillée et la voix de ton côté.

### Piste avancée
Le projet libre **Gadgetbridge** a rétro-ingénieré le protocole BLE des
compteurs iGPSPORT et le **BSC200S est explicitement supporté** (envoi de
fichiers de parcours en gpx / fit / tcx / cnx, protobuf sur BLE, CRC-8
MAXIM-DOW). Si tu veux un jour supprimer la dépendance à l'application
iGPSPORT, c'est la référence à lire — attention, code sous GPLv3.

---

## 4. Services externes utilisés

| Service | Rôle | Clé API | À savoir |
|---|---|---|---|
| **BRouter** (`brouter.de`) | Calcul d'itinéraires et de boucles | non | Serveur bénévole, aucune politique d'usage publiée. Correct pour un usage personnel ; pour une diffusion large, auto-héberge-le (MIT) ou embarque-le hors ligne. |
| **OpenFreeMap** | Fond de carte vectoriel | non | Gratuit, sans limite annoncée, financé par des dons. Pas de garantie de service. |
| **Nominatim** (OSM) | Recherche de lieux | non | Maximum 1 requête/seconde, User-Agent identifiant obligatoire. La recherche n'est déclenchée que par ta saisie. |

> **À faire avant toute diffusion** : remplace `contact@example.com` dans
> `HTTP_USER_AGENT` (`app/build.gradle.kts`) par une adresse réelle. La
> politique d'usage d'OpenStreetMap l'exige.

---

## 5. Architecture

```
app/src/main/java/fr/velo/cadence/
├── model/          Géométrie, profils, parcours, encodage de polylignes
├── data/           Room (sorties, points, parcours, records) + DataStore
├── net/            Clients BRouter et Nominatim (OkHttp + kotlinx.serialization)
├── routing/        Générateur de boucles, notation, modèle physique
├── tracking/       Service de premier plan, filtre GPS, baromètre, pause auto
├── sensors/        Bluetooth LE : cardio, puissance, vitesse/cadence
├── navigation/     Suivi de parcours, hors-parcours, annonces vocales
├── export/         GPX, TCX, encodeur FIT, partage vers iGPSPORT
├── stats/          Volumes, records personnels, charge d'entraînement
├── di/             Conteneur de dépendances explicite (pas de Hilt)
└── ui/             Compose : accueil, parcours, enregistrement, historique,
                    statistiques, profil, carte MapLibre
```

Pas de bibliothèque d'injection de dépendances : à cette taille, un conteneur
explicite (`di/AppContainer.kt`) se lit mieux et compile plus vite.

---

## 6. Ce qui n'est pas dans cette première version

Volontairement, pour rester sur du 100 % local :

- **Pas de réseau social** : ni flux d'amis, ni kudos, ni clubs. Ça demande un
  backend, un compte et une modération.
- **Pas de segments ni de classements** : même raison — les classements
  supposent des données partagées entre utilisateurs.
- **Pas de synchronisation Strava** : possible via leur API OAuth, mais ça
  demande d'enregistrer une application chez Strava. En attendant, l'export
  GPX d'une sortie permet un import manuel.
- **Pas de cartes hors ligne** : MapLibre sait lire des fichiers PMTiles
  locaux depuis la 11.7, c'est la voie la plus simple pour l'ajouter.
- **Pas de routage hors ligne** : BRouter est embarquable (licence MIT,
  segments `.rd5` par tuiles de 5°) — c'est l'évolution qui apporterait le plus
  en montagne, là où le réseau manque.

---

## 7. Pistes d'évolution, par ordre d'intérêt

1. **BRouter embarqué** + tuiles `.rd5` téléchargeables : parcours calculés
   sans réseau, plus aucune dépendance à un serveur bénévole.
2. **Cartes hors ligne** en PMTiles.
3. **Envoi BLE direct vers le BSC200S**, en s'inspirant de Gadgetbridge.
4. **Synchronisation Strava** en OAuth, pour publier les sorties.
5. **Segments personnels** : définir un segment sur une de tes traces et
   comparer tes passages — faisable en local, sans backend.
6. **Séances structurées** (intervalles avec alertes vocales).

---

## 8. Permissions demandées et pourquoi

| Permission | Raison |
|---|---|
| Localisation précise | Enregistrer la trace GPS |
| Service de premier plan (localisation) | Continuer l'enregistrement écran éteint |
| Notifications | Afficher le compteur persistant |
| Bluetooth (scan / connexion) | Capteurs cardio, puissance, cadence |
| Écriture Téléchargements (Android 8-9) | Écrire les fichiers de parcours |

La permission de localisation en arrière-plan n'est **pas** demandée : le
service de premier plan suffit, et ça évite une révision douloureuse sur le
Play Store.
