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

package uk.gov.hmrc.disareturnssubmission.models

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Json, OFormat, Reads, Writes}

import java.time.LocalDate

case class IsaSubscription(
  accountNumber: String,
  nino: String,
  firstName: String,
  middleName: Option[String],
  lastName: String,
  dateOfBirth: LocalDate,
  amountTransferredIn: BigDecimal,
  amountTransferredOut: BigDecimal,
  dateOfLastSubscription: LocalDate,
  totalCurrentYearSubscriptionsToDate: BigDecimal,
  marketValueOfAccount: BigDecimal,
  isaType: IsaType,
  flexibleIsa: Boolean
)
object IsaSubscription {
  implicit val format: OFormat[IsaSubscription] = Json.format[IsaSubscription]
}

sealed trait IsaType
object IsaType {
  case object Cash extends IsaType
  case object InnovativeFinance extends IsaType
  case object Lifetime extends IsaType
  case object StockAndShares extends IsaType

  def fromString(s: String): Either[String, IsaType] = s.toLowerCase match {
    case "cash"               => Right(Cash)
    case "innovative finance" => Right(InnovativeFinance)
    case "lifetime"           => Right(Lifetime)
    case "stock and shares"   => Right(StockAndShares)
    case other                => Left(s"Unknown status: $other")
  }

  def toString(isaType: IsaType): String = isaType match {
    case Cash              => "cash"
    case InnovativeFinance => "innovative finance"
    case Lifetime          => "lifetime"
    case StockAndShares    => "stock and shares"
  }

  implicit val reads: Reads[IsaType] = Reads[IsaType] { json =>
    json.validate[String].flatMap { s =>
      fromString(s).fold(
        err => JsError(err),
        status => JsSuccess(status)
      )
    }
  }

  implicit val writes: Writes[IsaType] = Writes[IsaType] { isaType =>
    JsString(toString(isaType))
  }

  implicit val format: Format[IsaType] = Format(reads, writes)
}
