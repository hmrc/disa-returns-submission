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

import org.bson.BsonDocument
import org.bson.conversions.Bson
import org.mongodb.scala.result.UpdateResult
import org.mongodb.scala.model.*
import play.api.libs.json.{JsArray, Json}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

private val declaredOnField  = "declaredOn"
private val lastUpdatedField = "lastUpdated"
private val monthField       = "month"
private val nilReturnField   = "nilReturn"
private val referenceField   = "reference"
private val statusField      = "status"
private val submissionsField = "submissions"
private val taxYearField     = "taxYear"
private val zReferenceField  = "zReference"

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

  def deleteByZReferences(zReferences: Seq[String]): Future[Long] =
    collection
      .deleteMany(Filters.in(zReferenceField, zReferences: _*))
      .toFuture()
      .map(_.getDeletedCount)

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

  def storeSubmission(
    zReference: String,
    taxYear: String,
    month: Int,
    reference: String,
    submissionDetails: SubmissionDetails
  ): Future[StoreSubmissionRepositoryResult] = {
    val storedOn         = now()
    val storedSubmission = Submission(
      reference = reference,
      status = SubmissionStatus.Stored,
      createdOn = storedOn,
      submissionDetails = Some(submissionDetails)
    )

    val matchingCreatedSubmission = Filters.elemMatch(
      submissionsField,
      Filters.and(
        Filters.equal(referenceField, reference),
        Filters.equal(statusField, SubmissionStatus.Created.value)
      )
    )
    val canCreateStoredSubmission = Filters.and(
      Filters.exists(declaredOnField, exists = false),
      Filters.equal(nilReturnField, false),
      Filters.not(Filters.elemMatch(submissionsField, Filters.equal(referenceField, reference)))
    )

    collection
      .updateOne(
        filter = Filters.and(
          byKey(zReference, taxYear, month),
          Filters.or(matchingCreatedSubmission, canCreateStoredSubmission)
        ),
        update = storeSubmissionUpdatePipeline(storedSubmission, submissionDetails, storedOn)
      )
      .toFuture()
      .flatMap {
        case result if result.getModifiedCount == 1 =>
          Future.successful(StoreSubmissionRepositoryResult.SubmissionStored)
        case _                                      =>
          get(zReference, taxYear, month).map {
            case Some(_) => StoreSubmissionRepositoryResult.SubmissionConflict
            case None    => StoreSubmissionRepositoryResult.MonthlyReturnNotFound
          }
      }
  }

  def declare(
    zReference: String,
    taxYear: String,
    month: Int,
    pendingSubmissionIds: List[String] = Nil
  ): Future[DeclareMonthlyReturnRepositoryResult] = {
    val declaredOn = now()

    val isNotDeclared = Filters.exists(declaredOnField, exists = false)

    val filter = Filters.and(
      byKey(zReference, taxYear, month),
      isNotDeclared
    )

    collection
      .updateOne(
        filter = filter,
        update = declarationUpdatePipeline(declaredOn, pendingSubmissionIds)
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

  private def storeSubmissionUpdatePipeline(
    submission: Submission,
    submissionDetails: SubmissionDetails,
    storedOn: Instant
  ): Seq[Bson] = {

    val existingSubmissionsExpression  =
      Json.obj("$ifNull" -> Json.arr("$" + submissionsField, Json.arr()))
    val referenceLiteral               = Json.obj("$literal" -> submission.reference)
    val isMatchingCreatedExpression    = Json.obj(
      "$and" -> Json.arr(
        Json.obj("$eq" -> Json.arr("$$existingSubmission.reference", referenceLiteral)),
        Json.obj("$eq" -> Json.arr("$$existingSubmission.status", SubmissionStatus.Created.value))
      )
    )
    val hasMatchingCreatedExpression   = Json.obj(
      "$anyElementTrue" -> Json.obj(
        "$map" -> Json.obj(
          "input" -> existingSubmissionsExpression,
          "as"    -> "existingSubmission",
          "in"    -> isMatchingCreatedExpression
        )
      )
    )
    val storedFieldsLiteral            = Json.obj(
      "$literal" -> Json.obj(
        statusField         -> SubmissionStatus.Stored.value,
        "submissionDetails" -> Json.toJson(submissionDetails)(SubmissionDetails.mongoFormat)
      )
    )
    val fulfilledSubmissionsExpression = Json.obj(
      "$map" -> Json.obj(
        "input" -> existingSubmissionsExpression,
        "as"    -> "existingSubmission",
        "in"    -> Json.obj(
          "$cond" -> Json.arr(
            isMatchingCreatedExpression,
            Json.obj("$mergeObjects" -> Json.arr("$$existingSubmission", storedFieldsLiteral)),
            "$$existingSubmission"
          )
        )
      )
    )
    val appendedSubmissionExpression   = Json.obj(
      "$concatArrays" -> Json.arr(
        existingSubmissionsExpression,
        Json.obj("$literal" -> Json.arr(Json.toJson(submission)(Submission.mongoFormat)))
      )
    )
    val submissionsExpression          = Json.obj(
      "$cond" -> Json.arr(
        hasMatchingCreatedExpression,
        fulfilledSubmissionsExpression,
        appendedSubmissionExpression
      )
    )
    val storedOnJson                   = MongoJavatimeFormats.instantWrites.writes(storedOn)

    Seq(
      BsonDocument.parse(
        Json.stringify(
          Json.obj(
            "$set" -> Json.obj(
              submissionsField -> submissionsExpression,
              lastUpdatedField -> storedOnJson
            )
          )
        )
      )
    )
  }

  private def declarationUpdatePipeline(
    declaredOn: Instant,
    pendingSubmissionIds: List[String]
  ): Seq[Bson] = {
    val pendingSubmissions = pendingSubmissionIds.distinct.map(reference =>
      Submission(
        reference = reference,
        status = SubmissionStatus.Created,
        createdOn = declaredOn
      )
    )

    val existingSubmissionsExpression =
      Json.obj("$ifNull" -> Json.arr("$" + submissionsField, Json.arr()))

    val existingReferencesExpression = Json.obj(
      "$map" -> Json.obj(
        "input" -> existingSubmissionsExpression,
        "as"    -> "existingSubmission",
        "in"    -> "$$existingSubmission.reference"
      )
    )

    val missingPendingSubmissionsExpression = Json.obj(
      "$filter" -> Json.obj(
        "input" -> Json.obj(
          "$literal" -> JsArray(pendingSubmissions.map(Json.toJson(_)(Submission.mongoFormat)))
        ),
        "as"    -> "pendingSubmission",
        "cond"  -> Json.obj(
          "$not" -> Json.arr(
            Json.obj(
              "$in" -> Json.arr(
                "$$pendingSubmission.reference",
                existingReferencesExpression
              )
            )
          )
        )
      )
    )

    val submissionsExpression = Json.obj(
      "$concatArrays" -> Json.arr(
        existingSubmissionsExpression,
        missingPendingSubmissionsExpression
      )
    )

    val declaredOnJson = MongoJavatimeFormats.instantWrites.writes(declaredOn)
    val setFields      = Json.obj(
      declaredOnField  -> declaredOnJson,
      submissionsField -> submissionsExpression,
      lastUpdatedField -> declaredOnJson
    )

    Seq(BsonDocument.parse(Json.stringify(Json.obj("$set" -> setFields))))
  }

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

sealed trait StoreSubmissionRepositoryResult

object StoreSubmissionRepositoryResult {
  case object SubmissionStored extends StoreSubmissionRepositoryResult
  case object SubmissionConflict extends StoreSubmissionRepositoryResult
  case object MonthlyReturnNotFound extends StoreSubmissionRepositoryResult
}

sealed trait UpdateNilReturnRepositoryResult

object UpdateNilReturnRepositoryResult {
  final case class NilReturnUpdated(monthlyReturn: MonthlyReturn) extends UpdateNilReturnRepositoryResult
  case object MonthlyReturnAlreadyDeclared extends UpdateNilReturnRepositoryResult
  case object MonthlyReturnNotFound extends UpdateNilReturnRepositoryResult
}
