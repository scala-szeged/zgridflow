package dev.zgridflow

import zio.*
import zio.json.*
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.errors.TopicExistsException
import java.util.Properties
import java.util.concurrent.ExecutionException
import scala.jdk.CollectionConverters.*

final class Events(producer: KafkaProducer[String, String], topic: String):
  def publish(message: PocMessage): Task[Unit] = ZIO.attemptBlocking {
    producer.send(new ProducerRecord(topic, message.id, MessageCreated("MessageCreated", message).toJson)).get()
    ()
  }

  def relay(store: Persistence): UIO[Nothing] =
    (for
      pending <- store.pending
      _ <- ZIO.foreachDiscard(pending)(message => publish(message) *> store.markPublished(message.id))
    yield ()).catchAll(error => ZIO.logWarning(s"Outbox will retry: ${error.getMessage}"))
      .repeat(Schedule.spaced(1.second)).forever

object Events:
  def properties(brokers: String): Properties =
    val props = new Properties()
    props.setProperty("bootstrap.servers", brokers)
    props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.setProperty("acks", "all")
    props.setProperty("enable.idempotence", "true")
    props.setProperty("delivery.timeout.ms", "15000")
    props.setProperty("request.timeout.ms", "5000")
    props.setProperty("max.block.ms", "10000")
    props

  def initialize(brokers: String, topic: String): Task[Unit] = ZIO.attemptBlocking {
    val props = new Properties()
    props.setProperty("bootstrap.servers", brokers)
    props.setProperty("default.api.timeout.ms", "10000")
    props.setProperty("request.timeout.ms", "5000")
    val admin = AdminClient.create(props)
    try
      try admin.createTopics(List(new NewTopic(topic, 1, 1.toShort)).asJava).all().get()
      catch
        case error: ExecutionException if error.getCause.isInstanceOf[TopicExistsException] => ()
    finally admin.close(java.time.Duration.ofSeconds(5))
  }
