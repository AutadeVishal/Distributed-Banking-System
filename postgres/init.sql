CREATE DATABASE accounts_db;
CREATE DATABASE transactions_db;
CREATE DATABASE payments_db;

\connect accounts_db

CREATE SEQUENCE IF NOT EXISTS account_number_seq
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999999999
    NO CYCLE
    CACHE 100;

CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number VARCHAR(12) NOT NULL UNIQUE,
    account_holder_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(255) NOT NULL,
    account_type VARCHAR(32) NOT NULL CHECK (account_type IN ('SAVINGS', 'CURRENT', 'FIXED_DEPOSIT')),
    account_status VARCHAR(32) NOT NULL CHECK (account_status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    balance NUMERIC(15, 2) NOT NULL,
    daily_transaction_limit NUMERIC(15, 2) NOT NULL,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6)
);

\connect transactions_db

CREATE SEQUENCE IF NOT EXISTS transaction_reference_seq
    START WITH 100000
    INCREMENT BY 1
    MINVALUE 100000
    MAXVALUE 999999999999
    NO CYCLE;


CREATE TABLE IF NOT EXISTS transactions (
                                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

                                            sender_account_number VARCHAR(255) NOT NULL,

    receiver_account_number VARCHAR(255) NOT NULL,

    amount NUMERIC(15, 2) NOT NULL,

    description VARCHAR(255),

    transaction_type VARCHAR(32) NOT NULL
    CHECK (
              transaction_type IN (
              'DEPOSIT',
              'WITHDRAWAL',
              'PAYMENT',
              'TRANSFER'
                                  )
    ),

    transaction_status VARCHAR(32) NOT NULL
    CHECK (
              transaction_status IN (
              'PENDING',
              'PROCESSING',
              'COMPLETED',
              'PENDING_VERIFICATION',
              'FAILED',
              'FLAGGED'
                                    )
    ),

    failure_reason VARCHAR(255),

    reference_number VARCHAR(16) NOT NULL UNIQUE,

    created_at TIMESTAMP(6),

    completed_at TIMESTAMP(6)
    );


CREATE INDEX IF NOT EXISTS idx_transactions_sender_created
    ON transactions (
    sender_account_number,
    created_at DESC
    );


CREATE TABLE IF NOT EXISTS idempotency_records (
                                                   id BIGSERIAL PRIMARY KEY,

                                                   idempotency_key VARCHAR(255)
    NOT NULL UNIQUE,

    transaction_id BIGINT
    NOT NULL UNIQUE,

    created_at TIMESTAMP(6)
    );


\connect payments_db

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    razorpay_order_id VARCHAR(255),
    razorpay_payment_id VARCHAR(255),
    account_number VARCHAR(255) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    currency VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED', 'PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    description VARCHAR(255),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_payments_razorpay_order_id
    ON payments (razorpay_order_id);
