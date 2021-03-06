package agora.api.exchange

import agora.BaseIOSpec
import agora.api.Implicits
import agora.api.exchange.observer.{ExchangeObserver, ExchangeObserverDelegate, OnMatch}
import agora.json.JPredicate
import agora.api.worker.{HostLocation, WorkerDetails, WorkerRedirectCoords}
import io.circe.generic.auto._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.language.{postfixOps, reflectiveCalls}

trait ExchangeSpec extends BaseIOSpec with Eventually with Implicits {

  val someTime = agora.time.fromEpochNanos(0)

  implicit def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  import ExchangeSpec._

  /**
    * @return true when our exchange under test can support match observes (true for server-side, false for clients currently)
    */
  def supportsObserverNotifications = true

  def newExchange(observer: ExchangeObserver): Exchange

  def exchangeName = getClass.getSimpleName.filter(_.isLetter).replaceAllLiterally("Test", "")

  s"${exchangeName}.request" should {
    "reduce the requested items when given a negative number" in {
      val exchange                                      = ServerSideExchange()
      val (ack, _)                                      = exchange.subscribe(WorkSubscription.localhost(1), 3).futureValue
      val QueueStateResponse(Nil, List(initialPending)) = exchange.queueState().futureValue
      initialPending.requested shouldBe 3

      // call the method under test
      exchange.request(ack.id, -1).futureValue shouldBe RequestWorkAck(ack.id, 3, 2)
      val QueueStateResponse(Nil, List(reducedPending)) = exchange.queueState().futureValue
      reducedPending.requested shouldBe 2
    }
    "reduce the requested items when given a negative number for a dependent subscription" in {
      val exchange                                      = ServerSideExchange()
      val (ack, _)                                      = exchange.subscribe(WorkSubscription.localhost(1), 3).futureValue
      val QueueStateResponse(Nil, List(initialPending)) = exchange.queueState().futureValue
      initialPending.requested shouldBe 3

      // call the method under test
      exchange.request(ack.id, -101).futureValue shouldBe RequestWorkAck(ack.id, 3, 0)
      val QueueStateResponse(Nil, List(reducedPending)) = exchange.queueState().futureValue
      reducedPending.requested shouldBe 0
    }
    "reduce the requested items to zero when asked to reduce the requested items by more than there are remaining" in {

      val exchange = ServerSideExchange()
      val (ack, _) = exchange.subscribe(WorkSubscription.localhost(1), 10).futureValue

      val QueueStateResponse(Nil, List(initialPending)) = exchange.queueState().futureValue
      initialPending.requested shouldBe 10

      // create some another subscriptions with dependencies on others
      val subscriptionIds = (0 to 4).foldLeft(List(ack.id)) {
        case (subscriptionIds, _) =>
          val sub   = WorkSubscription.localhost(1).withReferences(Set(subscriptionIds.head))
          val newId = exchange.subscribe(sub).futureValue.id
          newId :: subscriptionIds
      }

      subscriptionIds.foldLeft(9) {
        case (expectedRemaining, id) =>
          // call the method under test
          exchange.request(id, -1).futureValue shouldBe RequestWorkAck(id, expectedRemaining + 1, expectedRemaining)
          val QueueStateResponse(Nil, pendingSubscriptions) = exchange.queueState().futureValue
          pendingSubscriptions.foreach { reducedPending =>
            reducedPending.requested shouldBe expectedRemaining
          }

          expectedRemaining - 1
      }
    }
  }

  s"$exchangeName.submit" should {

    def newSubscription(name: String) =
      WorkSubscription.forDetails(WorkerDetails(HostLocation.localhost(1234))).append("name", name)

    "match against orElse clauses if the original match criteria doesn't match" in {

      val job = "some job".asJob
        .matching("topic" === "primary")
        .orElse("topic" === "secondary")
        .orElse("topic" === "tertiary")
        .withId("someJobId")
        .withAwaitMatch(false)
      val anotherJob =
        "another job".asJob.matching(JPredicate.matchNone).withId("anotherId").withAwaitMatch(false)

      val primary =
        newSubscription("primary").append("topic", "primary").withSubscriptionKey("primary key")
      val tertiary =
        newSubscription("tertiary").append("topic", "tertiary").withSubscriptionKey("tertiary key")

      // verify some preconditions
      withClue("precondition proof that the subscriptions match the jobs as expected") {
        job.matches(primary, 1) shouldBe true
        job.matches(tertiary, 1) shouldBe false
        job.orElseSubmission.get.matches(tertiary, 1) shouldBe false
        job.orElseSubmission.get.orElseSubmission.get.matches(tertiary, 1) shouldBe true
      }

      // create our exchange
      val obs                = ExchangeObserverDelegate()
      val exchange: Exchange = newExchange(obs)

      // and set some
      var notifications = ListBuffer[OnMatch]()
      obs.alwaysWhen {
        case notification: OnMatch =>
          notifications += notification.copy(time = someTime)
      }

      // set up our subscriptions
      exchange.subscribe(primary).futureValue shouldBe WorkSubscriptionAck("primary key")
      // don't actually request any work items
      exchange.subscribe(tertiary).futureValue shouldBe WorkSubscriptionAck("tertiary key")
      exchange.request(tertiary.key.get, 3).futureValue shouldBe RequestWorkAck("tertiary key", 0, 3)

      // submit the job which won't match anything
      exchange.submit(anotherJob).futureValue shouldBe SubmitJobResponse(anotherJob.jobId.get)

      // submit our 'fallback' job. For tests w/ local exchanges (e.g. ones where 'supportsObserverNotifications' is
      // true), The 'awaitMatch' has no effect, and we use the observerr directly. For remote cases (where
      // 'supportsObserverNotifications' is false), we can block and case the response future as a BlockingSubmitJobResponse
      val BlockingSubmitJobResponse(_, _, _, workerCoords, workerDetails) = {
        // await our match...
        val matchFuture = obs.awaitJob(job)

        // submit the job .. it should match the 'tertiary' orElse clause, but use the original job in the match notification
        val submitResponse = exchange.submit(job).futureValue
        submitResponse shouldBe SubmitJobResponse(job.jobId.get)

        matchFuture.futureValue
      }

      workerCoords should contain only (WorkerRedirectCoords(HostLocation("localhost", 1234), "tertiary key", 2))
      workerDetails should contain only (tertiary.details)

      val QueueStateResponse(actualJobs, actualSubscriptions) = exchange.queueState().futureValue

      withClue("the queue state should now only contain the unmatched job") {
        actualJobs should contain only (anotherJob)
      }
      actualSubscriptions should contain allOf (PendingSubscription(primary.key.get, primary, 0),
      PendingSubscription(tertiary.key.get, tertiary, 2))

      // we should've matched tertiary
      if (supportsObserverNotifications) {
        notifications should contain only (OnMatch(someTime, "someJobId", job, List(Candidate(tertiary.key.get, tertiary, 2))))
      }
    }
  }

  exchangeName should {
    "be able to cancel subscriptions" in {
      val obs          = ExchangeObserverDelegate()
      val ex: Exchange = newExchange(obs)

      val subscription: WorkSubscriptionAck =
        ex.subscribe(WorkSubscription(HostLocation.localhost(1234))).futureValue

      // check out queue
      val subscriptions = ex.queueState().futureValue.subscriptions.map(_.key)
      subscriptions should contain only (subscription.id)

      // call the method under test
      ex.cancelSubscriptions(subscription.id, "unknown").futureValue.cancelledSubscriptions shouldBe Map(subscription.id -> true, "unknown" -> false)

      // check out queue
      val afterCancel = ex.queueState().futureValue.subscriptions
      afterCancel should be(empty)
    }
    "be able to cancel jobs" in {
      val obs          = ExchangeObserverDelegate()
      val ex: Exchange = newExchange(obs)

      val input = DoubleMe(11).asJob.withAwaitMatch(false)
      input.jobId should be(empty)

      val jobId = ex.submit(input).futureValue.asInstanceOf[SubmitJobResponse].id

      // check out queue
      val queuedJobs = ex.queueState().futureValue.jobs.flatMap(_.jobId)
      queuedJobs should contain only (jobId)

      // call the method under test
      ex.cancelJobs(jobId, "unknownJob").futureValue.cancelledJobs shouldBe Map(jobId -> true, "unknownJob" -> false)

      // check out queue
      val afterCancel = ex.queueState().futureValue.jobs
      afterCancel should be(empty)
    }

    if (supportsObserverNotifications) {
      "match jobs with work subscriptions" in {

        object obs extends ExchangeObserverDelegate
        var matches = List[OnMatch]()
        obs.alwaysWhen {
          case jobMatch: OnMatch => matches = jobMatch :: matches
        }
        val ex: Exchange = newExchange(obs)

        val jobId = ex
          .submit(DoubleMe(11).asJob.withAwaitMatch(false))
          .futureValue
          .asInstanceOf[SubmitJobResponse]
          .id
        val jobPath = ("value" gt 7) and ("value" lt 17)

        val sub = WorkSubscription(HostLocation.localhost(1234), jobCriteria = jobPath)

        val subscriptionId = ex.subscribe(sub).futureValue.id
        ex.request(subscriptionId, 1).futureValue shouldBe RequestWorkAck(subscriptionId, 0, 0)

        matches.size shouldBe 1
      }
    }
  }

  implicit override def patienceConfig =
    PatienceConfig(timeout = scaled(Span(3, Seconds)), interval = scaled(Span(150, Millis)))

}

object ExchangeSpec {

  case class DoubleMe(value: Int)

}
