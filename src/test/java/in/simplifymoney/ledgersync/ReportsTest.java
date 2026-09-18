package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.report.Reports;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportsTest {

    @Test
    void summarySeparatesSpendIncomeMicroAndTransfers() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", "2026-07-01T10:00+05:30",
                        Direction.DEBIT, "100.00", Category.SPEND, "GROCERY"),
                txn("4821", "2026-07-02T10:00+05:30",
                        Direction.CREDIT, "500.00", Category.INCOME, "SALARY"),
                txn("4821", "2026-07-03T10:00+05:30",
                        Direction.DEBIT, "25.00", Category.MICRO, "UPI/TEA"),
                txn("4821", "2026-07-04T10:00+05:30",
                        Direction.DEBIT, "200.00", Category.TRANSFER, "SELF"),
                txn("4821", "2026-07-05T10:00+05:30",
                        Direction.CREDIT, "150.00", Category.TRANSFER, "SELF")
        );

        Map<String, Object> report = Reports.summary(ledger);

        @SuppressWarnings("unchecked")
        Map<String, Object> account =
                (Map<String, Object>) ((Map<String, Object>) report.get("accounts"))
                        .get("4821");

        assertEquals("100.00", account.get("spend"));
        assertEquals("500.00", account.get("income"));
        assertEquals(1, account.get("micro_count"));
        assertEquals("25.00", account.get("micro_total"));
        assertEquals("200.00", account.get("transferred_out"));
        assertEquals("150.00", account.get("transferred_in"));
    }

    @Test
    void ledgerDocumentContainsTransactionFields() {
        NormalizedTxn txn = txn(
                "9075",
                "2026-07-10T12:30+05:30",
                Direction.CREDIT,
                "750.00",
                Category.INCOME,
                "SALARY"
        );

        Map<String, Object> document =
                Reports.ledgerDocument(List.of(txn));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>) document.get("transactions");

        assertEquals(1, transactions.size());

        Map<String, Object> row = transactions.get(0);

        assertEquals("9075", row.get("account_last4"));
        assertEquals("2026-07-10T12:30+05:30", row.get("occurred_at"));
        assertEquals("credit", row.get("direction"));
        assertEquals("750.00", row.get("amount"));
        assertEquals("INCOME", row.get("category"));
        assertEquals("SALARY", row.get("merchant"));
        assertEquals(List.of("test-message-1"), row.get("source_message_ids"));
    }

    private static NormalizedTxn txn(
            String account,
            String occurredAt,
            Direction direction,
            String amount,
            Category category,
            String merchant) {

        return new NormalizedTxn(
                account,
                OffsetDateTime.parse(occurredAt),
                direction,
                new BigDecimal(amount),
                category,
                merchant,
                List.of("test-message-1")
        );
    }
}