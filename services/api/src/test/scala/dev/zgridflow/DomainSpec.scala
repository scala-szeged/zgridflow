package dev.zgridflow

import zio.test.*
import zio.json.*

object DomainSpec extends ZIOSpecDefault:
  def spec = suite("POC message domain")(
    test("normalizes text and generates an identifier") {
      val result = Domain.create(" hello ")
      assertTrue(result.exists(m => m.text == "hello" && m.id.nonEmpty && m.createdAt.nonEmpty))
    },
    test("rejects blank and oversized input") {
      assertTrue(Domain.create(" \n ").isLeft, Domain.create("a" * 201).isLeft, Domain.create("a" * 200).isRight)
    },
    test("event contract survives JSON serialization") {
      val event = MessageCreated("MessageCreated", PocMessage("123", "hello", "2026-11-21T00:00:00Z"))
      assertTrue(event.toJson.fromJson[MessageCreated] == Right(event))
    }
  )
