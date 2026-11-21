package dev.zgridflow

import zio.test.*
import zio.json.*
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import scala.util.Using

private final case class AvroFixture(schemaSha256: String, event: MessageCreated, hex: String) derives JsonCodec

object AvroEventSpec extends ZIOSpecDefault:
  private val fixture = Using.resource(getClass.getResourceAsStream("/avro/fixtures/message-created-v1.json")) { input =>
    new String(input.readAllBytes(), UTF_8).fromJson[AvroFixture].fold(error => throw new IllegalStateException(error), identity)
  }

  def spec = suite("Avro Kafka event contract")(
    test("Scala writer matches the shared binary fixture including Unicode") {
      assertTrue(HexFormat.of().formatHex(AvroEvent.encode(fixture.event.message)) == fixture.hex)
    },
    test("published schema and identifying headers match the immutable v1 contract") {
      assertTrue(
        AvroEvent.schemaHash == fixture.schemaSha256,
        AvroEvent.schemaId == "dev.zgridflow.events.v1.MessageCreated",
        new String(AvroEvent.headers.lastHeader("content-type").value(), UTF_8) == "application/avro",
        new String(AvroEvent.headers.lastHeader("avro-schema-sha256").value(), UTF_8) == fixture.schemaSha256
      )
    },
    test("standard Avro reader decodes the writer's Kafka value") {
      val reader = new GenericDatumReader[GenericRecord](AvroEvent.schema)
      val decoded = reader.read(null, DecoderFactory.get().binaryDecoder(AvroEvent.encode(fixture.event.message), null))
      val body = decoded.get("message").asInstanceOf[GenericRecord]
      assertTrue(decoded.get("kind").toString == "MessageCreated", body.get("id").toString == fixture.event.message.id)
    }
  )
