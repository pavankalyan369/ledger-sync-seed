package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class IciciSmsParserTest {

    @Test
    void parsesNewIciciDebitFormat() {

        RawMessage message = new RawMessage(
                "m-test-icici-v2",
                "sms",
                "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"),
                "test-subject",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"
        );

        IciciSmsParser parser = new IciciSmsParser();

        var result = parser.parse(message);

        assertTrue(result.isPresent());

        ParsedTxn txn = result.get();

        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(0, txn.amount().compareTo(new BigDecimal("5.00")));
        assertEquals("UPI/BARBER", txn.merchant());
        assertEquals(
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"),
                txn.occurredAt()
        );
    }
}