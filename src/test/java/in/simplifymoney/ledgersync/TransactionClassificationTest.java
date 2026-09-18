package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import org.junit.jupiter.api.Test;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionClassificationTest {

    @Test
    void classifiesSmallUpiDebitAsMicro() throws Exception {
        String json = """
                {"message_id":"test-micro","channel":"sms","sender":"AD-HDFCBK-S","received_at":"2026-07-05T09:06:00+05:30","device_id":"dev-test","body":"Rs.35 debited from a/c **4821 on 05-07-26 at 09:06 to UPI/TEA STALL. Avl Bal: Rs.89,305.60. Not you? Call 18002586161"}
                """;

        List<NormalizedTxn> saved = ingest(json);

        assertEquals(1, saved.size());
        assertEquals(Category.MICRO, saved.get(0).category());
    }

    @Test
    void classifiesDebitAboveMicroThresholdAsSpend() throws Exception {
        String json = """
                {"message_id":"test-spend","channel":"sms","sender":"AD-HDFCBK-S","received_at":"2026-07-03T10:41:00+05:30","device_id":"dev-test","body":"Rs.150.00 debited from a/c **4821 on 03-07-26 at 10:41 to UPI/PARKING. Avl Bal: Rs.91,437.61. Not you? Call 18002586161"}
                """;

        List<NormalizedTxn> saved = ingest(json);

        assertEquals(1, saved.size());
        assertEquals(Category.SPEND, saved.get(0).category());
    }

    @Test
    void classifiesCreditAsIncome() throws Exception {
        String json = """
                {"message_id":"test-income","channel":"sms","sender":"AD-HDFCBK-S","received_at":"2026-07-01T09:03:00+05:30","device_id":"dev-test","body":"Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40"}
                """;

        List<NormalizedTxn> saved = ingest(json);

        assertEquals(1, saved.size());
        assertEquals(Category.INCOME, saved.get(0).category());
    }

    @Test
    void classifiesMatchingInternalTransferAsTransfer() throws Exception {
        String json = """
                {"message_id":"test-transfer-debit","channel":"sms","sender":"AD-HDFCBK-S","received_at":"2026-07-21T13:14:00+05:30","device_id":"dev-test","body":"Rs 12,000.00 debited from a/c **4821 on 21-07-26 at 13:14 to IMPS/P2A/PARAG KAPOOR. Avl Bal: Rs.59,564.37. Not you? Call 18002586161"}
                {"message_id":"test-transfer-credit","channel":"sms","sender":"VM-ICICIB-T","received_at":"2026-07-21T13:16:00+05:30","device_id":"dev-test","body":"Dear Customer, Acct XX9075 is credited with INR 12000.00 on 21/07/2026 13:16. Info: IMPS/P2A/PARAG KAPOOR. Avl Bal Rs.51,838.14 -ICICI Bank"}
                """;

        List<NormalizedTxn> saved = ingest(json);

        assertEquals(2, saved.size());
        assertEquals(Category.TRANSFER, saved.get(0).category());
        assertEquals(Category.TRANSFER, saved.get(1).category());
    }

    private List<NormalizedTxn> ingest(String json) throws Exception {
        Path file = Files.createTempFile("classification-", ".jsonl");
        try {
            Files.writeString(file, json);

            CapturingLedgerStore store = new CapturingLedgerStore();
            IngestService service = new IngestService(new Parsers(), store);

            service.ingestFile(file);

            return List.copyOf(store.transactions);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static final class CapturingLedgerStore implements LedgerStore {

        private final List<NormalizedTxn> transactions = new ArrayList<>();

        @Override
        public void save(NormalizedTxn txn) {
            transactions.add(txn);
        }

        @Override
        public List<NormalizedTxn> all() {
            return List.copyOf(transactions);
        }

        @Override
        public long count() {
            return transactions.size();
        }
    }
}