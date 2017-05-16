package jabroni.exec

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory
import jabroni.exec.ExecutionWorker.onRun
import jabroni.rest.exchange.ExchangeClient
import jabroni.rest.multipart.MultipartPieces
import jabroni.rest.worker.{WorkContext, WorkerConfig}
import jabroni.domain.io.implicits._

import scala.concurrent.duration._
import scala.util.Try

class ExecConfig(val workerConfig: WorkerConfig) {
  def exec = workerConfig.config.getConfig("exec")

  import workerConfig.implicits._
  import jabroni.domain.io.implicits._

  private lazy val configuredWorker = {
    workerConfig.workerRoutes.addMultipartHandler { (req: WorkContext[MultipartPieces]) =>
      import jabroni.api.nextJobId
      val jobId = req.matchDetails.map(_.jobId).getOrElse(nextJobId)
      val logDir = baseLogDir.map(_.resolve(jobId).mkDirs())
      val uploadDir = baseUploadDir.resolve(jobId).mkDirs()
      val runner = ProcessRunner(uploadDir, workDir, logDir, errorLimit)
      req.completeWith(onRun(runner, req, uploadTimeout))
    }
    workerConfig
  }


  def start() = configuredWorker.startWorker

  override def toString = exec.root.render()

  def workDir = Try(exec.getString("workDir").asPath).toOption.filter(_.isDir)

  def baseUploadDir = exec.getString("uploadDir").asPath.mkDirs()

  def baseLogDir: Option[Path] = Try(exec.getString("logDir")).toOption.filterNot(_.isEmpty).map(_.asPath.mkDirs())

  def allowTruncation = exec.getBoolean("allowTruncation")

  def maximumFrameLength = exec.getInt("maximumFrameLength")

  def errorLimit = Option(exec.getInt("errorLimit")).filter(_ > 0)

  implicit def uploadTimeout: FiniteDuration = exec.getDuration("uploadTimeout", TimeUnit.MILLISECONDS).millis

  def remoteRunner(): ProcessRunner with AutoCloseable = {

    import workerConfig.implicits._

    ProcessRunner(workerConfig.exchangeClient, maximumFrameLength, allowTruncation)
  }
}

object ExecConfig {

  def apply(args: Array[String] = Array.empty) = {
    new ExecConfig(WorkerConfig(args, defaultConfig))
  }

  def defaultConfig = {
    val execConf = ConfigFactory.parseResourcesAnySyntax("exec")
    execConf.withFallback(WorkerConfig.baseConfig())
  }

}