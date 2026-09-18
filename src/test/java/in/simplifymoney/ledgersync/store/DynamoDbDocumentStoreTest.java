package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbDocumentStoreTest {

    @BeforeEach
    void cleanDynamoDb() {
        try (DynamoDbDocumentStore store =
                     new DynamoDbDocumentStore()) {
            store.clearForTests();
        }
    }

    @Test
    void savesAndQueriesTransactionsForAccountMonth() {

        try (DynamoDbDocumentStore store =
                     new DynamoDbDocumentStore()) {

            NormalizedTxn older =
                    new NormalizedTxn(
                            "4821",
                            OffsetDateTime.parse(
                                    "2026-07-10T10:00:00+05:30"
                            ),
                            Direction.DEBIT,
                            new BigDecimal("100.00"),
                            Category.SPEND,
                            "TEST OLDER",
                            List.of("test-message-1")
                    );

            NormalizedTxn newer =
                    new NormalizedTxn(
                            "4821",
                            OffsetDateTime.parse(
                                    "2026-07-20T10:00:00+05:30"
                            ),
                            Direction.DEBIT,
                            new BigDecimal("200.00"),
                            Category.SPEND,
                            "TEST NEWER",
                            List.of("test-message-2")
                    );

            store.save(older);
            store.save(newer);

            List<NormalizedTxn> result =
                    store.forAccountMonth(
                            "4821",
                            YearMonth.of(2026, 7)
                    );

            assertEquals(2, result.size());

            assertEquals(
                    "2026-07-20T10:00+05:30",
                    result.get(0).occurredAt().toString()
            );

            assertEquals(
                    "2026-07-10T10:00+05:30",
                    result.get(1).occurredAt().toString()
            );
        }
    }

    @Test
    void findsTransactionByMessageId() {

        try (DynamoDbDocumentStore store =
                     new DynamoDbDocumentStore()) {

            NormalizedTxn transaction =
                    new NormalizedTxn(
                            "9075",
                            OffsetDateTime.parse(
                                    "2026-07-15T12:30:00+05:30"
                            ),
                            Direction.CREDIT,
                            new BigDecimal("500.00"),
                            Category.INCOME,
                            "TEST MERCHANT",
                            List.of(
                                    "message-a",
                                    "message-b"
                            )
                    );

            store.save(transaction);

            Optional<NormalizedTxn> result =
                    store.byMessageId("message-a");

            assertTrue(result.isPresent());

            NormalizedTxn found = result.get();

            assertEquals(
                    transaction.accountLast4(),
                    found.accountLast4()
            );

            assertEquals(
                    transaction.occurredAt(),
                    found.occurredAt()
            );

            assertEquals(
                    transaction.direction(),
                    found.direction()
            );

            assertEquals(
                    transaction.amount(),
                    found.amount()
            );

            assertEquals(
                    transaction.category(),
                    found.category()
            );

            assertEquals(
                    transaction.merchant(),
                    found.merchant()
            );

            assertEquals(
                    transaction.sourceMessageIds(),
                    found.sourceMessageIds()
            );

            assertTrue(
                    store.byMessageId("does-not-exist").isEmpty()
            );
        }
    }

    @Test
    void categoryTotalsAreMaintainedAndSaveIsIdempotent() {

        try (DynamoDbDocumentStore store =
                     new DynamoDbDocumentStore()) {

            NormalizedTxn spend =
                    new NormalizedTxn(
                            "4821",
                            OffsetDateTime.parse(
                                    "2026-08-01T10:00:00+05:30"
                            ),
                            Direction.DEBIT,
                            new BigDecimal("100.00"),
                            Category.SPEND,
                            "TEST SPEND",
                            List.of("total-message-1")
                    );

            NormalizedTxn income =
                    new NormalizedTxn(
                            "4821",
                            OffsetDateTime.parse(
                                    "2026-08-02T10:00:00+05:30"
                            ),
                            Direction.CREDIT,
                            new BigDecimal("500.00"),
                            Category.INCOME,
                            "TEST INCOME",
                            List.of("total-message-2")
                    );

            store.save(spend);
            store.save(income);

            Map<Category, BigDecimal> totals =
                    store.categoryTotals("4821");

            assertEquals(
                    new BigDecimal("100.00"),
                    totals.get(Category.SPEND)
            );

            assertEquals(
                    new BigDecimal("500.00"),
                    totals.get(Category.INCOME)
            );

            assertEquals(
                    new BigDecimal("0.00"),
                    totals.get(Category.MICRO)
            );

            assertEquals(
                    new BigDecimal("0.00"),
                    totals.get(Category.TRANSFER)
            );

            /*
             * Save the same transaction again.
             *
             * The totals must NOT increase.
             */
            store.save(spend);

            Map<Category, BigDecimal> totalsAfterRetry =
                    store.categoryTotals("4821");

            assertEquals(
                    new BigDecimal("100.00"),
                    totalsAfterRetry.get(Category.SPEND)
            );

            assertEquals(
                    new BigDecimal("500.00"),
                    totalsAfterRetry.get(Category.INCOME)
            );
        }
    }
}