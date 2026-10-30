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
import play.api.libs.json.*
import play.api.libs.json.jackson.PlayJsonMapperModule
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnNpsSubmission

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import javax.inject.*
import scala.util.Using

@Singleton
class MonthlyReturnNdJsonToNpsSubmissionConverter @Inject() () {
  private val mapper = new ObjectMapper()
    .registerModule(new PlayJsonMapperModule(JsonConfig.settings))
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

  def convert(input: Path, maxRecordsPerSubmission: Int, createOutput: () => Path): Seq[(Path, Int)] = {
    require(maxRecordsPerSubmission > 0, "maxRecordsPerSubmission must be greater than zero")
    Using.resource(Files.newBufferedReader(input, UTF_8)) { reader =>
      val lines      = Iterator.continually(reader.readLine()).takeWhile(_ != null).zipWithIndex.filter(_._1.trim.nonEmpty)
      val batches    = Vector.newBuilder[(Path, Int)]
      var firstBatch = true
      while (firstBatch || lines.hasNext) {
        firstBatch = false
        val output = createOutput()
        val count  = Using.resource(mapper.getFactory.createGenerator(output.toFile, JsonEncoding.UTF8)) { writer =>
          writeSubmissionsAsJsonArray(lines.take(maxRecordsPerSubmission), writer)
        }
        batches += output -> count
      }
      batches.result()
    }
  }

  private def writeSubmissionsAsJsonArray(lines: Iterator[(String, Int)], writer: JsonGenerator): Int = {
    writer.writeStartArray()
    val count = lines.foldLeft(0) { case (count, (line, index)) =>
      val lineNumber = index + 1
      val json       = parseSubmissionJson(line, lineNumber)
      val submission = validateSubmission(json, lineNumber)
      writer.writeRawValue(Json.stringify(Json.toJson(submission)))
      count + 1
    }
    writer.writeEndArray()
    count
  }

  private def parseSubmissionJson(line: String, lineNumber: Int): JsValue =
    try mapper.readValue(line, classOf[JsValue])
    catch {
      case _: JsonProcessingException =>
        throw new IllegalArgumentException(s"Invalid submission JSON at line $lineNumber")
    }

  private def validateSubmission(json: JsValue, lineNumber: Int): MonthlyReturnNpsSubmission =
    json.validate[MonthlyReturnNpsSubmission] match {
      case JsSuccess(value, _) => value
      case JsError(errors)     =>
        throw new IllegalArgumentException(
          s"Invalid submission at line $lineNumber, fields: ${errors.map(_._1).mkString(", ")}"
        )
    }
}
