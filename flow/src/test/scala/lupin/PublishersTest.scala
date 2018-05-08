package lupin

import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually

class PublishersTest extends BaseFlowSpec with GivenWhenThen {

  "Publishers.apply(iter)" should {
    "only progress the iterator when elements are requested" in {
      var hasNextRetVal = true
      object Iter extends Iterator[Int] {
        var lastValue = 0

        override def hasNext: Boolean = {
          hasNextRetVal
        }

        override def next(): Int = {
          val x = lastValue
          lastValue = lastValue + 1
          x
        }
      }

      Given("A publisher for an iterator")
      val publisherUnderTest = Publishers(Iter)

      When("A subscription is added")
      val sub1 = new ListSubscriber[Int]
      publisherUnderTest.subscribe(sub1)

      Then("No values should yet be requested from the iterator")
      Iter.lastValue shouldBe 0

      When("A subscription requests some values")
      val sub2 = new ListSubscriber[Int]
      sub2.request(2)
      publisherUnderTest.subscribe(sub2)

      Then("Only the values necessary should be requested of the iterator")
      eventually {
        Iter.lastValue shouldBe 2
      }

      When("The first subscription is advanced, but not past the second")
      sub1.request(2)

      Then("No more elements should be requested of the iterator")
      eventually {
        sub1.receivedInOrderReceived() shouldBe List(0, 1)
        sub2.receivedInOrderReceived() shouldBe List(0, 1)
      }
      Iter.lastValue shouldBe 2


    }

  }
  "Publishers.forList" should {
    "publish the values to all subscribers" in {
      val p = Publishers.forList(List(1, 2, 3, 4))

      val sub = new ListSubscriber[Int]
      p.subscribe(sub)

      sub.receivedInOrderReceived() shouldBe Nil
      sub.request(3)
      eventually {
        sub.receivedInOrderReceived() shouldBe List(1, 2, 3)
      }

      val sub2 = new ListSubscriber[Int]
      p.subscribe(sub2)
      sub2.receivedInOrderReceived() shouldBe Nil
      sub2.request(10)
      eventually {
        sub2.receivedInOrderReceived() shouldBe List(1, 2, 3, 4)
        sub2.isCompleted() shouldBe true
      }

      sub.receivedInOrderReceived() shouldBe List(1, 2, 3)
      sub.isCompleted() shouldBe false
    }
  }

}