package uk.gov.hmrc.disareturnssubmission.controllers.parsers

import com.fasterxml.jackson.core.{JsonFactory, JsonParseException, JsonParser}
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.{BodyParser, RequestHeader, Result}
import play.api.mvc.Results.BadRequest
import uk.gov.hmrc.disareturnssubmission.models.common.DuplicateNilReturnField

import scala.concurrent.ExecutionContext

class StrictOptionalJsonBodyParser @Inject() ()(implicit ec: ExecutionContext) extends BodyParser[Option[JsValue]] with Logging {

  private val mapper: ObjectMapper =
    new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))

  override def apply(request: RequestHeader): Accumulator[ByteString, Either[Result, Option[JsValue]]] =
    Accumulator(Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)).map { bytes =>
      if (bytes.isEmpty)
        Right(None)
      else {
        val raw = bytes.toArray
        try {
          mapper.readTree(raw)
          Right(Some(Json.parse(raw)))
        } catch {
          case _: JsonParseException =>
            logger.warn("Duplicate NilReturn Field Detected")
            Left(BadRequest(Json.toJson(DuplicateNilReturnField)))
        }
      }
    }
}
