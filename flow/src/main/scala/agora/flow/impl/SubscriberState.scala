package agora.flow.impl

import java.util.concurrent._

import agora.flow.DurableProcessorReader
import com.typesafe.scalalogging.StrictLogging
import org.reactivestreams.Subscriber

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * This contains the business logic which is executed within a separate runnable for reading from the
  * publisher and driving the [[org.reactivestreams.Subscriber]]
  *
  * This class is NOT thread-safe, but expects to be executed within a single thread.
  *
  * This state could be embedded in an actor which accepts [[agora.flow.impl.SubscriberState.Command]]
  * messages, or a simple Runnable which pulls commands from a queue
  */
class SubscriberState[T](subscription: Subscriber[_ >: T],
                         dao: DurableProcessorReader[T],
                         initialRequestedIndex: Long) extends StrictLogging {

  import SubscriberState._

  private var maxIndexAvailable: Long = -1L
  @volatile private var maxIndexRequested: Long = initialRequestedIndex
  private var lastIndexPushed: Long = initialRequestedIndex
  private var complete = false

  private[impl] def maxRequestedIndex() = maxIndexRequested

  /** Update the execution based on the given state
    *
    * @param cmd the command to process
    * @return the result
    */
  def update(cmd: Command): CommandResult = {
    try {
      logger.trace(s"handling $cmd")
      val res = onCommandUnsafe(cmd)
      logger.trace(s"$cmd returned $res")
      res
    } catch {
      case NonFatal(err) =>
        logger.error(s"$cmd threw $err")
        Try(subscription.onError(err))
        StopResult(Option(err))
    }
  }

  private def onCommandUnsafe(cmd: Command): CommandResult = {
    cmd match {
      case OnRequest(n) =>
        require(n > 0)
        maxIndexRequested = maxIndexRequested + n
        // As governed by rule 3.17, when demand overflows `Long.MAX_VALUE` we treat the signalled demand as "effectively unbounded"
        if (maxIndexRequested < 0L) {
          maxIndexRequested = Long.MaxValue
        }

        pushValues()
      case OnNewIndexAvailable(index) =>
        require(index >= maxIndexAvailable, s"Max index should never be decremented $maxIndexAvailable to $index")
        require(!complete, "OnNewIndexAvailable after complete")
        maxIndexAvailable = index
        pushValues()
      case OnCancel => StopResult(None)
      case OnError(err) =>
        subscription.onError(err)
        StopResult(Option(err))
      case OnComplete(index) =>
        complete = true
        require(index >= maxIndexAvailable, s"Max index should never be decremented $maxIndexAvailable to $index")
        maxIndexAvailable = index
        pushValues()
    }
  }

  private def pushValues[T](): CommandResult = {
    val max = maxIndexAvailable.min(maxIndexRequested)
    logger.trace(s"pushValues(req=$maxIndexRequested, available=$maxIndexAvailable, lastPushed=$lastIndexPushed, max=$max)")

    while (lastIndexPushed < max) {
      lastIndexPushed = lastIndexPushed + 1

      logger.trace(s"reading $lastIndexPushed")
      // TODO - request ranges
      dao.at(lastIndexPushed) match {
        case Success(value) =>
          subscription.onNext(value)
        case Failure(err) =>
          logger.error(s"subscriber was naughty and threw an exception on onNext for $lastIndexPushed: $err")
          subscription.onError(err)
          return StopResult(Option(err))
      }
    }
    tryComplete()

  }

  private def tryComplete(): CommandResult = {
    val res = if (complete) {
      if (lastIndexPushed >= maxIndexAvailable) {
        subscription.onComplete()
        StopResult(None)
      } else {
        ContinueResult
      }
    } else {
      ContinueResult
    }
    logger.trace(s"tryComplete(complete=$complete, lastIndexPushed=$lastIndexPushed, maxIndexAvailable=$maxIndexAvailable) returning $res")

    res
  }

}

object SubscriberState {

  /**
    * Represents something which can drive a subscription
    */
  trait Api {
    def send(cmd: Command): Future[CommandResult]

    def onRequest(n: Long) = send(OnRequest(n))

    def onNewIndexAvailable(maxIndex: Long) = send(OnNewIndexAvailable(maxIndex))

    def onCancel() = send(OnCancel)

    def onComplete(maxIndex: Long) = send(OnComplete(maxIndex))

    def onError(err: Throwable) = send(OnError(err))
  }

  private type Q = BlockingQueue[(SubscriberState.Command, Promise[CommandResult])]

  object Api {

    class QueueBasedApi(commands: Q) extends Api with StrictLogging {
      override def send(cmd: Command): Future[CommandResult] = {
        val promise = Promise[CommandResult]()
        logger.debug(s"queueing $cmd")
        commands.add(cmd -> promise)
        promise.future
      }
    }

    def apply[T](state: SubscriberState[T], capacity: Int)(implicit execContext: ExecutionContext) = {
      val queue = new ArrayBlockingQueue[(SubscriberState.Command, Promise[CommandResult])](capacity, true)

      val runnable = new SubscriberRunnable[T](state, queue)
      execContext.execute(runnable)
      new QueueBasedApi(queue)
    }
  }

  /** A runnable wrapper to drive the SubscriberState */
  class SubscriberRunnable[T](state: SubscriberState[T],
                              queue: Q) extends Runnable with StrictLogging {

    private def pullLoop() = {
      val (firstCmd, firstPromise) = next()
      var result: CommandResult = state.update(firstCmd)
      firstPromise.trySuccess(result)

      while (result == SubscriberState.ContinueResult) {
        val (cmd, promise) = next()
        result = state.update(cmd)
        promise.success(result)
      }
      result
    }

    private def next(): (Command, Promise[CommandResult]) = queue.take()

    override def run(): Unit = {
      logger.debug("Waiting for first command...")
      val result = Try(pullLoop())
      logger.debug(s"SubscriberRunnable completing with ${result}")
    }
  }

  /** The state input result */
  sealed trait CommandResult

  /** the carry-on-as-normal case */
  case object ContinueResult extends CommandResult

  /** the stop case, either by completion or exception/error */
  case class StopResult(error: Option[Throwable]) extends CommandResult

  /**
    * A state input
    */
  sealed trait Command

  case class OnRequest(n: Long) extends Command

  case class OnNewIndexAvailable(maxIndex: Long) extends Command

  case object OnCancel extends Command

  case class OnComplete(maxIndex: Long) extends Command

  case class OnError(error: Throwable) extends Command

}
