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
  * This state could be embedded in an actor which accepts [[SubscriberStateCommand]]
  * messages, or a simple Runnable which pulls commands from a queue
  */
class SubscriberState[T](subscription: Subscriber[_ >: T], dao: DurableProcessorReader[T], initialRequestedIndex: Long) extends StrictLogging {

  private var maxIndexAvailable: Long           = -1L
  @volatile private var maxIndexRequested: Long = initialRequestedIndex
  private var lastIndexPushed: Long             = initialRequestedIndex
  private var complete                          = false

  private[impl] def maxRequestedIndex() = maxIndexRequested

  /** Update the execution based on the given state
    *
    * @param cmd the command to process
    * @return the result
    */
  def update(cmd: SubscriberStateCommand): SubscriberStateCommandResult = {
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

  private def onCommandUnsafe(cmd: SubscriberStateCommand): SubscriberStateCommandResult = {
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

  private def pushValues[T](): SubscriberStateCommandResult = {
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

  private def tryComplete(): SubscriberStateCommandResult = {
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
    def send(cmd: SubscriberStateCommand): Future[SubscriberStateCommandResult]

    def onRequest(n: Long) = send(OnRequest(n))

    def onNewIndexAvailable(maxIndex: Long) = send(OnNewIndexAvailable(maxIndex))

    def onCancel() = send(OnCancel)

    def onComplete(maxIndex: Long) = send(OnComplete(maxIndex))

    def onError(err: Throwable) = send(OnError(err))
  }

  private[impl] type Q = BlockingQueue[(SubscriberStateCommand, Promise[SubscriberStateCommandResult])]

  def newQ(capacity: Int) = new ArrayBlockingQueue[(SubscriberStateCommand, Promise[SubscriberStateCommandResult])](capacity, true)

  /**
    * what we're trying to do here is conflate the queue by collapsing all similar commnds
    *
    * @param commands
    * @param cmd
    * @return
    */
  private[impl] def enqueue(commands: Q, cmd: SubscriberStateCommand, capacitySizeCheckLimit: Int) = {
    val promise = Promise[SubscriberStateCommandResult]()
    commands.add(cmd -> promise)

    if (commands.size() > capacitySizeCheckLimit) {
      SubscriberStateCommand.conflate(commands)
    }
    promise.future
  }

  object Api {

    class QueueBasedApi(commands: Q, capacitySizeCheckLimit: Int) extends Api with StrictLogging {

      override def send(cmd: SubscriberStateCommand): Future[SubscriberStateCommandResult] = {
        logger.trace(s"enqueueing $cmd")
        enqueue(commands, cmd, capacitySizeCheckLimit)
      }
    }

    def apply[T](state: SubscriberState[T], capacity: Int, conflateCommandQueueLimit: Option[Int] = None)(implicit execContext: ExecutionContext) = {
      val queue = newQ(capacity)

      val runnable = new SubscriberRunnable[T](state, queue)
      execContext.execute(runnable)
      val capacitySizeCheckLimit = conflateCommandQueueLimit.getOrElse {
        (capacity * 0.7).toInt.min(100)
      }
      require(capacitySizeCheckLimit > 0, s"Invalid capacitySizeCheckLimit '$capacitySizeCheckLimit': we should conflate commands before they reach capacity")
      require(capacitySizeCheckLimit < capacity,
              s"Invalid capacitySizeCheckLimit '$capacitySizeCheckLimit': we should conflate commands before they reach capacity")
      new QueueBasedApi(queue, capacitySizeCheckLimit)
    }
  }

  /** A runnable wrapper to drive the SubscriberState */
  class SubscriberRunnable[T](state: SubscriberState[T], queue: Q) extends Runnable with StrictLogging {

    private def pullLoop() = {
      val (firstCmd, firstPromise)             = next()
      var result: SubscriberStateCommandResult = state.update(firstCmd)
      firstPromise.trySuccess(result)

      while (result == ContinueResult) {
        val (cmd, promise) = next()
        result = state.update(cmd)
        promise.trySuccess(result)
      }
      result
    }

    private def next(): (SubscriberStateCommand, Promise[SubscriberStateCommandResult]) = queue.take()

    override def run(): Unit = {
      logger.debug("Waiting for first command...")
      val result = Try(pullLoop())
      logger.debug(s"SubscriberRunnable completing with ${result}")
    }
  }

}
