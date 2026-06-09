package uk.gov.hmrc.disareturnssubmission

import base.TestConstants
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.test.DefaultAwaitTimeout
import play.api.test.Helpers.await
import uk.gov.hmrc.disareturnssubmission.utils.RequestUtils
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.play.audit.http.connector.DatastreamMetrics

import java.time.{Clock, ZoneOffset}
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

trait BaseIntegrationSpec extends AnyWordSpec
  with Matchers
  with GuiceOneServerPerSuite
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with DefaultAwaitTimeout
  with ScalaFutures
  with IntegrationPatience
  with TestConstants
  with RequestUtils {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(config)
    .overrides(
      bind[Clock].toInstance(Clock.fixed(testCreatedOn, ZoneOffset.UTC)),
      bind[DatastreamMetrics].toInstance(DatastreamMetrics.disabled)
    )
    .build()

  def config: Map[String, Any] =
    Map(
      "auditing.enabled"                    -> false,
      "create-internal-auth-token-on-start" -> false,
      "mongodb.uri"                         -> "mongodb://localhost:27017/disa-returns-backend-it"
    )

  protected def inject[T: ClassTag]: T =
    app.injector.instanceOf[T]

  implicit val ws: WSClient                       = inject[WSClient]
  implicit val executionContext: ExecutionContext = inject[ExecutionContext]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    clearMongoCollections()
  }

  def serviceUrl(path: String): String = s"http://localhost:$port$path"

  protected val emptyJson: JsObject             = Json.obj()
  protected val nilReturnFalseRequest: JsObject = Json.obj(nilReturnFieldName -> false)

  def clearMongoCollections(): Unit = {
    await(
      inject[MongoComponent]
        .database
        .getCollection(monthlyReturnsCollectionName)
        .deleteMany(Filters.empty())
        .toFuture()
    )
  }
}

