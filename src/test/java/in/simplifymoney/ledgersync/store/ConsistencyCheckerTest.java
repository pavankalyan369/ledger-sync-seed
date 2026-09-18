package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyCheckerTest {

    @Test
    void detectsAlteredTransaction() throws Exception {

        Path db = Files.createTempDirectory("consistency-check");

        try (SqlLedgerStore sql = new SqlLedgerStore(db)) {

            sql.migrate(Path.of("db", "migration"));

            NormalizedTxn original = new NormalizedTxn(
                    "4821",
                    OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                    Direction.DEBIT,
                    new BigDecimal("500.00"),
                    Category.SPEND,
                    "TEST MERCHANT",
                    List.of("message-123")
            );

            sql.save(original);

            DocumentStore alteredDocuments =
                    new DocumentStore() {

                        @Override
                        public List<NormalizedTxn> forAccountMonth(
                                String accountLast4,
                                java.time.YearMonth month) {
                            return List.of();
                        }

                        @Override
                        public java.util.Map<Category, BigDecimal> categoryTotals(
                                String accountLast4) {
                            return java.util.Map.of();
                        }

                        @Override
                        public java.util.Optional<NormalizedTxn> byMessageId(
                                String messageId) {

                            return java.util.Optional.of(
                                    new NormalizedTxn(
                                            "4821",
                                            OffsetDateTime.parse(
                                                    "2026-07-01T10:00:00+05:30"
                                            ),
                                            Direction.DEBIT,

                                            // Deliberately altered amount
                                            new BigDecimal("700.00"),

                                            Category.SPEND,
                                            "TEST MERCHANT",
                                            List.of("message-123")
                                    )
                            );
                        }

                        @Override
                        public void save(NormalizedTxn txn) {
                            // Not needed for this test.
                        }
                    };

            ConsistencyChecker checker =
                    new ConsistencyChecker(sql, alteredDocuments);

            List<ConsistencyChecker.Divergence> divergences =
                    checker.check();

            assertFalse(divergences.isEmpty());

            assertTrue(
                    divergences.stream()
                            .anyMatch(d ->
                                    d.what().contains("amount")
                                            && d.inSql().equals("500.00")
                                            && d.inDocuments().equals("700.00")
                            )
            );
        }
    }
}