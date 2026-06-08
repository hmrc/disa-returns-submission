import sbt.Keys.libraryDependencies
import sbt.*

object AppDependencies {

  private val bootstrapVersion = "10.7.0"
  private val hmrcMongoVersion = "2.12.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"   % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-30"          % hmrcMongoVersion,
    "org.typelevel"           %% "cats-core"                   % "2.13.0",
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30" % "2.5.0",
    "io.lemonlabs"      %% "scala-uri"                 % "4.0.3"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % bootstrapVersion % Test,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30" % hmrcMongoVersion % Test
  )

  val it: Seq[Nothing] = Seq.empty
}
