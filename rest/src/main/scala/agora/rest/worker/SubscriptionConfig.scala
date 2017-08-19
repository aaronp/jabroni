package agora.rest.worker

import agora.api.exchange.WorkSubscription
import agora.api.json.JMatcher
import agora.api.worker.{HostLocation, WorkerDetails}
import agora.rest.asJson
import com.typesafe.config.Config
import io.circe

import agora.domain.RichConfig.implicits._
import scala.util.Try

/**
  * A parsed work subscription
  *
  * @param subscriptionConfig
  */
case class SubscriptionConfig(subscriptionConfig: Config) {

  /** @param location the location of the worker
    * @return a WorkSubscription based on the configuration
    */
  def subscription(location: HostLocation): WorkSubscription = subscription(workerDetails(location))

  /** @param details the details of the worker
    * @return a WorkSubscription based on the configuration
    */
  def subscription(details: WorkerDetails): WorkSubscription = subscriptionEither(details) match {
    case Left(err) => sys.error(s"Couldn't parse the config as a subscription: $err")
    case Right(s)  => s
  }

  def workerDetails(location: HostLocation): WorkerDetails = {
    val detailsConf     = subscriptionConfig.getConfig("details")
    val name            = detailsConf.getString("name")
    val id              = detailsConf.getString("id")
    val path            = detailsConf.getString("path")
    val runUser: String = detailsConf.getString("runUser")
    WorkerDetails(location, path, name, id, runUser).append(asJson(detailsConf))
  }

  def asMatcher(at: String): Either[circe.Error, JMatcher] = {
    val fromConfig: Option[Either[circe.Error, JMatcher]] = Try(subscriptionConfig.getConfig(at)).toOption.map { subConf =>
      val json = asJson(subConf)
      json.as[JMatcher]
    }

    val fromString = asJson(subscriptionConfig).hcursor.downField(at).as[JMatcher]

    fromConfig.getOrElse(fromString)
  }

  def subscriptionEither(details: WorkerDetails): Either[circe.Error, WorkSubscription] = {
    for {
      jm <- asMatcher("jobMatcher").right
      sm <- asMatcher("submissionMatcher").right
    } yield {
      WorkSubscription.forDetails(details, jm, sm, subscriptionReferences)
    }
  }

  def subscriptionReferences = subscriptionConfig.asList("subscriptionReferences").toSet

}