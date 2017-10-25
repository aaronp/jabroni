package agora.api.exchange.instances

import agora.api.exchange._
import agora.api.exchange.observer.{ExchangeObserver, OnMatch}

import scala.concurrent.Future
import scala.util.Try

/**
  * A default, ephemeral, non-thread-safe implementation of an exchange
  *
  * @param observer
  * @param matcher
  */
class ExchangeInstance(initialState: ExchangeState, observer: ExchangeObserver)(implicit matcher: JobPredicate) extends Exchange {

  private var state = initialState

  override def toString = state.toString

  override def queueState(request: QueueState): Future[QueueStateResponse] = {
    Future.fromTry(Try(state.queueState(request)))
  }

  override def subscribe(inputSubscription: WorkSubscription) = {
    val (ack, newState) = state.subscribe(inputSubscription, observer)

    state = newState
    Future.successful(ack)
  }

  override def updateSubscriptionDetails(update: UpdateSubscription): Future[UpdateSubscriptionAck] = {
    val ack: UpdateSubscriptionAck = state.subscriptionsById.get(update.id) match {
      case Some(old) =>
        state.updateSubscription(update, observer) match {

          // the update condition matched, and the state has been updated
          case Some(newState) =>
            state = newState
            checkMatchesAndUpdateState(newState, _.withSubscription(update.id))

            val newDetails = state.subscriptionsById(update.id)._1.details
            UpdateSubscriptionAck(update.id, Option(old._1.details), Option(newDetails))
          case None =>
            UpdateSubscriptionAck(update.id, Option(old._1.details), None)
        }

      // we don't know anything about the given subscription
      case None => UpdateSubscriptionAck(update.id, None, None)
    }
    Future.successful(ack)
  }

  override def take(request: RequestWork) = {
    val tri = state.request(request.id, request.itemsRequested)
    val ackTry: Try[RequestWorkAck] = tri.map {
      case (ack, newState) =>
        // if there weren't any jobs previously, then we may be able to take some work
        if (ack.isUpdatedFromEmpty) {

          val matches = checkMatchesAndUpdateState(newState, _.withSubscription(request.id))

          matches.flatMap(_.chosen).foldLeft(ack) {
            case (ack, chosen) => ack.withNewTotal(chosen.remaining)
          }
        } else {
          logger.debug(s"Not triggering match for subscription work item change of ${request.itemsRequested} on [${request.id}]")
          state = newState
          ack
        }
    }
    Future.fromTry(ackTry)
  }

  override def submit(inputJob: SubmitJob) = {
    val (ack, newState) = state.submit(inputJob)
    checkMatchesAndUpdateState(newState, _.withJob(ack.id))
    Future.successful(ack)
  }

  /**
    * Checks for matches w/ the given exchange after having received a job, a work subscription, or requested work
    * from a work subscription which previously had 0 work items requested (and thus would've prevented it from having
    * matched owt).
    *
    * To check for matches in the above scenarios, we don't need to check every single job or every single work
    * subscription -- we just need to reevaluate what's changed ... hence the 'filterState' predicate.
    *
    * @param newState    the initial state
    * @param filterState a predicate which will optionally return an exchange state centered around the event which
    *                    triggered this match check
    * @return the match notifications
    */
  private def checkMatchesAndUpdateState(newState: ExchangeState, filterState: ExchangeState => Option[ExchangeState]): List[OnMatch] = {
    // checks for matches on the filtered state, returning the notifications from said matches
    val notifications: List[OnMatch] =
      ExchangeInstance.checkForMatches(newState, filterState)

    // send out our notifications for matches and update the internal state
    publish(notifications)
    state = notifications.foldLeft(newState)(_ updateStateFromMatch _)

    notifications
  }

  private def publish(notifications: List[OnMatch]) = {
    notifications.foreach {
      case notification =>
        logger.debug(s"Triggering match $notification")
        observer.onMatch(notification)
    }
    notifications
  }

  override def cancelJobs(request: CancelJobs): Future[CancelJobsResponse] = {
    val (resp, newState) = state.cancelJobs(request)
    state = newState
    Future.successful(resp)
  }

  override def cancelSubscriptions(request: CancelSubscriptions): Future[CancelSubscriptionsResponse] = {
    val (resp, newState) = state.cancelSubscriptions(request.ids)
    state = newState
    Future.successful(resp)
  }
}

object ExchangeInstance {

  /**
    * Checks the jobs against the work subscriptions for matches using
    */
  private def checkForMatches(state: ExchangeState, filterState: ExchangeState => Option[ExchangeState])(implicit matcher: JobPredicate): List[OnMatch] = {
    val notifications = checkForMatchesRecursive(state, filterState)

    // as the notifications may be on updated SubmitJobs (e.g. the 'orElse' cases of jobs), we need to reinstate the
    // original jobs, not the 'orElse' SubmitJob produced by 'orElseSubmission'
    notifications.map { onMatch =>
      onMatch.copy(job = state.jobsById(onMatch.jobId))
    }
  }

  private def checkForMatchesRecursive(state: ExchangeState, filterState: ExchangeState => Option[ExchangeState])(
      implicit matcher: JobPredicate): List[OnMatch] = {
    filterState(state) match {
      case Some(filtered) =>
        val (notifications, newState) = filtered.matches
        newState.orElseState match {
          case Some(orElse) => checkForMatchesRecursive(orElse, filterState) ++ notifications
          case None         => notifications
        }
      case None => Nil
    }
  }
}
