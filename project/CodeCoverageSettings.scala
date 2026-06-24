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
    "uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator",
    "uk.gov.hmrc.disareturnssubmission.models.checkMd5Base64",
    "uk.gov.hmrc.disareturnssubmission.models.UuidFormat",
    "uk.gov.hmrc.disareturnssubmission.models.CreateMonthlyReturnRequest",
    "uk.gov.hmrc.disareturnssubmission.models.CreateMonthlyReturnResponse",
    "uk.gov.hmrc.disareturnssubmission.testOnly.*"
  )

  val settings: Seq[Setting[?]] = Seq(
    ScoverageKeys.coverageExcludedPackages := excludedPackages.mkString(";"),
    ScoverageKeys.coverageMinimumStmtTotal := 90,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
