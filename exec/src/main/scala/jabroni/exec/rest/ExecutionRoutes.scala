package jabroni.exec.rest

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.server.Directives.{entity, path, _}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import jabroni.api._
import jabroni.domain.io.implicits._
import jabroni.exec.ExecConfig
import jabroni.exec.log.IterableLogger._
import jabroni.exec.model.{OperationResult, RunProcess, Upload}
import jabroni.exec.ws.ExecuteOverWS
import jabroni.rest.worker.WorkerRoutes

import scala.concurrent.Future


object ExecutionRoutes {
  def apply(execConfig: ExecConfig) = new ExecutionRoutes(execConfig)
}

/**
  * Combines both the worker routes and some job output ones.
  *
  * NOTE: These routes are separate from the WorkerRoutes which handle
  * jobs that have been redirected from the exchange
  *
  * @param execConfig
  */
class ExecutionRoutes(val execConfig: ExecConfig) extends StrictLogging with FailFastCirceSupport {

  import execConfig._

  def routes(workerRoutes: WorkerRoutes, exchangeRoutes: Option[Route]): Route = {
    execConfig.routes(workerRoutes, exchangeRoutes) ~ jobRoutes
  }

  def jobRoutes = pathPrefix("rest" / "exec") {
    listJobs ~ removeJob ~ jobOutput ~ submitJobFromForm ~ runSavedJob ~ search
  }

  /**
    * Simply upload a job (RunProc), but don't execute it -- return an 'id' for it to be run.
    *
    * This is useful for the UI which makes one multipart request to upload/save a job, and
    * another to then read the output via web sockets
    */
  def submitJobFromForm: Route = post {
    path("submit") {
      extractRequestContext { reqCtxt =>
        import reqCtxt.{executionContext, materializer}

        entity(as[Multipart.FormData]) { (formData: Multipart.FormData) =>
          val jobId = nextJobId()

          def uploadDir = execConfig.uploads.dir(jobId).get

          val uploadFutures: Future[(RunProcess, List[Upload])] = {
            MultipartExtractor.fromUserForm(execConfig.uploads, formData, uploadDir, execConfig.chunkSize)
          }
          val savedIdFuture = uploadFutures.map {
            case (runProcess, uploads) =>
              val dao = execConfig.execDao
              dao.save(jobId, runProcess, uploads)
              Json.obj("jobId" -> Json.fromString(jobId))
          }

          complete {
            savedIdFuture
          }
        }
      }
    }
  }

  def runSavedJob: Route = get {
    (path("run") & pathEnd) {
      extractRequestContext { requestCtxt =>
        import requestCtxt.materializer

        requestCtxt.request.header[UpgradeToWebSocket] match {
          case None => complete(HttpResponse(400, entity = "Not a valid websocket request"))
          case Some(upgrade) =>

            logger.info(s"upgrading to web socket")

            complete {
              upgrade.handleMessages(ExecuteOverWS(execConfig))
            }
        }
      }
    }
  }

  def listJobs: Route = {
    get {
      (path("jobs") & pathEnd) {
        complete {
          val ids = logs.path.fold(List[Json]()) { dir =>
            dir.children.toList.sortBy(_.created.toEpochMilli).map { child =>
              Json.obj("id" -> Json.fromString(child.fileName), "started" -> Json.fromString(child.createdString))
            }
          }
          Json.arr(ids: _*)
        }
      }
    }
  }

  def search: Route = {
    get {
      (path("search") & pathEnd) {
        parameterMap { params =>
          complete {
            execDao.findJobsByMetadata(params).map { ids =>
              Json.arr(ids.map(Json.fromString).toArray: _*)
            }
          }
        }
      }
    }
  }

  /**
    * remove the output for a job
    */
  def removeJob = {
    delete {
      (path("job") & parameters('id.as[String])) { jobId =>
        complete {
          val json = onRemoveJob(jobId).asJson.noSpaces
          HttpResponse(entity = HttpEntity(`application/json`, json))
        }
      }
    }
  }

  private def onRemoveJob(jobId: JobId): OperationResult = {
    val logMsg = pathForJob("logs", logs.path, jobId, None) match {
      case Left(err) => err
      case Right(logDir) =>
        logDir.delete()
        s"Removed ${logDir.toAbsolutePath.toString}"
    }
    val jobUploadDir = uploads.dir(jobId).getOrElse(sys.error("upload dir can't be empty"))
    val uploadCount = jobUploadDir.children.size
    jobUploadDir.delete()
    val uploadMsg = s"Removed ${uploadCount} uploads"
    OperationResult(logMsg, uploadMsg)
  }

  /**
    * Get the output for a job
    */
  def jobOutput = {
    get {
      (path("job") & parameters('id.as[String], 'file.?)) {
        (jobId, fileName) =>
          complete {
            onJobOutput(jobId, fileName.getOrElse("std.out"))
          }
      }
    }
  }

  private def onJobOutput(jobId: JobId, logFileName: String): HttpResponse = {
    pathForJob("logs", logs.path, jobId, Option(logFileName)) match {
      case Left(err) =>
        HttpResponse(NotFound, entity = HttpEntity(`application/json`, OperationResult(err).asJson.noSpaces))
      case Right(logFile) =>
        val src = Source.fromIterator(() => logFile.lines).map(s => ByteString(s"$s\n"))
        HttpResponse(entity = HttpEntity(contentType = `text/plain(UTF-8)`, src))
    }
  }


  override def toString = {
    s"ExecutionRoutes {${execConfig.describe}}"
  }

}