package agora.rest.streams

import java.util.concurrent.atomic.AtomicInteger

import agora.BaseRestApiSpec
import agora.io.{IterableSubscriber, IteratorPublisher}

import concurrent.Future
import concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._

class IteratorPublisherTest extends BaseRestApiSpec {

  "IteratorPublisher" should {
    "publish the elements in an iterator" in {
      val tookCounter = new AtomicInteger(0)

      def newIter = Iterator.from(1).take(100).map { x =>
        tookCounter.incrementAndGet()
        x
      }

      val positiveInts = new IteratorPublisher(() => newIter)
      val subscriber   = new IterableSubscriber[Int](initialRequestToTake = 1)(pollTimeout = 1.second)

      // check 'hasNext' before subscribing
      val initialHnFuture = Future(subscriber.iterator.hasNext)
      initialHnFuture.isCompleted shouldBe false

      // call the method under test
      positiveInts.subscribe(subscriber)

      subscriber.iterator.next shouldBe 1

      subscriber.iterator.next shouldBe 2
      subscriber.iterator.next shouldBe 3
      subscriber.iterator.next shouldBe 4

      //... and on and on...
      tookCounter.get < 10 shouldBe true
    }
  }

}
