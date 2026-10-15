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

import java.nio.file.{Files, Path}
import javax.inject.{Inject, Singleton}
import scala.util.Using

trait NdjsonRecordCounter {
  def count(file: Path): Long
}

@Singleton
class NdjsonRecordCounterImpl @Inject() () extends NdjsonRecordCounter {

  private val newline = '\n'.toByte
  private val space = ' '.toByte
  private val tab = '\t'.toByte
  private val carriageReturn = '\r'.toByte
  private val endOfFile = -1

  override def count(file: Path): Long = {
    val buffer = new Array[Byte](8192)
    var recordCount = 0L
    var lineHasContent = false

    Using.resource(Files.newInputStream(file)) { input =>
      var bytesRead = input.read(buffer)

      while (bytesRead != endOfFile) {
        var index = 0
        while (index < bytesRead) {
          val currentByte = buffer(index)
          val isNewline = currentByte == newline
          val isSpace = currentByte == space
          val isTab = currentByte == tab
          val isCarriageReturn = currentByte == carriageReturn
          val isWhitespace = isSpace || isTab || isCarriageReturn

          if (isNewline) {
            if (lineHasContent) recordCount += 1L
            lineHasContent = false
          } else if (!isWhitespace) {
            lineHasContent = true
          }
          index += 1
        }
        bytesRead = input.read(buffer)
      }
    }

    if (lineHasContent) {
      recordCount + 1L
    } else {
      recordCount
    }
  }
}
