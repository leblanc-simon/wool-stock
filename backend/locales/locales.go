// Package locales embarque les traductions du serveur (un fichier YAML par
// langue) : le binaire est autonome, aucun dossier à déployer à côté.
package locales

import "embed"

//go:embed *.yaml
var FS embed.FS
