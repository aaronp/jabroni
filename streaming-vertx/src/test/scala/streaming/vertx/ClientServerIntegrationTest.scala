package streaming.vertx

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.typesafe.scalalogging.StrictLogging
import io.vertx.lang.scala.ScalaVerticle
import monix.execution.Cancelable
import streaming.api._
import streaming.api.sockets.WebFrame
import streaming.rest.EndpointCoords
import streaming.vertx.client.Client
import streaming.vertx.server.{Server, ServerEndpoint}

import scala.collection.mutable.ListBuffer

class ClientServerIntegrationTest extends BaseStreamingApiSpec with StrictLogging {

  "Server.start / Client.connect" should {
    "route endpoints accordingly" in {
      val port = 1236
      val UserIdR = "/user/(.*)".r


      val started: ScalaVerticle = Server.start(port) {
        case "/admin" => endpt =>
          endpt.toRemote.onNext(WebFrame.text("Thanks for connecting to admin"))
          endpt.toRemote.onComplete()
        case UserIdR(user) => _.handleTextFramesWith { clientMsgs =>
          clientMsgs.map(s"$user : " + _)
        }
      }

      try {
        var adminResults: List[String] = null
        val admin = Client.connect(EndpointCoords.get(port, "/admin"), "test admin client") { endpoint =>
          endpoint.toRemote.onNext(WebFrame.text("already, go!"))
          endpoint.toRemote.onComplete()

          endpoint.fromRemote.toListL.runAsync.foreach { list =>
            adminResults = list.flatMap(_.asText)
          }
        }
        try {
          eventually {
            adminResults shouldBe List("Thanks for connecting to admin")
          }
        } finally {
          admin.stop()
        }


        val resultsByUser = new java.util.concurrent.ConcurrentHashMap[String, List[String]]()
        val clients: Seq[Client] = Seq("Alice", "Bob", "Dave").zipWithIndex.map {
          case (user, i) =>
            Client.connect(EndpointCoords.get(port, s"/user/$user")) { endpoint =>
              endpoint.toRemote.onNext(WebFrame.text(s"client $user ($i) sending message")).onComplete { _ =>
                endpoint.toRemote.onComplete()
              }
              endpoint.fromRemote.toListL.runAsync.foreach { clientList =>
                resultsByUser.put(user, clientList.flatMap(_.asText))
              }
            }
        }
        eventually {
          resultsByUser.get("Alice") shouldBe List("Alice : client Alice (0) sending message")
        }
        eventually {
          resultsByUser.get("Bob") shouldBe List("Bob : client Bob (1) sending message")
        }
        eventually {
          resultsByUser.get("Dave") shouldBe List("Dave : client Dave (2) sending message")
        }
        clients.foreach(_.stop())

      } finally {
        started.stop()
      }
    }
    "notify the server when the client completes" in {

      val port = 1235

      val messagesReceivedByTheServer = ListBuffer[String]()
      val messagesReceivedByTheClient = ListBuffer[String]()
      var serverReceivedOnComplete = false

      def chat(endpoint: Endpoint[WebFrame, WebFrame]): Cancelable = {
        endpoint.handleTextFramesWith { incomingMsgs =>
          val obs = "Hi - you're connected to an echo-bot" +: incomingMsgs.doOnNext( msg =>
            messagesReceivedByTheServer += msg
          ).map("echo: " + _)

          obs.doOnComplete { () =>
            logger.debug("from remote on complete")
            serverReceivedOnComplete = true
          }
        }
      }

      val started: ScalaVerticle = Server.startSocket(port)(chat)
      var c: Client = null
      try {

        val gotFive = new CountDownLatch(5)
        c = Client.connect(EndpointCoords.get(port, "/some/path")) { endpoint =>
          endpoint.toRemote.onNext(WebFrame.text("from client"))
          endpoint.fromRemote.zipWithIndex.foreach {
            case (frame, i) =>
              logger.debug(s"$i Client got : " + frame)
              messagesReceivedByTheClient += frame.asText.getOrElse("non-text message received from server")
              gotFive.countDown()
              endpoint.toRemote.onNext(WebFrame.text(s"client sending: $i"))
              if (gotFive.getCount == 0) {
                logger.debug("completing client...")
                endpoint.toRemote.onComplete()
              }
          }
        }

        gotFive.await(testTimeout.toMillis, TimeUnit.MILLISECONDS) shouldBe true
        eventually {
          serverReceivedOnComplete shouldBe true
        }

        eventually {
          messagesReceivedByTheClient should contain inOrder("Hi - you're connected to an echo-bot",
            "echo: from client",
            "echo: client sending: 0",
            "echo: client sending: 1",
            "echo: client sending: 2",
            "echo: client sending: 3")
        }

        eventually {
          messagesReceivedByTheServer should contain inOrder("from client",
            "client sending: 0",
            "client sending: 1",
            "client sending: 2",
            "client sending: 3")
        }

      } finally {
        started.stop()
        if (c != null) {
          c.stop()
        }
      }

    }
    "connect to a server" in {

      val port = 1234

      val receivedFromServer = new CountDownLatch(1)
      var fromServer = ""
      val receivedFromClient = new CountDownLatch(1)
      var fromClient = ""

      // start the server
      val started: ScalaVerticle = Server.startSocket(port) { endpoint: ServerEndpoint =>

        endpoint.toRemote.onNext(WebFrame.text(s"hello from the server at ${endpoint.socket.path}"))

        endpoint.fromRemote.foreach { msg: WebFrame =>
          msg.asText.foreach(fromClient = _)
          receivedFromClient.countDown()
        }
      }

      val c: Client = Client.connect(EndpointCoords.get(port, "/some/path")) { endpoint =>
        endpoint.fromRemote.foreach { msg =>
          msg.asText.foreach(fromServer = _)
          receivedFromServer.countDown()
        }
        endpoint.toRemote.onNext(WebFrame.text("from the client"))
      }

      receivedFromServer.await(testTimeout.toMillis, TimeUnit.MILLISECONDS)
      receivedFromClient.await(testTimeout.toMillis, TimeUnit.MILLISECONDS)

      fromServer shouldBe "hello from the server at /some/path"
      fromClient shouldBe "from the client"

      c.stop()
      started.stop()
    }
  }
}