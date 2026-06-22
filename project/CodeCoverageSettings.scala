import sbt.Setting
import scoverage.ScoverageKeys

object CodeCoverageSettings {

  private val excludedPackages: Seq[String] = Seq(
    "<empty>",
    "Reverse.*",
    "uk.gov.hmrc.BuildInfo",
    "app.*",
    "prod.*",
    ".*Routes.*",
    "testOnly.*",
    "testOnlyDoNotUseInAppConf.*",
    ".*\\$anon.*",
    "uk.gov.hmrc.disareturnssubmission.Module",
    "uk.gov.hmrc.disareturnssubmission.AppInitialiser",
    "uk.gov.hmrc.disareturnssubmission.services.UuidGenerator",
    "uk.gov.hmrc.disareturnssubmission.utils.*",
    "uk.gov.hmrc.disareturnssubmission.models.*",
    "uk.gov.hmrc.disareturnssubmission.testOnly.*",
    "uk.gov.hmrc.disareturnssubmission.connectors.*"
  )

  val settings: Seq[Setting[_]] = Seq(
    ScoverageKeys.coverageExcludedPackages := excludedPackages.mkString(";"),
    ScoverageKeys.coverageMinimumStmtTotal := 90,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
