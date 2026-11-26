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

enum LisaClosureReason {
  case CANCELLED, CLOSED, VOID, TRANSFERRED_IN_FULL, ALL_FUNDS_WITHDRAWN
}

enum IsaClosureReason {
  case CANCELLED, CLOSED, VOID
}

final case class MonthlyReturnNpsSubmissionCommon(
  accountIdentifier: String,
  closureDate: Option[LocalDate],
  dateOfBirth: LocalDate,
  firstForename: String,
  firstSubscription: Option[LocalDate],
  isaClosureReason: Option[IsaClosureReason] = None,
  isaType: MonthlyReturnNpsIsaType,
  lastSubscription: LocalDate,
  lisaBonusClaim: Option[BigDecimal],
  lisaClosureReason: Option[LisaClosureReason] = None,
  lisaQualifyingAddition: Option[BigDecimal],
  nationalInsuranceNumberAndSuffix: String,
  secondForename: Option[String],
  surname: String,
  totalSubscriptions: BigDecimal,
  totalTransferredIn: BigDecimal,
  totalTransferredOut: BigDecimal
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
  lisaClosureReason: LisaClosureReason
) extends MonthlyReturnNpsSubmission

final case class MonthlyReturnNpsLifetimeSubscription(
  common: MonthlyReturnNpsSubmissionCommon,
  firstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal
) extends MonthlyReturnNpsSubmission

final case class MonthlyReturnNpsLifetimeClosure(
  common: MonthlyReturnNpsSubmissionCommon,
  firstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal,
  closureDate: LocalDate,
  lisaClosureReason: LisaClosureReason
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

  private implicit val isaTypeFormat: Format[MonthlyReturnNpsIsaType]          = enumFormat(MonthlyReturnNpsIsaType.values)
  private implicit val lisaClosureReasonFormat: Format[LisaClosureReason]      =
    enumFormat(LisaClosureReason.values)
  private implicit val isaClosureReasonFormat: Format[IsaClosureReason]        =
    enumFormat(IsaClosureReason.values)
  private implicit val decimalReads: Reads[BigDecimal]                         = Reads {
    case JsNumber(value) => JsSuccess(value)
    case _               => JsError("Expected a JSON number")
  }
  private val commonReads: Reads[MonthlyReturnNpsSubmissionCommon]             = Json.reads[MonthlyReturnNpsSubmissionCommon]
  private implicit val commonWrites: OWrites[MonthlyReturnNpsSubmissionCommon] =
    Json.writes[MonthlyReturnNpsSubmissionCommon]

  private val standardSubscriptionReads: Reads[MonthlyReturnNpsStandardSubscription] = (
    commonReads and (__ \ "flexibleIsa").read[Boolean]
  )(MonthlyReturnNpsStandardSubscription.apply)

  private val standardClosureReads: Reads[MonthlyReturnNpsStandardClosure] = (
    commonReads and
      (__ \ "flexibleIsa").read[Boolean] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "lisaClosureReason").read[LisaClosureReason]
  )(MonthlyReturnNpsStandardClosure.apply)

  private val lifetimeSubscriptionReads: Reads[MonthlyReturnNpsLifetimeSubscription] = (
    commonReads and
      (__ \ "firstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal]
  )(MonthlyReturnNpsLifetimeSubscription.apply)

  private val lifetimeClosureReads: Reads[MonthlyReturnNpsLifetimeClosure] = (
    commonReads and
      (__ \ "firstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "lisaClosureReason").read[LisaClosureReason]
  )(MonthlyReturnNpsLifetimeClosure.apply)

  implicit val reads: Reads[MonthlyReturnNpsSubmission] = Reads { json =>
    (json \ "isaType").validate[MonthlyReturnNpsIsaType].flatMap { isaType =>
      val isClosure = (json \ "closureDate").toOption.nonEmpty || (json \ "lisaClosureReason").toOption.nonEmpty
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
          "flexibleIsa"       -> value.flexibleIsa,
          "closureDate"       -> value.closureDate,
          "lisaClosureReason" -> value.lisaClosureReason
        )
      case value: MonthlyReturnNpsLifetimeSubscription =>
        Json.obj(
          "firstSubscription"      -> value.firstSubscription,
          "lisaQualifyingAddition" -> value.lisaQualifyingAddition,
          "lisaBonusClaim"         -> value.lisaBonusClaim
        )
      case value: MonthlyReturnNpsLifetimeClosure      =>
        Json.obj(
          "firstSubscription"      -> value.firstSubscription,
          "lisaQualifyingAddition" -> value.lisaQualifyingAddition,
          "lisaBonusClaim"         -> value.lisaBonusClaim,
          "closureDate"            -> value.closureDate,
          "lisaClosureReason"      -> value.lisaClosureReason
        )
    }
    common ++ specific
  }
}
