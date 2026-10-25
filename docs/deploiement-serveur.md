# Déployer le serveur

Procédure pour installer le serveur Wool Stock sur une machine Linux avec systemd. Elle vise
`https://stock-laine.carin.family`, l'adresse par défaut de l'app. Le serveur Go n'écoute
qu'en local. nginx reçoit le HTTPS et lui transmet les requêtes. Le service tourne sous un
utilisateur dédié, sans droits, et n'écrit que dans son propre dossier.

```
téléphones, navigateurs ──HTTPS──▶ nginx :443 ──HTTP──▶ wool-stock 127.0.0.1:8080 ──▶ /var/lib/wool-stock/wool-stock.db
```

Les fichiers à installer sont dans [`backend/deploy/`](../backend/deploy) :

| Fichier | Destination sur le serveur |
|---|---|
| `config.yaml` | `/etc/wool-stock/config.yaml` |
| `wool-stock.service` | `/etc/systemd/system/wool-stock.service` |
| `wool-stock-backup.service`, `wool-stock-backup.timer` | `/etc/systemd/system/` |
| `nginx.conf` | `/etc/nginx/sites-available/stock-laine.carin.family` |

Les commandes supposent une Debian ou une Ubuntu. Sur une autre distribution, seuls changent
les noms de paquets et l'emplacement des sites nginx.

## 1. Prérequis sur le serveur

- Le nom `stock-laine.carin.family` pointe vers le serveur. Il répond déjà : nginx y sert pour
  l'instant une page d'attente.
- Les ports 80 et 443 sont ouverts. Le port 8080 reste fermé, car le serveur n'écoute que sur
  `127.0.0.1`.
- Les paquets :

  ```bash
  sudo apt install nginx certbot sqlite3
  ```

  `sqlite3` sert aux sauvegardes. Le serveur lui-même n'a besoin d'aucune bibliothèque : le
  binaire est autonome, et SQLite y est compilé.

## 2. Construire le binaire (poste de développement)

Choisir l'architecture du serveur : `uname -m` y affiche `x86_64` (amd64) ou `aarch64` (arm64).

```bash
cd backend
git tag v0.8.0      # facultatif : sinon /api/health annonce la version « develop »
make test
make build-linux    # build/wool-stock-<version>-linux-amd64 et -linux-arm64
```

Copier le binaire et les fichiers de déploiement sur le serveur :

```bash
ssh serveur mkdir -p /tmp/wool-stock
scp build/wool-stock-v0.8.0-linux-amd64 deploy/* serveur:/tmp/wool-stock/
```

## 3. Utilisateur dédié et binaire

```bash
sudo useradd --system --home-dir /var/lib/wool-stock --no-create-home \
  --shell /usr/sbin/nologin wool-stock
sudo install -o root -g root -m 0755 /tmp/wool-stock/wool-stock-v0.8.0-linux-amd64 /usr/local/bin/wool-stock
/usr/local/bin/wool-stock --version
```

Le binaire appartient à root. Le service peut l'exécuter, mais pas le modifier.

## 4. Configuration

```bash
sudo install -d -o root -g wool-stock -m 0750 /etc/wool-stock
sudo install -o root -g wool-stock -m 0640 /tmp/wool-stock/config.yaml /etc/wool-stock/config.yaml
sudo -u wool-stock test -r /etc/wool-stock/config.yaml && echo lisible
```

Le dernier test compte. Si le service ne voit pas le fichier, par exemple parce que le
dossier n'est pas traversable, il démarre quand même, mais avec les valeurs par défaut. Il
écoute alors sur le port 8080 et ouvre une base dans un autre dossier.

Points de [`config.yaml`](../backend/deploy/config.yaml) à connaître :

- `web.host: 127.0.0.1` : seul nginx joint le serveur.
- `web.port: 8080` : à changer si le port est déjà pris, en même temps que l'upstream de
  `nginx.conf`.
- `web.trusted_proxies` : nginx, en local. Les limites de requêtes et de connexions portent
  ainsi sur l'IP réelle de chaque client. Sans ce réglage, tout le monde partagerait l'IP de
  nginx, et 5 connexions par minute bloqueraient tout le monde.
- `database.path: /var/lib/wool-stock/wool-stock.db` : le dossier que systemd crée pour le
  service.

Toute clé peut aussi venir d'une variable `WS_*` (`wool-stock --help` les liste), à poser
avec `Environment=` dans le service.

## 5. Base de données

Il y a deux cas : reprendre la base actuelle, ou partir d'une base vide.

**Reprendre la base actuelle**, celle du serveur de développement, avec ses références, son
stock, ses comptes et ses sessions. Sur le poste de développement, dans `backend/`, la
commande `.backup` prend une copie cohérente, même serveur démarré :

```bash
sqlite3 data/wool-stock.db ".backup /tmp/wool-stock.db"
scp /tmp/wool-stock.db serveur:/tmp/wool-stock/
```

Sur le serveur, avant le premier démarrage :

```bash
sudo install -d -o wool-stock -g wool-stock -m 0750 /var/lib/wool-stock
sudo install -o wool-stock -g wool-stock -m 0640 /tmp/wool-stock/wool-stock.db /var/lib/wool-stock/wool-stock.db
```

**Partir d'une base vide** : elle est créée au premier démarrage. Les migrations sont
embarquées et jouées à chaque démarrage. Il faut ensuite créer le premier administrateur
(étape 7).

## 6. Service systemd

```bash
sudo install -o root -g root -m 0644 /tmp/wool-stock/wool-stock.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now wool-stock
systemctl status wool-stock
curl -s http://127.0.0.1:8080/api/health    # {"status":"ok","version":"v0.8.0"}
```

Ce que fait [`wool-stock.service`](../backend/deploy/wool-stock.service) :

- **Utilisateur** : il tourne sous `wool-stock`, sans aucune capacité ni élévation possible
  (`NoNewPrivileges`).
- **Dossier de données** : `StateDirectory=wool-stock` crée `/var/lib/wool-stock`, le donne à
  l'utilisateur et en fait le seul dossier où le service peut écrire. Tout le reste est en
  lecture seule (`ProtectSystem=strict`), et `/home` est invisible.
- **Fuseau** : `TZ=Europe/Paris` fixe les jours des pages Entrées et Sorties, même si le
  serveur tourne en UTC.
- **Durcissement** : appels système limités à ceux d'un service ordinaire, réseau limité à
  IPv4, IPv6 et sockets Unix, mémoire jamais à la fois inscriptible et exécutable. Ces
  réglages ont été essayés avec le binaire et la base : connexion, pages, saisies et export
  fonctionnent. `systemd-analyze security wool-stock` donne le détail.
- **Redémarrage** : en cas de plantage, le service redémarre au bout de 5 s. À l'arrêt, il
  laisse 15 s aux requêtes en cours.

Les journaux vont dans journald :

```bash
journalctl -u wool-stock -f
```

## 7. Premier administrateur (base vide seulement)

La commande s'exécute sous l'utilisateur du service, avec la même configuration. Elle peut
tourner pendant que le service est démarré.

```bash
sudo -u wool-stock /usr/local/bin/wool-stock -c /etc/wool-stock/config.yaml \
  users add marie.dupont@exemple.fr "Marie Dupont" admin
```

Cet administrateur ajoute ensuite les autres comptes depuis `/users` ou depuis l'app.

## 8. nginx et certificat

Le domaine sert déjà du HTTPS valide, donc un certificat existe sans doute. Le chercher :

```bash
sudo certbot certificates
```

**Si un certificat couvre déjà le nom**, que ce soit `stock-laine.carin.family` ou un joker
`*.carin.family` :

1. Reporter ses chemins dans les deux lignes `ssl_certificate` de
   [`nginx.conf`](../backend/deploy/nginx.conf).
2. Vérifier comment il se renouvelle :

   ```bash
   sudo grep -E '^(authenticator|webroot_path)' /etc/letsencrypt/renewal/<nom>.conf
   ```

   - `nginx` : rien à faire.
   - `webroot` : la ligne `root` du bloc `/.well-known/acme-challenge/` doit désigner ce
     même `webroot_path`. Sinon, le renouvellement échouera une fois la page d'attente
     remplacée.

**Sinon**, obtenir un certificat avant d'activer le site. nginx tourne déjà, et le module
certbot pour nginx passe par sa configuration actuelle :

```bash
sudo apt install python3-certbot-nginx
sudo certbot certonly --nginx -d stock-laine.carin.family
```

Installer et activer le site, puis désactiver la page d'attente si un autre site de
`sites-enabled` déclare le même `server_name` :

```bash
sudo install -o root -g root -m 0644 /tmp/wool-stock/nginx.conf /etc/nginx/sites-available/stock-laine.carin.family
sudo ln -s /etc/nginx/sites-available/stock-laine.carin.family /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Le paquet certbot installe déjà un timer de renouvellement. Pour que nginx charge chaque
nouveau certificat, ajouter un hook de rechargement :

```bash
printf '#!/bin/sh\nsystemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo certbot renew --dry-run
```

Ce que la configuration transmet au serveur, et pourquoi :

| En-tête | Rôle |
|---|---|
| `Host $host` | Le serveur compare l'origine des formulaires au nom du site. Sans le vrai nom, les formulaires des pages web sont refusés en 403. |
| `X-Forwarded-For`, `X-Real-IP` | Les limites de requêtes et de connexions s'appliquent à l'IP réelle du client. |
| `X-Forwarded-Proto` | Le cookie de session des pages web est marqué `Secure`. |

nginx ajoute aussi HSTS. Les en-têtes de sécurité des pages (CSP, `nosniff`) viennent du
serveur.

nginx 1.25.1 et plus acceptent `http2 on;`. Debian 12 fournit nginx 1.22 : y remplacer
cette ligne par `listen 443 ssl http2;` (et `listen [::]:443 ssl http2;`).

## 9. Vérifications

```bash
curl -s https://stock-laine.carin.family/api/health
curl -sI http://stock-laine.carin.family/stock | head -1    # 301 vers https
curl -sI https://stock-laine.carin.family/stock | head -3   # 303 vers /login
```

Ensuite, dans un navigateur :

1. Se connecter sur `https://stock-laine.carin.family/login`.
2. Ouvrir le stock, saisir une entrée, puis télécharger l'export Excel.

Sur le téléphone :

1. Dans les réglages, l'adresse doit être `https://stock-laine.carin.family`. Une app qui
   gardait `http://127.0.0.1:8080` doit la changer : l'adresse enregistrée passe avant celle
   par défaut.
2. Se connecter, puis faire un essai d'entrée et d'export.

Diagnostics courants :

| Symptôme | Cause probable |
|---|---|
| 502 Bad Gateway | Le service est arrêté, ou le port diffère entre `config.yaml` et l'upstream nginx : `systemctl status wool-stock`. |
| Formulaires web refusés (403) | `Host` n'est pas transmis par nginx. |
| Tout le monde reçoit 429 à la connexion | `web.trusted_proxies` manque : tous les clients partagent l'IP de nginx. |
| Le service écoute sur `0.0.0.0` ou la base est vide | `config.yaml` est illisible pour l'utilisateur `wool-stock` (étape 4). |
| L'app release refuse le serveur | L'adresse est en `http://` : une version release exige HTTPS. |

## 10. Sauvegardes

La base est en mode WAL. Copier le seul fichier `.db` peut perdre les dernières saisies.
`sqlite3 .backup` prend une copie cohérente, serveur démarré. Le timer fourni sauvegarde
chaque nuit vers `/var/backups/wool-stock`, et garde 30 jours :

```bash
sudo install -d -o wool-stock -g wool-stock -m 0750 /var/backups/wool-stock
sudo install -o root -g root -m 0644 /tmp/wool-stock/wool-stock-backup.service /tmp/wool-stock/wool-stock-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now wool-stock-backup.timer
sudo systemctl start wool-stock-backup.service      # première sauvegarde, tout de suite
ls -l /var/backups/wool-stock
```

Ces sauvegardes restent sur la même machine. Les recopier ailleurs, par exemple avec restic,
borg ou rsync vers une autre machine.

**Restaurer** une sauvegarde :

```bash
sudo systemctl stop wool-stock
sudo rm -f /var/lib/wool-stock/wool-stock.db-wal /var/lib/wool-stock/wool-stock.db-shm
sudo install -o wool-stock -g wool-stock -m 0640 /var/backups/wool-stock/wool-stock-AAAA-MM-JJ.db /var/lib/wool-stock/wool-stock.db
sudo systemctl start wool-stock
```

## 11. Mettre à jour

Les migrations sont jouées au démarrage et ne reviennent pas en arrière. Il faut donc
sauvegarder avant chaque mise à jour.

```bash
# poste de développement
cd backend && make test && make build-linux
scp build/wool-stock-v0.9.0-linux-amd64 serveur:/tmp/wool-stock/

# serveur
sudo systemctl start wool-stock-backup.service
sudo cp /usr/local/bin/wool-stock /usr/local/bin/wool-stock.prev
sudo install -o root -g root -m 0755 /tmp/wool-stock/wool-stock-v0.9.0-linux-amd64 /usr/local/bin/wool-stock
sudo systemctl restart wool-stock
curl -s http://127.0.0.1:8080/api/health
```

**Revenir en arrière** : reprendre `wool-stock.prev`. Si la nouvelle version a migré la
base, restaurer aussi la sauvegarde du jour (étape 10).

## Avant d'ouvrir le serveur à Internet

La connexion se fait par **adresse email seule**, sans mot de passe ni code. Une fois le
serveur public, quiconque connaît une adresse autorisée peut se connecter, voir le stock et
saisir des mouvements.

La limite de 5 tentatives par minute et par IP freine qui essaierait des adresses à la
chaîne, mais pas quelqu'un qui connaît déjà une adresse. Deux mesures en réduisent l'effet :

- **Retirer les accès** dès qu'une personne ne doit plus entrer (`/users`).
- **Fermer les sessions** d'un téléphone perdu (`/users/{id}`).

Pour un serveur exposé durablement, la prochaine étape est une vérification par code envoyé
par email (voir « Limites » dans [`backend/README.md`](../backend/README.md)).
