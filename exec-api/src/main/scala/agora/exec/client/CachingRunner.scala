package agora.exec.client

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}

import agora.api.io.{CloseableIterator, MD5}
import agora.exec.model._
import agora.api.io.implicits._
import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object CachingRunner {
  type ProcessOutput = Future[Iterator[String]]

  def apply(underlying: ProcessRunner, computationThreshold: FiniteDuration, sizeThreshold: Int)(implicit ec: ExecutionContext) = {
    new InMemory(underlying, computationThreshold, sizeThreshold)
  }

  def apply(dir: Path, underlying: ProcessRunner)(implicit ec: ExecutionContext) = new ToDisk(dir, underlying)

  class ToDisk(dir: Path, underlying: ProcessRunner)(implicit ec: ExecutionContext) extends CachingRunner(underlying) {

    override def getFromCache(key: String, proc: StreamingProcess): Option[ProcessOutput] = {
      Option(dir.resolve(key).resolve("output")).filter(_.exists).map { path =>
        Future(path.lines)
      }
    }

    private object Lock

    override def writeToCache(key: String, proc: StreamingProcess, output: ProcessOutput): ProcessOutput = {
      val saveMe = Lock.synchronized {
        val file = dir.resolve(key)
        if (file.exists) {
          None
        } else {
          Option(file.mkDirs())
        }
      }

      saveMe match {
        case None => output
        case Some(dir) =>
          val started    = System.currentTimeMillis()
          val outputFile = dir.resolve("output").createIfNotExists()

          ???
          //dir.resolve("input").text = pprint.apply(proc).toString()

          output.map { iter =>
            val os = outputFile.outputStream(StandardOpenOption.APPEND)
            val writingIter = iter.map { line =>
              os.write(s"$line\n".getBytes(StandardCharsets.UTF_8))
              line
            }
            CloseableIterator(writingIter) {
              val took = System.currentTimeMillis - started
              try {
                dir.resolve(s"took-${took}").createIfNotExists()
                os.flush()
                os.close
              } catch {
                case NonFatal(e) =>
                  logger.error("error cleaning up " + e)
              }
            }
          }
      }
    }
  }

  class InMemory(underlying: ProcessRunner, computationThreshold: FiniteDuration, sizeThreshold: Int)(implicit ec: ExecutionContext) extends CachingRunner(underlying) {

    private object Lock

    private val outputByKey = mutable.HashMap[String, List[String]]()

    override def getFromCache(key: String, proc: StreamingProcess): Option[ProcessOutput] = {
      Lock.synchronized {
        outputByKey.get(key).map(list => Future.successful(list.iterator))
      }
    }

    def isCached(key: String) = {
      Lock.synchronized {
        outputByKey.contains(key)
      }
    }

    private[client] def cache(key: String, proc: StreamingProcess, started: Long, output: ProcessOutput): ProcessOutput = {
      val listBuffer = ListBuffer[String]()
      output.map { iter =>
        val writingIter = iter.zipWithIndex.map {
          case (line, size) if (size == sizeThreshold) =>
            listBuffer.clear()
            line
          case (line, size) if (size <= sizeThreshold) =>
            listBuffer += line
            line
          case (line, _) => line
        }
        CloseableIterator(writingIter) {
          val took = (System.currentTimeMillis - started).millis
          if (took >= computationThreshold && listBuffer.nonEmpty) {
            Lock.synchronized {
              outputByKey.update(key, listBuffer.toList)
            }
          }
        }
      }
    }

    override def writeToCache(key: String, proc: StreamingProcess, output: ProcessOutput): ProcessOutput = {
      if (!isCached(key)) {
        val started = System.currentTimeMillis()
        cache(key, proc, started, output)
      } else {
        output
      }
    }

  }

  private[client] def keyForProc(proc: RunProcess) = MD5(proc.command.mkString("^.^"))

}

abstract class CachingRunner(val underlying: ProcessRunner) extends ProcessRunner with StrictLogging {

  import CachingRunner._

  override def run(input: RunProcess): input.Result = {
    input match {
      case proc: StreamingProcess =>
        val key = keyForProc(proc)
        getFromCache(key, proc) match {
          case Some(res) =>
            logger.debug(s"using cached result $key")
            res.asInstanceOf[input.Result]
          case None =>
            logger.debug(s"caching result as $key")
            val res = writeToCache(key, proc, underlying.run(proc))
            res.asInstanceOf[input.Result]
        }
      case _ => underlying.run(input)
    }

  }

  def getFromCache(key: String, proc: StreamingProcess): Option[ProcessOutput]

  def writeToCache(key: String, proc: StreamingProcess, output: ProcessOutput): ProcessOutput

  protected def keyForProc(proc: RunProcess) = CachingRunner.keyForProc(proc)

}