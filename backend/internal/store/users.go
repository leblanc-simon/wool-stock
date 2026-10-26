package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"
)

// ErrEmailTaken signale une adresse déjà attribuée à un compte.
var ErrEmailTaken = errors.New("store: email already used")

// ErrLastAdmin refuse une modification qui laisserait le stock sans administrateur.
var ErrLastAdmin = errors.New("store: last admin")

// Role est le droit d'un compte.
type Role string

const (
	// RoleAdmin peut, en plus, créer des comptes.
	RoleAdmin Role = "admin"
	// RoleManager (gestionnaire) gère le stock : entrées, sorties, inventaires.
	RoleManager Role = "manager"
)

// User est un compte autorisé à se connecter.
type User struct {
	ID        int64     `json:"id"`
	Email     string    `json:"email"`
	Name      string    `json:"name"`
	Role      Role      `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

// IsAdmin dit si le compte peut gérer les autres comptes.
func (u User) IsAdmin() bool { return u.Role == RoleAdmin }

// Account est un compte avec son activité : sessions ouvertes et dernière visite.
type Account struct {
	User
	Sessions int
	// LastSeenAt est la dernière activité connue, à l'heure près ; nil sans session ouverte.
	LastSeenAt *time.Time
}

// lastSeenEvery espace les mises à jour de last_seen_at : une écriture par heure suffit.
const lastSeenEvery = time.Hour

const userColumns = `id, email, name, role, created_at`

// maxEmail est la longueur maximale d'une adresse (RFC 5321).
const maxEmail = 254

// NormalizeEmail met une adresse sous la forme enregistrée : sans espaces, en minuscules.
func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// ParseEmail valide une adresse simple (sans nom affiché) et la normalise ;
// ok vaut false si elle n'est pas valide.
func ParseEmail(raw string) (email string, ok bool) {
	email = NormalizeEmail(raw)
	if email == "" || len(email) > maxEmail {
		return "", false
	}
	addr, err := mail.ParseAddress(email)
	if err != nil || addr.Name != "" || addr.Address != email {
		return "", false
	}
	return email, true
}

// CreateUser crée un compte ; createdBy vaut 0 pour un compte créé en ligne de commande.
func (s *Store) CreateUser(ctx context.Context, email, name string, role Role, createdBy int64) (User, error) {
	var by any
	if createdBy > 0 {
		by = createdBy
	}
	res, err := s.db.ExecContext(ctx, `INSERT INTO users (email, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?)`,
		NormalizeEmail(email), strings.TrimSpace(name), role, s.timestamp(), by)
	if err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed") {
		return User{}, ErrEmailTaken
	}
	if err != nil {
		return User{}, fmt.Errorf("store: create user: %w", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		return User{}, err
	}
	return scanUser(s.db.QueryRowContext(ctx, `SELECT `+userColumns+` FROM users WHERE id = ?`, id))
}

// UserByEmail renvoie le compte d'une adresse, ou ErrNotFound.
func (s *Store) UserByEmail(ctx context.Context, email string) (User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, `SELECT `+userColumns+` FROM users WHERE email = ?`, NormalizeEmail(email)))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, fmt.Errorf("store: user: %w", err)
	}
	return u, nil
}

// Users renvoie tous les comptes, par nom.
func (s *Store) Users(ctx context.Context) ([]User, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+userColumns+` FROM users ORDER BY name COLLATE NOCASE, email`)
	if err != nil {
		return nil, fmt.Errorf("store: users: %w", err)
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, fmt.Errorf("store: users: %w", err)
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// User renvoie un compte, ou ErrNotFound.
func (s *Store) User(ctx context.Context, id int64) (User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, `SELECT `+userColumns+` FROM users WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, fmt.Errorf("store: user: %w", err)
	}
	return u, nil
}

// Accounts renvoie tous les comptes avec leur activité (sessions encore valides), par nom.
func (s *Store) Accounts(ctx context.Context) ([]Account, error) {
	join, args := "", []any{}
	if cutoff, ok := s.sessionCutoff(); ok {
		join, args = ` AND julianday(s.last_seen_at) >= julianday(?)`, append(args, cutoff)
	}
	rows, err := s.db.QueryContext(ctx, `SELECT u.id, u.email, u.name, u.role, u.created_at,
			COUNT(s.token_hash), MAX(s.last_seen_at)
		FROM users u LEFT JOIN sessions s ON s.user_id = u.id`+join+`
		GROUP BY u.id ORDER BY u.name COLLATE NOCASE, u.email`, args...)
	if err != nil {
		return nil, fmt.Errorf("store: accounts: %w", err)
	}
	defer rows.Close()
	accounts := []Account{}
	for rows.Next() {
		var (
			a         Account
			createdAt string
			lastSeen  sql.NullString
		)
		if err := rows.Scan(&a.ID, &a.Email, &a.Name, &a.Role, &createdAt, &a.Sessions, &lastSeen); err != nil {
			return nil, fmt.Errorf("store: accounts: %w", err)
		}
		a.CreatedAt = parseTime(createdAt)
		if lastSeen.Valid {
			at := parseTime(lastSeen.String)
			a.LastSeenAt = &at
		}
		accounts = append(accounts, a)
	}
	return accounts, rows.Err()
}

// UpdateUser change l'adresse, le nom et le droit d'un compte. ErrNotFound,
// ErrEmailTaken, ou ErrLastAdmin s'il était le dernier administrateur.
func (s *Store) UpdateUser(ctx context.Context, id int64, email, name string, role Role) (User, error) {
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if err := keepAnAdmin(ctx, tx, id, role == RoleAdmin); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE users SET email = ?, name = ?, role = ? WHERE id = ?`,
			NormalizeEmail(email), strings.TrimSpace(name), role, id)
		if err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed") {
			return ErrEmailTaken
		}
		return err
	})
	if errors.Is(err, ErrNotFound) || errors.Is(err, ErrEmailTaken) || errors.Is(err, ErrLastAdmin) {
		return User{}, err
	}
	if err != nil {
		return User{}, fmt.Errorf("store: update user: %w", err)
	}
	return s.User(ctx, id)
}

// DeleteUser retire l'accès d'un compte et ferme ses sessions ; son nom reste sur
// les mouvements et inventaires déjà saisis. ErrNotFound, ou ErrLastAdmin.
func (s *Store) DeleteUser(ctx context.Context, id int64) error {
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if err := keepAnAdmin(ctx, tx, id, false); err != nil {
			return err
		}
		// Les comptes qu'il a créés restent, sans créateur.
		if _, err := tx.ExecContext(ctx, `UPDATE users SET created_by = NULL WHERE created_by = ?`, id); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, id)
		return err
	})
	if errors.Is(err, ErrNotFound) || errors.Is(err, ErrLastAdmin) {
		return err
	}
	if err != nil {
		return fmt.Errorf("store: delete user: %w", err)
	}
	return nil
}

// keepAnAdmin vérifie que le compte existe et qu'il reste un administrateur une
// fois le compte modifié (stillAdmin) ou supprimé.
func keepAnAdmin(ctx context.Context, tx *sql.Tx, id int64, stillAdmin bool) error {
	var role Role
	err := tx.QueryRowContext(ctx, `SELECT role FROM users WHERE id = ?`, id).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if role != RoleAdmin || stillAdmin {
		return nil
	}
	var admins int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE role = ?`, RoleAdmin).Scan(&admins); err != nil {
		return err
	}
	if admins <= 1 {
		return ErrLastAdmin
	}
	return nil
}

// CloseUserSessions ferme toutes les sessions d'un compte (téléphone perdu, par
// exemple) et renvoie leur nombre ; l'accès reste autorisé.
func (s *Store) CloseUserSessions(ctx context.Context, userID int64) (int, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE user_id = ?`, userID)
	if err != nil {
		return 0, fmt.Errorf("store: close sessions: %w", err)
	}
	n, err := res.RowsAffected()
	return int(n), err
}

// sessionCutoff donne l'horodatage avant lequel une session inutilisée a expiré ; ok vaut
// false quand les sessions n'expirent pas.
func (s *Store) sessionCutoff() (cutoff string, ok bool) {
	if s.sessionIdle <= 0 {
		return "", false
	}
	return s.now().Add(-s.sessionIdle).UTC().Format(timestampLayout), true
}

// OpenSession crée une session pour un compte et renvoie son jeton, à présenter ensuite
// en « Authorization: Bearer ». La session reste ouverte tant qu'elle sert : elle expire
// après sessionIdle sans utilisation. Les sessions expirées sont effacées au passage.
func (s *Store) OpenSession(ctx context.Context, userID int64, device string) (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	now := s.timestamp()
	if _, err := s.db.ExecContext(ctx, `INSERT INTO sessions (token_hash, user_id, device, created_at, last_seen_at)
		VALUES (?, ?, ?, ?, ?)`, tokenHash(token), userID, nullable(device), now, now); err != nil {
		return "", fmt.Errorf("store: open session: %w", err)
	}
	if cutoff, ok := s.sessionCutoff(); ok {
		if _, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE julianday(last_seen_at) < julianday(?)`, cutoff); err != nil {
			return "", fmt.Errorf("store: purge sessions: %w", err)
		}
	}
	return token, nil
}

// SessionUser renvoie le compte d'un jeton de session, ou ErrNotFound si la session
// n'existe pas ou a expiré.
func (s *Store) SessionUser(ctx context.Context, token string) (User, error) {
	hash := tokenHash(token)
	query, args := `SELECT u.id, u.email, u.name, u.role, u.created_at
		FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?`, []any{hash}
	if cutoff, ok := s.sessionCutoff(); ok {
		query, args = query+` AND julianday(s.last_seen_at) >= julianday(?)`, append(args, cutoff)
	}
	u, err := scanUser(s.db.QueryRowContext(ctx, query, args...))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, fmt.Errorf("store: session: %w", err)
	}
	now := s.now()
	if _, err := s.db.ExecContext(ctx, `UPDATE sessions SET last_seen_at = ?
		WHERE token_hash = ? AND julianday(last_seen_at) < julianday(?)`,
		now.UTC().Format(timestampLayout), hash, now.Add(-lastSeenEvery).UTC().Format(timestampLayout)); err != nil {
		return User{}, fmt.Errorf("store: session: %w", err)
	}
	return u, nil
}

// CloseSession supprime une session (déconnexion).
func (s *Store) CloseSession(ctx context.Context, token string) error {
	if _, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = ?`, tokenHash(token)); err != nil {
		return fmt.Errorf("store: close session: %w", err)
	}
	return nil
}

func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func scanUser(row scanner) (User, error) {
	var (
		u         User
		createdAt string
	)
	if err := row.Scan(&u.ID, &u.Email, &u.Name, &u.Role, &createdAt); err != nil {
		return User{}, err
	}
	u.CreatedAt = parseTime(createdAt)
	return u, nil
}
