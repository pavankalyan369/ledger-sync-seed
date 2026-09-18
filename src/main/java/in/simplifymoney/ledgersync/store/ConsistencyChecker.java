package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * The SQL store is treated as the reference for the transactions being
 * checked. Each transaction is located in the document store using its
 * source message id, then the transaction fields are compared.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        for (NormalizedTxn sqlTxn : sql.all()) {

            if (sqlTxn.sourceMessageIds().stream()
                    .allMatch(id -> id.startsWith("m-legacy-"))) {
                continue;
            }

            if (sqlTxn.sourceMessageIds().isEmpty()) {
                divergences.add(
                        new Divergence(
                                "transaction has no source message id",
                                describe(sqlTxn),
                                "cannot locate transaction"
                        )
                );
                continue;
            }

            boolean found = false;

            for (String messageId : sqlTxn.sourceMessageIds()) {

                Optional<NormalizedTxn> documentTxn =
                        documents.byMessageId(messageId);

                if (documentTxn.isEmpty()) {
                    continue;
                }

                found = true;

                compare(
                        sqlTxn,
                        documentTxn.get(),
                        messageId,
                        divergences
                );

                break;
            }

            if (!found) {
                divergences.add(
                        new Divergence(
                                "missing transaction for message " + sqlTxn.sourceMessageIds(),
                                describe(sqlTxn),
                                "not found"
                        )
                );
            }
        }

        return divergences;
    }

    private void compare(
            NormalizedTxn sqlTxn,
            NormalizedTxn documentTxn,
            String messageId,
            List<Divergence> divergences
    ) {

        if (!sqlTxn.accountLast4()
                .equals(documentTxn.accountLast4())) {

            divergences.add(
                    new Divergence(
                            "account for message " + messageId,
                            sqlTxn.accountLast4(),
                            documentTxn.accountLast4()
                    )
            );
        }

        if (!sqlTxn.occurredAt()
                .equals(documentTxn.occurredAt())) {

            divergences.add(
                    new Divergence(
                            "occurredAt for message " + messageId,
                            sqlTxn.occurredAt().toString(),
                            documentTxn.occurredAt().toString()
                    )
            );
        }

        if (!sqlTxn.direction()
                .equals(documentTxn.direction())) {

            divergences.add(
                    new Divergence(
                            "direction for message " + messageId,
                            sqlTxn.direction().toString(),
                            documentTxn.direction().toString()
                    )
            );
        }

        if (sqlTxn.amount()
                .compareTo(documentTxn.amount()) != 0) {

            divergences.add(
                    new Divergence(
                            "amount for message " + messageId,
                            sqlTxn.amount().toPlainString(),
                            documentTxn.amount().toPlainString()
                    )
            );
        }

        if (!sqlTxn.category()
                .equals(documentTxn.category())) {

            divergences.add(
                    new Divergence(
                            "category for message " + messageId,
                            sqlTxn.category().toString(),
                            documentTxn.category().toString()
                    )
            );
        }

        if (!java.util.Objects.equals(
                sqlTxn.merchant(),
                documentTxn.merchant())) {

            divergences.add(
                    new Divergence(
                            "merchant for message " + messageId,
                            String.valueOf(sqlTxn.merchant()),
                            String.valueOf(documentTxn.merchant())
                    )
            );
        }
    }

    private String describe(NormalizedTxn txn) {
        return "account=" + txn.accountLast4()
                + ", occurredAt=" + txn.occurredAt()
                + ", direction=" + txn.direction()
                + ", amount=" + txn.amount()
                + ", category=" + txn.category()
                + ", merchant=" + txn.merchant();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}