package agora.api.worker

case class HostLocation(host: String, port: Int) {
  def asHostPort = s"$host:$port"
  def asURL      = s"http://$asHostPort"
}

object HostLocation {

  // doing this is very slow, but obviously all workers can just say 'localhost' to a central exchange
  //java.net.InetAddress.getLocalHost.getHostName
  private lazy val host = "localhost"

  def localhost(port: Int): HostLocation = HostLocation(host, port)
}
