# Déployer l'app sur iPhone

Étapes pour proposer Wool Stock sur iPhone, du build sur un Mac à l'App Store. État des règles au 24
octobre 2026 ; les liens de la dernière section donnent les versions officielles.

## Où en est le portage

L'app iOS est écrite : c'est la même app que sur Android, en Kotlin Multiplatform et Compose
Multiplatform. Les écrans, la navigation, l'accès au serveur, le cache et le parseur sont
communs (module `shared`) ; seuls quelques blocs ont une version par plateforme :

| Bloc | Android | iPhone |
|---|---|---|
| Interface et navigation | Compose Multiplatform, commun | idem |
| Caméra | CameraX | AVFoundation (`VisionCamera`) |
| OCR et code-barre | ML Kit, modèles embarqués | Vision (`VNRecognizeTextRequest`, `VNDetectBarcodesRequest`), sur l'iPhone |
| Parseur d'étiquettes | `label-parser`, commun | idem |
| Appels au serveur | Ktor, moteur Android | Ktor, moteur Darwin |
| Cache local | Room, SQLite du système | Room, SQLite embarqué |
| Réglages | `SharedPreferences` | `NSUserDefaults` |
| Jeton de session | `SharedPreferences`, hors sauvegardes et transferts | Trousseau, réservé à l'iPhone (`KeychainStore`) |
| Partage des exports | `FileProvider` + fenêtre de partage | `UIActivityViewController` |
| Couleurs | dynamiques (Android 12+) | palette Material claire ou sombre |
| Traductions | `shared/src/commonMain/composeResources`, communes | idem, plus `InfoPlist.strings` pour le nom et la demande de caméra |

Le projet Xcode est dans `iosApp/` : il héberge l'écran Compose (`MainViewController`) et
compile le framework Kotlin à chaque build, par Gradle.

**Ce qui est vérifié, sans Mac.** Tout le code Kotlin, iOS compris, se compile sous Linux
(`./gradlew :shared:compileKotlinIosArm64`), contre les vraies API d'Apple : une faute de
type ou d'API dans le code iOS y est donc détectée. L'app Android, qui exécute le même code
commun, a été essayée sur un émulateur. La conversion des boîtes Vision vers le repère de
l'écran a ses tests (`./gradlew :shared:testAndroidHostTest`).

**Ce qui ne l'est pas.** L'édition de liens du framework, le build Xcode et l'exécution
demandent un Mac : l'app iOS n'a encore jamais tourné. Restent donc à essayer la caméra, la
qualité de l'OCR de Vision sur de vraies étiquettes, la demande d'autorisation, la feuille de
partage et le clavier (section 1).

**Il faut un Mac.** Xcode, seul outil capable de compiler et d'envoyer une app iOS, ne
tourne que sur macOS : un Mac à soi (un Mac mini suffit), un Mac loué dans le cloud, ou une
CI macOS (Xcode Cloud, 25 heures par mois comprises dans l'abonnement développeur, ou les
runners macOS de GitHub Actions).

**En attendant : les pages web.** Le serveur sert déjà des pages utilisables dans Safari, à
l'adresse `https://stock-laine.carin.family` : stock, entrées, sorties, inventaires et
export. Le partage → « Sur l'écran d'accueil » leur donne une icône. Il n'y manque que le
scan : l'EAN et le bain se tapent à la main.

## 1. Finaliser sur le Mac

### Installer les outils

- **Xcode 26** ou plus, depuis l'App Store, avec le simulateur iOS.
- **Un JDK 17 ou plus**, par exemple Temurin 21. Le build Xcode le trouve seul
  (`/usr/libexec/java_home`).
- **Le SDK Android** : Gradle configure aussi la cible Android du module `shared`. Le plus
  simple est d'installer Android Studio, qui sert aussi à modifier le code Kotlin. Sinon,
  indiquer le chemin du SDK dans `local.properties` (`sdk.dir=…`).

### Premier build

1. Dans le dépôt, lier une première fois le framework pour le simulateur :

   ```bash
   ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
   ```

   C'est la seule étape que Linux n'a pas pu faire. Une erreur d'édition de liens nomme en
   général un framework système : l'ajouter à `OTHER_LDFLAGS` dans le projet Xcode.
2. Ouvrir `iosApp/iosApp.xcodeproj`. Si Xcode propose de passer aux réglages recommandés,
   accepter, mais laisser **User Script Sandboxing à No** : la phase « Compile Kotlin
   Framework » lance Gradle, qui écrit hors du bac à sable.
3. Choisir un simulateur d'iPhone, puis Run. Le simulateur n'a pas de caméra : l'écran de
   scan reste noir, les autres écrans fonctionnent. Par défaut, l'app vise le serveur de
   production. Pour un serveur lancé sur le Mac, changer l'adresse dans les réglages de l'app,
   ou `WOOLSTOCK_API_URL` dans `iosApp/Configuration/Config.xcconfig`, par exemple
   `http:/$()/127.0.0.1:8080`.

### Sur un iPhone

1. Dans `Config.xcconfig`, renseigner `TEAM_ID`, l'identifiant de l'équipe (Xcode →
   Settings → Accounts). Avec l'équipe personnelle gratuite, le profil expire au bout de
   7 jours ; avec l'abonnement (section 2), l'iPhone est déclaré dans le compte.
2. Si Xcode répond que l'identifiant est déjà pris, changer `BUNDLE_ID`.
3. Brancher l'iPhone, activer le mode développeur (Réglages → Confidentialité et sécurité →
   Mode développeur), puis Run.

### À vérifier

- **Autorisation** : la demande s'affiche en français, avec le texte de `InfoPlist.strings`.
  Après un refus, le bouton de l'écran de scan ouvre les Réglages.
- **Aperçu et boîtes** : en mode Debug, les rectangles doivent tomber sur le texte de
  l'étiquette. Un décalage vient de la conversion des coordonnées (`AspectFillMapper`) ou de
  l'orientation des images (`VisionCamera`, rotation de 90°).
- **Lecture** : EAN-13 et UPC, bain, marque, coloris, capture automatique, lampe.
- **Qualité de l'OCR** : exporter des relevés JSON depuis l'iPhone (Debug → Exporter JSON),
  les ajouter aux fixtures (`label-parser/src/jvmTest/resources/labels/`) et comparer le taux
  de réussite au rapport du parseur. Si Vision lit mal les petits tampons, essayer un zoom de
  la caméra (`videoZoomFactor`), une zone d'intérêt (`regionOfInterest`) ou, en dernier
  recours, ML Kit pour iOS.
- **Le reste** : saisies, export Excel par la feuille de partage, clavier qui ne masque pas
  les champs, retour par glissement depuis le bord, mode sombre, connexion gardée après un
  redémarrage, « Supprimer mon compte ».

### Points connus

- Le jeton de session est gardé dans le Trousseau (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) :
  ni dans les sauvegardes de l'iPhone, ni transféré vers un autre appareil. Une session des
  versions précédentes, dans `NSUserDefaults`, y passe au premier lancement. À vérifier sur
  le Mac : connexion, puis redémarrage de l'app, qui doit rester connectée.
- Après un envoi à App Store Connect, Apple peut écrire (ITMS-91053) qu'une API « à raison
  déclarée » manque au manifeste `PrivacyInfo.xcprivacy` : l'y ajouter avec la raison
  proposée.

## 2. Compte Apple Developer Program

1. S'inscrire sur [developer.apple.com/programs](https://developer.apple.com/programs/),
   avec un compte Apple protégé par l'identification à deux facteurs.
2. Choisir le type d'inscription :
   - **Particulier** : votre nom apparaît comme vendeur.
   - **Organisation** : il faut un numéro D-U-N-S.
3. Payer l'abonnement annuel de 99 USD, environ 99 € en France. S'il n'est pas renouvelé,
   l'app disparaît de l'App Store et TestFlight s'arrête.
4. Dans App Store Connect → Entreprise, **déclarer le statut de commerçant**, exigé par le
   Digital Services Act pour l'Union européenne :
   - une app gratuite, sans activité commerciale, relève en principe du statut
     « non-commerçant » ;
   - un commerçant voit son adresse, son téléphone et son email publiés sur la fiche de l'app
     dans l'UE.

**Déjà réglé dans le projet** (`iosApp/`) :

- iPhone seulement, iOS 17 ou plus, en portrait (sinon l'App Store exigerait aussi des
  captures d'iPad) ;
- bundle ID `io.leblanc.woolstock`, le même que sur Android, version et numéro de build
  repris d'Android (`Config.xcconfig`) ;
- `NSCameraUsageDescription`, en anglais et en français ; sans cette clé, l'app planterait à
  l'ouverture de la caméra ;
- `ITSAppUsesNonExemptEncryption = NO` : l'app ne chiffre que par HTTPS, via le système ;
- `NSAllowsLocalNetworking`, pour viser un serveur de développement en HTTP sur le réseau
  local ; seule une app construite en Debug l'utilise : en Release, l'app elle-même refuse
  toute adresse en `http://` autre que `127.0.0.1`. Le serveur de production est en HTTPS ;
- le manifeste de confidentialité : données collectées (adresse email, nom, mouvements
  saisis), sans pistage, et API « à raison déclarée » (`UserDefaults`, horodatage des fichiers
  pour SQLite, horloge du système pour le scan) ;
- l'icône, tirée du logo (`iosApp/AppIcon.svg`).

**Version d'Xcode.** Depuis le 28 avril 2026, tout envoi à App Store Connect doit être
compilé avec **Xcode 26 ou plus** et le SDK iOS 26. Apple relève cette exigence chaque
printemps.

## 3. Créer l'app dans App Store Connect

**Apps → Nouvelle app** :
- plateforme iOS ;
- nom « Stock de laine » (30 caractères au plus) ;
- langue principale : français ;
- bundle ID ;
- SKU, une référence interne, par exemple `wool-stock`.

## 4. Envoyer un build

Le numéro de build (`CURRENT_PROJECT_VERSION` dans `Config.xcconfig`) augmente à chaque
envoi. La version (`MARKETING_VERSION`) suit le `versionName` d'Android.

Envoi depuis Xcode :
1. Product → **Archive**.
2. Organizer → **Distribute App** → App Store Connect.

Xcode Cloud peut faire la même chose à chaque tag Git. Le build apparaît dans App Store
Connect après un traitement de quelques minutes.

## 5. TestFlight

- **Testeurs internes** :
  - jusqu'à 100 personnes, membres de l'équipe App Store Connect ;
  - pas de revue.
- **Testeurs externes** :
  - jusqu'à 10 000 personnes, invitées par email ou par lien public ;
  - le premier build passe une revue bêta, d'un jour ou deux.
- **Expiration** : chaque build expire au bout de **90 jours**. TestFlight convient pour
  essayer, mais une diffusion durable demande un envoi au moins tous les trois mois.

Les testeurs installent l'app TestFlight, puis Wool Stock par l'invitation.

## 6. Choisir la diffusion

| Mode | Pour qui | Contraintes |
|---|---|---|
| **App Store public** | Tout le monde | Revue complète. Une app qui ne sert qu'à un groupe privé risque un refus au titre de la guideline 3.2 (public trop restreint). |
| **Distribution non répertoriée** | Quiconque a le lien | Revue complète, mais l'app n'apparaît ni dans les recherches ni dans les classements. Faite pour les publics restreints : c'est le mode qui correspond à Wool Stock. |
| **TestFlight** | 10 000 personnes au plus | Builds à renouveler tous les 90 jours. |
| **Ad Hoc** | 100 iPhone au plus par an, déclarés un par un (UDID) | Pas de revue, mais installation manuelle et profil annuel. |

Pour la distribution non répertoriée :
1. Préparer la version finale (pas une bêta).
2. Écrire dans les notes de revue qu'elle est destinée à une distribution non répertoriée.
3. La soumettre, puis remplir le
   [formulaire de demande](https://developer.apple.com/contact/request/unlisted-app/).
4. Une fois la demande acceptée, App Store Connect fournit le lien à partager. Le serveur
   reste le contrôle d'accès : sans compte autorisé, l'app ne montre rien.

## 7. Fiche App Store et confidentialité

Champs de la fiche :

- **Textes** :
  - sous-titre (30 caractères) ;
  - description ;
  - mots-clés (100 caractères) ;
  - catégorie « Productivité ».
- **Captures d'iPhone 6,9 pouces** (1 320 × 2 868 px), de 1 à 10 : stock, scan, saisie,
  inventaire.
- **URL d'assistance et URL de règles de confidentialité**, toutes deux obligatoires. La
  page de confidentialité peut être la même que pour Google Play.
- **Confidentialité de l'app** (« étiquette nutritionnelle ») :
  - adresse email, nom et mouvements saisis (« autres contenus de l'utilisateur »), collectés,
    liés à la personne, pour le fonctionnement de l'app, comme dans le manifeste ;
  - pas de pistage ;
  - images de la caméra non collectées.
- **Classification par âge** : remplir le questionnaire. L'app ne contient aucun contenu
  sensible.
- **Prix** : gratuit ; disponibilité : pays choisis.

## 8. Revue

À donner dans les **informations de revue** :

- **Compte de démonstration.** La connexion est obligatoire. Fournir une adresse autorisée,
  par exemple un compte gestionnaire « Revue Apple » créé avec `wool-stock users add`, et
  préciser qu'il n'y a pas de mot de passe. Comme pour Google, ce compte voit le vrai stock,
  sauf avec un serveur de démonstration.
- **Notes de revue.** L'app sert une famille ou une association. Les comptes sont créés par
  un administrateur. Le cas échéant, préciser la demande de distribution non répertoriée.
- **Suppression de compte** (guideline 5.1.1(v)). Toute app qui permet de créer un compte
  doit permettre de le supprimer depuis l'app. Ici, seuls les administrateurs créent des
  comptes : le même cas limite que pour Google. L'app le permet déjà : Réglages → « Supprimer
  mon compte ». Le signaler dans les notes de revue.
- **Serveur joignable** pendant toute la revue (guideline 2.1).

La revue prend en général 24 à 48 heures. Un refus arrive avec la guideline en cause, et on
peut répondre depuis App Store Connect.

## 9. Mettre à jour

1. Augmenter la version et le numéro de build.
2. Archiver, puis envoyer le build.
3. Créer la nouvelle version dans App Store Connect, choisir le build, puis soumettre.

À surveiller :

- l'exigence de version d'Xcode, relevée chaque printemps ;
- le renouvellement annuel de l'abonnement ;
- les nouveaux accords à accepter dans App Store Connect : tant qu'ils ne le sont pas, les
  envois sont bloqués.

## Références

- [Exigences de SDK (Xcode 26, depuis le 28 avril 2026)](https://developer.apple.com/news/upcoming-requirements/)
- [Distribution non répertoriée](https://developer.apple.com/support/unlisted-app-distribution/)
- [TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview)
- [Statut de commerçant (Digital Services Act)](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements)
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [Manifestes de confidentialité](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files)
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) et [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)
