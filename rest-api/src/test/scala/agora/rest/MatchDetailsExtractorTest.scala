package agora.rest

import java.time.LocalDateTime

import agora.BaseRestApiSpec
import agora.api.`match`.MatchDetails
import akka.http.scaladsl.model.HttpRequest

class MatchDetailsExtractorTest extends BaseRestApiSpec {

  "MatchDetailsExtractor.headersFor and unapply" should {
    "extract match details from request headers" in {

      val details = MatchDetails(
        matchId = "it was the best of times...",
        subscriptionKey = "the subscription key",
        jobId = "the job id",
        remainingItems = 123,
        LocalDateTime.now().withNano(0)
      )

      val headers = MatchDetailsExtractor.headersFor(details)
      val req     = HttpRequest().withHeaders(headers)
      MatchDetailsExtractor.unapply(req) shouldBe Some(details)
    }
  }

}
