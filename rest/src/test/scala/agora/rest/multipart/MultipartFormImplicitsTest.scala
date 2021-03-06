package agora.rest.multipart

import agora.io.Sources
import agora.rest.multipart.MultipartFormImplicits._
import agora.rest.{BaseRoutesSpec, HasMaterializer}
import akka.http.scaladsl.model.Multipart
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.circe.Json
import io.circe.generic.auto._

import scala.concurrent.Future

class MultipartFormImplicitsTest extends BaseRoutesSpec {

  "MultipartDirectives" should {
    "upload" in {

      val text  = "This is a lot of text\n" * 1000000
      val bytes = ByteString(text)
      val data  = Source.single(bytes)
      val form = MultipartBuilder()
        .json("foo", MultipartFormImplicitsTest.JsonData("BB", 8))
        .text("something", "text")
        .fromStrictSource("upload1", bytes.size, data, fileName = "upload1.dat")
        .fromStrictSource("upload2", bytes.size, data, fileName = "upload2.dat")

      val testUploadRoute = post {
        (path("test")) {
          entity(as[Multipart.FormData]) { (formData: Multipart.FormData) =>
            complete {
              val sizesForKeysFuture: Future[List[Future[(String, Long)]]] = formData.mapMultipart {
                case (info, src) =>
                  val lenFuture = Sources.sizeOf(src)
                  lenFuture.map { len =>
                    info.fieldName -> len
                  }
              }
              sizesForKeysFuture.flatMap { sizesForKeys =>
                Future.sequence(sizesForKeys).fast.map { sizeByName =>
                  val pears = sizeByName.map {
                    case (name, len) => (name, Json.fromLong(len))
                  }
                  Json.obj(pears: _*)
                }
              }
            }
          }
        }
      }

      val fd: Multipart.FormData.Strict = form.formData.futureValue

      Post("/test", fd) ~> Route.seal(testUploadRoute) ~> check {
        response.status.intValue() shouldBe 200
        val sizeByName = responseAs[Map[String, Long]]
        sizeByName shouldBe Map("upload2" -> 22000000, "upload1" -> 22000000, "something" -> 4, "foo" -> 16)
      }
    }
  }
}

object MultipartFormImplicitsTest {

  case class JsonData(x: String, y: Int)

}
