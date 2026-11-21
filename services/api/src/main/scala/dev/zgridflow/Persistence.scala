package dev.zgridflow

import zio.*
import zio.json.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import scala.jdk.CollectionConverters.*

final class Persistence(client: DynamoDbClient, table: String):
  private def value(s: String) = AttributeValue.builder().s(s).build()
  private def key(id: String) = Map("id" -> value(id)).asJava

  val initialize: Task[Unit] = ZIO.attemptBlocking {
    try client.createTable(CreateTableRequest.builder().tableName(table)
      .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
      .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
      .billingMode(BillingMode.PAY_PER_REQUEST).build())
    catch case _: ResourceInUseException => ()
    client.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(table).build())
    ()
  }

  // The message and pending event are one atomic DynamoDB item.
  def save(message: PocMessage): Task[Unit] = ZIO.attemptBlocking {
    client.putItem(PutItemRequest.builder().tableName(table).conditionExpression("attribute_not_exists(id)")
      .item(Map("id" -> value(message.id), "payload" -> value(message.toJson),
        "published" -> AttributeValue.builder().bool(false).build()).asJava).build())
    ()
  }

  private def scan(pendingOnly: Boolean): Task[List[PocMessage]] = ZIO.attemptBlocking {
    val results = List.newBuilder[PocMessage]
    var cursor = Map.empty[String, AttributeValue].asJava
    var more = true
    while more do
      val request = ScanRequest.builder().tableName(table).consistentRead(true)
      if !cursor.isEmpty then request.exclusiveStartKey(cursor)
      if pendingOnly then request.filterExpression("#p = :no")
        .expressionAttributeNames(Map("#p" -> "published").asJava)
        .expressionAttributeValues(Map(":no" -> AttributeValue.builder().bool(false).build()).asJava)
      val page = client.scan(request.build())
      page.items().asScala.foreach { item =>
        results += item.get("payload").s().fromJson[PocMessage].fold(
          error => throw new IllegalStateException(s"Invalid persisted message: $error"), identity)
      }
      cursor = page.lastEvaluatedKey()
      more = cursor != null && !cursor.isEmpty
    results.result()
  }

  val pending: Task[List[PocMessage]] = scan(true)
  val list: Task[List[PocMessage]] = scan(false).map(_.sortBy(_.createdAt).reverse.take(100))

  def markPublished(id: String): Task[Unit] = ZIO.attemptBlocking {
    client.updateItem(UpdateItemRequest.builder().tableName(table).key(key(id))
      .updateExpression("SET #p = :yes")
      .expressionAttributeNames(Map("#p" -> "published").asJava)
      .expressionAttributeValues(Map(":yes" -> AttributeValue.builder().bool(true).build()).asJava).build())
    ()
  }
