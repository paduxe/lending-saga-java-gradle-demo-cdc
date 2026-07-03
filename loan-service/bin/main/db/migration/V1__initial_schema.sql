CREATE TABLE IF NOT EXISTS loan_application (
    loan_application_id VARCHAR(255) PRIMARY KEY,
    saga_id VARCHAR(255),
    customer_id VARCHAR(255),
    amount DECIMAL(19, 2),
    product_type VARCHAR(255),
    status VARCHAR(255),
    simulate_kyc_fail BOOLEAN,
    simulate_credit_reject BOOLEAN,
    simulate_disbursement_fail BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE
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
