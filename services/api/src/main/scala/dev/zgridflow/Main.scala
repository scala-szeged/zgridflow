package dev.zgridflow

import zio.*
import zio.http.{Routes, Method, Response, Request, Server, Status, handler}
import zio.json.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import org.apache.kafka.clients.producer.KafkaProducer
import java.net.URI

object Main extends ZIOAppDefault:
  private def env(name: String, fallback: String) = sys.env.getOrElse(name, fallback)
  private def error(message: String, status: Status) = Response.json(Map("error" -> message).toJson).status(status)

  def routes(store: Persistence) = Routes(
    Method.GET / "health" -> handler(Response.json("""{"status":"ok"}""")),
    Method.GET / "messages" -> handler {
      store.list.map(messages => Response.json(messages.toJson))
        .catchAll(cause => ZIO.logError(cause.toString).as(error("Persistence unavailable", Status.ServiceUnavailable)))
    },
    Method.POST / "messages" -> handler { (request: Request) =>
      (for
        body <- request.body.asString
        input <- ZIO.fromEither(body.fromJson[CreateMessage])
        message <- ZIO.fromEither(Domain.create(input.text))
      yield message).foldZIO(
        _ => ZIO.succeed(error("Expected JSON with text containing 1–200 characters", Status.BadRequest)),
        message => store.save(message).as(Response.json(message.toJson).status(Status.Created))
          .catchAll(cause => ZIO.logError(cause.toString).as(error("Persistence unavailable", Status.ServiceUnavailable)))
      )
    }
  )

  def run = ZIO.scoped {
    for
      client <- ZIO.acquireRelease(ZIO.attempt(DynamoDbClient.builder()
        .endpointOverride(URI.create(env("DYNAMODB_ENDPOINT", "http://localhost:8001")))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration(c => c.apiCallTimeout(java.time.Duration.ofSeconds(10)))
        .build()))(client => ZIO.attempt(client.close()).orDie)
      store = new Persistence(client, env("DYNAMODB_TABLE", "zgridflow-messages"))
      brokers = env("KAFKA_BROKERS", "localhost:9094")
      topic = env("KAFKA_TOPIC", "zgridflow.message-created.avro.v1")
      _ <- (store.initialize *> Events.initialize(brokers, topic))
        .tapError(error => ZIO.logWarning(s"Infrastructure initialization will retry: ${error.toString}"))
        .retry(Schedule.spaced(2.seconds) && Schedule.recurs(30))
      producer <- ZIO.acquireRelease(ZIO.attempt(new KafkaProducer[String, Array[Byte]](Events.properties(brokers))))(
        producer => ZIO.attempt(producer.close(java.time.Duration.ofSeconds(5))).orDie)
      _ <- new Events(producer, topic).relay(store).forkScoped
      _ <- ZIO.logInfo("POC API ready; DynamoDB and Kafka initialized")
      _ <- Server.serve(routes(store)).provide(Server.defaultWithPort(env("PORT", "8080").toInt))
    yield ()
  }
