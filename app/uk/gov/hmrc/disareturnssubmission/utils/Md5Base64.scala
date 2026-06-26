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

import uk.gov.hmrc.objectstore.client.Md5Hash

import java.nio.file.{Files, Path as FilePath}
import java.security.MessageDigest
import java.util.Base64
import scala.util.Using

trait Md5Base64 {
  def checkMd5Base64(file: FilePath): Md5Hash = {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = new Array[Byte](8192)

    Using.resource(Files.newInputStream(file)) { input =>
      var read = input.read(buffer)

      while (read != -1) {
        digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    }

    Md5Hash(Base64.getEncoder.encodeToString(digest.digest()))
  }
}
