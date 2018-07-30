package riff

import agora.BaseIOSpec
import org.scalatest.GivenWhenThen
import riff.raft._

abstract class RiffSpec extends BaseIOSpec with GivenWhenThen {


  implicit def asRichNode(node: RaftNode) = new {
    private def prepare(line: String) = line.trim

    private def prepareLines(text: String): List[String] = {
      text.lines.map(prepare).filterNot(_.isEmpty).toList
    }

    def shouldMatch(str: String) = {
      val actual = prepareLines(node.toString)
      val expected = prepareLines(str)

      actual should contain theSameElementsInOrderAs (expected)
    }
  }

  implicit class LeaderHelper(sc: StringContext) {
    def leader(args: Any*): LeaderNode = {
      val text = sc.s(args: _*).stripMargin('>')
      leaderInState(text)
    }
  }


  // parses the toString repr of a node
  def leaderInState(state: String): LeaderNode = {

    /**
      * parses e.g.
      *
      * Peer   | Next Index | Match Index | Vote Granted
      * second | 1          | 0           | false
      * third  | 1          | 0           | false
      *
      * @param peers the peers lines (excluding the header)
      * @return a Peer by name
      */
    def parsePeers(peers: List[String]): Map[String, Peer] = {
      peers.map { row =>
        val List(name, nextIndex, matchIndex, voteGranted) = row.split("\\|", -1).toList
        val peer = Peer(name.trim, nextIndex.trim.toInt, matchIndex.trim.toInt, voteGranted.trim.toBoolean, 0, 0)
        peer.name -> peer
      }.toMap.ensuring(_.size == peers.size)
    }

    val ValueR = ".*: (.*)".r
    state.lines.toList.map(_.trim).filterNot(_.isEmpty) match {
      case ValueR(name) ::
        ValueR("Leader") ::
        ValueR(currentTerm) ::
        ValueR(_) :: // voted for
        ValueR(commitIndex) :: peers =>
        val data = NodeData(
          leaderOpinion = LeaderOpinion.Unknown(name, currentTerm.toInt),
          uncommittedLogIndex = commitIndex.toInt,
          peersByName = parsePeers(peers.tail)
        )
        LeaderNode(data)

      case other => sys.error(s"Couldn't parse $other")
    }
  }

}
