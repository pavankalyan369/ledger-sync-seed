package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    @Test
    void parsesDebitEmail() {
        RawMessage message = new RawMessage(
                "email-001",
                "email",
                "alerts@icicibank.com",
                OffsetDateTime.parse("2026-08-10T22:15:00+05:30"),
                "dev-1",
                """
                Date: Mon, 10 Aug 2026 21:30:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 9075 has been debited with Rs.2,499.50.
                Merchant / Remarks: ZOMATO
                Transaction reference: 9703796037

                This is a system generated email.
                """
        );

        ParsedTxn result = parser.parse(message).orElseThrow();

        assertEquals("9075", result.accountLast4());
        assertEquals(
                OffsetDateTime.parse("2026-08-10T21:30:00+05:30"),
                result.occurredAt()
        );
        assertEquals(Direction.DEBIT, result.direction());
        assertEquals("2499.50", result.amount().toPlainString());
        assertEquals("ZOMATO", result.merchant());
        assertNull(result.statedBalance());
        assertEquals("email-001", result.sourceMessageId());
    }

    @Test
    void parsesCreditEmail() {
        RawMessage message = new RawMessage(
                "email-002",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"),
                "dev-1",
                """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                Transaction reference: 1597155421

                This is a system generated email.
                """
        );

        ParsedTxn result = parser.parse(message).orElseThrow();

        assertEquals("4821", result.accountLast4());
        assertEquals(
                OffsetDateTime.parse("2026-07-01T09:02:00+05:30"),
                result.occurredAt()
        );
        assertEquals(Direction.CREDIT, result.direction());
        assertEquals("45000.00", result.amount().toPlainString());
        assertEquals("SALARY CREDIT", result.merchant());
        assertNull(result.statedBalance());
        assertEquals("email-002", result.sourceMessageId());
    }
}