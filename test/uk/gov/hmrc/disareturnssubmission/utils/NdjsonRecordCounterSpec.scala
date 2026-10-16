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

package uk.gov.hmrc.disareturnssubmission.utils

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class NdjsonRecordCounterSpec extends AnyFreeSpec with Matchers {

  private def countOf(content: String): Long = {
    val file = Files.createTempFile("ndjson-record-counter", ".ndjson")
    try {
      Files.write(file, content.getBytes(UTF_8))
      new NdjsonRecordCounterImpl().count(file)
    } finally Files.deleteIfExists(file)
  }

  private val bufferSize = 8192

  "NdjsonRecordCounter" - {
    "must count every line when the content ends with a newline" in {
      countOf("{\"id\": \"1\"}\n{\"id\": \"2\"}\n") mustBe 2L
    }

    "must count the last line when the content does not end with a newline" in {
      countOf("{\"id\": \"1\"}\n{\"id\": \"2\"}") mustBe 2L
    }

    "must count a single record without a newline" in {
      countOf("{\"id\": \"1\"}") mustBe 1L
    }

    "must not count blank or whitespace-only lines" in {
      countOf("\n{\"id\": \"1\"}\n\n  \t \n{\"id\": \"2\"}\n\n") mustBe 2L
    }

    "must not count a whitespace-only last line without a newline" in {
      countOf("{\"id\": \"1\"}\n  \t ") mustBe 1L
    }

    "must count lines with whitespace around the content" in {
      countOf("  {\"id\": \"1\"}\t\n\t{\"id\": \"2\"}  \n") mustBe 2L
    }

    "must count lines ending in \\r\\n once each" in {
      countOf("{\"id\": \"1\"}\r\n{\"id\": \"2\"}\r\n") mustBe 2L
    }

    "must not count blank lines ending in \\r\\n" in {
      countOf("{\"id\": \"1\"}\r\n\r\n  \r\n{\"id\": \"2\"}\r\n") mustBe 2L
    }

    "must count a line containing only multi-byte characters" in {
      countOf("é\n") mustBe 1L
    }

    "must return zero for an empty file" in {
      countOf("") mustBe 0L
    }

    "must return zero for a file containing only whitespace and newlines" in {
      countOf("\n \t\r\n\n   \n") mustBe 0L
    }

    "must count a line longer than the read buffer once" in {
      countOf(s"{\"data\": \"${"a" * (bufferSize * 2)}\"}\n") mustBe 1L
    }

    "must count records across many read buffers" in {
      countOf("{\"id\": \"1\"}\n" * 10000) mustBe 10000L
    }
  }
}
