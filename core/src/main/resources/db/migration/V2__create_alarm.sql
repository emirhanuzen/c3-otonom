-- Increments by 50 to match allocationSize = 50 on AlarmEntity.id.
CREATE SEQUENCE alarm_id_seq START WITH 1 INCREMENT BY 50;

-- type and severity hold the names of the Java enums AlarmType and AlarmSeverity.
-- cleared_at is null while the alarm is active.
CREATE TABLE alarm (
    id              bigint       PRIMARY KEY,
    drone_id        integer      NOT NULL,
    type            varchar(32)  NOT NULL,
    severity        varchar(16)  NOT NULL,
    message         text         NOT NULL,
    raised_at       timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    cleared_at      timestamptz,
    acknowledged_at timestamptz,
    acknowledged_by text
);

ALTER SEQUENCE alarm_id_seq OWNED BY alarm.id;

CREATE INDEX idx_alarm_drone_cleared ON alarm (drone_id, cleared_at);
CREATE INDEX idx_alarm_raised_at ON alarm (raised_at);
