// Formulaire d'entrée : un EAN déjà enregistré remplit la marque et le coloris, lus
// dans la liste des EAN connus (attributs data-). Sans ce script, le formulaire marche
// pareil : marque et coloris laissés vides gardent ceux enregistrés.
(() => {
  const form = document.querySelector("[data-reference-lookup]");
  if (!form) {
    return;
  }
  const ean = form.elements.ean;
  const status = form.querySelector("[data-reference-status]");
  // Clé : le nom de la donnée dans les attributs data- de la liste.
  const fields = {
    brand: form.elements.brand,
    colorCode: form.elements.color_code,
    colorName: form.elements.color_name,
  };

  const known = new Map();
  for (const option of document.getElementById(ean.getAttribute("list"))?.options ?? []) {
    known.set(option.value, option.dataset);
  }

  // Comme le serveur : sans espaces, un code UPC à 12 chiffres prend un 0 devant.
  const normalize = (raw) => {
    const digits = raw.replace(/\s+/g, "");
    return /^\d{12}$/.test(digits) ? "0" + digits : digits;
  };

  // Valeurs posées par le script : on les remplace ou on les efface quand l'EAN change,
  // jamais ce que la personne a saisi elle-même.
  const filled = new Map();

  const update = () => {
    const reference = known.get(normalize(ean.value));
    for (const [key, input] of Object.entries(fields)) {
      const ours = filled.has(input) && input.value === filled.get(input);
      if (reference && (input.value === "" || ours)) {
        input.value = reference[key] ?? "";
        filled.set(input, input.value);
      } else if (!reference && ours) {
        input.value = "";
        filled.delete(input);
      }
    }
    status.textContent = reference && filled.size > 0 ? form.dataset.knownLabel : "";
  };

  ean.addEventListener("input", update);
  // Le formulaire renvoyé après une erreur garde l'EAN saisi.
  update();
})();
