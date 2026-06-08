package uk.gov.hmrc.disareturnssubmission.validators

object MonthValidator {

  private val ValidMonths: Range = 1 to 12

  def isValid(month: String): Boolean =
    parse(month).isDefined

  def parse(month: String): Option[Int] =
    Option(month)
      .flatMap(value => value.toIntOption)
      .filter(ValidMonths.contains)
}
