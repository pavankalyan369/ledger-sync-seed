package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages, parses transactions,
 * removes duplicate notifications, classifies transactions,
 * identifies transfers between our accounts, and stores the ledger.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        int skipped = 0;

        /*
         * LinkedHashMap preserves the order in which unique transactions
         * first appear in the corpus.
         */
        Map<String, NormalizedTxn> uniqueTransactions = new LinkedHashMap<>();

        for (RawMessage m : messages) {

            Optional<ParsedTxn> p = parsers.parse(m);

/*            if (p.isEmpty()) {
                skipped++;
                continue;
            }*/

            if (p.isEmpty()) {
                skipped++;

                System.out.println(
                        "SKIPPED: "
                                + m.messageId()
                                + " | "
                                + m.body()
                );

                continue;
            }

            NormalizedTxn txn = toTransaction(p.get());

            String key = transactionKey(txn);

            //temp
            if (uniqueTransactions.containsKey(key)) {
                NormalizedTxn existing = uniqueTransactions.get(key);

                System.out.println(
                        "DUPLICATE: "
                                + existing.sourceMessageIds()
                                + " <-> "
                                + txn.sourceMessageIds()
                                + " | "
                                + txn.accountLast4()
                                + " | "
                                + txn.occurredAt()
                                + " | "
                                + txn.direction()
                                + " | "
                                + txn.amount()
                                + " | "
                                + txn.merchant()
                );
            }

            if (!uniqueTransactions.containsKey(key)) {
                uniqueTransactions.put(key, txn);
            } else {
                /*
                 * Multiple bank messages can describe the same transaction.
                 * Keep one ledger transaction and retain all source message IDs.
                 */
                NormalizedTxn existing = uniqueTransactions.get(key);

                List<String> sourceIds =
                        new ArrayList<>(existing.sourceMessageIds());

                sourceIds.addAll(txn.sourceMessageIds());
                sourceIds.sort(String::compareTo);

                NormalizedTxn merged = new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        sourceIds
                );

                uniqueTransactions.put(key, merged);
            }
        }

        /*
         * Transactions between our own accounts are transfers,
         * not spending or income.
         */
        uniqueTransactions = classifyTransfers(uniqueTransactions);

        for (NormalizedTxn txn : uniqueTransactions.values()) {
            store.save(txn);
        }

        return new Stats(
                messages.size(),
                uniqueTransactions.size(),
                skipped
        );
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();

        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line :
                    (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {

                Map<String, Object> o = Json.parseObject(line);

                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse(
                                (String) o.get("received_at")
                        ),
                        (String) o.get("device_id"),
                        (String) o.get("body")
                ));
            }
        }

        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {
        Category category = categoryFor(p);

        return new NormalizedTxn(
                p.accountLast4(),
                p.occurredAt(),
                p.direction(),
                p.amount(),
                category,
                p.merchant(),
                List.of(p.sourceMessageId())
        );
    }

    /**
     * Category rules:
     *
     * DEBIT + UPI + amount <= 100 -> MICRO
     * other DEBIT                 -> SPEND
     * CREDIT                      -> INCOME
     *
     * Transfers are classified separately after deduplication.
     */
    private Category categoryFor(ParsedTxn p) {

        if (p.direction() == Direction.DEBIT
                && p.merchant()
                .toUpperCase()
                .contains("UPI")
                && p.amount().compareTo(
                new BigDecimal("100.00")
        ) <= 0) {

            return Category.MICRO;
        }

        if (p.direction() == Direction.DEBIT) {
            return Category.SPEND;
        }

        return Category.INCOME;
    }

    /**
     * Finds matching debit/credit transactions between accounts 4821 and 9075
     * and marks both sides as TRANSFER.
     */
    private Map<String, NormalizedTxn> classifyTransfers(
            Map<String, NormalizedTxn> transactions) {

        List<NormalizedTxn> txns =
                new ArrayList<>(transactions.values());

        Set<String> transferKeys = new HashSet<>();

        for (NormalizedTxn a : txns) {

            if (!isOurAccount(a.accountLast4())) {
                continue;
            }

            for (NormalizedTxn b : txns) {

                if (a == b) {
                    continue;
                }

                if (!isOurAccount(b.accountLast4())) {
                    continue;
                }

                // The two transactions must belong to different accounts.
                if (a.accountLast4().equals(b.accountLast4())) {
                    continue;
                }

                // One side must be DEBIT and the other CREDIT.
                if (a.direction() == b.direction()) {
                    continue;
                }

                // Amount must be the same.
                if (a.amount().compareTo(b.amount()) != 0) {
                    continue;
                }

                // Transfer description must match.
                if (!a.merchant().equalsIgnoreCase(b.merchant())) {
                    continue;
                }

                /*
                 * The two sides of an internal transfer should occur
                 * within a few minutes of each other.
                 */
                long seconds = Math.abs(
                        Duration.between(
                                a.occurredAt(),
                                b.occurredAt()
                        ).getSeconds()
                );

                if (seconds > 5 * 60) {
                    continue;
                }

                transferKeys.add(transactionKey(a));
                transferKeys.add(transactionKey(b));
            }
        }

        Map<String, NormalizedTxn> result =
                new LinkedHashMap<>();

        for (Map.Entry<String, NormalizedTxn> entry
                : transactions.entrySet()) {

            NormalizedTxn txn = entry.getValue();

            if (transferKeys.contains(entry.getKey())) {

                txn = new NormalizedTxn(
                        txn.accountLast4(),
                        txn.occurredAt(),
                        txn.direction(),
                        txn.amount(),
                        Category.TRANSFER,
                        txn.merchant(),
                        txn.sourceMessageIds()
                );
            }

            result.put(entry.getKey(), txn);
        }

        return result;
    }

    private boolean isOurAccount(String account) {
        return account.equals("4821")
                || account.equals("9075");
    }

    /**
     * Deduplication key.
     *
     * toInstant() is important because the same transaction can be
     * represented using different timezone offsets.
     *
     * Example:
     * 2026-07-19T00:20+05:30
     * and
     * 2026-07-18T18:50Z
     *
     * represent the same instant.
     */
    private String transactionKey(NormalizedTxn txn) {
        return txn.accountLast4()
                + "|"
                + txn.occurredAt()
                + "|"
                + txn.direction()
                + "|"
                + txn.amount()
                + "|"
                + txn.merchant();
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped
    ) {
    }
}