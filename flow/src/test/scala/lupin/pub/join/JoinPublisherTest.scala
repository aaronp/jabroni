package lupin.pub.join

import lupin.{BaseFlowSpec, ListSubscriber, Publishers}
import org.scalatest.GivenWhenThen

class JoinPublisherTest extends BaseFlowSpec with GivenWhenThen {

  def unpack(received: List[TupleUpdate[Int, Int]]): List[Int] = {
    received.flatMap {
      case BothUpdated(a, b) => List(a, b)
      case LeftUpdate(a) => List(a)
      case RightUpdate(a) => List(a)
    }
  }

  "JoinPublisher" should {
    "send values from both publishers" in {
      val joined = JoinPublisher(Publishers.of(1, 2, 3), Publishers.of(4, 5, 6, 7, 8))

      val sub1 = new ListSubscriber[TupleUpdate[Int, Int]]
      joined.subscribe(sub1)

      val sub2 = new ListSubscriber[TupleUpdate[Int, Int]]
      joined.subscribe(sub2)

      sub1.request(4)
      eventually {
        val actual: List[TupleUpdate[Int, Int]] = sub1.receivedInOrderReceived()

        unpack(actual) should contain only(1, 2, 4, 5)
      }

      sub2.request(2)
      eventually {
        sub2.receivedInOrderReceived() shouldBe List(
          BothUpdated(1, 4)
        )
      }

      sub1.request((1 to 8).size)
      eventually {
        val actual: List[TupleUpdate[Int, Int]] = sub1.receivedInOrderReceived()
        unpack(actual) should contain theSameElementsAs (1 to 8)
      }
    }
  }
}