CREATE EXTENSION IF NOT EXISTS postgis;

-- Increments by 50 to match allocationSize = 50 on TelemetryRecord.id:
-- Hibernate reserves 50 ids per nextval() call.
CREATE SEQUENCE telemetry_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE telemetry (
    id              bigint                 PRIMARY KEY,
    drone_id        integer                NOT NULL,
    session_id      uuid                   NOT NULL,
    seq             bigint                 NOT NULL,
    ts              timestamptz            NOT NULL,
    received_at     timestamptz            NOT NULL,
    late            boolean                NOT NULL,
    position        geography(Point, 4326),
    alt_msl         double precision,
    alt_rel         double precision,
    heading         double precision,
    ground_speed    double precision,
    air_speed       double precision,
    climb_rate      double precision,
    battery_percent integer,
    battery_voltage double precision,
    gps_fix_type    integer,
    gps_satellites  integer,
    mode            text,
    armed           boolean,
    CONSTRAINT uq_telemetry_drone_session_seq UNIQUE (drone_id, session_id, seq)
);

ALTER SEQUENCE telemetry_id_seq OWNED BY telemetry.id;

CREATE INDEX idx_telemetry_position ON telemetry USING GIST (position);
CREATE INDEX idx_telemetry_drone_ts ON telemetry (drone_id, ts);
