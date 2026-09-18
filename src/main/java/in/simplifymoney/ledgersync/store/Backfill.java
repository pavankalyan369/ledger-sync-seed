package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(
            SqlLedgerStore source,
            DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {

        List<NormalizedTxn> transactions =
                source.all();

        long read = transactions.size();
        long written = 0;
        long skipped = 0;

        /*
         * Keep track of transaction keys that we have already
         * encountered during this backfill run.
         *
         * The SQL database can contain duplicate rows because
         * the old store had no uniqueness guarantee.
         */
        Set<String> seen = new HashSet<>();

        for (NormalizedTxn txn : transactions) {

            String key = transactionKey(txn);

            if (!seen.add(key)) {
                skipped++;
                continue;
            }

            target.save(txn);
            written++;
        }

        return new Result(
                read,
                written,
                skipped
        );
    }

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

    public record Result(
            long read,
            long written,
            long skipped) {
    }
}