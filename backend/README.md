# Wool Stock — serveur

Serveur Go qui tient un **stock global de pelotes** partagé par plusieurs téléphones :
cache des codes-barres (EAN → marque / coloris), quantité par bain, journal des entrées
et sorties, inventaires. Pas d'emplacement ni d'entrepôt : un seul stock. L'app et les pages
web se connectent par **adresse email seule** ; chaque mouvement porte le nom du compte connecté.

Il sert une API JSON (pour l'app) et des pages web, utilisables sans JavaScript, réservées aux comptes
connectés. Admin comme gestionnaire y consultent le stock et y saisissent entrées, sorties et
inventaires, comme depuis l'app :

| Page | Contenu |
|---|---|
| `/stock` | tous les bains, épuisés compris, groupés par marque puis coloris (n° et nom) ; les marques en colonnes (3 sur ordinateur, 1 sur mobile) |
| `/stock/export` | classeur Excel, lien « Exporter en Excel » des pages Stock et Sorties, dans la présentation du classeur tenu à la main : une feuille de stock (une table par marque : coloris, bain, n° de coloris, stock du jour ; chaque bain reçu, épuisés compris à 0) et une feuille de suivi des distributions (date, personne, pelotes, détail des bains), sur tout l'historique |
| `/entries` | formulaire d'entrée (EAN, bain, pelotes, et marque / coloris pour un EAN inconnu ; un EAN déjà enregistré les remplit, si JavaScript est actif) ; historique : quand, qui, quelle laine, quel bain, combien |
| `/exits` | formulaire de sortie (un bain en stock, pelotes, pour qui) ; historique groupé par destinataire, puis par jour, puis par bain, avec qui l'a saisi et à quelle heure |
| `/inventories` | lancer un inventaire, partiel ou complet, ou reprendre celui en cours ; liste des inventaires : bains comptés, nombre d'anomalies, écart net |
| `/inventories/{id}` | anomalies d'un inventaire : stock connu, quantité comptée, écart ; bains conformes repliés |
| `/inventories/{id}/count` | feuille de comptage de l'inventaire en cours : chaque bain par marque puis coloris, son stock connu, « Valider le stock » ou une autre quantité, ajouter ou remplacer un comptage déjà fait ; terminer l'inventaire |
| `/account` | le compte connecté (lien sur son nom, dans l'en-tête) : se déconnecter, supprimer son compte. C'est l'adresse à déclarer aux stores pour la suppression de compte |
| `/users` | admin seulement : comptes, droit, sessions ouvertes et dernière activité ; ajout d'un compte |
| `/users/{id}` | admin seulement : modifier l'adresse, le nom et le droit, fermer les sessions, retirer l'accès |

Les formulaires suivent les règles de l'API (`POST /api/movements`, `/api/inventories/…`) :
mêmes validations et mêmes erreurs traduites, affichées sous le champ fautif (stock
insuffisant, bain déjà compté…). L'auteur est le compte connecté. Chaque formulaire porte un
identifiant de saisie lié à son contenu, comme le `request_id` de l'app : un double clic
n'enregistre qu'une fois. Sur la page d'entrée, marque et coloris laissés vides gardent ce que
le cache connaît de l'EAN ; remplis, ils le remplacent. L'inventaire web est celui des
téléphones : un seul est ouvert à la fois, et un bain compté depuis l'un apparaît compté sur
l'autre. Les bains épuisés n'apparaissent dans la feuille de comptage qu'à la demande.

Entrées et sorties se filtrent par période (`?from=AAAA-MM-JJ&to=AAAA-MM-JJ`, les 30 derniers
jours par défaut). Les jours sont ceux du fuseau du serveur : positionner `TZ` (par exemple
`TZ=Europe/Paris`) s'il tourne en UTC. Les pages sont traduites (en, fr) selon la langue du
navigateur ou `?lang=`.

Bâti sur [`leblanc.io/open-go-base`](https://github.com/leblanc-simon/open-go-base) :
`appconf` (configuration, `--help`, `--version`), `logx`, `ratelimit` et `i18n` (messages
d'erreur traduits selon `Accept-Language`, locales embarquées). Base SQLite
(`modernc.org/sqlite`, sans CGO), migrations embarquées et jouées au démarrage.

## Lancer

```bash
make test                       # go test -race ./...
cp config.yaml.dist config.yaml # puis ajuster
make run                        # go run . -c config.yaml
make release                    # binaires autonomes dans build/
```

Le premier administrateur se crée en ligne de commande ; il gère ensuite les autres
comptes depuis les pages web (`/users`) ou, pour l'ajout, depuis l'app (Réglages → Gérer les
utilisateurs) :

```bash
wool-stock -c config.yaml users add marie.dupont@exemple.fr "Marie Dupont" admin
wool-stock -c config.yaml users list
```

Sans `config.yaml`, la configuration vient des variables `WS_*` (`wool-stock --help` les
liste). Par défaut, le serveur écoute sur `127.0.0.1:8080`. Pour que des téléphones du
réseau local le joignent, passer `web.host` à `0.0.0.0`. En développement, avec le
téléphone branché en USB, `adb reverse tcp:8080 tcp:8080` puis, dans les réglages de l'app,
l'adresse `http://127.0.0.1:8080` (l'app vise `https://stock-laine.carin.family` par défaut).

## Connexion et droits

- `POST /api/auth/login` avec `{"email": "…", "device": "…"}` : si l'adresse a un compte,
  la réponse donne un jeton (`{"token","user"}`), sinon `403 error.email_not_allowed`. Pas de
  mot de passe, et la session n'expire pas : le téléphone reste connecté.
- Toutes les autres routes `/api/…` (sauf `health`) demandent `Authorization: Bearer <jeton>` ;
  sans jeton valide : `401 error.unauthorized`. `GET /api/auth/me` renvoie le compte,
  `POST /api/auth/logout` ferme la session.
- Chacun peut supprimer son propre compte : `DELETE /api/auth/me` depuis l'app (Réglages →
  Supprimer mon compte), ou la page `/account`. Le compte et toutes ses sessions disparaissent ;
  son nom reste sur les mouvements et inventaires déjà saisis, qui font l'historique du stock.
  Le dernier admin est refusé (`409 error.last_admin`) : il nomme d'abord un autre admin.
- Deux droits : **admin** et **manager** (gestionnaire). Seul l'admin gère les comptes : par
  l'API (`GET /api/users`, `POST /api/users` avec `{"email","name","role"}`) et sur les pages
  `/users`. Sur ces pages, un nom laissé vide se déduit de l'adresse, comme dans l'app ; fermer
  les sessions d'un compte déconnecte ses appareils (téléphone perdu) sans retirer l'accès ;
  retirer l'accès supprime le compte et ses sessions, son nom restant sur les mouvements déjà
  saisis. Un admin ne change ni son propre droit ni son propre accès, et le dernier admin ne peut
  être ni rétrogradé ni retiré. Un gestionnaire reçoit
  `403 error.forbidden`.
- L'auteur des entrées, sorties, comptages et inventaires est le nom du compte connecté ; un
  champ `author` envoyé par le client est ignoré.
- Seule l'empreinte SHA-256 des jetons est enregistrée (table `sessions`).
- **Pages web** : sans session, toute page renvoie vers `/login?next=…`, qui y ramène après
  la connexion (seules les adresses de ce site sont suivies). La session vit dans le cookie
  `ws_session` (`HttpOnly`, `SameSite=Lax`, `Secure` quand la requête arrive en HTTPS ou avec
  `X-Forwarded-Proto: https`), renouvelé pour 400 jours à chaque page : le navigateur reste
  connecté tant qu'on revient dans l'année. « Se déconnecter » (en-tête, et page `/account`
  sur un téléphone) ne ferme que la session du navigateur. Les formulaires postés depuis un autre site sont refusés (`403`).
- **Limite des connexions** : `POST /api/auth/login` et `POST /login` partagent un limiteur
  dédié, `login.rate_limit` (`WS_LOGIN_RATE_LIMIT`, 5 tentatives par minute et par client par
  défaut), en plus de la limite générale `web.rate_limit`. C'est le composant `ratelimit`
  d'open-go-base (fenêtre d'une minute, IP réelle derrière les `trusted_proxies`). Au-delà :
  `429 error.rate_limit` avec `Retry-After` pour l'app, le formulaire et son message pour les
  pages. Sans mot de passe, c'est ce qui freine qui essaierait des adresses à la chaîne.

## API

Toutes les réponses sont en JSON. Un EAN accepte 8, 12, 13 ou 14 chiffres ; un UPC-A
(12 chiffres) est enregistré en EAN-13 (préfixe `0`), comme dans l'app.

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/api/health` | `{"status":"ok","version":"…"}` |
| `GET` | `/api/references` | tout le cache des codes-barres : `{"references":[…]}` |
| `GET` | `/api/references/{ean}` | une référence, ou 404 |
| `PUT` | `/api/references/{ean}` | crée ou remplace marque / coloris : `{"brand","color_code","color_name"}` |
| `GET` | `/api/stock` | bains en stock : `{"items":[…]}` ; `?ean=` pour une référence, `?include_empty=true` pour garder les bains épuisés |
| `GET` | `/api/export` | classeur Excel (`.xlsx`) du stock et du suivi des distributions, le même que `/stock/export` ; titres et nom de fichier dans la langue de `Accept-Language` |
| `POST` | `/api/movements` | entrée ou sortie de pelotes (voir ci-dessous) |
| `GET` | `/api/movements` | historique, du plus récent au plus ancien : `?ean=`, `?limit=` (1 à 500, 50 par défaut) |
| `GET` | `/api/inventories/current` | inventaire en cours et ses bains comptés, ou 404 |
| `POST` | `/api/inventories` | lance un inventaire : `{"author", "scope"}` facultatifs, `scope` = `partial` (défaut) ou `full` ; 409 si un autre est en cours |
| `GET` | `/api/inventories/{id}` | un inventaire et ses bains comptés |
| `POST` | `/api/inventories/{id}/counts` | comptage d'un bain (voir ci-dessous) |
| `POST` | `/api/inventories/{id}/close` | termine l'inventaire ; un inventaire complet remet à 0 les bains non comptés |

### Entrée / sortie

```json
{
  "request_id": "5f0c…",
  "kind": "in",
  "ean": "8435407948594",
  "dye_lot": "81784",
  "quantity": 3,
  "author": "Simon",
  "reference": { "brand": "Katia", "color_code": "1", "color_name": "blanc" }
}
```

- `kind` : `in` (entrée) ou `out` (sortie).
- `dye_lot` : chaîne vide pour une laine sans numéro de bain.
- `quantity` : 1 à 10 000.
- `recipient` : **obligatoire en sortie**, la personne pour qui les pelotes sortent.
- `reference` : en entrée seulement. Il remplace la marque et le coloris en cache (champ
  vide ou absent = effacé) ; sans `reference`, le cache est laissé tel quel. Une sortie ne
  touche jamais au cache.
- `request_id` : identifiant de la saisie, généré par le client (64 caractères au plus).
  S'il est renvoyé après une coupure réseau, le mouvement n'est pas compté deux fois : la
  réponse est alors `200` avec `"replayed": true`, au lieu de `201`.

La réponse renvoie le mouvement et le bain mis à jour : `{"movement":{…},"lot":{…},"replayed":false}`.
Une sortie supérieure au stock du bain est refusée en `409`.

### Inventaire

Un seul inventaire est ouvert à la fois, partagé par tous les téléphones. Il est :

- **partiel** : seuls les bains comptés changent ;
- **complet** : à la clôture, chaque bain encore en stock qui n'a pas été compté est remis
  à 0. L'écart est gardé comme un comptage marqué `zeroed`. Pendant l'inventaire, `uncounted`
  (dans les réponses de `/api/inventories/…`) donne le nombre de bains concernés. Une entrée
  saisie pendant un inventaire complet doit donc être comptée aussi, ou faite après la clôture.

Chaque comptage **fixe le stock** du bain à la quantité comptée :

```json
{ "request_id": "…", "ean": "8435407948594", "dye_lot": "81784", "quantity": 6, "mode": "new",
  "author": "Simon", "reference": { "brand": "Katia", "color_code": "1", "color_name": "blanc" } }
```

- `quantity` : obligatoire, de 0 (bain vide) à 10 000.
- `mode` : `new` par défaut. Si le bain a déjà été compté dans cet inventaire (par ce
  téléphone ou un autre), la réponse est `409 error.already_counted` avec `counted`, la
  quantité déjà comptée. Le client renvoie alors le même comptage avec `add` (ajouté au
  comptage et au stock : pelotes trouvées ailleurs) ou `set` (remplace le comptage et le stock).
- `reference` met à jour le cache, comme une entrée ; un bain inconnu est créé.
- La réponse donne le comptage (`quantity`, `previous_quantity` = stock avant le premier
  comptage) et le bain mis à jour : `{"count":{…},"lot":{…},"replayed":false}`.

Une **anomalie** est un bain dont la quantité comptée diffère du stock connu au moment de son
premier comptage (ou, pour un bain remis à 0, de son stock à la clôture).

### Erreurs

```json
{"error":{"code":"error.insufficient_stock","message":"Stock insuffisant pour ce bain : il reste 2 pelotes","field":"quantity","available":2}}
```

`code` est stable et sert aussi de clé de traduction : l'app le résout dans ses propres
ressources, et `message` est la version traduite par le serveur (`?lang=` puis
`Accept-Language`, langue par défaut sinon).

Les codes possibles :
- `validation.*` : `ean_required`, `ean_format`, `kind`, `quantity`, `count`, `mode`,
  `recipient_required`, `scope`, `too_long`, `limit`. Le champ fautif est indiqué dans `field`.
- `error.*` : `invalid_json`, `not_found`, `insufficient_stock` (409), `already_counted` (409),
  `inventory_open` (409), `inventory_closed` (409), `rate_limit` (429), `internal`.

## Organisation

- `main.go` : chargement de la config, câblage, arrêt propre sur SIGINT / SIGTERM.
- `internal/config` : composition des fragments `appconf` (préfixe `WS_`).
- `internal/store` : SQLite, et les règles de saisie communes à l'API et aux pages (EAN,
  longueurs, quantités). Tables : `yarn_references`, `lots` (quantité par bain, jamais
  négative), `movements` (journal des entrées / sorties), `inventories`, `inventory_counts`
  et `inventory_scans` (journal des comptages). Une seule connexion :
  la vérification du stock et la sortie sont atomiques.
- `internal/api` : handlers JSON, validation, erreurs traduites.
- `internal/web` : pages HTML de consultation et de saisie (gabarits et feuille de style
  embarqués, sans JavaScript).
- `locales` : traductions `en` / `fr` de l'API et des pages, embarquées dans le binaire.
- `deploy` : configuration de production, services systemd (serveur et sauvegarde
  quotidienne) et site nginx ; voir [`docs/deploiement-serveur.md`](../docs/deploiement-serveur.md).

## Limites

- **Connexion sans preuve d'identité** : connaître une adresse autorisée suffit pour se
  connecter. Une étape de vérification (code envoyé par email) serait le prochain pas pour un
  serveur exposé au-delà d'un réseau de confiance.
- L'app ne fait qu'ajouter des comptes ; la modification et le retrait se font sur les pages web.
- Le limiteur compte en mémoire : il repart de zéro au redémarrage et ne se partage pas entre
  plusieurs instances du serveur.
- Une version *release* de l'app exige HTTPS (reverse proxy avec TLS) ; seule la
  version *debug* accepte HTTP en clair sur le réseau local. Les pages web fonctionnent en
  HTTP, mais le cookie de session y circule alors en clair.
