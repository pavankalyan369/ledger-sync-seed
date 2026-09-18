package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final Pattern TRANSACTION = Pattern.compile(
            "Date:\\s*.*?\\n"
                    + "Subject:\\s*Transaction alert on your account.*?\\n\\s*"
                    + "Dear Customer,\\s*\\n\\s*"
                    + "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with "
                    + "(?<amount>(?:Rs\\.?|INR)\\s*[0-9,]+(?:\\.[0-9]{2})?)\\.\\s*\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DATE = Pattern.compile(
            "Date:\\s*[^,]+,\\s*"
                    + "(?<when>\\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher transaction = TRANSACTION.matcher(m.body());

        if (!transaction.find()) {
            return Optional.empty();
        }

        Matcher date = DATE.matcher(m.body());

        if (!date.find()) {
            return Optional.empty();
        }

        OffsetDateTime occurredAt;

        try {
            occurredAt = OffsetDateTime.parse(
                    date.group("when"),
                    java.time.format.DateTimeFormatter.ofPattern(
                            "dd MMM yyyy HH:mm:ss xx"
                    )
            );
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first(transaction.group("amount"));

        if (amount == null) {
            return Optional.empty();
        }

        Direction direction =
                "debited".equalsIgnoreCase(transaction.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(
                new ParsedTxn(
                        transaction.group("acct"),
                        occurredAt,
                        direction,
                        amount,
                        transaction.group("merchant").trim(),
                        null,
                        m.messageId()
                )
        );
    }
}