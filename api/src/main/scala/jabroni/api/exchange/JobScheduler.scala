package jabroni.api.exchange


import scala.concurrent.Future

trait JobScheduler {
  def send(request: ClientRequest): Future[ClientResponse] = request match {
    case req : SubmitJob => submit(req)
  }

  def submit(req: SubmitJob) = send(req).mapTo[SubmitJobResponse]
}
