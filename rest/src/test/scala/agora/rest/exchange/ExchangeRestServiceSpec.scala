package agora.rest.exchange

import agora.api.Implicits._
import agora.api.exchange.{AsClient, QueueStateResponse}
import agora.io.IterableSubscriber
import agora.rest.ClientConfig
import agora.rest.implicits._
import agora.rest.integration.BaseIntegrationTest
import agora.rest.worker.{WorkContext, WorkerClient}
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.circe.Json
import io.circe.optics.JsonPath

import scala.language.reflectiveCalls

trait ExchangeRestServiceSpec { self: BaseIntegrationTest =>

  "Exchange rest service" should {
    "Stream work from workers" in {

      val subscriptionAckFuture = worker.service.addHandler[Int] { (ctxt: WorkContext[Int]) =>
        def iter = Iterator.continually(ctxt.request).zipWithIndex.take(100).map {
          case (offset, n) =>
            val res = offset + n
            ByteString.fromString(s"$res\n".toString)
        }

        ctxt.completeWithSource(Source.fromIterator(() => iter))
      }
      subscriptionAckFuture.futureValue

      implicit val strResp: AsClient[Int, Iterator[String]] = {
        val mkRequest = AsClient.lift[Int, HttpResponse] { dispatchJob =>
          val client = WorkerClient(workerConfig.clientConfig, dispatchJob)
          client.sendRequest(dispatchJob.request)
        }
        mkRequest.map { resp =>
          IterableSubscriber.iterate(resp.entity.dataBytes, 1000, true)
        }
      }

      val strings = exchangeClient.enqueue(12345).futureValue

      val stringList = strings.toList
      stringList.head shouldBe "12345"
      stringList.last shouldBe "12444"
      stringList.size shouldBe 100
    }
    "Route match responses to workers" in {
      // the path to our echoed response
      val gotPath = JsonPath.root.got.string

      var workerRequests = List[Json]()

      // verify preconditions
      val initialQueue: QueueStateResponse = exchangeClient.queueState().futureValue
      initialQueue.isEmpty shouldBe true

      /**
        * Add a worker which will reply to work with 'got : XYZ'
        */
      val subscriptionAckFuture = worker.service.addHandler[Json] { req =>
        workerRequests = req.request :: workerRequests
        req.completeWithJson(Json.obj("got" -> req.request))
      }
      val subscriptionKey = subscriptionAckFuture.futureValue.id

      // verify the queue is as we expected
      val queue = exchangeClient.queueState().futureValue
      queue.subscriptions.size shouldBe 1
      val List(ourSubscription) = queue.subscriptions
      ourSubscription.key shouldBe subscriptionKey
      ourSubscription.requested shouldBe worker.service.defaultInitialRequest

      implicit val clientConf: ClientConfig = workerConfig.clientConfig

      val jsonResp = exchangeClient.enqueue[String, Json]("hello world!").futureValue
      val found    = gotPath.getOption(jsonResp)
      val actual   = found.get
      actual shouldBe "hello world!"
    }
  }
}
