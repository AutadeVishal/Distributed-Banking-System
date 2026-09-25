# Distributed Banking System

A distributed banking microservices demo built with Spring Boot, Kafka, Redis, and PostgreSQL. The project demonstrates an event-driven transaction workflow with fraud screening, OTP verification, and atomic settlement owned by the Account Service.





### Services
- API Gateway: entry point for external requests and routing
- Account Service: account creation, balance lookup, and atomic account-to-account transfer
- Transaction Service: transfer creation, status tracking, OTP verification, settlement flow
- Fraud Detection Service: suspicious transaction screening using account balance and Redis counters
- Payment Service: payment order and callback flow
- Notification Service: consumes events and simulates alerts for notifications

### Communication patterns
1. Synchronous HTTP
   - API Gateway to backend services
   - Transaction Service to Account Service via Feign
   - Fraud Detection Service to Account Service via Feign

2. Asynchronous Kafka events
   - `transaction.initiated`
   - `verification.required`
   - `transaction.otp.generated`
   - `fraud.check.clean`
   - `transaction.completed`
   - `transaction.failed`
   - `fraud.detected`
   - `payment.completed`
   - `payment.failed`

3. Temporary Redis state
   - OTP storage for verification
   - fraud-rate counters and average-amount tracking

## Transaction flow

1. Client calls transfer API through API Gateway.
2. Transaction Service saves a local transaction record with status `PROCESSING`.
3. Transaction Service emits `transaction.initiated`.
4. Fraud Detection Service consumes the event and checks:
   - transaction velocity
   - unusual amount threshold
   - account balance risk
5. If the transaction is suspicious, it emits `verification.required` and the Transaction Service generates a one-time OTP.
6. User verifies the OTP via the transaction service. Incorrect submissions are counted in Redis while the OTP is valid.
   - attempts 1 and 2 keep the transaction in `PENDING_VERIFICATION`
   - attempt 3 locks the sender account through Account Service, marks the transaction `FAILED`, and publishes both `fraud.detected` and `transaction.failed` with explicit reasons
   - an expired OTP does not count as a wrong attempt; the client can request a new OTP with `POST /api/v1/transaction/{transactionId}/request-otp`
7. On valid verification or a clean fraud result, the Transaction Service attempts settlement:
   - call one Account Service transfer operation
   - deduct sender and credit receiver inside one Account Service database transaction
   - mark transaction as `COMPLETED`
8. `transaction.completed` is published.
9. Notification Service consumes the event and simulates alerting.

## Dockerized environment

The repository includes a multi-stage `Dockerfile` and a `docker-compose.yml` that builds and runs the complete demo environment:

| Component | Container port | Purpose |
|---|---:|---|
| API Gateway | 8080 | External entry point |
| Account Service | 8081 | Account and atomic transfer operations |
| Transaction Service | 8082 | Transaction workflow and status |
| Payment Service | 8083 | Payment order and webhook flow |
| Fraud Detection Service | 8084 | Fraud checks and OTP decision |
| Notification Service | 8085 | Fake event-based notifications |
| PostgreSQL | 5432 | Per-service databases |
| Redis | 6379 | OTP and fraud counters |
| Kafka | 9092 internal / 9092 host | Event bus |

Run the environment from the repository root:

```bash
docker compose up --build
```

Docker containers use the `docker` Spring profile. Internal service-to-service communication uses Compose DNS names such as `postgres`, `redis`, `kafka`, and `account-service`; local development profiles use `localhost` equivalents. The Compose file was validated with `docker compose config`.

PostgreSQL initialization creates `accounts_db`, `transactions_db`, and `payments_db`. The named PostgreSQL volume preserves data between restarts. Use `docker compose down -v` only when you intentionally want to remove the demo database.

## Service health check by flow

### Flow status: demo-level working
| Service | Role in flow | Status | Notes                                                                  |
|---|---|---|------------------------------------------------------------------------|
| API Gateway | routing | Working as demo entrypoint | Simple routing layer; not a full API management layer                  |
| Account Service | balance and account lifecycle | Working | Core balance operations are implemented                                |
| Transaction Service | transaction orchestration | Working | Main flow is working                                                   |
| Fraud Detection Service | risk checks | Working | Risk logic exists and is event-driven                                  |
| Notification Service | alert simulation | Functional as stub | Real notification integration is not implemented; fake alerts are used |
| Payment Service | independent payment flow | Separate and usable | Not part of direct transfer saga but part of the overall fintech demo  |

## Audit checklist

| Requirement | Status | Notes |
|---|---|---|
| Account creation | Pass | Account entity and controller flow exist |
| Balance lookup | Pass | AccountService exposes current balance |
| Deduct credit operations | Pass | Basic operations are implemented |
| Transfer initiation | Pass | `TransactionService.initiateTransaction` exists |
| Idempotency for duplicate requests | Pass | Atomic claim via `idempotency_records` |
| Event-driven fraud review | Pass | Kafka listener and fraud workflow implemented |
| OTP verification flow | Pass | OTP generation and validation exist |
| OTP brute-force protection | Pass (demo) | Three incorrect submissions lock the sender account and fail the transaction |
| OTP renewal | Pass (demo) | A pending-verification transaction can request a new OTP after expiry |
| Success path settlement | Pass (demo) | Works in a happy path |
| Settlement failure path | Pass (demo) | Account Service rolls back the atomic transfer operation; Transaction Service marks the transaction failed |
| Explicit failure notification | Pass (demo) | `transaction.failed` carries transaction and reason details |
