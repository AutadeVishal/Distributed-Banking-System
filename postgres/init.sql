CREATE DATABASE accounts_db;
CREATE DATABASE transactions_db;
CREATE DATABASE payments_db;

\connect accounts_db

CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(255) PRIMARY KEY,
    account_number VARCHAR(255) NOT NULL UNIQUE,
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

CREATE TABLE IF NOT EXISTS transactions (
    id VARCHAR(255) PRIMARY KEY,
    sender_account_number VARCHAR(255) NOT NULL,
    receiver_account_number VARCHAR(255) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    description VARCHAR(255),
    transaction_type VARCHAR(32) NOT NULL CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'PAYMENT', 'TRANSFER')),
    transaction_status VARCHAR(32) NOT NULL CHECK (transaction_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PENDING_VERIFICATION', 'FAILED', 'FLAGGED')),
    failure_reason VARCHAR(255),
    reference_number VARCHAR(255),
    created_at TIMESTAMP(6),
    completed_at TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_transactions_sender_created
    ON transactions (sender_account_number, created_at DESC);

\connect payments_db

CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(255) PRIMARY KEY,
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
