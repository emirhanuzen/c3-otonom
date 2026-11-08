-- No-fly zones (geofences). The alarm engine checks every ONLINE drone against
-- the active ones on each evaluation (GeofenceChecker).

-- Increments by 50 to match allocationSize = 50 on GeofenceEntity.id.
CREATE SEQUENCE geofence_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE geofence (
    id         bigint                   PRIMARY KEY,
    name       text                     NOT NULL,
    area       geography(Polygon, 4326) NOT NULL,
    active     boolean                  NOT NULL DEFAULT true,
    created_at timestamptz              NOT NULL DEFAULT now()
);

ALTER SEQUENCE geofence_id_seq OWNED BY geofence.id;

CREATE INDEX idx_geofence_area ON geofence USING GIST (area);
