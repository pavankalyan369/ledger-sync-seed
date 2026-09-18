package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;

import java.math.BigDecimal;
import java.util.*;


/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();

        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4)
                .toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) {
                    continue;
                }

                if (t.category() == Category.SPEND) {
                    spend = spend.add(t.amount());
                } else if (t.category() == Category.INCOME) {
                    income = income.add(t.amount());
                } else if (t.category() == Category.MICRO) {
                    microCount++;
                    microTotal = microTotal.add(t.amount());
                } else if (t.category() == Category.TRANSFER) {
                    if (t.direction() == Direction.DEBIT) {
                        transferredOut = transferredOut.add(t.amount());
                    } else {
                        transferredIn = transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());

            accounts.put(acct, a);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(
            List<ParsedTxn> transactions,
            Map<String, Object> expectedAccounts) {

        List<Object> discrepancies = new ArrayList<>();

        for (String acct : new TreeSet<>(expectedAccounts.keySet())) {

            @SuppressWarnings("unchecked")
            Map<String, Object> expected =
                    (Map<String, Object>) expectedAccounts.get(acct);

            BigDecimal openingBalance =
                    new BigDecimal(expected.get("opening_balance").toString());

            /*
             * For reconciliation, multiple messages can describe the same
             * real-world transaction.
             *
             * In particular, an SMS and an email can use different timezone
             * representations of the same transaction. When two parsed
             * transactions represent the same account, direction, amount,
             * merchant and instant, keep only one. Prefer the message that
             * contains a stated bank balance because it gives us the
             * information needed for reconciliation.
             */
            Map<String, ParsedTxn> uniqueTransactions = new LinkedHashMap<>();

            for (ParsedTxn t : transactions) {

                String key =
                        t.accountLast4()
                                + "|"
                                + t.occurredAt().toInstant()
                                + "|"
                                + t.direction()
                                + "|"
                                + t.amount()
                                + "|"
                                + t.merchant();

                ParsedTxn existing = uniqueTransactions.get(key);

                if (existing == null
                        || (existing.statedBalance() == null
                        && t.statedBalance() != null)) {

                    uniqueTransactions.put(key, t);
                }
            }

            List<ParsedTxn> accountTransactions =
                    uniqueTransactions.values()
                            .stream()
                            .filter(t -> acct.equals(t.accountLast4()))
                            .sorted(Comparator.comparing(ParsedTxn::occurredAt))
                            .toList();

            BigDecimal runningBalance = openingBalance;

            for (ParsedTxn t : accountTransactions) {

                BigDecimal expectedBalance;

                if (t.direction() == Direction.CREDIT) {
                    expectedBalance = runningBalance.add(t.amount());
                } else {
                    expectedBalance = runningBalance.subtract(t.amount());
                }

                if (t.statedBalance() != null
                        && expectedBalance.compareTo(t.statedBalance()) != 0) {

                    BigDecimal unexplained =
                            expectedBalance
                                    .subtract(t.statedBalance())
                                    .abs();

                    if (unexplained.compareTo(ZERO) > 0) {

                        Map<String, Object> discrepancy =
                                new LinkedHashMap<>();

                        discrepancy.put(
                                "account_last4",
                                acct
                        );

                        discrepancy.put(
                                "occurred_at",
                                t.occurredAt().toString()
                        );

                        discrepancy.put(
                                "amount",
                                unexplained.toPlainString()
                        );

                        discrepancy.put(
                                "note",
                                "Bank-stated balance does not match the "
                                        + "balance explained by the preceding "
                                        + "ledger transactions."
                        );

                        discrepancies.add(discrepancy);
                    }
                }

                /*
                 * If the bank supplied a balance, trust that observed balance
                 * as the new starting point for the next transaction.
                 *
                 * Otherwise continue from the balance calculated from the
                 * transaction itself.
                 */
                if (t.statedBalance() != null) {
                    runningBalance = t.statedBalance();
                } else {
                    runningBalance = expectedBalance;
                }
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);

        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
