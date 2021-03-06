package agora.rest

import agora.BaseRestApiSpec
import agora.io.BaseActor
import akka.actor.Props

class AkkaImplicitsTest extends BaseRestApiSpec {

  def threads() = AkkaImplicits.allThreads()

  "AkkaImplicits.stop" should {
    "close all created threads when complete" ignore {

      val before = threads()

      val ai = AkkaImplicits("AkkaImplicitsTest", conf"akka.daemonic = off")
      ai.system.actorOf(Props[AkkaImplicitsTest.SomeActor])
      val mat = ai.materializer

      val after = threads()
      withClue("We expected AkkaImplicits to have created some threads") {
        after.size should be >= before.size
      }

      val akkaThreads = ai.threads()
      akkaThreads should not be (empty)

      after should contain allElementsOf (akkaThreads)

      ai.stop().futureValue

      ai.threads() shouldBe empty

    }
  }
}

object AkkaImplicitsTest {

  class SomeActor extends BaseActor {
    override def receive: Receive = {
      case _ =>
    }
  }

}
