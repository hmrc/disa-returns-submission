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

package uk.gov.hmrc.disareturnssubmission.models.enums

import play.api.libs.json.*

object EnumFormat:
  def apply[E <: scala.reflect.Enum](values: Array[E]): Format[E] =
    Format(
      Reads {
        case JsString(s) =>
          values
            .find(_.toString == s)
            .map(JsSuccess(_))
            .getOrElse(JsError(s"Unknown value: $s"))
        case _           => JsError("String expected")
      },
      Writes(e => JsString(e.toString))
    )
