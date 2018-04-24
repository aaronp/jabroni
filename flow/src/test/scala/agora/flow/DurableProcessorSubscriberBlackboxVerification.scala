package agora.flow

import agora.flow.DurableProcessorPublisherVerification._
import agora.flow.impl.DurableProcessorInstance
import agora.io.Lazy
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.testng.annotations.AfterTest

class DurableProcessorSubscriberBlackboxVerification extends SubscriberBlackboxVerification[Int](testEnv) {

  private val lazyCtxt = Lazy(newContextWithThreadPrefix(getClass.getSimpleName))

  implicit def ctxt = lazyCtxt.value

  @AfterTest
  def afterAll(): Unit = {
    lazyCtxt.foreach(_.shutdown())
  }
  override def createSubscriber() = {
    val dp: DurableProcessorInstance[Int] = DurableProcessor[Int]()
    dp.requestIndex(10)
    dp
  }

  override def createElement(element: Int): Int = element
}
