# Distributed Banking System

This project is a distributed banking demo built with Spring Boot microservices. It demonstrates the flow of account management, money transfers, fraud detection, payment processing, and notification workflows using Kafka for async event-driven communication and Redis for rate limiting and fraud tracking.

The codebase is structured as a set of independent services that work together to simulate a real-world digital banking ecosystem.

## 1. Architecture Overview

### High-level components

- API Gateway: central entry point for external requests
- Account Service: manages customer accounts and balances
- Transaction Service: handles transfers and the future-step saga workflow
- Fraud Detection Service: validates suspicious transactions and triggers OTP verification
- Payment Service: creates Razorpay orders and handles payment webhook callbacks
- Notification Service: listens for key events and sends alerts
- Kafka: asynchronous event bus between services
- Redis: rate limiting at gateway and temporary fraud/verification tracking
- PostgreSQL: per-service persistence


## 2. Intra-Service Communication Model

This project mixes three communication patterns:

1. Synchronous HTTP calls
   - API Gateway -> services
   - Transaction Service -> Account Service via Feign client
   - Fraud Detection Service -> Account Service via Feign client

2. Event-driven messaging via Kafka
   - transaction.initiated
   - verification.required
   - transaction.otp.generated
   - transaction.completed
   - transaction.refunded
   - fraud.detected
   - fraud.check.clean
   - payment.completed
   - payment.failed

3. Temporary state in Redis
   - Rate limiting at gateway
   - OTP storage for transaction verification
   - Velocity and average-amount fraud counters

---


### How the flow works in practice

1. The client creates a transfer request through the API Gateway.
2. The Transaction Service creates a processing transaction record and emits an event for fraud review.
3. Fraud Detection Service evaluates the transfer using Redis counters and the current account balance.
4. If the transfer looks risky, verification is required and a one-time OTP is created.
5. The user verifies the OTP when required; otherwise the clean result continues automatically.
6. The Transaction Service debits the sender, credits the receiver, and marks the transaction completed.
7. Notification Service consumes these Kafka events and triggers alerts to the user.



### Next Updates
- Global Exception Handling and proper Response
- Locking Mechanisms to keep system fast as well as reliable
- security