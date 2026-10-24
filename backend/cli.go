package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"

	"leblanc.io/wool-stock/backend/internal/config"
	"leblanc.io/wool-stock/backend/internal/store"
)

const usage = `Usage: wool-stock [-c config.yaml] [command]

Commands:
  serve                              start the server (default)
  users add <email> <name> [role]    allow an email address; role is admin or manager (default)
  users list                         list the accounts

The first administrator is created with "users add"; administrators then add
the other accounts from the app.
`

// commandArgs renvoie les arguments après les flags lus par appconf (-c, --version).
func commandArgs(args []string) []string {
	fs := flag.NewFlagSet(appName, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	fs.String("c", "", "")
	fs.Bool("version", false, "")
	if err := fs.Parse(args); err != nil {
		return nil
	}
	return fs.Args()
}

// command exécute une commande d'administration en ligne de commande.
func command(cfg config.Config, args []string, out io.Writer) error {
	if args[0] == "help" || args[0] == "-h" {
		fmt.Fprint(out, usage)
		return nil
	}
	if args[0] != "users" || len(args) < 2 {
		fmt.Fprint(out, usage)
		return fmt.Errorf("unknown command %q", strings.Join(args, " "))
	}
	ctx := context.Background()
	st, err := openStore(ctx, cfg)
	if err != nil {
		return err
	}
	defer st.Close()

	switch args[1] {
	case "add":
		if len(args) < 4 || len(args) > 5 {
			return errors.New("usage: users add <email> <name> [admin|manager]")
		}
		role := store.RoleManager
		if len(args) == 5 {
			role = store.Role(args[4])
		}
		if role != store.RoleAdmin && role != store.RoleManager {
			return fmt.Errorf("role %q: admin or manager", role)
		}
		if !strings.Contains(args[2], "@") || strings.TrimSpace(args[3]) == "" {
			return errors.New("usage: users add <email> <name> [admin|manager]")
		}
		u, err := st.CreateUser(ctx, args[2], args[3], role, 0)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "%s (%s) can now sign in as %s\n", u.Name, u.Email, u.Role)
	case "list":
		users, err := st.Users(ctx)
		if err != nil {
			return err
		}
		for _, u := range users {
			fmt.Fprintf(out, "%-8s %-32s %s\n", u.Role, u.Email, u.Name)
		}
	default:
		fmt.Fprint(out, usage)
		return fmt.Errorf("unknown command %q", strings.Join(args, " "))
	}
	return nil
}
