package agora.flow

import agora.flow.HistoricProcessorDao.InvalidIndexException

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits._

class HistoricProcessorDaoTest extends BaseFlowSpec {

  "HistoricProcessorDao.inDir" should {
    "only keep the specified number of elements" in {
      withDir { dir =>
        val dao = HistoricProcessorDao.inDir[String](dir, 2)

        dao.writeDown(10, "ten")
        dir.children.map(_.fileName).toList shouldBe List("10")

        dao.writeDown(11, "eleven")
        dir.children.map(_.fileName).toList should contain only ("10", "11")

        dao.writeDown(12, "twelve")
        withClue("10 should be deleted as we only are keeping 2") {
          dir.children.map(_.fileName).toList should contain only ("11", "12")
        }

        dao.at(12).futureValue shouldBe "twelve"
        dao.at(11).futureValue shouldBe "eleven"
        val invalid = intercept[InvalidIndexException] {
          Await.result(dao.at(10), testTimeout)
        }
        invalid shouldBe new InvalidIndexException(10)
      }
    }
  }
}