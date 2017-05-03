package jabroni.api.worker

import java.net.InetAddress

import io.circe.{Encoder, Json}
import io.circe.optics.JsonPath
import jabroni.api.User
import jabroni.api.json.JsonAppendable

import scala.util.Properties

case class HostLocation(host: String, port: Int)

object HostLocation {
  def apply(port: Int): HostLocation = {
    val host = InetAddress.getLocalHost.getHostName
    HostLocation(host, port)
  }
}

/**
  * The 'aboutMe' should also contain the location/user
  *
  * @param aboutMe
  */
case class WorkerDetails(override val aboutMe: Json) extends JsonAppendable {

  def +[T: Encoder](data: T): WorkerDetails = append(WorkerDetails.asName(data.getClass), data)

  def +[T: Encoder](name: String, data: T): WorkerDetails = append(name, implicitly[Encoder[T]].apply(data))

  def append[T: Encoder](name: String, data: T): WorkerDetails = append(name, implicitly[Encoder[T]].apply(data))

  def append(name: String, data: Json): WorkerDetails = append(Json.obj(name -> data))

  def append(data: Json): WorkerDetails = copy(aboutMe.deepMerge(data))

  import WorkerDetails._

  def withData[T: Encoder](data: T, name: String = null) = copy(aboutMe = mergeJson(data, name))

  private def locationOpt: Option[HostLocation] = {
    for {
      host <- hostPath.getOption(aboutMe)
      port <- portPath.getOption(aboutMe)
    } yield HostLocation(host, port)
  }

  def location: HostLocation = locationOpt.getOrElse {
    sys.error(s"invalid json: 'location' not set: ${aboutMe}")
  }

  def runUser: User = runUserPath.getOption(aboutMe).getOrElse {
    sys.error(s"invalid json: 'runUser' not set: ${aboutMe}")
  }
}

object WorkerDetails {

  import io.circe.generic.auto._
  import io.circe.syntax._

  val locationPath = JsonPath.root.location
  val hostPath = locationPath.host.string
  val portPath = locationPath.port.int
  val pathPath = locationPath.path.int
  val runUserPath = JsonPath.root.runUser.string

  val defaultPort = 1234

  private case class DefaultDetails(runUser: String, location: HostLocation)

  def apply(runUser: String = Properties.userName, location: HostLocation = HostLocation(defaultPort)): WorkerDetails = {
    val details = DefaultDetails(runUser, location)
    val json = details.asJson
    WorkerDetails(json)
  }

  def asName(c1ass: Class[_]): String = {
    val name = c1ass.getSimpleName.replaceAllLiterally("$", "")
    name.headOption.fold("")(_.toLower +: name.tail)
  }
}