# Déployer l'app sur iPhone

Étapes pour proposer Wool Stock sur iPhone, du portage à l'App Store. État des règles au 24
octobre 2026 ; les liens de la dernière section donnent les versions officielles.

## Le point de départ : l'app n'existe que pour Android

L'app actuelle ne tourne pas sur iPhone. Chacun de ses blocs est propre à Android :

| Bloc | Aujourd'hui (Android) | Sur iPhone |
|---|---|---|
| Interface | Jetpack Compose | Compose Multiplatform, ou SwiftUI |
| Caméra | CameraX | AVFoundation |
| OCR et code-barre | ML Kit Android (modèles embarqués) | Vision d'Apple (`VNRecognizeTextRequest`, `VNDetectBarcodesRequest`), ou ML Kit iOS |
| Parseur d'étiquettes | `label-parser`, Kotlin pur | Réutilisable tel quel avec Kotlin Multiplatform |
| Appels au serveur | `HttpURLConnection` | Ktor (moteur Darwin), ou `URLSession` |
| Cache local | Room | Room KMP (2.7 et plus), ou SQLDelight |
| Réglages, session | `SharedPreferences` | DataStore KMP, ou `UserDefaults` / Trousseau |
| Partage de l'export | `FileProvider` + `ACTION_SEND` | `UIActivityViewController` |
| Traductions | `res/values*/strings.xml` | Ressources Compose Multiplatform, ou `Localizable.xcstrings`, avec les mêmes clés |

**Il faut aussi un Mac.** Xcode, seul outil capable de compiler et d'envoyer une app iOS, ne
tourne que sur macOS. Trois possibilités :

- un Mac à soi (un Mac mini suffit) ;
- un Mac loué dans le cloud ;
- une CI macOS, comme Xcode Cloud (25 heures par mois comprises dans l'abonnement développeur)
  ou les runners macOS de GitHub Actions.

### Avant de porter : les pages web suffisent-elles ?

Le serveur sert déjà des pages utilisables sur iPhone, dans Safari, à l'adresse
`https://stock-laine.carin.family`. On y consulte le stock, on y saisit entrées, sorties et
inventaires, et on y télécharge l'export. Le partage → « Sur l'écran d'accueil » leur donne
une icône. Il manque seulement le scan : l'EAN et le bain se tapent à la main.

Si les personnes sur iPhone saisissent peu, c'est la solution immédiate, sans Mac, sans
abonnement et sans revue.

## 1. Choisir la technique de portage

**Kotlin Multiplatform et Compose Multiplatform (recommandé).** On garde une seule base de
code, et Compose Multiplatform est stable sur iOS depuis sa version 1.8. Le partage se fait
ainsi :

- **En commun** : le parseur, les modèles de l'API, le client HTTP, le cache, les écrans et
  leurs ViewModels.
- **Propre à chaque plateforme** : la caméra, l'OCR et le partage de fichier, derrière une
  interface commune (`expect` / `actual`).

**SwiftUI, avec le parseur en framework Kotlin.** Une app iOS native qui n'importe que
`label-parser`, compilé en XCFramework. L'interface est plus « iPhone », mais tous les écrans
sont à réécrire et à maintenir deux fois.

## 2. Porter le code

Dans l'ordre :

1. **`label-parser` en module multiplateforme.** Il n'utilise que trois API propres à la JVM :
   - `java.text.Normalizer`, qui retire les accents ;
   - `java.util.Locale` ;
   - `getResourceAsStream("brands.json")`, qui charge le dictionnaire des marques.

   Les remplacer par des `expect` / `actual`, ou embarquer le dictionnaire comme ressource
   multiplateforme. Ses tests passent alors sur la JVM et sur iOS.
2. **Client et modèles de l'API.**
   - `kotlinx.serialization` est déjà multiplateforme.
   - `HttpURLConnection` devient Ktor. Il faut garder les en-têtes `Authorization` et
     `Accept-Language`, et la lecture de `Content-Disposition` pour l'export.
3. **Cache et réglages** : Room KMP pour les références, DataStore pour l'adresse du serveur
   et la session. La session est un jeton qui n'expire pas : sur iPhone, elle a sa place dans
   le Trousseau.
4. **Écrans** : migrer les écrans Compose vers Compose Multiplatform, et les chaînes vers ses
   ressources, avec les mêmes clés en anglais et les traductions fr et en.
5. **Caméra et OCR sur iOS.**
   - AVFoundation fournit les images, et Vision y lit le texte et les codes-barres.
   - Vision renvoie des boîtes en coordonnées normalisées, avec l'origine en **bas** à gauche.
     Le parseur attend des `Rect` en pixels, avec l'origine en haut à gauche : la conversion
     décide de la recherche « à droite / en dessous ».
   - Pour valider, enregistrer des relevés iOS au format `LabelDump` et comparer le taux de
     réussite au rapport du parseur (`label-parser/build/reports/label-parser/report.md`).
6. **Partage de l'export** : `UIActivityViewController`, avec le fichier `.xlsx` écrit dans
   le dossier temporaire.

**Réseau.** iOS exige HTTPS (App Transport Security), ce que le serveur de production
respecte. Pour viser un serveur de développement en HTTP, ajouter une exception
`NSAllowsLocalNetworking` réservée à la configuration Debug. iOS n'a pas d'équivalent à
`adb reverse` : l'iPhone vise l'adresse du Mac sur le réseau local.

## 3. Compte Apple Developer Program

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

## 4. Projet Xcode

**Version d'Xcode.** Depuis le 28 avril 2026, tout envoi à App Store Connect doit être
compilé avec **Xcode 26 ou plus** et le SDK iOS 26. Apple relève cette exigence chaque
printemps.

**Identifiant et signature.**
- Bundle ID : `eu.leblancsimon.woolstock`, le même que sur Android.
- Signature automatique avec l'équipe du compte développeur.

**Appareils.** iPhone seulement. Sinon, l'App Store exigera aussi des captures d'iPad.

**`Info.plist` :**
- `NSCameraUsageDescription` : pourquoi l'app demande la caméra (« Lire les étiquettes et
  les codes-barres des pelotes »), traduit en français et en anglais dans
  `InfoPlist.xcstrings`. Sans cette clé, l'app plante à l'ouverture de la caméra et la
  revue la refuse.
- `ITSAppUsesNonExemptEncryption = NO` : l'app ne chiffre que par HTTPS, via le système.
  Cela évite la question sur l'export de cryptographie à chaque envoi.

**Manifeste de confidentialité** (`PrivacyInfo.xcprivacy`) :
- `UserDefaults` fait partie des API « à raison déclarée » (raison `CA92.1`) ;
- y déclarer aussi les données collectées : adresse email, nom.

## 5. Essayer sur un iPhone

- **Sans abonnement** : avec l'équipe personnelle gratuite, Xcode installe l'app sur un
  iPhone branché. Le profil expire au bout de 7 jours.
- **Avec l'abonnement** : déclarer l'iPhone dans le compte et installer des builds de
  développement sans limite de durée courte.

## 6. Créer l'app dans App Store Connect

**Apps → Nouvelle app** :
- plateforme iOS ;
- nom « Stock de laine » (30 caractères au plus) ;
- langue principale : français ;
- bundle ID ;
- SKU, une référence interne, par exemple `wool-stock`.

## 7. Envoyer un build

Le numéro de build (`CFBundleVersion`) augmente à chaque envoi. La version
(`CFBundleShortVersionString`) peut suivre le `versionName` d'Android.

Envoi depuis Xcode :
1. Product → **Archive**.
2. Organizer → **Distribute App** → App Store Connect.

Xcode Cloud peut faire la même chose à chaque tag Git. Le build apparaît dans App Store
Connect après un traitement de quelques minutes.

## 8. TestFlight

- **Testeurs internes** :
  - jusqu'à 100 personnes, membres de l'équipe App Store Connect ;
  - pas de revue.
- **Testeurs externes** :
  - jusqu'à 10 000 personnes, invitées par email ou par lien public ;
  - le premier build passe une revue bêta, d'un jour ou deux.
- **Expiration** : chaque build expire au bout de **90 jours**. TestFlight convient pour
  essayer, mais une diffusion durable demande un envoi au moins tous les trois mois.

Les testeurs installent l'app TestFlight, puis Wool Stock par l'invitation.

## 9. Choisir la diffusion

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

## 10. Fiche App Store et confidentialité

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
  - adresse email et nom collectés, liés à la personne, pour le fonctionnement de l'app ;
  - pas de pistage ;
  - images de la caméra non collectées.
- **Classification par âge** : remplir le questionnaire. L'app ne contient aucun contenu
  sensible.
- **Prix** : gratuit ; disponibilité : pays choisis.

## 11. Revue

À donner dans les **informations de revue** :

- **Compte de démonstration.** La connexion est obligatoire. Fournir une adresse autorisée,
  par exemple un compte gestionnaire « Revue Apple » créé avec `wool-stock users add`, et
  préciser qu'il n'y a pas de mot de passe. Comme pour Google, ce compte voit le vrai stock,
  sauf avec un serveur de démonstration.
- **Notes de revue.** L'app sert une famille ou une association. Les comptes sont créés par
  un administrateur. Le cas échéant, préciser la demande de distribution non répertoriée.
- **Suppression de compte** (guideline 5.1.1(v)). Toute app qui permet de créer un compte
  doit permettre de le supprimer depuis l'app. Ici, seuls les administrateurs créent des
  comptes : le même cas limite que pour Google. Un « Supprimer mon compte » dans les réglages
  règle la question des deux côtés.
- **Serveur joignable** pendant toute la revue (guideline 2.1).

La revue prend en général 24 à 48 heures. Un refus arrive avec la guideline en cause, et on
peut répondre depuis App Store Connect.

## 12. Mettre à jour

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
