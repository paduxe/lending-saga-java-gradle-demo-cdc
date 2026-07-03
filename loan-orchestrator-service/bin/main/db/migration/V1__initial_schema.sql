CREATE TABLE IF NOT EXISTS loan_saga (
    saga_id VARCHAR(255) PRIMARY KEY,
    loan_application_id VARCHAR(255),
    current_step VARCHAR(255),
    saga_status VARCHAR(255),
    error_reason VARCHAR(255),
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    last_command_at TIMESTAMP WITHOUT TIME ZONE,
    deadline_at TIMESTAMP WITHOUT TIME ZONE,
    timeout_alerted BOOLEAN,
    version BIGINT
);

CREATE TABLE IF NOT EXISTS processed_message (
    message_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    topic_name VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    published_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP WITHOUT TIME ZONE,
    locked_at TIMESTAMP WITHOUT TIME ZONE,
    locked_by VARCHAR(255)
);
