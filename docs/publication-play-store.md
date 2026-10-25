# Publier l'app sur le Play Store

Procédure pour diffuser l'app Android par Google Play, de la création du compte développeur
aux mises à jour. État des règles au 24 octobre 2026 : elles changent chaque année, et les
liens de la dernière section donnent les versions officielles.

Avant tout, le serveur doit répondre sur `https://stock-laine.carin.family` (voir
[`deploiement-serveur.md`](deploiement-serveur.md)). C'est l'adresse par défaut de l'app.
Une version *release* refuse le HTTP en clair, et la revue de Google aura besoin d'un serveur
joignable.

## 1. Choisir le mode de diffusion

| Mode | Pour qui | Contraintes |
|---|---|---|
| **Test interne** | Jusqu'à 100 personnes, invitées par leur adresse Google | Disponible en quelques minutes. Pas de seuil de testeurs. L'app n'apparaît pas dans les recherches. |
| **Test fermé** | Une liste ou un groupe Google | Passe par la revue. Il conditionne l'accès à la production (voir plus bas). |
| **Production** | Tout le monde, visible dans le Play Store | Compte personnel créé après le 13 novembre 2023 : il faut d'abord un test fermé d'au moins 12 testeurs inscrits pendant 14 jours de suite, puis une demande d'accès, avec une réponse sous 7 jours environ. |

Le stock n'est utile qu'aux comptes autorisés sur le serveur. Pour une famille ou une
association, **le test interne suffit**. C'est une vraie installation Play Store, avec ses
mises à jour automatiques, sans le parcours des 12 testeurs.

La suite décrit le test interne, puis ce qu'ajoute la production.

## 2. Compte développeur

1. Créer le compte sur [play.google.com/console](https://play.google.com/console), avec un
   compte Google protégé par la validation en deux étapes.
2. Choisir le type de compte :
   - **Personnel**, pour une personne.
   - **Organisation**, qui demande un numéro D-U-N-S. Les comptes d'organisation échappent à
     la règle des 12 testeurs.
3. Payer les frais d'inscription, uniques : 25 USD.
4. Faire vérifier son identité, avec une pièce d'identité, et vérifier un numéro de téléphone
   et une adresse email de contact. Comptez quelques jours.

## 3. Préparer l'app

**Identifiant.** `applicationId = "eu.leblancsimon.woolstock"` ne pourra plus changer après
le premier envoi.

**Versions.** Chaque envoi doit porter un `versionCode` plus grand que le précédent. Le
`versionName` est celui qu'on voit (aujourd'hui 8 et 0.8.0, dans
[`app/build.gradle.kts`](../app/build.gradle.kts)).

**API cible.** Depuis le 31 août 2026, toute nouvelle app et toute mise à jour doivent viser
Android 16 (API 36). `targetSdk = 36` est conforme. L'exigence monte chaque année en août.

**Pages mémoire de 16 Ko.** Les bibliothèques natives (ML Kit, CameraX) doivent être alignées
sur 16 Ko. C'est vérifié le 24 octobre 2026 : les cinq `.so` arm64 de l'APK sont alignées à
`0x4000`. À revérifier après une mise à jour de ML Kit ou de CameraX :

```bash
$ANDROID_HOME/build-tools/36.1.0/zipalign -c -P 16 -v 4 app-release.apk | grep '\.so'
```

**Adresse du serveur.** Le build par défaut vise `https://stock-laine.carin.family`. Ne pas
passer `-Pwoolstock.apiUrl` pour une version Play Store.

**Version debug et version Play Store.** Elles ont le même identifiant mais pas la même
signature : Android refuse d'installer l'une par-dessus l'autre. Avant d'installer la version
Play Store, désinstaller la version debug. Les données locales ne sont qu'une copie du serveur,
mais il faudra se reconnecter. Pour garder les deux côte à côte, on peut ajouter
`applicationIdSuffix = ".debug"` au type `debug`.

## 4. Clé d'envoi et signature

Google Play signe l'app avec sa propre clé (Play App Signing, obligatoire pour une nouvelle
app). Vous signez seulement les envois, avec une **clé d'envoi** que vous gardez hors du
dépôt :

```bash
mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
keytool -genkeypair -v -keystore ~/.android-keys/wool-stock-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Sauvegarder ce fichier et ses mots de passe, par exemple dans un gestionnaire de mots de
passe. Une clé d'envoi perdue se remplace sur demande au support, mais pas dans la journée.

Ranger les secrets dans `~/.gradle/gradle.properties`, en dehors du dépôt :

```properties
woolstock.upload.storeFile=/home/leviathan/.android-keys/wool-stock-upload.jks
woolstock.upload.storePassword=…
woolstock.upload.keyAlias=upload
woolstock.upload.keyPassword=…
```

Puis déclarer la signature dans `app/build.gradle.kts`. Elle ne s'applique que si les
propriétés existent : sur une autre machine, le build release reste possible, non signé.

```kotlin
android {
    signingConfigs {
        create("upload") {
            val storeFile = providers.gradleProperty("woolstock.upload.storeFile").orNull
            if (storeFile != null) {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("woolstock.upload.storePassword").get()
                keyAlias = providers.gradleProperty("woolstock.upload.keyAlias").get()
                keyPassword = providers.gradleProperty("woolstock.upload.keyPassword").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (providers.gradleProperty("woolstock.upload.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }
}
```

## 5. Construire et essayer la version release

```bash
./gradlew :label-parser:test :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab, le fichier à envoyer
```

Essayer d'abord la même version sur un téléphone, avec l'APK release, signé de la même façon :

```bash
./gradlew :app:assembleRelease
adb uninstall eu.leblancsimon.woolstock      # retire la version debug
adb install app/build/outputs/apk/release/app-release.apk
```

Points à vérifier sur le téléphone :

- connexion au serveur de production ;
- scan d'une étiquette ;
- une entrée et une sortie ;
- l'export Excel.

## 6. Créer l'app dans la Play Console

1. **Créer une application** :
   - nom affiché : « Stock de laine » ;
   - langue par défaut : français (France) ;
   - type : application, gratuite.
2. Accepter les déclarations : règles du programme, lois d'exportation des États-Unis.

## 7. Contenu de l'app (Règles et programmes → Contenu de l'application)

Ces déclarations sont requises avant la production. Mieux vaut les remplir dès le test
interne.

**Règles de confidentialité.** Une URL publique est obligatoire, car l'app utilise la caméra
et des adresses email. La publier sur le serveur, par exemple
`https://stock-laine.carin.family/confidentialite`, ou sur une page à part. Elle doit dire :

- les données gardées sur le serveur : l'adresse email et le nom de chaque compte, le modèle
  du téléphone noté à la connexion, et les mouvements, signés du nom du compte ;
- que les images de la caméra sont analysées sur le téléphone et jamais envoyées ;
- que les données restent sur ce serveur, sans revente ni partage ;
- combien de temps les données sont gardées, et comment demander leur suppression ;
- un contact.

**Accès à l'application.** Toutes les fonctions demandent une connexion. Donner aux
vérificateurs de Google une adresse autorisée, avec la marche à suivre : « saisir l'adresse,
toucher Se connecter ». Créer pour eux un compte gestionnaire dédié :

```bash
sudo -u wool-stock /usr/local/bin/wool-stock -c /etc/wool-stock/config.yaml \
  users add revue.play@exemple.fr "Revue Google Play" manager
```

Ce compte voit le vrai stock et peut saisir des mouvements. Les revues reviennent à chaque
mise à jour, donc il doit rester actif. Pour éviter qu'il touche au vrai stock, il faut un
second serveur de démonstration, avec des données fictives.

**Annonces.** L'app n'en contient pas.

**Classification du contenu.** Remplir le questionnaire IARC : catégorie utilitaire, sans
contenu sensible.

**Public cible.** Adultes (18 ans et plus). Cela évite les règles « Familles ».

**Sécurité des données.** Données collectées par l'app :

| Donnée | Déclaration |
|---|---|
| Adresse email, nom | Collectées, liées à la personne, pour la gestion du compte et le fonctionnement de l'app. Non partagées. Obligatoires. |
| Mouvements de stock | Collectés pour le fonctionnement de l'app (« Autres contenus générés par l'utilisateur »). |
| Images de la caméra | Non collectées : traitées sur le téléphone seulement. |
| Données de ML Kit | ML Kit envoie à Google des informations sur l'appareil et l'app, et des mesures de performance, pour le diagnostic. Les déclarer d'après la [page de divulgation de ML Kit](https://developers.google.com/ml-kit/android-data-disclosure). |

Toutes ces données sont chiffrées en transit (HTTPS).

**Suppression de compte.** Google exige un chemin de suppression, dans l'app et sur une page
web, de toute app qui permet de **créer un compte** depuis l'app. Ici, personne ne crée son
propre compte, mais un administrateur peut en créer depuis l'app. Le cas est limite. Le plus
sûr est de :

- ajouter dans les réglages un « Supprimer mon compte », qui retire le compte et ses
  sessions, le nom restant sur les mouvements passés ;
- publier une page web qui explique comment demander la suppression ;
- déclarer cette page dans le formulaire « Sécurité des données ».

**Autres déclarations** (applications gouvernementales, fonctionnalités financières, santé,
actualités) : non concerné.

## 8. Fiche Play Store

Obligatoire avant la production, utile dès le test interne :

- **Description courte** (80 caractères), par exemple : « Scannez les étiquettes de laine
  et suivez le stock de pelotes par bain. »
- **Description complète** (4 000 caractères au plus) :
  - ce que fait l'app ;
  - qu'elle demande un compte sur un serveur Wool Stock ;
  - que l'OCR se fait sur le téléphone.
- **Icône** : 512 × 512 px, PNG 32 bits. Elle s'exporte à part : l'icône du lanceur est
  vectorielle.
- **Image de présentation** : 1 024 × 500 px.
- **Captures d'écran** : au moins 2, prises sur le téléphone. Écrans conseillés : stock,
  scan, saisie d'une entrée, inventaire.
- **Catégorie** « Productivité », adresse email de contact.

## 9. Publier en test interne

1. **Tests → Test interne → Testeurs** : créer une liste d'adresses Google (100 au plus).
2. **Créer une release** :
   - envoyer `app-release.aab` ;
   - accepter Play App Signing la première fois ;
   - écrire les notes de version en français.
3. **Vérifier la release**, puis la **lancer**.
4. Envoyer aux testeurs le **lien d'inscription** de la page Testeurs. Chacun l'ouvre avec son
   compte Google, accepte, puis installe l'app depuis le Play Store.

Chaque testeur doit aussi avoir un compte sur le serveur (`/users`). Le Play Store distribue
l'app, mais c'est le serveur qui décide qui entre.

## 10. Passer en production (facultatif)

1. Créer un **test fermé** avec au moins 12 testeurs. Ils doivent rester inscrits 14 jours de
   suite : un testeur qui se désinscrit repart de zéro, et Google regarde s'ils utilisent
   vraiment l'app.
2. Au bout des 14 jours, faire la **demande d'accès à la production** depuis le tableau de
   bord. Elle a trois parties :
   - le déroulement du test ;
   - l'app, son public et les installations prévues ;
   - ce qui a changé depuis le test.

   Rappeler les identifiants de test.
3. Une fois l'accès obtenu, créer la release de **production**, avec un déploiement
   progressif si l'on veut, et la soumettre à la revue. Pour un nouveau compte, la revue prend
   de quelques jours à une semaine.

## 11. Mettre à jour

1. Augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Lancer `./gradlew :label-parser:test :app:bundleRelease`.
3. Créer une release sur la piste voulue (test interne, puis production le cas échéant) et y
   envoyer le nouvel `.aab`. Une release de test interne peut aussi être **promue** vers une
   autre piste sans renvoyer de fichier.

À surveiller chaque année :

- l'API cible exigée, en août ;
- les avertissements de la Play Console, par exemple sur les pages de 16 Ko ou sur des
  bibliothèques obsolètes ;
- le renouvellement de la vérification d'identité, si Google le demande.

## Et sans le Play Store ?

On peut aussi diffuser un APK release signé, depuis le serveur par exemple. Mais Google
généralise la **vérification des développeurs**, y compris pour les apps installées hors
Play Store :

- depuis le 30 septembre 2026 au Brésil, en Indonésie, à Singapour et en Thaïlande ;
- dans le reste du monde à partir de 2027.

Un compte « diffusion limitée », gratuit et sans pièce d'identité, permettra de partager une
app avec 20 appareils au plus. La Play Console reste le chemin le plus simple et le plus
durable.

## Références

- [Test des nouveaux comptes personnels (12 testeurs, 14 jours)](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Exigence d'API cible](https://developer.android.com/google/play/requirements/target-sdk)
- [Pages mémoire de 16 Ko](https://developer.android.com/guide/practices/page-sizes)
- [Suppression de compte](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Données collectées par ML Kit](https://developers.google.com/ml-kit/android-data-disclosure)
- [Signer l'app (Play App Signing)](https://developer.android.com/studio/publish/app-signing)
