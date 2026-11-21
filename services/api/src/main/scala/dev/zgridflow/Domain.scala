package dev.zgridflow

import zio.json.*
import java.time.Instant
import java.util.UUID

final case class PocMessage(id: String, text: String, createdAt: String) derives JsonCodec
final case class CreateMessage(text: String) derives JsonCodec
final case class MessageCreated(kind: String, message: PocMessage) derives JsonCodec

object Domain:
  def validate(text: String): Either[String, String] =
    val normalized = text.trim
    Either.cond(normalized.nonEmpty && normalized.length <= 200, normalized, "text must contain 1–200 characters")

  def create(text: String): Either[String, PocMessage] =
    validate(text).map(PocMessage(UUID.randomUUID().toString, _, Instant.now().toString))
