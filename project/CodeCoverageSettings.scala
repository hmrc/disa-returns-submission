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
    "uk.gov.hmrc.disareturnssubmission.models.CreateMonthlyReturnRequest",
    "uk.gov.hmrc.disareturnssubmission.models.CreateMonthlyReturnResponse",
    "uk.gov.hmrc.disareturnssubmission.models.FileUploadDetails",
    "uk.gov.hmrc.disareturnssubmission.models.FileUpload",
    "uk.gov.hmrc.disareturnssubmission.testOnly.*",
    "uk.gov.hmrc.disareturnssubmission.services.UuidGenerator",
    "uk.gov.hmrc.disareturnssubmission.models.UuidFormat"
  )

  val settings: Seq[Setting[_]] = Seq(
    ScoverageKeys.coverageExcludedPackages := excludedPackages.mkString(";"),
    ScoverageKeys.coverageMinimumStmtTotal := 90,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
