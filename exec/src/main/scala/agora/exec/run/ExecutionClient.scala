package agora.exec.run

import agora.domain.IterableSubscriber
import agora.exec.model.{RunProcess, RunProcessAndSave, RunProcessAndSaveResponse}
import agora.rest.client.RestClient
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.{implicitConversions, reflectiveCalls}

/**
  * A client of the [[agora.exec.rest.ExecutionRoutes]] and [[agora.exec.rest.UploadRoutes]]
  *
  * @param client
  */
case class ExecutionClient(override val client: RestClient, defaultFrameLength: Int, allowTruncation: Boolean)(implicit uploadTimeout: FiniteDuration)
    extends UploadClient
    with AutoCloseable {

  import client.materializer
  import client.executionContext

  /**
    * Execute the request
    * @param proc
    * @return the http response whose entity body contains the process output
    */
  def execute(proc: RunProcess): Future[HttpResponse] = {
    client.send(ExecutionClient.asRequest(proc))
  }

  def executeAndSave(proc: RunProcessAndSave): Future[HttpResponse] = {
    client.send(ExecutionClient.asRequest(proc))
  }

  /**
    * like execute, but returns a user-friendly return value
    * @param proc the process to run
    * @return the future of the process output
    */
  def run(proc: RunProcess): Future[Iterator[String]] = {
    execute(proc).map { httpResp =>
      val iter: Iterator[String] = IterableSubscriber.iterate(httpResp.entity.dataBytes, proc.frameLength.getOrElse(defaultFrameLength), allowTruncation)
      proc.filterForErrors(iter)
    }
  }

  def runAndSave(proc: RunProcessAndSave) = {
    import io.circe.generic.auto._
    executeAndSave(proc).flatMap { httpResp =>
      Unmarshal(httpResp).to[RunProcessAndSaveResponse]
    }
  }

  final def run(cmd: String, theRest: String*): Future[Iterator[String]] = run(RunProcess(cmd :: theRest.toList))

  override def close(): Unit = client.close()
}

object ExecutionClient extends RequestBuilding {

  import io.circe.generic.auto._
  import io.circe.syntax._

  def asRequest(job: RunProcess)(implicit ec: ExecutionContext) = {
    Post("/rest/exec/run", HttpEntity(ContentTypes.`application/json`, job.asJson.noSpaces))
  }
  def asRequest(job: RunProcessAndSave)(implicit ec: ExecutionContext) = {
    Post("/rest/exec/save", HttpEntity(ContentTypes.`application/json`, job.asJson.noSpaces))
  }
}
