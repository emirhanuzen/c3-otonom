-- Kinds and sources of no-fly zones, and an optional validity window.
--   kind:   PERMANENT (imported, read-only through the API), TEMPORARY or MISSION (drawn by an operator)
--   source: OSM (imported from OpenStreetMap data) or OPERATOR
-- Existing zones were drawn by operators, so they become TEMPORARY / OPERATOR.
-- A zone is effective at time t when active AND (valid_from IS NULL OR valid_from <= t)
-- AND (valid_until IS NULL OR valid_until > t); only effective zones raise alarms.
ALTER TABLE geofence
    ADD COLUMN kind        varchar(16) NOT NULL DEFAULT 'TEMPORARY',
    ADD COLUMN source      varchar(16) NOT NULL DEFAULT 'OPERATOR',
    ADD COLUMN external_id text,
    ADD COLUMN valid_from  timestamptz,
    ADD COLUMN valid_until timestamptz;

-- The defaults only filled the existing rows; new rows always state both.
ALTER TABLE geofence
    ALTER COLUMN kind DROP DEFAULT,
    ALTER COLUMN source DROP DEFAULT;

ALTER TABLE geofence
    ADD CONSTRAINT ck_geofence_kind CHECK (kind IN ('PERMANENT', 'TEMPORARY', 'MISSION')),
    ADD CONSTRAINT ck_geofence_source CHECK (source IN ('OSM', 'OPERATOR')),
    ADD CONSTRAINT ck_geofence_validity CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from),
    -- Imports match their rows by (source, external_id). Operator zones have no
    -- external_id, and NULLs never collide in a unique constraint.
    ADD CONSTRAINT uq_geofence_source_external_id UNIQUE (source, external_id);
