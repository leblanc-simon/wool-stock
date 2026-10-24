# Wool Stock — scan d'étiquettes de laine et stock partagé

- **Application Android** (Kotlin, Compose, CameraX, ML Kit embarqué, Room). Elle lit sur une
  étiquette de pelote le code-barre, le numéro de bain et, si possible, la marque et le coloris.
  L'analyse d'image reste sur le téléphone : aucune image n'est envoyée.
- **Serveur Go** (`backend/`, sur [`open-go-base`](https://github.com/leblanc-simon/open-go-base)).
  Il tient un stock global unique, sans emplacement, partagé par plusieurs téléphones, et le
  cache des codes-barres (EAN → marque / coloris). Il sert aussi des pages web, réservées aux
  comptes connectés : `/stock`, `/entries`, `/exits` et `/inventories` (avec le détail des
  anomalies de chaque inventaire). On y saisit aussi entrées, sorties et inventaires, comme
  depuis l'app, et on y exporte le stock et le suivi des distributions en Excel.

On se connecte à l'app comme aux pages web par adresse email seule, si un administrateur l'a
ajoutée ; le téléphone et le navigateur restent ensuite connectés. Les administrateurs ajoutent
les comptes depuis Réglages → Gérer les utilisateurs (le nom se préremplit d'après l'adresse),
ou depuis la page web `/users`, où ils modifient aussi un compte, ferment ses sessions ou lui
retirent l'accès. Le premier administrateur se crée sur le serveur (`wool-stock users add …`,
voir `backend/README.md`).

L'app a quatre onglets, sous un en-tête commun (logo, « Stock de laine », réglages) :
- **Stock** : le stock global par référence puis par bain. Le bouton « Sortir » d'un bain ouvre
  directement une sortie.
- **Entrée** : scan, vérification de l'étiquette, ajout au stock.
- **Sortie** : scan, choix du bain parmi ceux en stock, nom de la personne pour qui les pelotes
  sortent.
- **Inventaire** : on lance un inventaire, partiel ou complet, puis on scanne chaque bain et on
  saisit le nombre de pelotes comptées, et le stock prend ce nombre. À la fin d'un inventaire
  complet, les bains non comptés sont remis à 0. Si un bain déjà compté est rescanné, l'app
  demande s'il faut ajouter au comptage ou le remplacer. Plusieurs téléphones peuvent compter
  le même inventaire.

## Construire et tester

```bash
./gradlew :label-parser:test        # parseur + rapport de taux de réussite
./gradlew :app:installDebug         # installe sur le téléphone branché
cd backend && make test && make run # serveur (voir backend/README.md)
```

Par défaut, l'app vise le serveur `https://stock-laine.carin.family`. Pour un autre serveur,
saisir son adresse dans les réglages (roue dentée de l'onglet Stock), ou changer l'adresse par
défaut au build. En développement, avec le téléphone en USB : `adb reverse tcp:8080 tcp:8080`
et `./gradlew :app:installDebug -Pwoolstock.apiUrl=http://127.0.0.1:8080`.

Le rapport du parseur est écrit dans `label-parser/build/reports/label-parser/report.md`.

Prérequis : JDK 17 ou plus, SDK Android avec la plateforme 37 (AGP l'installe au premier build),
Go 1.26 ou plus pour le serveur.
Versions : Gradle 9.8, AGP 9.4, Kotlin 2.4, minSdk 26, compileSdk 37, targetSdk 36.

## Organisation

- `label-parser/` — module **Kotlin pur** (aucune dépendance Android) :
  - `LabelParser` : mots-clés multilingues (tolérants aux accents et à une erreur OCR),
    valeur sur la même ligne, sinon recherche spatiale à droite / en dessous, validation
    (poids, métrages, aiguilles, températures, EAN rejetés), correction prudente O→0, I/l→1, S→5, B→8
    avec conservation de la valeur brute, arbitrage bain / coloris.
  - `BrandMatcher` + `brands.json` (dans `src/main/resources/…/parser/`) : Levenshtein normalisé,
    bonus aux lignes en gros caractères. Ajouter une marque = ajouter une entrée JSON.
  - `ScanAccumulator` / `CandidateVoter` : vote sur les 15 dernières frames (≥ 3 votes et ≥ 50 %
    des frames qui proposent une valeur), capture automatique quand EAN et bain sont stables 1 s.
  - `LabelDump` : format JSON commun aux fixtures et à l'export du mode debug.
- `app/` :
  - `scan/` : CameraX + `MlKitAnalyzer`, overlay debug, export ; un même écran pour Entrée,
    Sortie et Inventaire.
  - `confirm/` : vérification avant envoi.
  - `stock/` : liste du stock global.
  - `inventory/` : inventaire en cours, bains comptés, clôture.
  - `login/` : connexion par adresse email.
  - `settings/` : compte connecté, déconnexion, adresse du serveur, gestion des comptes (admin).
  - `data/` : client HTTP de l'API (`api/`), réglages, et copie locale du cache des références
    dans Room.
- `backend/` — serveur Go : API JSON, SQLite, documentation de l'API dans `backend/README.md`.
- Logo : `backend/internal/web/static/logo.svg` est la source (favicon et en-tête des pages,
  avec une variante pour le mode sombre). L'icône de l'app en reprend les tracés dans
  `app/src/main/res/drawable/ic_launcher_foreground.xml` (et `ic_launcher_monochrome.xml` pour
  les icônes à thème) : modifier l'un, c'est reporter la modification dans les autres.

## Enrichir les fixtures avec de vraies étiquettes

1. Dans l'app, activer **Debug**, viser l'étiquette quelques secondes, puis **Exporter JSON**.
   Le fichier contient les 15 dernières frames OCR (texte brut, lignes, mots, boîtes) et la
   proposition de l'app dans `parsed`.
2. Déposer le fichier dans `label-parser/src/test/resources/labels/`.
3. Ajouter un bloc `expected` avec les vraies valeurs (une chaîne vide = « aucune valeur attendue »,
   un champ absent n'est pas vérifié). Sans `expected`, la fixture est listée « à annoter ».
4. `./gradlew :label-parser:test` : le test échoue si le bain est faux sur plus de 30 % des
   étiquettes réelles annotées.

## Choix et limites

- **Le serveur fait foi pour le stock.** Une entrée ou une sortie exige le serveur ; sans lui,
  l'app affiche le dernier stock chargé et un message d'erreur. Chaque saisie porte un
  identifiant (`request_id`) : la renvoyer après une coupure réseau ne la compte pas deux fois.
- **Cache des références.** Le téléphone en garde une copie dans Room, recopiée à chaque
  rafraîchissement du stock. Un EAN connu préremplit donc marque et coloris instantanément,
  même hors réseau. Une entrée met à jour le cache du serveur ; une sortie n'y touche jamais.
- **Autocomplétion.** Les champs Marque et Nom du coloris proposent les orthographes déjà
  connues : les marques du dictionnaire embarqué, puis celles du cache (donc aussi celles saisies
  sur d'autres téléphones), et les noms de coloris de la marque saisie en tête. La recherche
  ignore casse et accents et tolère une faute (`Suggestions` dans `label-parser`).
- **Migration depuis la première version.** Le stock local (tables `yarn_lot` et `stock_entry`)
  est supprimé par la migration Room 1 → 2. Les références locales sont transmises au serveur
  à la première synchronisation.
- **Réseau.** L'app demande désormais la permission `INTERNET`, ce qui permet aussi à la
  télémétrie de ML Kit de sortir ; les images, elles, ne quittent jamais le téléphone. La
  version debug accepte HTTP en clair. La version release exige HTTPS, sauf vers
  `127.0.0.1` (`adb reverse`).
- **Pas d'authentification.** Chacun se nomme dans les réglages, et ce nom accompagne ses
  mouvements. N'exposer le serveur que sur un réseau de confiance.
- Les coordonnées OCR sont celles de la `PreviewView` (`COORDINATE_SYSTEM_VIEW_REFERENCED`) :
  l'overlay les dessine telles quelles, et le parseur ne dépend que des distances relatives.
- La capture automatique est suspendue en mode debug, et une étiquette déjà capturée n'est
  recapturée automatiquement qu'après avoir quitté le champ (le bouton Capturer reste disponible).
- Sans mot-clé reconnu, aucun bain ni coloris n'est proposé : le cache EAN et la saisie prennent
  le relais. En sortie, un seul bain en stock pour la référence est présélectionné.
