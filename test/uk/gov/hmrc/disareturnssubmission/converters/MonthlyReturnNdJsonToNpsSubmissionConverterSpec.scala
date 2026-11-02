/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.disareturnssubmission.converters

import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.databind.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.*

import java.math.BigDecimal as JavaBigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

class MonthlyReturnNdJsonToNpsSubmissionConverterSpec extends AnyFreeSpec with Matchers {
  private val converter            = new MonthlyReturnNdJsonToNpsSubmissionConverter()
  private val backendContent       = Files.readString(fixture("backend"), UTF_8)
  private val records              = backendContent.linesIterator.toVector
  private val expected             = Json.parse(records.mkString("[", ",", "]"))
  private val standardRecord       = Json.parse(records.head).as[JsObject]
  private val defaultBatchLimit    = 3000
  private val overriddenBatchLimit = 2500
  private val highPrecisionAmount  = "1234567890123456789012345678901234567890.12"
  private val privateAccountNumber = "private-account"

  "MonthlyReturnNdJsonToNpsSubmissionConverter" - {
    Seq("backend", "api").foreach { producer =>
      s"must convert all four $producer variants to the backend-compatible NPS JSON array" in {
        Json.parse(converted(Files.readString(fixture(producer), UTF_8))) mustBe expected
      }
    }

    "must write only model fields instead of forwarding arbitrary JSON" in {
      val input = standardRecord + ("unrecognisedField" -> JsString("not part of the NPS model"))

      Json.parse(converted(Json.stringify(input))) mustBe Json.arr(standardRecord)
    }

    Seq("12345678901234567890.12", highPrecisionAmount).foreach { amount =>
      s"must use Play's configured decimal precision for $amount" in {
        val record = standardRecord ++ Json.obj("amountTransferredIn" -> BigDecimal.exact(amount))
        val mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        val result = mapper.readTree(converted(Json.stringify(record))).get(0).get("amountTransferredIn").decimalValue()

        result.compareTo(new JavaBigDecimal(amount, JsonConfig.settings.bigDecimalParseConfig.mathContext)) mustBe 0
      }
    }

    "must preserve Unicode and escaped strings" in {
      val record = standardRecord + ("firstName" -> JsString("Zoë \\\"\n"))

      Json.parse(converted(Json.stringify(record))) mustBe Json.arr(record)
    }

    "must ignore blank lines and support CRLF, whitespace and a missing final newline" in {
      Json.parse(converted(s"\r\n  ${records.head} \t\r\n\r\n\t${records(1)}  ")) mustBe
        Json.parse(records.take(2).mkString("[", ",", "]"))
    }

    "must produce an empty array for empty or whitespace-only input" in {
      convertedBatches("", defaultBatchLimit) mustBe Seq("[]" -> 0)
      convertedBatches("\r\n \t\n", defaultBatchLimit) mustBe Seq("[]" -> 0)
    }

    Seq(
      (defaultBatchLimit, overriddenBatchLimit, Seq(overriddenBatchLimit, defaultBatchLimit - overriddenBatchLimit)),
      (overriddenBatchLimit, overriddenBatchLimit, Seq(overriddenBatchLimit)),
      (2 * overriddenBatchLimit, overriddenBatchLimit, Seq(overriddenBatchLimit, overriddenBatchLimit)),
      (defaultBatchLimit, defaultBatchLimit, Seq(defaultBatchLimit)),
      (defaultBatchLimit + 1, defaultBatchLimit, Seq(defaultBatchLimit, 1)),
      (2 * defaultBatchLimit, defaultBatchLimit, Seq(defaultBatchLimit, defaultBatchLimit)),
      (4, 1, Seq(1, 1, 1, 1))
    ).foreach { (count, limit, expectedCounts) =>
      s"must split $count records into $expectedCounts with a $limit limit and preserve their order" in {
        val input = (1 to count).iterator
          .map { index =>
            Json.stringify(
              Json.parse(records((index - 1) % records.size)).as[JsObject] + ("accountNumber" -> JsString(s"ACC$index"))
            )
          }
          .mkString("\n")

        val batches = convertedBatches(input, limit)

        batches.map(_._2) mustBe expectedCounts
        batches.flatMap { (json, count) =>
          val batch = Json.parse(json).as[JsArray].value
          batch.size mustBe count
          batch.map(record => (record \ "accountNumber").as[String])
        } mustBe (1 to count).map(index => s"ACC$index")
      }
    }

    "must count records rather than blank lines and not create a trailing empty batch" in {
      val batches =
        convertedBatches(s"\n${records.head}\n\n${records(1)}\r\n \t\n${records(2)}\n\n${records(3)}\n \t", 2)

      batches.map(_._2) mustBe Seq(2, 2)
      batches.flatMap(batch => Json.parse(batch._1).as[JsArray].value) mustBe expected.as[JsArray].value
    }

    Seq(0, -1).foreach { limit =>
      s"must reject a non-positive batch limit of $limit without creating an output" in withFiles { (input, _) =>
        intercept[IllegalArgumentException](converter.convert(input, limit, () => fail("Output must not be created")))
      }
    }

    "must preserve Unicode and decimal precision across batch boundaries" in {
      val firstName = "Zoë \"\n"
      val record    =
        standardRecord ++ Json.obj(
          "firstName"           -> firstName,
          "amountTransferredIn" -> BigDecimal.exact(highPrecisionAmount)
        )
      val batches   = convertedBatches(Seq.fill(3)(Json.stringify(record)).mkString("\n"), 2)
      val mapper    = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

      batches.map(_._2) mustBe Seq(2, 1)
      batches.foreach { (json, _) =>
        val batch = mapper.readTree(json)
        batch.get(0).get("firstName").textValue() mustBe firstName
        batch
          .get(0)
          .get("amountTransferredIn")
          .decimalValue()
          .compareTo(
            new JavaBigDecimal(highPrecisionAmount, JsonConfig.settings.bigDecimalParseConfig.mathContext)
          ) mustBe 0
      }
    }

    Seq("[]", "null", "42", "\"text\"", "{}", "not JSON", "{\"accountNumber\":").foreach { input =>
      s"must reject an invalid submission record: $input" in {
        intercept[IllegalArgumentException](converted(input)).getMessage must include("line 1")
      }
    }

    Seq("garbage", "[]", records.head).foreach { trailing =>
      s"must reject trailing content on a record line: $trailing" in
        intercept[IllegalArgumentException](converted(s"${records.head} $trailing"))
    }

    "must reject duplicate fields rather than silently choosing a value" in {
      val duplicate = records.head.replace("{", "{\"accountNumber\":\"duplicate\",")

      intercept[IllegalArgumentException](converted(duplicate))
    }

    "must retain the parser's numeric size limits" in {
      val oversized = "1" * (JsonConfig.settings.bigDecimalParseConfig.digitsLimit + 1)
      val record    = records.head.replace("\"amountTransferredIn\":10", s"\"amountTransferredIn\":$oversized")

      intercept[IllegalArgumentException](converted(record))
    }

    "must identify the failed line and fields without exposing record data" in {
      val invalid = (standardRecord - "nino") + ("accountNumber" -> JsString(privateAccountNumber))
      val failure = intercept[IllegalArgumentException](converted(s"\n${records.head}\n${Json.stringify(invalid)}"))

      failure.getMessage must include("line 3")
      failure.getMessage must include("nino")
      failure.getMessage must not include privateAccountNumber
      failure.getCause mustBe null
    }

    "must not expose malformed record contents in parser errors" in {
      val failure = intercept[IllegalArgumentException](converted(s"{\"accountNumber\":\"$privateAccountNumber\","))

      failure.getMessage mustBe "Invalid submission JSON at line 1"
      failure.getCause mustBe null
    }

    "must validate records in later batches and report the original line number" in {
      val failure = intercept[IllegalArgumentException](convertedBatches(s"${records.head}\n\n${records(1)}\n\n{}", 1))

      failure.getMessage must include("line 5")
    }

    "must handle a record larger than the file buffers" in {
      val record = standardRecord + ("firstName" -> JsString("a" * 65536))

      Json.parse(converted(Json.stringify(record))) mustBe Json.arr(record)
    }

    "must convert a large generated file without collecting records in memory" in withFiles { (input, createOutput) =>
      val count = 100000
      Using.resource(Files.newBufferedWriter(input, UTF_8)) { writer =>
        (1 to count).foreach { _ => writer.write(records.head); writer.newLine() }
      }

      val batches = converter.convert(input, defaultBatchLimit, createOutput)

      batches.map(_._2) mustBe Seq.fill(count / defaultBatchLimit)(defaultBatchLimit) :+ (count % defaultBatchLimit)
      var convertedRecords = 0
      batches.foreach { (output, expectedCount) =>
        Using.resource(new JsonFactory().createParser(output.toFile)) { parser =>
          parser.nextToken() mustBe JsonToken.START_ARRAY
          var batchRecords = 0
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            parser.currentToken() mustBe JsonToken.START_OBJECT
            parser.skipChildren()
            batchRecords += 1
          }
          batchRecords mustBe expectedCount
          convertedRecords += batchRecords
          parser.nextToken() mustBe null
        }
      }
      convertedRecords mustBe count
    }
  }

  private def fixture(producer: String): Path =
    Path.of(getClass.getResource(s"/monthly-return-submissions/$producer.ndjson").toURI)

  private def converted(content: String): String = convertedBatches(content, defaultBatchLimit).head._1

  private def convertedBatches(content: String, limit: Int): Seq[(String, Int)] =
    withFiles { (input, createOutput) =>
      Files.writeString(input, content, UTF_8)
      converter.convert(input, limit, createOutput).map((output, count) => Files.readString(output, UTF_8) -> count)
    }

  private def withFiles[A](use: (Path, () => Path) => A): A = {
    val input   = Files.createTempFile("monthly-return-nps-converter-input", ".ndjson")
    val outputs = ArrayBuffer.empty[Path]
    try
      use(
        input,
        () => {
          val output = Files.createTempFile("monthly-return-nps-converter-output", ".json")
          outputs += output
          output
        }
      )
    finally {
      Files.deleteIfExists(input)
      outputs.foreach(Files.deleteIfExists)
    }
  }
}
