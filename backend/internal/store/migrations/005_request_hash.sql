-- Empreinte du contenu d'un envoi rejouable : un request_id renvoyé avec un autre contenu
-- est refusé, au lieu de rendre l'envoi d'origine comme si c'était le même. NULL pour les
-- envois enregistrés avant cette colonne, qui se rejouent sans comparaison.
ALTER TABLE movements ADD COLUMN request_hash TEXT;
ALTER TABLE inventory_scans ADD COLUMN request_hash TEXT;
