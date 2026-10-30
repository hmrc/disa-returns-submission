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
import scala.util.Using

class MonthlyReturnNdJsonToNpsSubmissionConverterSpec extends AnyFreeSpec with Matchers {
  private val converter      = new MonthlyReturnNdJsonToNpsSubmissionConverter()
  private val backendContent = Files.readString(fixture("backend"), UTF_8)
  private val records        = backendContent.linesIterator.toVector
  private val expected       = Json.parse(records.mkString("[", ",", "]"))
  private val standardRecord = Json.parse(records.head).as[JsObject]

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

    Seq("12345678901234567890.12", "1234567890123456789012345678901234567890.12").foreach { amount =>
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
      converted("") mustBe "[]"
      converted("\r\n \t\n") mustBe "[]"
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
      val invalid = (standardRecord - "nino") + ("accountNumber" -> JsString("private-account"))
      val failure = intercept[IllegalArgumentException](converted(s"\n${records.head}\n${Json.stringify(invalid)}"))

      failure.getMessage must include("line 3")
      failure.getMessage must include("nino")
      failure.getMessage must not include "private-account"
      failure.getCause mustBe null
    }

    "must not expose malformed record contents in parser errors" in {
      val failure = intercept[IllegalArgumentException](converted("{\"accountNumber\":\"private-account\","))

      failure.getMessage mustBe "Invalid submission JSON at line 1"
      failure.getCause mustBe null
    }

    "must handle a record larger than the file buffers" in {
      val record = standardRecord + ("firstName" -> JsString("a" * 65536))

      Json.parse(converted(Json.stringify(record))) mustBe Json.arr(record)
    }

    "must convert a large generated file without collecting records in memory" in withFiles { (input, output) =>
      val count = 100000
      Using.resource(Files.newBufferedWriter(input, UTF_8)) { writer =>
        (1 to count).foreach { _ => writer.write(records.head); writer.newLine() }
      }

      converter.convert(input, output)

      Using.resource(new JsonFactory().createParser(output.toFile)) { parser =>
        parser.nextToken() mustBe JsonToken.START_ARRAY
        var convertedRecords = 0
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          parser.currentToken() mustBe JsonToken.START_OBJECT
          parser.skipChildren()
          convertedRecords += 1
        }
        convertedRecords mustBe count
        parser.nextToken() mustBe null
      }
    }
  }

  private def fixture(producer: String): Path =
    Path.of(getClass.getResource(s"/monthly-return-submissions/$producer.ndjson").toURI)

  private def converted(content: String): String = withFiles { (input, output) =>
    Files.writeString(input, content, UTF_8)
    converter.convert(input, output)
    Files.readString(output, UTF_8)
  }

  private def withFiles[A](use: (Path, Path) => A): A = {
    val input  = Files.createTempFile("monthly-return-nps-converter-input", ".ndjson")
    val output = Files.createTempFile("monthly-return-nps-converter-output", ".json")
    try use(input, output)
    finally {
      Files.deleteIfExists(input)
      Files.deleteIfExists(output)
    }
  }
}
