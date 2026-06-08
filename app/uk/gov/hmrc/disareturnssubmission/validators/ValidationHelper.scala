package uk.gov.hmrc.disareturnssubmission.validators

import scala.Option.*

object ValidationHelper {

  def validateParams(
    zReference: String,
    taxYear: String,
    month: String
  ): Either[String, (String, String, Int)] = {
    val maybeMonth = MonthValidator.parse(month)

    val errors = Seq(
      when(!ZReferenceValidator.isValid(zReference))("zReference"),
      when(!TaxYearValidator.isValid(taxYear))("taxYear"),
      when(maybeMonth.isEmpty)("month")
    ).flatten

    errors match {
      case Nil           => Right((zReference.toUpperCase, taxYear, maybeMonth.get))
      case invalidFields => Left(s"Invalid monthly return submission fields: [${invalidFields.mkString(", ")}]")
    }
  }
}
