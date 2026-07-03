CREATE TABLE IF NOT EXISTS credit_result (
    id VARCHAR(255) PRIMARY KEY,
    saga_id VARCHAR(255),
    loan_application_id VARCHAR(255),
    customer_id VARCHAR(255),
    status VARCHAR(255),
    processed_at TIMESTAMP WITHOUT TIME ZONE
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
