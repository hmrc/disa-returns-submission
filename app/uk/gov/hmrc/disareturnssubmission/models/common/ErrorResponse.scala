package uk.gov.hmrc.disareturnssubmission.models.common

import play.api.libs.json.*
import uk.gov.hmrc.disareturnssubmission.models.*

trait ErrorResponse {
  def code: String
  def message: String
}

case class BadRequestErr(message: String) extends ErrorResponse {
  val code = "BAD_REQUEST"
}

case object UnauthorisedErr extends ErrorResponse {
  val code    = "UNAUTHORISED"
  val message = "Unauthorised"
}

case class InternalServerErr(
  message: String = "There has been an issue processing your request"
) extends ErrorResponse {
  val code = "INTERNAL_SERVER_ERROR"
}

case class ReturnNotFoundErr(message: String) extends ErrorResponse {
  val code = "RETURN_NOT_FOUND"
}

case object ReportNotFoundErr extends ErrorResponse {
  val code    = "REPORT_NOT_FOUND"
  val message = "Report not found"
}

case class ReportPageNotFoundErr private (message: String) extends ErrorResponse {
  val code = "PAGE_NOT_FOUND"
}

object ReportPageNotFoundErr {
  def apply(pageIndex: Int): ReportPageNotFoundErr = ReportPageNotFoundErr(s"No page $pageIndex found")
}

case object ObligationClosed extends ErrorResponse {
  val code    = "OBLIGATION_CLOSED"
  val message = "Obligation closed"
}

case object ReportingWindowClosed extends ErrorResponse {
  val code    = "REPORTING_WINDOW_CLOSED"
  val message = "Reporting window has been closed"
}

case object NinoOrAccountNumMissingErr extends ErrorResponse {
  val code    = "NINO_OR_ACC_NUM_MISSING"
  val message = "All models sent must include an account number and nino in order to process correctly"
}

case object NinoOrAccountNumInvalidErr extends ErrorResponse {
  val code    = "NINO_OR_ACC_NUM_INVALID"
  val message = "All models sent must include a valid account number and nino in order to process correctly"
}

case class MalformedJsonFailureErr(override val message: String) extends ErrorResponse {
  val code = "MALFORMED_JSON"
}

case object InvalidZReference extends ErrorResponse {
  val code    = "INVALID_Z_REFERENCE"
  val message = "Z reference is not formatted correctly"
}

case object InvalidTaxYear extends ErrorResponse {
  val code    = "INVALID_TAX_YEAR"
  val message = "Tax year is not formatted correctly"
}

case object InvalidMonth extends ErrorResponse {
  val code    = "INVALID_MONTH"
  val message = "Month is not formatted correctly"
}
case object EmptyPayload extends ErrorResponse {
  val code    = "EMPTY_PAYLOAD"
  val message =
    "NDJSON payload is empty. Please ensure the request body contains a valid NDJSON payload before resubmitting."
}

case object InvalidPageErr extends ErrorResponse {
  val code    = "INVALID_PAGE"
  val message = "Invalid page index parameter provided"
}

case object DuplicateNilReturnField extends ErrorResponse {
  val code    = "DUPLICATE_FIELD"
  val message = "Duplicate Nil Return field provided"
}

object ErrorResponse {

  implicit val returnNotFoundErrReads: Reads[ReturnNotFoundErr]             = Json.reads[ReturnNotFoundErr]
  implicit val reportPageNotFoundErrReads: Reads[ReportPageNotFoundErr]     = Json.reads[ReportPageNotFoundErr]
  implicit val malformedJsonFailureErrReads: Reads[MalformedJsonFailureErr] = Json.reads[MalformedJsonFailureErr]
  implicit val badRequestErrReads: Reads[BadRequestErr]                     = Json.reads[BadRequestErr]

  implicit val internalServerErrReads: Reads[InternalServerErr] =
    (JsPath \ "message")
      .readWithDefault[String](
        "There has been an issue processing your request"
      )
      .map(InternalServerErr.apply)

  private val singletons: Map[String, ErrorResponse] = Map(
    ObligationClosed.code           -> ObligationClosed,
    ReportingWindowClosed.code      -> ReportingWindowClosed,
    UnauthorisedErr.code            -> UnauthorisedErr,
    NinoOrAccountNumMissingErr.code -> NinoOrAccountNumMissingErr,
    NinoOrAccountNumInvalidErr.code -> NinoOrAccountNumInvalidErr,
    InvalidZReference.code          -> InvalidZReference,
    InvalidTaxYear.code             -> InvalidTaxYear,
    InvalidMonth.code               -> InvalidMonth,
    InvalidPageErr.code             -> InvalidPageErr,
    EmptyPayload.code               -> EmptyPayload,
    DuplicateNilReturnField.code    -> DuplicateNilReturnField
  )

  implicit val format: Format[ErrorResponse] = new Format[ErrorResponse] {
    override def reads(json: JsValue): JsResult[ErrorResponse] =
      (json \ "code").validate[String].flatMap {
        case "FORBIDDEN"                                                                                   =>
          Json.fromJson[MultipleErrorResponse](json)
        case "VALIDATION_FAILURE" if (json \ "errors").validate[Seq[SecondLevelValidationError]].isSuccess =>
          json.validate[SecondLevelValidationResponse]
        case "BAD_REQUEST"                                                                                 =>
          (json \ "errors").toOption match {
            case Some(_) => Json.fromJson[MultipleErrorResponse](json)
            case None    => Json.fromJson[BadRequestErr](json)
          }
        case "INTERNAL_SERVER_ERROR"                                                                       => internalServerErrReads.reads(json)
        case "RETURN_NOT_FOUND"                                                                            => returnNotFoundErrReads.reads(json)
        case "PAGE_NOT_FOUND"                                                                              => reportPageNotFoundErrReads.reads(json)
        case "MALFORMED_JSON"                                                                              => malformedJsonFailureErrReads.reads(json)
        case code if singletons.contains(code)                                                             => JsSuccess(singletons(code))
        case other                                                                                         => JsError(s"Unknown error code: $other")
      }

    override def writes(errorResponse: ErrorResponse): JsValue = errorResponse match {
      case m: MultipleErrorResponse =>
        Json.toJson(m)(MultipleErrorResponse.format)
      case v: FieldValidationError  =>
        Json.obj("code" -> v.code, "message" -> v.message, "path" -> v.path)
      case r: ReportPageNotFoundErr => Json.obj("code" -> r.code, "message" -> r.message)
      case error: ErrorResponse     =>
        Json.obj("code" -> error.code, "message" -> error.message)
    }
  }

  implicit def subtypeWrites[A <: ErrorResponse]: Writes[A] =
    format.contramap[A](er => er: ErrorResponse)
}

case class MultipleErrorResponse(
  code: String,
  message: String = "Multiple issues found regarding your submission",
  errors: Seq[ErrorResponse]
) extends ErrorResponse

object MultipleErrorResponse {
  implicit val format: OFormat[MultipleErrorResponse] = Json.format[MultipleErrorResponse]
}

case class ValidationFailureResponse(
  code: String = "VALIDATION_FAILURE",
  message: String = "Bad request",
  errors: Seq[FieldValidationError]
) extends ErrorResponse

object ValidationFailureResponse {
  implicit val format: OFormat[ValidationFailureResponse] = Json.format[ValidationFailureResponse]

  private def mapJsErrorToResponseCode(message: String): String = message match {
    case "error.path.missing" => "MISSING_FIELD"
    case _                    => "VALIDATION_ERROR"
  }

  private def formatFieldPath(jsPath: JsPath): String = {
    val pathString = jsPath.path
      .map {
        case KeyPathNode(key) => s"/$key"
        case IdxPathNode(idx) => s"/$idx"
      }
      .mkString("")

    if (pathString.isEmpty) "/" else pathString
  }

  def createFromJsError(jsError: JsError): ValidationFailureResponse = {

    val fieldErrors: Seq[FieldValidationError] = jsError.errors.toSeq.flatMap { case (path, errors) =>
      errors.map { validationError =>
        FieldValidationError(
          code = mapJsErrorToResponseCode(validationError.message),
          message = validationError.message match {
            case "error.path.missing"            => "This field is required"
            case "error.min"                     => "This field must be greater than or equal to 0"
            case "error.expected.validenumvalue" => "Invalid month provided"
            case "error.expected.enumstring"     => "Invalid month provided must be a string"
            case "error.expected.jsnumber"       => "This field must be greater than or equal to 0"
            case other                           => other
          },
          path = formatFieldPath(path)
        )
      }
    }

    ValidationFailureResponse(errors = fieldErrors)
  }
}

case class FieldValidationError(
  code: String,
  message: String,
  path: String
) extends ErrorResponse

object FieldValidationError {
  implicit val format: OFormat[FieldValidationError] = Json.format[FieldValidationError]
}

case class SecondLevelValidationResponse(
  code: String = "VALIDATION_FAILURE",
  message: String = "One or more models failed validation",
  errors: Seq[SecondLevelValidationError]
) extends ErrorResponse

object SecondLevelValidationResponse {
  implicit val format: OFormat[SecondLevelValidationResponse] = Json.format[SecondLevelValidationResponse]
}

case class SecondLevelValidationError(
  nino: String,
  accountNumber: String,
  code: String,
  message: String
) extends ErrorResponse

object SecondLevelValidationError {
  implicit val format: OFormat[SecondLevelValidationError] = Json.format[SecondLevelValidationError]
}
