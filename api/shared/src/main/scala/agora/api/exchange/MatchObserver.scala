package agora.api
package exchange

import java.util.UUID

import com.typesafe.scalalogging.StrictLogging
import agora.api.`match`.MatchDetails
import agora.api.exchange.Exchange.Match
import agora.api.worker.WorkerRedirectCoords

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._
import scala.util.control.NonFatal

/**
  * Represents a match 'handler' which delegates out to other observers
  */
trait MatchObserver extends Exchange.OnMatch with StrictLogging {

  import Exchange._
  import MatchObserver._

  private var observers = List[OnMatch]()

  def -=(observer: OnMatch): Boolean = {
    val before = observers.size
    observers = observers.diff(List(observer))
    before != observers.size
  }

  def +=[O <: OnMatch](observer: O): O = {
    observers = observer :: observers
    observer
  }

  /**
    * Appends a match observer which will trigger when it sees a match with the given job
    *
    * @return a future match
    */
  def onJob(job: SubmitJob)(implicit ec: ExecutionContext): Future[BlockingSubmitJobResponse] = {
    val promise = Promise[BlockingSubmitJobResponse]()

    onceWhen {
      case (`job`, workers) =>
        val idTry = job.jobId match {
          case Some(id) => Success(id)
          case None     => Failure(new Exception(s"no job id was set on $job"))
        }
        val coordsAndDetails = workers.map {
          case (key, workSubscription, remaining) =>
            val d = workSubscription.details
            val c = WorkerRedirectCoords(workSubscription.details.location, key, remaining)
            (c, d)
        }
        val (coords, details) = coordsAndDetails.unzip

        val respFuture = idTry.map { id =>
          BlockingSubmitJobResponse(nextMatchId(), id, epochUTC, coords.toList, details.toList)
        }
        promise.complete(respFuture)
        ()
    }
    promise.future
  }

  /**
    * Invoke the partial function when it applies, then remove it
    */
  def onceWhen(pf: PartialFunction[Match, Unit]): PartialHandler = +=(new PartialHandler(this, pf, true))

  /**
    * Always invoke the partial function whenever it applies
    */
  def alwaysWhen(pf: PartialFunction[Match, Unit]): PartialHandler = +=(new PartialHandler(this, pf, false))

  override def apply(jobMatch: Exchange.Match): Unit = {
    observers.foreach { obs =>
      try {
        obs(jobMatch)
      } catch {
        case NonFatal(e) =>
          logger.error(s"Observer threw $e on $jobMatch")
      }
    }
  }
}

object MatchObserver {

  class Instance extends MatchObserver

  def apply(): MatchObserver = new Instance

  abstract class BaseHandler extends Exchange.OnMatch {
    private val id = UUID.randomUUID()

    override def hashCode = id.hashCode() * 7

    override def equals(obj: Any) = obj match {
      case once: BaseHandler => once.id == id
      case _                 => false
    }
  }

  class PartialHandler(mo: MatchObserver, pf: PartialFunction[Match, Unit], removeAfterInvocation: Boolean) extends BaseHandler {
    override def apply(jobMatch: Exchange.Match): Unit = {
      if (pf.isDefinedAt(jobMatch)) {
        pf(jobMatch)
        if (removeAfterInvocation) {
          remove()
          ()
        }
      }
    }

    def remove(): Boolean = mo -= this
  }

}
