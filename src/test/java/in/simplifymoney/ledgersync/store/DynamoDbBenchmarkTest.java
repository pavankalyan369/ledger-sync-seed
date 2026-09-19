package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamoDbBenchmarkTest {

    private static final String TABLE = "ledger";
    private static final String INDEX = "account-month-index";

    @Test
    void benchmarkAt100kTransactions() {

        try (DynamoDbClient dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        "dummy",
                                        "dummy"
                                )
                        )
                )
                .build()) {

            System.out.println();
            System.out.println("========================================");
            System.out.println("DYNAMODB 100K ACCESS-PATTERN BENCHMARK");
            System.out.println("========================================");

            /*
             * Use a dedicated benchmark account/month so the benchmark
             * does not interfere with corpus-A data.
             */
            String account = "9999";
            String month = "2026-07";

            /*
             * Insert 100,000 transaction documents.
             *
             * We use direct PutItem here rather than DocumentStore.save()
             * because this benchmark measures the required READ patterns,
             * not the write throughput of save().
             */
            System.out.println();
            System.out.println("Loading 100,000 synthetic transactions...");

            for (int i = 0; i < 100_000; i++) {

                int day = (i % 28) + 1;
                int hour = (i / 28) % 24;
                int minute = i % 60;
                int second = i % 60;

                String occurredAt = String.format(
                        "2026-07-%02dT%02d:%02d:%02d+05:30",
                        day,
                        hour,
                        minute,
                        second
                );

                String transactionKey =
                        "TXN#"
                                + account
                                + "#"
                                + occurredAt
                                + "#DEBIT#100.00";

                Map<String, AttributeValue> item =
                        new HashMap<>();

                item.put(
                        "pk",
                        AttributeValue.builder()
                                .s(transactionKey)
                                .build()
                );

                item.put(
                        "sk",
                        AttributeValue.builder()
                                .s("TXN")
                                .build()
                );

                item.put(
                        "accountLast4",
                        AttributeValue.builder()
                                .s(account)
                                .build()
                );

                item.put(
                        "occurredAt",
                        AttributeValue.builder()
                                .s(occurredAt)
                                .build()
                );

                item.put(
                        "direction",
                        AttributeValue.builder()
                                .s("DEBIT")
                                .build()
                );

                item.put(
                        "amount",
                        AttributeValue.builder()
                                .n("100.00")
                                .build()
                );

                item.put(
                        "category",
                        AttributeValue.builder()
                                .s("SPEND")
                                .build()
                );

                item.put(
                        "merchant",
                        AttributeValue.builder()
                                .s("BENCHMARK")
                                .build()
                );

                item.put(
                        "sourceMessageIds",
                        AttributeValue.builder()
                                .ss("benchmark-message-" + i)
                                .build()
                );

                item.put(
                        "transactionKey",
                        AttributeValue.builder()
                                .s(transactionKey)
                                .build()
                );

                item.put(
                        "gsi1pk",
                        AttributeValue.builder()
                                .s("ACCOUNT#" + account + "#MONTH#" + month)
                                .build()
                );

                item.put(
                        "gsi1sk",
                        AttributeValue.builder()
                                .s(occurredAt)
                                .build()
                );

                dynamo.putItem(
                        PutItemRequest.builder()
                                .tableName(TABLE)
                                .item(item)
                                .build()
                );
            }

            /*
             * TOTALS item for Q2.
             */
            dynamo.putItem(
                    PutItemRequest.builder()
                            .tableName(TABLE)
                            .item(
                                    Map.of(
                                            "pk",
                                            AttributeValue.builder()
                                                    .s("TOTALS#" + account)
                                                    .build(),

                                            "sk",
                                            AttributeValue.builder()
                                                    .s("TOTALS")
                                                    .build(),

                                            "SPEND",
                                            AttributeValue.builder()
                                                    .n("10000000.00")
                                                    .build(),

                                            "INCOME",
                                            AttributeValue.builder()
                                                    .n("0.00")
                                                    .build(),

                                            "MICRO",
                                            AttributeValue.builder()
                                                    .n("0.00")
                                                    .build(),

                                            "TRANSFER",
                                            AttributeValue.builder()
                                                    .n("0.00")
                                                    .build()
                                    )
                            )
                            .build()
            );

            /*
             * MESSAGE mapping for Q3.
             */
            String benchmarkMessageId =
                    "benchmark-message-50000";

            String benchmarkOccurredAt =
                    String.format(
                            "2026-07-%02dT%02d:%02d:%02d+05:30",
                            (50000 % 28) + 1,
                            (50000 / 28) % 24,
                            50000 % 60,
                            50000 % 60
                    );

            String benchmarkTransactionKey =
                    "TXN#"
                            + account
                            + "#"
                            + benchmarkOccurredAt
                            + "#DEBIT#100.00";

            dynamo.putItem(
                    PutItemRequest.builder()
                            .tableName(TABLE)
                            .item(
                                    Map.of(
                                            "pk",
                                            AttributeValue.builder()
                                                    .s("MESSAGE#" + benchmarkMessageId)
                                                    .build(),

                                            "sk",
                                            AttributeValue.builder()
                                                    .s("TXN")
                                                    .build(),

                                            "transactionKey",
                                            AttributeValue.builder()
                                                    .s(benchmarkTransactionKey)
                                                    .build()
                                    )
                            )
                            .build()
            );

            System.out.println("100,000 transactions loaded.");

            /*
             * -----------------------------------------
             * Q1
             * Account + month transaction query
             * -----------------------------------------
             */
            QueryResponse q1 =
                    dynamo.query(
                            QueryRequest.builder()
                                    .tableName(TABLE)
                                    .indexName(INDEX)
                                    .keyConditionExpression(
                                            "gsi1pk = :pk"
                                    )
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":pk",
                                                    AttributeValue.builder()
                                                            .s(
                                                                    "ACCOUNT#"
                                                                            + account
                                                                            + "#MONTH#"
                                                                            + month
                                                            )
                                                            .build()
                                            )
                                    )
                                    .build()
                    );

            System.out.println();
            System.out.println("Q1 - Account + Month");
            System.out.println(
                    "ScannedCount = " + q1.scannedCount()
            );
            System.out.println(
                    "Count        = " + q1.count()
            );

            /*
             * -----------------------------------------
             * Q2
             * Category totals
             * -----------------------------------------
             */
            var q2 =
                    dynamo.getItem(
                            GetItemRequest.builder()
                                    .tableName(TABLE)
                                    .key(
                                            Map.of(
                                                    "pk",
                                                    AttributeValue.builder()
                                                            .s("TOTALS#" + account)
                                                            .build(),

                                                    "sk",
                                                    AttributeValue.builder()
                                                            .s("TOTALS")
                                                            .build()
                                            )
                                    )
                                    .build()
                    );

            System.out.println();
            System.out.println("Q2 - Category Totals");
            System.out.println("Items read = 1");
            System.out.println(
                    "Items returned = "
                            + (q2.hasItem() ? 1 : 0)
            );

            /*
             * -----------------------------------------
             * Q3
             * Message ID -> Transaction
             * -----------------------------------------
             */

            var message =
                    dynamo.getItem(
                            GetItemRequest.builder()
                                    .tableName(TABLE)
                                    .key(
                                            Map.of(
                                                    "pk",
                                                    AttributeValue.builder()
                                                            .s(
                                                                    "MESSAGE#"
                                                                            + benchmarkMessageId
                                                            )
                                                            .build(),

                                                    "sk",
                                                    AttributeValue.builder()
                                                            .s("TXN")
                                                            .build()
                                            )
                                    )
                                    .build()
                    );

            int q3Reads = 1;
            int q3Transactions = 0;

            if (message.hasItem()) {

                String transactionKey =
                        message.item()
                                .get("transactionKey")
                                .s();

                var transaction =
                        dynamo.getItem(
                                GetItemRequest.builder()
                                        .tableName(TABLE)
                                        .key(
                                                Map.of(
                                                        "pk",
                                                        AttributeValue.builder()
                                                                .s(transactionKey)
                                                                .build(),

                                                        "sk",
                                                        AttributeValue.builder()
                                                                .s("TXN")
                                                                .build()
                                                )
                                        )
                                        .build()
                        );

                q3Reads++;

                if (transaction.hasItem()) {
                    q3Transactions = 1;
                }
            }

            System.out.println();
            System.out.println("Q3 - Message ID -> Transaction");
            System.out.println("Items read = " + q3Reads);
            System.out.println(
                    "Transactions returned = "
                            + q3Transactions
            );

            System.out.println();
            System.out.println("========================================");
            System.out.println("BENCHMARK COMPLETE");
            System.out.println("========================================");
        }
    }
}