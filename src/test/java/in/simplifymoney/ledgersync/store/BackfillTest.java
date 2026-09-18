package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackfillTest {

    @Test
    void backfillWritesAllUniqueSqlTransactionsToTarget() throws Exception {

        Path dbFile = Files.createTempFile("backfill-test-", "");

        try (SqlLedgerStore source = new SqlLedgerStore(dbFile)) {

            source.migrate(Path.of("db", "migration"));

            try (var connection = java.sql.DriverManager.getConnection(
                    "jdbc:h2:" + dbFile.toAbsolutePath() + ";MODE=PostgreSQL",
                    "sa",
                    "")) {

                try (var statement = connection.createStatement()) {
                    statement.execute("DELETE FROM ledger");
                }
            }

            NormalizedTxn first = transaction(
                    "4821",
                    "2026-08-01T10:00:00+05:30",
                    "100.00",
                    Category.SPEND,
                    "UPI/TEA",
                    "backfill-1"
            );

            NormalizedTxn second = transaction(
                    "4821",
                    "2026-08-02T11:00:00+05:30",
                    "200.00",
                    Category.SPEND,
                    "UPI/FOOD",
                    "backfill-2"
            );

            source.save(first);
            source.save(second);

            CapturingDocumentStore target =
                    new CapturingDocumentStore();

            Backfill backfill =
                    new Backfill(source, target);

            Backfill.Result result = backfill.run();

            assertEquals(2, result.read());
            assertEquals(2, result.written());
            assertEquals(0, result.skipped());
            assertEquals(2, target.saved.size());

        } finally {
            Files.deleteIfExists(dbFile);
        }
    }

    private static NormalizedTxn transaction(
            String account,
            String occurredAt,
            String amount,
            Category category,
            String merchant,
            String messageId) {

        return new NormalizedTxn(
                account,
                OffsetDateTime.parse(occurredAt),
                Direction.DEBIT,
                new BigDecimal(amount),
                category,
                merchant,
                List.of(messageId)
        );
    }

    private static final class CapturingDocumentStore
            implements DocumentStore {

        private final List<NormalizedTxn> saved =
                new ArrayList<>();

        @Override
        public void save(NormalizedTxn txn) {
            saved.add(txn);
        }

        @Override
        public List<NormalizedTxn> forAccountMonth(
                String accountLast4,
                YearMonth month) {
            return List.of();
        }

        @Override
        public Map<Category, BigDecimal> categoryTotals(
                String accountLast4) {
            return Map.of();
        }

        @Override
        public Optional<NormalizedTxn> byMessageId(
                String messageId) {
            return Optional.empty();
        }
    }
}