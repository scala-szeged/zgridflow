package dev.zgridflow

import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.header.Headers
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

object AvroEvent:
  private val schemaBytes =
    val input = Option(getClass.getResourceAsStream("/avro/message-created-v1.avsc"))
      .getOrElse(throw new IllegalStateException("Missing shared Avro schema"))
    try input.readAllBytes() finally input.close()
  val schema: Schema = new Schema.Parser().parse(new String(schemaBytes, UTF_8))
  val schemaId: String = schema.getFullName
  val schemaHash: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(schemaBytes))

  def headers: Headers = new RecordHeaders()
    .add("content-type", "application/avro".getBytes(UTF_8))
    .add("avro-schema", schemaId.getBytes(UTF_8))
    .add("avro-schema-sha256", schemaHash.getBytes(UTF_8))

  def encode(message: PocMessage): Array[Byte] =
    val record = new GenericData.Record(schema)
    val body = new GenericData.Record(schema.getField("message").schema())
    body.put("id", message.id)
    body.put("text", message.text)
    body.put("createdAt", message.createdAt)
    record.put("kind", "MessageCreated")
    record.put("message", body)
    val output = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(output, null)
    new GenericDatumWriter[GenericRecord](schema).write(record, encoder)
    encoder.flush()
    output.toByteArray
