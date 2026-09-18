package in.simplifymoney.ledgersync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {

    @Test
    void usageListsAllSupportedCommands() {
        assertEquals(
                "usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check",
                App.usage()
        );
    }
}