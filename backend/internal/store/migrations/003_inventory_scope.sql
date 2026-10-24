-- Inventaire partiel (seuls les bains comptés changent) ou complet (à la clôture, les
-- bains non comptés sont remis à 0).
ALTER TABLE inventories ADD COLUMN scope TEXT NOT NULL DEFAULT 'partial' CHECK (scope IN ('partial', 'full'));

-- 1 : bain non compté d'un inventaire complet, remis à 0 à la clôture ; previous_quantity
-- est alors son stock à ce moment-là.
ALTER TABLE inventory_counts ADD COLUMN zeroed INTEGER NOT NULL DEFAULT 0;
