package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.DynamoDbDocumentStore;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    static String usage() {
        return "usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check";
    }

    public static void main(String[] args) throws Exception {


        if (args.length == 0) {
            System.err.println(usage());
            System.exit(2);
        }

        Files.createDirectories(DB.getParent());

        switch (args[0]) {

            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }

            case "ingest" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException(
                            "ingest needs a corpus"
                    );
                }

                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);

                    var stats = new IngestService(
                            new Parsers(),
                            store
                    ).ingestFile(Path.of(args[1]));

                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }

            case "backfill" -> {

                try (SqlLedgerStore store =
                             new SqlLedgerStore(DB);
                     DynamoDbDocumentStore documents =
                             new DynamoDbDocumentStore()) {

                    store.migrate(MIGRATIONS);

                    Backfill.Result result =
                            new Backfill(
                                    store,
                                    documents
                            ).run();

                    System.out.println(
                            "backfill read: " + result.read()
                    );

                    System.out.println(
                            "backfill written: " + result.written()
                    );

                    System.out.println(
                            "backfill skipped: " + result.skipped()
                    );
                }
            }

            case "check" -> {

                try (SqlLedgerStore store =
                             new SqlLedgerStore(DB);
                     DynamoDbDocumentStore documents =
                             new DynamoDbDocumentStore()) {

                    store.migrate(MIGRATIONS);

                    ConsistencyChecker checker =
                            new ConsistencyChecker(
                                    store,
                                    documents
                            );

                    var divergences = checker.check();

                    if (divergences.isEmpty()) {
                        System.out.println(
                                "consistency check: PASS"
                        );
                    } else {
                        System.out.println(
                                "consistency check: FAIL"
                        );

                        for (var divergence : divergences) {
                            System.out.println(
                                    "what: " + divergence.what()
                            );
                            System.out.println(
                                    "sql: " + divergence.inSql()
                            );
                            System.out.println(
                                    "documents: " + divergence.inDocuments()
                            );
                            System.out.println();
                        }
                    }
                }
            }

            case "report" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException(
                            "report needs a directory"
                    );
                }

                Path out = Path.of(args[1]);
                Files.createDirectories(out);

                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {

                    /*
                     * The SQL database contains the legacy V2 seed rows.
                     * Those rows are not part of the corpus-a report.
                     */
                    var ledger = store.all().stream()
                            .filter(t -> t.sourceMessageIds().stream()
                                    .noneMatch(id ->
                                            id.startsWith("m-legacy-")))
                            .toList();

                    /*
                     * ledger.json
                     */
                    Files.writeString(
                            out.resolve("ledger.json"),
                            Json.writePretty(
                                    Reports.ledgerDocument(ledger)
                            )
                    );

                    /*
                     * summary.json
                     */
                    Files.writeString(
                            out.resolve("summary.json"),
                            Json.writePretty(
                                    Reports.summary(ledger)
                            )
                    );

                    /*
                     * Read the expected checkpoint.
                     */
                    Map<String, Object> checkpoint =
                            Json.parseObject(
                                    Files.readString(
                                            Path.of(
                                                    "fixtures",
                                                    "corpus-a-totals.json"
                                            )
                                    )
                            );

                    @SuppressWarnings("unchecked")
                    Map<String, Object> expectedAccounts =
                            (Map<String, Object>) checkpoint.get("accounts");

                    /*
                     * Reconciliation needs the bank-stated balances
                     * contained in ParsedTxn.
                     *
                     * We parse the corpus again because NormalizedTxn is
                     * intentionally frozen and does not contain statedBalance.
                     */
                    Parsers parsers = new Parsers();

                    /*
                     * Deduplicate parsed transactions before reconciliation.
                     *
                     * The corpus contains replay messages. The same real
                     * transaction can therefore appear more than once.
                     */
                    Map<String, ParsedTxn> uniqueParsedTransactions =
                            new LinkedHashMap<>();

                    for (var message :
                            IngestService.readCorpus(
                                    Path.of("fixtures", "corpus-a.jsonl"))) {

                        var parsed = parsers.parse(message);

                        if (parsed.isEmpty()) {
                            continue;
                        }

                        ParsedTxn txn = parsed.get();

                        String key =
                                txn.accountLast4()
                                        + "|"
                                        + txn.occurredAt()
                                        + "|"
                                        + txn.direction()
                                        + "|"
                                        + txn.amount()
                                        + "|"
                                        + txn.merchant();

                        uniqueParsedTransactions.putIfAbsent(
                                key,
                                txn
                        );
                    }

                    var parsedTransactions =
                            uniqueParsedTransactions.values()
                                    .stream()
                                    .toList();

                    /*
                     * reconciliation.json
                     */
                    Files.writeString(
                            out.resolve("reconciliation.json"),
                            Json.writePretty(
                                    Reports.reconciliation(
                                            parsedTransactions,
                                            expectedAccounts
                                    )
                            )
                    );

                    System.out.println(
                            "wrote 3 files to " + out
                    );
                }
            }

            default -> {
                System.err.println(
                        "unknown command: " + args[0]
                );
                System.exit(2);
            }
        }
    }
}