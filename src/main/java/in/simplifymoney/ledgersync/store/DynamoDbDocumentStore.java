package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DynamoDbDocumentStore
        implements DocumentStore, AutoCloseable {

    private static final String TABLE_NAME = "ledger";

    private final DynamoDbClient dynamoDb;

    public DynamoDbDocumentStore() {
        this.dynamoDb = DynamoDbClient.builder()
                .endpointOverride(
                        URI.create("http://localhost:8000")
                )
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        "dummy",
                                        "dummy"
                                )
                        )
                )
                .build();

        createTableIfNeeded();
    }

    private void createTableIfNeeded() {

        try {
            dynamoDb.createTable(
                    CreateTableRequest.builder()
                            .tableName(TABLE_NAME)

                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("pk")
                                            .keyType(KeyType.HASH)
                                            .build(),

                                    KeySchemaElement.builder()
                                            .attributeName("sk")
                                            .keyType(KeyType.RANGE)
                                            .build()
                            )

                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("pk")
                                            .attributeType("S")
                                            .build(),

                                    AttributeDefinition.builder()
                                            .attributeName("sk")
                                            .attributeType("S")
                                            .build(),

                                    AttributeDefinition.builder()
                                            .attributeName("gsi1pk")
                                            .attributeType("S")
                                            .build(),

                                    AttributeDefinition.builder()
                                            .attributeName("gsi1sk")
                                            .attributeType("S")
                                            .build()
                            )

                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("account-month-index")

                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("gsi1pk")
                                                            .keyType(KeyType.HASH)
                                                            .build(),

                                                    KeySchemaElement.builder()
                                                            .attributeName("gsi1sk")
                                                            .keyType(KeyType.RANGE)
                                                            .build()
                                            )

                                            .projection(
                                                    Projection.builder()
                                                            .projectionType("ALL")
                                                            .build()
                                            )

                                            .provisionedThroughput(
                                                    ProvisionedThroughput.builder()
                                                            .readCapacityUnits(5L)
                                                            .writeCapacityUnits(5L)
                                                            .build()
                                            )

                                            .build()
                            )

                            .provisionedThroughput(
                                    ProvisionedThroughput.builder()
                                            .readCapacityUnits(5L)
                                            .writeCapacityUnits(5L)
                                            .build()
                            )

                            .build()
            );

            dynamoDb.waiter().waitUntilTableExists(
                    b -> b.tableName(TABLE_NAME)
            );

            System.out.println(
                    "created DynamoDB table: " + TABLE_NAME
            );

        } catch (ResourceInUseException e) {

            System.out.println(
                    "DynamoDB table already exists: " + TABLE_NAME
            );
        }
    }

    @Override
    public void save(NormalizedTxn txn) {

        String transactionKey =
                "TXN#"
                        + txn.accountLast4()
                        + "#"
                        + txn.occurredAt()
                        + "#"
                        + txn.direction()
                        + "#"
                        + txn.amount().toPlainString();

        Map<String, AttributeValue> transactionKeyMap =
                Map.of(
                        "pk",
                        AttributeValue.builder()
                                .s(transactionKey)
                                .build(),

                        "sk",
                        AttributeValue.builder()
                                .s("TXN")
                                .build()
                );

        /*
         * Fast idempotency check.
         *
         * If the transaction already exists, the complete transaction was
         * previously committed by the atomic transaction below.
         */
        var existing =
                dynamoDb.getItem(
                        GetItemRequest.builder()
                                .tableName(TABLE_NAME)
                                .key(transactionKeyMap)
                                .build()
                );

        if (existing.hasItem()) {
            return;
        }

        Map<String, AttributeValue> transactionItem =
                new HashMap<>();

        transactionItem.put(
                "pk",
                AttributeValue.builder()
                        .s(transactionKey)
                        .build()
        );

        transactionItem.put(
                "sk",
                AttributeValue.builder()
                        .s("TXN")
                        .build()
        );

        transactionItem.put(
                "accountLast4",
                AttributeValue.builder()
                        .s(txn.accountLast4())
                        .build()
        );

        transactionItem.put(
                "occurredAt",
                AttributeValue.builder()
                        .s(txn.occurredAt().toString())
                        .build()
        );

        transactionItem.put(
                "direction",
                AttributeValue.builder()
                        .s(txn.direction().name())
                        .build()
        );

        transactionItem.put(
                "amount",
                AttributeValue.builder()
                        .n(txn.amount().toPlainString())
                        .build()
        );

        transactionItem.put(
                "category",
                AttributeValue.builder()
                        .s(txn.category().name())
                        .build()
        );

        transactionItem.put(
                "merchant",
                AttributeValue.builder()
                        .s(txn.merchant())
                        .build()
        );

        transactionItem.put(
                "sourceMessageIds",
                AttributeValue.builder()
                        .ss(txn.sourceMessageIds())
                        .build()
        );

        transactionItem.put(
                "transactionKey",
                AttributeValue.builder()
                        .s(transactionKey)
                        .build()
        );

        String accountMonth =
                txn.occurredAt().getYear()
                        + "-"
                        + String.format(
                        "%02d",
                        txn.occurredAt().getMonthValue()
                );

        transactionItem.put(
                "gsi1pk",
                AttributeValue.builder()
                        .s(
                                "ACCOUNT#"
                                        + txn.accountLast4()
                                        + "#MONTH#"
                                        + accountMonth
                        )
                        .build()
        );

        transactionItem.put(
                "gsi1sk",
                AttributeValue.builder()
                        .s(txn.occurredAt().toString())
                        .build()
        );

        List<TransactWriteItem> writes =
                new ArrayList<>();

        /*
         * Transaction itself.
         *
         * The condition prevents two concurrent writers from creating
         * the same transaction.
         */
        writes.add(
                TransactWriteItem.builder()
                        .put(
                                Put.builder()
                                        .tableName(TABLE_NAME)
                                        .item(transactionItem)
                                        .conditionExpression(
                                                "attribute_not_exists(pk)"
                                        )
                                        .build()
                        )
                        .build()
        );

        /*
         * Message-id lookup items.
         */
        for (String messageId : txn.sourceMessageIds()) {

            Map<String, AttributeValue> messageItem =
                    new HashMap<>();

            messageItem.put(
                    "pk",
                    AttributeValue.builder()
                            .s("MESSAGE#" + messageId)
                            .build()
            );

            messageItem.put(
                    "sk",
                    AttributeValue.builder()
                            .s("TXN")
                            .build()
            );

            messageItem.put(
                    "transactionKey",
                    AttributeValue.builder()
                            .s(transactionKey)
                            .build()
            );

            writes.add(
                    TransactWriteItem.builder()
                            .put(
                                    Put.builder()
                                            .tableName(TABLE_NAME)
                                            .item(messageItem)
                                            .conditionExpression(
                                                    "attribute_not_exists(pk)"
                                            )
                                            .build()
                            )
                            .build()
            );
        }

        /*
         * Category total update.
         *
         * This is part of the same DynamoDB transaction, so it cannot
         * be updated without the transaction and message mappings.
         */
        Map<String, AttributeValue> totalsKey =
                Map.of(
                        "pk",
                        AttributeValue.builder()
                                .s("TOTALS#" + txn.accountLast4())
                                .build(),

                        "sk",
                        AttributeValue.builder()
                                .s("TOTALS")
                                .build()
                );

        writes.add(
                TransactWriteItem.builder()
                        .update(
                                Update.builder()
                                        .tableName(TABLE_NAME)
                                        .key(totalsKey)
                                        .updateExpression(
                                                "SET #category = "
                                                        + "if_not_exists(#category, :zero) "
                                                        + "+ :amount"
                                        )
                                        .expressionAttributeNames(
                                                Map.of(
                                                        "#category",
                                                        txn.category().name()
                                                )
                                        )
                                        .expressionAttributeValues(
                                                Map.of(
                                                        ":zero",
                                                        AttributeValue.builder()
                                                                .n("0.00")
                                                                .build(),

                                                        ":amount",
                                                        AttributeValue.builder()
                                                                .n(
                                                                        txn.amount()
                                                                                .toPlainString()
                                                                )
                                                                .build()
                                                )
                                        )
                                        .build()
                        )
                        .build()
        );

        try {

            dynamoDb.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                            .transactItems(writes)
                            .build()
            );

        } catch (TransactionCanceledException e) {

            /*
             * Another writer may have committed the same transaction
             * between our GetItem and TransactWriteItems.
             *
             * The transaction is therefore already safely stored.
             */
            var afterRace =
                    dynamoDb.getItem(
                            GetItemRequest.builder()
                                    .tableName(TABLE_NAME)
                                    .key(transactionKeyMap)
                                    .build()
                    );

            if (!afterRace.hasItem()) {
                throw e;
            }
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        String monthValue = month.toString();

        String gsiPartitionKey =
                "ACCOUNT#"
                        + accountLast4
                        + "#MONTH#"
                        + monthValue;

        QueryRequest request =
                QueryRequest.builder()
                        .tableName(TABLE_NAME)
                        .indexName("account-month-index")
                        .keyConditionExpression(
                                "gsi1pk = :pk"
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":pk",
                                        AttributeValue.builder()
                                                .s(gsiPartitionKey)
                                                .build()
                                )
                        )
                        .scanIndexForward(false)
                        .build();

        QueryResponse response =
                dynamoDb.query(request);

        List<NormalizedTxn> transactions =
                new ArrayList<>();

        for (Map<String, AttributeValue> item :
                response.items()) {

            transactions.add(toNormalizedTxn(item));
        }

        return transactions;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        Map<String, AttributeValue> key =
                Map.of(
                        "pk",
                        AttributeValue.builder()
                                .s("TOTALS#" + accountLast4)
                                .build(),

                        "sk",
                        AttributeValue.builder()
                                .s("TOTALS")
                                .build()
                );

        var response =
                dynamoDb.getItem(
                        GetItemRequest.builder()
                                .tableName(TABLE_NAME)
                                .key(key)
                                .build()
                );

        Map<Category, BigDecimal> result =
                new java.util.LinkedHashMap<>();

        for (Category category : Category.values()) {

            AttributeValue value =
                    response.item().get(category.name());

            BigDecimal amount =
                    value == null
                            ? BigDecimal.ZERO.setScale(2)
                            : new BigDecimal(value.n()).setScale(2);

            result.put(category, amount);
        }

        return result;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(
            String messageId) {

        Map<String, AttributeValue> key = Map.of(
                "pk",
                AttributeValue.builder()
                        .s("MESSAGE#" + messageId)
                        .build(),

                "sk",
                AttributeValue.builder()
                        .s("TXN")
                        .build()
        );

        var messageResponse =
                dynamoDb.getItem(
                        GetItemRequest.builder()
                                .tableName(TABLE_NAME)
                                .key(key)
                                .build()
                );

        if (!messageResponse.hasItem()) {
            return Optional.empty();
        }

        String transactionKey =
                messageResponse.item()
                        .get("transactionKey")
                        .s();

        Map<String, AttributeValue> transactionKeyMap =
                Map.of(
                        "pk",
                        AttributeValue.builder()
                                .s(transactionKey)
                                .build(),

                        "sk",
                        AttributeValue.builder()
                                .s("TXN")
                                .build()
                );

        var transactionResponse =
                dynamoDb.getItem(
                        GetItemRequest.builder()
                                .tableName(TABLE_NAME)
                                .key(transactionKeyMap)
                                .build()
                );

        if (!transactionResponse.hasItem()) {
            return Optional.empty();
        }

        return Optional.of(
                toNormalizedTxn(
                        transactionResponse.item()
                )
        );
    }

    private NormalizedTxn toNormalizedTxn(
            Map<String, AttributeValue> item) {

        List<String> sourceMessageIds =
                item.get("sourceMessageIds").ss();

        return new NormalizedTxn(
                item.get("accountLast4").s(),

                java.time.OffsetDateTime.parse(
                        item.get("occurredAt").s()
                ),

                Direction.valueOf(
                        item.get("direction").s()
                ),

                new BigDecimal(
                        item.get("amount").n()
                ).setScale(2),

                Category.valueOf(
                        item.get("category").s()
                ),

                item.get("merchant").s(),

                sourceMessageIds
        );
    }

    private void updateCategoryTotal(NormalizedTxn txn) {

        String categoryAttribute =
                txn.category().name();

        Map<String, AttributeValue> key =
                Map.of(
                        "pk",
                        AttributeValue.builder()
                                .s("TOTALS#" + txn.accountLast4())
                                .build(),

                        "sk",
                        AttributeValue.builder()
                                .s("TOTALS")
                                .build()
                );

        dynamoDb.updateItem(
                software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
                        .builder()
                        .tableName(TABLE_NAME)
                        .key(key)
                        .updateExpression(
                                "SET #category = if_not_exists(#category, :zero) + :amount"
                        )
                        .expressionAttributeNames(
                                Map.of(
                                        "#category",
                                        categoryAttribute
                                )
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":zero",
                                        AttributeValue.builder()
                                                .n("0.00")
                                                .build(),

                                        ":amount",
                                        AttributeValue.builder()
                                                .n(txn.amount().toPlainString())
                                                .build()
                                )
                        )
                        .build()
        );
    }

    public void clearForTests() {
        var response = dynamoDb.scan(
                software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                        .tableName(TABLE_NAME)
                        .projectionExpression("pk, sk")
                        .build()
        );

        for (var item : response.items()) {
            dynamoDb.deleteItem(
                    DeleteItemRequest.builder()
                            .tableName(TABLE_NAME)
                            .key(
                                    Map.of(
                                            "pk", item.get("pk"),
                                            "sk", item.get("sk")
                                    )
                            )
                            .build()
            );
        }
    }

    @Override
    public void close() {
        dynamoDb.close();
    }
}