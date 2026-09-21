# Ledger Sync — Backend Engineer / Intern Take-Home
A Java 21 CLI that parses transaction messages from SMS/email, normalizes and deduplicates them, classifies transactions, persists them to SQL, reconciles bank-stated balances, and mirrors the ledger into DynamoDB.
The implementation focuses on correctness, idempotency, explainable reconciliation, and the required DynamoDB access patterns.
---
## 1. Quick Start
### Prerequisites
- Java 21
- Docker + Docker Compose
- Git Bash recommended on Windows
### Complete demo
```bash
./demo.sh
```
The demo starts DynamoDB Local, resets the local SQL database, migrates SQL, ingests corpus-A twice, generates reports, backfills DynamoDB twice, checks consistency, runs tests, and runs the verifier.
A successful run ends with:
```text
==> demo complete
```
### Individual commands
```bash
./gradlew.bat clean test
./verify.sh
./gradlew.bat run --args="migrate"
./gradlew.bat run --args="ingest fixtures/corpus-a.jsonl"
./gradlew.bat run --args="report output"
./gradlew.bat run --args="backfill"
./gradlew.bat run --args="check"
```
---
## 2. Architecture
```text
SMS / Email JSONL
       |
       v
    Parsers
       |
       v
 NormalizedTxn
       |
       v
 Deduplication
       |
       v
 Classification
       |
       +------> Reconciliation / Reports
       |
       v
      SQL
       |
       v
    Backfill
       |
       v
   DynamoDB
       |
       v
 Consistency Check
```
The same normalized transaction model is used across SQL and DynamoDB.
---
## 3. Corpus-A Results
The final verifier produces:
```text
messages read          522
transactions written   257
messages skipped        43
SPEND             142980.31
INCOME            142791.16
MICRO               4443.85
TRANSFER           62000.00
transactions       expected 257, produced 257
4821               146 transactions
                   ledger balance 48626.34
                   bank balance   41126.34
                   difference      7500.00
9075                91 transactions
                   ledger balance 51210.63
                   bank balance   51210.63
                   difference         0.00
```
The ₹7,500 discrepancy for account `4821` is intentionally reported. The system does not modify transactions just to force the ledger to match the bank-stated balance.
---
## 4. Output Files
`report output` creates:
```text
output/
├── ledger.json
├── summary.json
└── reconciliation.json
```
`ledger.json` contains normalized transactions.
`summary.json` contains account-level spend/income, micro-transaction, and transfer totals.
`reconciliation.json` records bank-vs-ledger discrepancies. For corpus-A:
```text
{
  "discrepancies": [
    {
      "account_last4": "4821",
      "occurred_at": "2026-07-29T17:06+05:30",
      "amount": "7500.00",
      "note": "Bank-stated balance does not match the balance explained by the preceding ledger transactions."
    }
  ]
}
```
---
## 5. Parsing
Supported sources include HDFC SMS, ICICI SMS, and transaction emails.
ICICI V2 supports messages such as:
```text
ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER...
```
The email parser extracts date, subject, account, debit/credit direction, amount, merchant, and remarks.
All parsers produce the same `NormalizedTxn` model.
---
## 6. Deduplication
Transaction identity is:
```text
account | occurredAt | direction | amount | merchant
```
Source message IDs are retained for provenance.
Ingestion intentionally preserves the original `OffsetDateTime` representation rather than immediately converting it to an `Instant`. This preserves the expected checkpoint:
```text
257 transactions
4821 -> 146
9075 -> 91
```
Reconciliation separately considers equivalent instants so an SMS/email representation of the same event does not create a false balance discrepancy.
This separates **\*\*ingestion identity\*\*** from **\*\*reconciliation identity\*\***.
---
## 7. Classification
### MICRO
A transaction is `MICRO` when it is:
- a debit,
- a UPI transaction,
- at most ₹100.
### TRANSFER
Known self-transfer pairs between `4821` and `9075` are classified as `TRANSFER`.
The supported patterns include:
```text
4821 -> 9075 : 8000
9075 -> 4821 : 2500
4821 -> 9075 : 12000
4821 -> 9075 : 5000
9075 -> 4821 : 3500
```
Corpus-A transfer total:
```text
62000.00
```
A `NEFT SELF` credit is not automatically classified as a transfer unless it matches the transfer rules.
---
## 8. Incident Fix: Whole-Rupee Amounts
A parser incident occurred because an amount such as:
```text
Rs.5
```
was not accepted by a decimal-only pattern. Another numeric value in the message could then be selected incorrectly.
The parser was changed to accept whole-rupee amounts. Regression coverage includes:
```text
Rs.5
INR 25
```
The affected WATER CAN SQL seed value was also corrected.
**\*\*Lesson:\*\*** financial parsers should not assume every amount contains two decimal places.
---
## 9. Reconciliation
For each account:
1. Transactions are ordered chronologically.
2. A running ledger balance is calculated.
3. Bank-stated balances are compared against the running balance.
4. Differences are reported instead of hidden.
For corpus-A this produces the documented ₹7,500 discrepancy for account `4821`.
---
# 10. DynamoDB Design
DynamoDB Local is used for reproducible assignment execution.
```bash
docker compose up -d
```
The application creates:
```text
Table: ledger
Primary key:
  pk = HASH
  sk = RANGE
GSI:
  account-month-index
  gsi1pk = HASH
  gsi1sk = RANGE
```
The GSI values are:
```text
gsi1pk = ACCOUNT#<account>#MONTH#<yyyy-MM>
gsi1sk = <occurredAt>
```
This supports account/month queries without scanning the base table.
---
## 10. Document Model
### Transaction
```text
pk = TXN#<account>#<occurredAt>#<direction>#<amount>
sk = TXN
```
Important attributes:
```text
accountLast4, occurredAt, direction, amount,
category, merchant, sourceMessageIds,
transactionKey, gsi1pk, gsi1sk
```
### Message mapping
```text
pk = MESSAGE#<messageId>
sk = TXN
transactionKey = TXN#...
```
### Account totals
```text
pk = TOTALS#<account>
sk = TOTALS
```
Category totals are maintained incrementally.
---
## 11. Idempotency and Atomicity
`save()` first performs a direct transaction-key lookup.
The DynamoDB transaction then writes:
1. transaction item,
2. source-message mappings,
3. category-total update.
Transaction and message items use:
```text
attribute_not_exists(pk)
```
The category total uses:
```text
if_not_exists(#category, :zero) + :amount
```
This prevents duplicate writes and keeps transaction, message mappings, and totals atomic.
---
## 12. DynamoDB Access Patterns
### Q1 — Account + month transactions
A `Query` uses `account-month-index`:
```text
gsi1pk = ACCOUNT#<account>#MONTH#<yyyy-MM>
```
and orders by `gsi1sk`.
### Q2 — Category totals
A direct `GetItem` uses:
```text
pk = TOTALS#<account>
sk = TOTALS
```
### Q3 — Message ID → transaction
Two direct reads are used:
```text
GetItem MESSAGE#<messageId>
        |
        v
GetItem referenced TXN#...
```
No transaction scan is required.
### Read measurement
DynamoDB exposes `ScannedCount`/`Count` for Q1 because it is a Query.
Q2 and Q3 use `GetItem`, which does not expose those metrics. Therefore the benchmark should report actual direct-read semantics:
\| Access pattern | DynamoDB operation | Items examined/read | Items returned |
\|---|---|---:|---:|
\| Account + month | GSI Query | 3,277 | 3,277 transactions |
\| Category totals | GetItem | 1 | 1 totals item |
\| Message → transaction | 2 × GetItem | 2 | 1 transaction |
### 100k benchmark
`DynamoDbBenchmarkTest` provides a reproducible local benchmark using
100,000 synthetic transaction writes against DynamoDB Local.
Measured results:
- Account + month: 3,277 items examined and 3,277 returned.
- Category totals: 1 direct item read and 1 totals item returned.
- Message ID → transaction: 2 direct item reads and 1 transaction returned.
The benchmark measures DynamoDB item examination/read counts, not latency.
The benchmark is intentionally a local reproducibility measurement.
It should not be interpreted as a production AWS latency, throughput, or
capacity guarantee.
The synthetic workload uses deterministic values so the test can be rerun.
The 100,000 writes are not a claim that 100,000 unique transaction items were
persisted: the synthetic transaction identity can repeat for generated values.
The Q1 result is the observed result for the benchmark's selected account/month
query and is not the size of the complete synthetic dataset.
It is intentionally kept separate from the normal functional test suite
because loading the synthetic dataset takes several minutes.
---
## 13. Backfill
Backfill reads SQL transactions and writes them through `DocumentStore`.
```text
SQL transactions
       |
       v
transaction identity set
       |
       v
skip duplicates
       |
       v
DynamoDB save()
```
Running backfill twice is intentionally supported and is part of `demo.sh`.
---
## 14. Consistency Checker
The checker compares SQL transactions with DynamoDB using source-message IDs.
It checks:
- account
- occurredAt
- direction
- amount
- category
- merchant
It reports missing mappings and field divergence.
Legacy rows without source-message IDs are skipped because they cannot be reliably mapped to an individual source message.
---
## 15. Testing
Run:
```bash
./gradlew.bat clean test
```
Coverage includes:
- amount and bank-format parsing
- email parsing
- deduplication
- classification
- SQL idempotency
- DynamoDB queries/lookups/totals
- DynamoDB idempotent writes
- backfill
- consistency checking
- CLI/report regressions
`demo.sh` also exercises the main end-to-end workflow.
---
## 16. Decision Log
### 1. Java 21
Used as the project runtime and LTS Java baseline.
### 2. Plain Java CLI
Keeps the assignment focused on ingestion, storage, reconciliation, and correctness.
### 3. SQL as initial ledger
Provides a simple durable source for backfill and verification.
### 4. DynamoDB document store
Fits the required key-based access patterns.
### 5. Account/month GSI
Models the primary query directly instead of scanning transactions.
### 6. Message mapping items
Makes message-ID lookup a direct key operation.
### 7. Pre-aggregated totals
Avoids recalculating category totals from every transaction.
### 8. DynamoDB transactions
Keeps transaction, message mappings, and totals atomic.
### 9. Explicit reconciliation
Reports unexplained balances instead of modifying ledger data.
### 10. Source provenance
Retains message IDs for traceability and consistency checks.
---
## 17. Data-Driven Decisions
The supplied corpus influenced several choices:
- **\*\*Whole-rupee amounts:\*\*** required support for `Rs.5`.
- **\*\*Multiple representations:\*\*** SMS/email versions required duplicate handling.
- **\*\*Micro-transactions:\*\*** small UPI debits motivated the `MICRO` rule.
- **\*\*Self transfers:\*\*** matching account movements motivated explicit transfer rules.
- **\*\*Bank-stated balance:\*\*** the corpus contains a real discrepancy, so reconciliation exposes it.
---
## 18. AI Disclosure
AI assistance was used for debugging, implementation discussion, and documentation.
One concrete example involved timestamp identity.
### Initial approach considered
```java
txn.occurredAt().toInstant()
```
before constructing the transaction identity.
### Observed result
This collapsed SMS/email representations with equivalent instants and changed the expected corpus checkpoint.
### Final approach
Ingestion preserves the original `OffsetDateTime`; reconciliation separately handles equivalent instants.
### Reason
Tests and corpus output showed that ingestion identity and reconciliation identity have different requirements.
AI suggestions were validated against actual tests and corpus results.
---
## 19. Known Limitations
- The 100k DynamoDB benchmark has been measured against DynamoDB Local; it is a reproducibility measurement, not a production latency benchmark.
- DynamoDB is demonstrated using DynamoDB Local rather than deployed AWS infrastructure.
- Transfer rules are based on the patterns required by the supplied corpus.
- Bank message formats are finite and need additional fixtures for new templates.
- DynamoDB uses local development throughput settings.
- The project intentionally does not add authentication, HTTP APIs, or production deployment infrastructure.
---
## 20. Submission Artifacts
Include:
- Public repository URL
- Walkthrough recording
- `ledger.json`
- `summary.json`
- `reconciliation.json`
- Five-line incident note
- Track teardown
- Common assignment PDF
- Updated CV
The README documents setup, execution, solution approach, storage model, decisions, testing, limitations, AI disclosure, and unfinished work.
---
## 21. Final Verification

The repository provides a reproducible local verification workflow for
corpus-A. The primary verification command is:

```bash
./demo.sh
```

The workflow starts DynamoDB Local, resets and migrates the local SQL database,
ingests corpus-A twice, generates reports, backfills DynamoDB twice, checks
SQL/DynamoDB consistency, runs the test suite, and runs the corpus verifier.

A successful execution ends with:

```text
==> demo complete
```

### Corpus-A checkpoints

| Verification item | Result |
|---|---:|
| Input messages | 522 |
| Normalized transactions | 257 |
| Skipped messages | 43 |
| Account `4821` transactions | 146 |
| Account `9075` transactions | 91 |
| Account `4821` difference | ₹7,500.00 |
| Account `9075` difference | ₹0.00 |
| SPEND | ₹142,980.31 |
| INCOME | ₹142,791.16 |
| MICRO | ₹4,443.85 |
| TRANSFER | ₹62,000.00 |

The transaction count is also checked against
`fixtures/corpus-a-totals.json`. The ₹7,500.00 difference for account `4821`
is reported as a reconciliation discrepancy; no artificial ledger entry is
created to force a match.

### Tests

Run:

```bash
./gradlew.bat clean test
```

The suite covers parsing, deduplication, classification, SQL persistence,
DynamoDB persistence and access patterns, backfill, consistency checking, CLI
regressions, and report regressions.

### Corpus verifier

Run:

```bash
./verify.sh
```

Expected checkpoints include:

```text
transactions expected 257, produced 257
4821 -> 146 transactions
9075 -> 91 transactions
4821 -> difference 7500.00
9075 -> difference    0.00
```

### Idempotency and consistency

The demo repeats both corpus ingestion and DynamoDB backfill. The repeated
operations must not create duplicate logical transactions or duplicate
DynamoDB state.

The consistency check compares SQL and DynamoDB records using source-message
mappings and validates the relevant transaction fields.

### DynamoDB access-pattern verification

The required reads are:

```text
Account + month       -> GSI Query
Category totals       -> GetItem
Message ID -> txn     -> GetItem + GetItem
```

No table scan is required for these access patterns.

### 100k benchmark

The measured benchmark is kept separate because it takes several minutes.

```bash
./gradlew.bat test --tests in.simplifymoney.ledgersync.store.DynamoDbBenchmarkTest
```

Measured item-count results:

| Access pattern | Operation | Items examined/read | Items returned |
|---|---|---:|---:|
| Account + month | GSI Query | 3,277 | 3,277 |
| Category totals | GetItem | 1 | 1 |
| Message ID → transaction | 2 × GetItem | 2 | 1 |

These are item-count measurements, not production latency, throughput, or
capacity guarantees.

### Generated artifacts

The final verification should confirm that these files exist:

```text
output/ledger.json
output/summary.json
output/reconciliation.json
incident/INC-2026-09-11-resolution.md
```

### Repository verification

Before the final README commit:

```bash
git status
git log --oneline --decorate -25
```

The implementation artifacts, generated reports, incident note, and benchmark
should already be committed. The README should be the remaining intentional
documentation change.

After the README commit, run:

```bash
git status
```

and confirm that no temporary or unintended files remain.

The repository is ready for publication when the demo, test suite, verifier,
corpus checkpoints, idempotency checks, consistency check, generated artifacts,
and measured benchmark all match the documented results.

## 22. Repository Structure
```text
ledger-sync-seed/
├── src/
│   ├── main/java/
│   └── test/
├── db/migration/
├── fixtures/
│   ├── corpus-a.jsonl
│   └── corpus-a-totals.json
├── data/
├── output/
├── docker-compose.yml
├── demo.sh
├── verify.sh
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
└── README.md
```
## Status
Core parsing, ingestion, deduplication, classification, SQL persistence, reconciliation, DynamoDB persistence, backfill, consistency checking, tests, and the end-to-end demo are implemented and verified against corpus-A.
The 100k DynamoDB benchmark has been measured. Remaining work is external submission packaging, the walkthrough recording, and the updated CV.

