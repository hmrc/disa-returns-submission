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

package uk.gov.hmrc.disareturnssubmission.models.monthly

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import java.time.LocalDate

enum MonthlyReturnNpsIsaType {
  case CASH, STOCKS_AND_SHARES, INNOVATIVE_FINANCE, LIFETIME
}

enum MonthlyReturnNpsClosureReason {
  case CANCELLED, CLOSED, VOID, TRANSFERRED_IN_FULL, ALL_FUNDS_WITHDRAWN
}

final case class MonthlyReturnNpsSubmissionCommon(
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
  isaType: MonthlyReturnNpsIsaType
)

sealed trait MonthlyReturnNpsSubmission {
  def common: MonthlyReturnNpsSubmissionCommon
}

final case class MonthlyReturnNpsStandardSubscription(common: MonthlyReturnNpsSubmissionCommon, flexibleIsa: Boolean)
    extends MonthlyReturnNpsSubmission

final case class MonthlyReturnNpsStandardClosure(
  common: MonthlyReturnNpsSubmissionCommon,
  flexibleIsa: Boolean,
  closureDate: LocalDate,
  reasonForClosure: MonthlyReturnNpsClosureReason
) extends MonthlyReturnNpsSubmission

final case class MonthlyReturnNpsLifetimeSubscription(
  common: MonthlyReturnNpsSubmissionCommon,
  dateOfFirstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal
) extends MonthlyReturnNpsSubmission

final case class MonthlyReturnNpsLifetimeClosure(
  common: MonthlyReturnNpsSubmissionCommon,
  dateOfFirstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal,
  closureDate: LocalDate,
  reasonForClosure: MonthlyReturnNpsClosureReason
) extends MonthlyReturnNpsSubmission

object MonthlyReturnNpsSubmission {
  private def enumFormat[A](values: Array[A]): Format[A] =
    Format(
      Reads.StringReads.flatMapResult { value =>
        values.find(_.toString == value) match {
          case Some(enumValue) => JsSuccess(enumValue)
          case None            => JsError("Unsupported enum value")
        }
      },
      Writes(value => JsString(value.toString))
    )

  private implicit val isaTypeFormat: Format[MonthlyReturnNpsIsaType]             = enumFormat(MonthlyReturnNpsIsaType.values)
  private implicit val closureReasonFormat: Format[MonthlyReturnNpsClosureReason] =
    enumFormat(MonthlyReturnNpsClosureReason.values)
  private implicit val decimalReads: Reads[BigDecimal]                            = Reads {
    case JsNumber(value) => JsSuccess(value)
    case _               => JsError("Expected a JSON number")
  }
  private val commonReads: Reads[MonthlyReturnNpsSubmissionCommon]                = Json.reads[MonthlyReturnNpsSubmissionCommon]
  private implicit val commonWrites: OWrites[MonthlyReturnNpsSubmissionCommon]    =
    Json.writes[MonthlyReturnNpsSubmissionCommon]

  private val standardSubscriptionReads: Reads[MonthlyReturnNpsStandardSubscription] = (
    commonReads and (__ \ "flexibleIsa").read[Boolean]
  )(MonthlyReturnNpsStandardSubscription.apply)

  private val standardClosureReads: Reads[MonthlyReturnNpsStandardClosure] = (
    commonReads and
      (__ \ "flexibleIsa").read[Boolean] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "reasonForClosure").read[MonthlyReturnNpsClosureReason]
  )(MonthlyReturnNpsStandardClosure.apply)

  private val lifetimeSubscriptionReads: Reads[MonthlyReturnNpsLifetimeSubscription] = (
    commonReads and
      (__ \ "dateOfFirstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal]
  )(MonthlyReturnNpsLifetimeSubscription.apply)

  private val lifetimeClosureReads: Reads[MonthlyReturnNpsLifetimeClosure] = (
    commonReads and
      (__ \ "dateOfFirstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "reasonForClosure").read[MonthlyReturnNpsClosureReason]
  )(MonthlyReturnNpsLifetimeClosure.apply)

  implicit val reads: Reads[MonthlyReturnNpsSubmission] = Reads { json =>
    (json \ "isaType").validate[MonthlyReturnNpsIsaType].flatMap { isaType =>
      val isClosure = (json \ "closureDate").toOption.nonEmpty || (json \ "reasonForClosure").toOption.nonEmpty
      (isaType == MonthlyReturnNpsIsaType.LIFETIME, isClosure) match {
        case (false, false) => standardSubscriptionReads.reads(json)
        case (false, true)  => standardClosureReads.reads(json)
        case (true, false)  => lifetimeSubscriptionReads.reads(json)
        case (true, true)   => lifetimeClosureReads.reads(json)
      }
    }
  }

  implicit val writes: Writes[MonthlyReturnNpsSubmission] = Writes { submission =>
    val common   = Json.toJson(submission.common).as[JsObject]
    val specific = submission match {
      case value: MonthlyReturnNpsStandardSubscription =>
        Json.obj("flexibleIsa" -> value.flexibleIsa)
      case value: MonthlyReturnNpsStandardClosure      =>
        Json.obj(
          "flexibleIsa"      -> value.flexibleIsa,
          "closureDate"      -> value.closureDate,
          "reasonForClosure" -> value.reasonForClosure
        )
      case value: MonthlyReturnNpsLifetimeSubscription =>
        Json.obj(
          "dateOfFirstSubscription" -> value.dateOfFirstSubscription,
          "lisaQualifyingAddition"  -> value.lisaQualifyingAddition,
          "lisaBonusClaim"          -> value.lisaBonusClaim
        )
      case value: MonthlyReturnNpsLifetimeClosure      =>
        Json.obj(
          "dateOfFirstSubscription" -> value.dateOfFirstSubscription,
          "lisaQualifyingAddition"  -> value.lisaQualifyingAddition,
          "lisaBonusClaim"          -> value.lisaBonusClaim,
          "closureDate"             -> value.closureDate,
          "reasonForClosure"        -> value.reasonForClosure
        )
    }
    common ++ specific
  }
}
