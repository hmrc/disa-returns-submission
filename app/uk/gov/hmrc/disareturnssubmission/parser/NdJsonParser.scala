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

package uk.gov.hmrc.disareturnssubmission.parser

import org.apache.pekko.stream.scaladsl.Framing
import play.api.libs.json.{JsValue, Json}
import org.apache.pekko.stream.scaladsl.{Source as PekkoSource, *}
import org.apache.pekko.util.ByteString
import uk.gov.hmrc.disareturnssubmission.models.IsaSubscription

import scala.util.Try

trait NdJsonParser {

  def parseIsaSubscription(body: PekkoSource[ByteString, _]): PekkoSource[Try[IsaSubscription], _] =
    body
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = Int.MaxValue, allowTruncation = true))
      .map(_.utf8String)
      .filter(_.nonEmpty)
      .map(line => Try(Json.parse(line).as[IsaSubscription]))

}
