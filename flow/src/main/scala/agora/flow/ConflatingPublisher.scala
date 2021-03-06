package agora.flow

import cats.Semigroup
import cats.syntax.SemigroupSyntax
import cats.syntax.all._
import org.reactivestreams.Subscriber

/**
  * A publisher who will publish the state-of-the world for the first message on a new subscription
  *
  * @tparam T
  */
trait ConflatingPublisher[T] extends BasePublisher[T] with SemigroupSyntax {

  private[this] object SowLock

  private[this] var stateOfTheWorld: Option[T] = None

  implicit def semigroup: Semigroup[T]

  override protected def newSubscription(subscriber: Subscriber[_ >: T]): BasePublisher.BasePublisherSubscription[T] = {
    val s = super.newSubscription(subscriber)
    SowLock.synchronized {
      stateOfTheWorld.foreach(s.onElement)
    }
    s
  }

  override def publish(elem: T) = {
    val sg = implicitly[Semigroup[T]]
    SowLock.synchronized {
      val newSOW = stateOfTheWorld.fold(elem) { before =>
        sg.combine(before, elem)
      }
      stateOfTheWorld = newSOW.some
    }

    super.publish(elem)
  }

}

object ConflatingPublisher {
  def apply[T: Semigroup](mkQueue: () => ConsumerQueue[T]) = {
    val sg = implicitly[Semigroup[T]]
    new ConflatingPublisher[T] {
      override val semigroup = sg

      override def newDefaultSubscriberQueue() = mkQueue()
    }
  }

  def apply[T: Semigroup](maxCapacity: Int) = {
    val sg = implicitly[Semigroup[T]]
    new ConflatingPublisher[T] {
      override val semigroup = sg

      override def newDefaultSubscriberQueue() = ConsumerQueue.withMaxCapacity(maxCapacity)
    }
  }

  def apply[T: Semigroup](initialValue: Option[T] = None) = {
    val sg = implicitly[Semigroup[T]]
    new ConflatingPublisher[T] {
      override val semigroup = sg

      override def newDefaultSubscriberQueue() = ConsumerQueue(initialValue)
    }
  }
}
