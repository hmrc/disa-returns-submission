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

package uk.gov.hmrc.disareturnssubmission.repositories

import org.bson.conversions.Bson
import org.mongodb.scala.result.UpdateResult
import org.mongodb.scala.model.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

private val declaredOnField          = "declaredOn"
private val lastUpdatedField         = "lastUpdated"
private val monthField               = "month"
private val nilReturnField           = "nilReturn"
private val submissionsField         = "submissions"
private val submissionReferenceField = "submissions.reference"
private val taxYearField             = "taxYear"
private val zReferenceField          = "zReference"

@Singleton
class MonthlyReturnRepository @Inject() (
  mongoComponent: MongoComponent,
  appConfig: AppConfig,
  clock: Clock
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[MonthlyReturn](
      mongoComponent = mongoComponent,
      collectionName = "monthlyReturns",
      domainFormat = MonthlyReturn.mongoFormat,
      indexes = Seq(
        IndexModel(
          Indexes.ascending(lastUpdatedField),
          IndexOptions()
            .name("monthlyReturnsTtl")
            .expireAfter(appConfig.monthlyReturnTimeToLiveInDays, TimeUnit.DAYS)
        ),
        IndexModel(
          Indexes.ascending(zReferenceField, taxYearField, monthField),
          IndexOptions()
            .name("monthlyReturnKeyIdx")
            .unique(true)
        )
      ),
      replaceIndexes = true
    ) {

  def create(
    zReference: String,
    taxYear: String,
    month: Int,
    submissionId: UUID,
    nilReturn: Boolean
  ): Future[Option[MonthlyReturn]] = {
    val createdOn = now()

    val monthlyReturn = MonthlyReturn(
      zReference = zReference,
      submissionId = submissionId,
      taxYear = taxYear,
      month = month,
      createdOn = createdOn,
      nilReturn = nilReturn,
      submissions = Nil,
      lastUpdated = createdOn
    )

    collection
      .insertOne(monthlyReturn)
      .toFuture()
      .map(result => Option.when(result.wasAcknowledged())(monthlyReturn))
      .recover { case DuplicateKey(_) => None }
  }

  def createSubmission(
    zReference: String,
    taxYear: String,
    month: Int,
    reference: String,
    submissionDetails: SubmissionDetails
  ): Future[Boolean] = {
    val createdOn = now()

    val submission = Submission(
      reference = reference,
      createdOn = createdOn,
      submissionDetails = Some(submissionDetails)
    )

    val isNotNilReturn           = Filters.equal(nilReturnField, false)
    val hasNoDuplicateSubmission = Filters.ne(submissionReferenceField, reference)
    val filter                   = Filters.and(
      byKey(zReference, taxYear, month),
      isNotNilReturn,
      hasNoDuplicateSubmission
    )

    val updateWithNewSubmission = Updates.combine(
      Updates.push(submissionsField, submissionBson(submission)),
      Updates.set(lastUpdatedField, createdOn)
    )

    collection
      .updateOne(
        filter = filter,
        update = updateWithNewSubmission
      )
      .toFuture()
      .map(_.getModifiedCount == 1)
  }

  def declare(zReference: String, taxYear: String, month: Int): Future[DeclareMonthlyReturnRepositoryResult] = {
    val declaredOn = now()

    val isNotDeclared = Filters.exists(declaredOnField, exists = false)

    val filter = Filters.and(
      byKey(zReference, taxYear, month),
      isNotDeclared
    )

    val updateDeclaration = Updates.combine(
      Updates.set(declaredOnField, declaredOn),
      Updates.set(lastUpdatedField, declaredOn)
    )

    collection
      .updateOne(
        filter = filter,
        update = updateDeclaration
      )
      .toFuture()
      .flatMap(toDeclareMonthlyReturnRepositoryResult(zReference, taxYear, month))
  }

  def declareNilReturn(
    zReference: String,
    taxYear: String,
    month: Int
  ): Future[DeclareMonthlyReturnRepositoryResult] = {
    val declaredOn = now()

    val isNotDeclared = Filters.exists(declaredOnField, exists = false)

    val filter = Filters.and(
      byKey(zReference, taxYear, month),
      isNotDeclared
    )

    val updateDeclarationAndClearSubmissions = Updates.combine(
      Updates.set(nilReturnField, true),
      Updates.set(submissionsField, List.empty[Submission]),
      Updates.set(declaredOnField, declaredOn),
      Updates.set(lastUpdatedField, declaredOn)
    )

    collection
      .updateOne(
        filter = filter,
        update = updateDeclarationAndClearSubmissions
      )
      .toFuture()
      .flatMap(toDeclareMonthlyReturnRepositoryResult(zReference, taxYear, month))
  }

  def deleteAll(): Future[Long] =
    collection
      .deleteMany(Filters.empty())
      .toFuture()
      .map(_.getDeletedCount)

  def get(zReference: String, taxYear: String, month: Int): Future[Option[MonthlyReturn]] =
    collection
      .find(byKey(zReference, taxYear, month))
      .headOption()

  private def byKey(zReference: String, taxYear: String, month: Int): Bson =
    Filters.and(
      Filters.equal(zReferenceField, zReference),
      Filters.equal(taxYearField, taxYear),
      Filters.equal(monthField, month)
    )

  private def now(): Instant = Instant.now(clock)

  private def submissionBson(submission: Submission) =
    Codecs.toBson(submission)(Submission.mongoFormat)

  private def toDeclareMonthlyReturnRepositoryResult(
    zReference: String,
    taxYear: String,
    month: Int
  )(result: UpdateResult): Future[DeclareMonthlyReturnRepositoryResult] =
    if (result.getModifiedCount == 1) {
      Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
    } else {
      get(zReference, taxYear, month).map {
        case Some(monthlyReturn) if monthlyReturn.hasDeclaration =>
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared
        case _                                                   =>
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }
    }
}

sealed trait DeclareMonthlyReturnRepositoryResult

object DeclareMonthlyReturnRepositoryResult {
  case object MonthlyReturnDeclared extends DeclareMonthlyReturnRepositoryResult

  case object MonthlyReturnAlreadyDeclared extends DeclareMonthlyReturnRepositoryResult

  case object MonthlyReturnNotFound extends DeclareMonthlyReturnRepositoryResult
}

sealed trait UpdateNilReturnRepositoryResult

object UpdateNilReturnRepositoryResult {
  final case class NilReturnUpdated(monthlyReturn: MonthlyReturn) extends UpdateNilReturnRepositoryResult
  case object MonthlyReturnAlreadyDeclared extends UpdateNilReturnRepositoryResult
  case object MonthlyReturnNotFound extends UpdateNilReturnRepositoryResult
}
