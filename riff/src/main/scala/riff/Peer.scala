package riff

/**
  * The view of a peer in the cluster
  * @param name
  * @param nextIndex the latest index required by the peer as understood by the leader. This can be updated/maintained w/o consulting the node, but rather immediately upon the leader receiving an append request.
  * @param matchIndex
  * @param voteGranted
  * @param lastRpcSent
  * @param lastHeartbeatSent
  */
case class Peer(name: String,
                nextIndex: Int,
                matchIndex: Int,
                voteGranted: Boolean,
                lastRpcSent : Long,
                lastHeartbeatSent : Long) {
  require(nextIndex > 0)
  require(matchIndex >= 0)
}

object Peer {


  def apply(peerName : String): Peer = initial(peerName)

  def initial(peerName : String): Peer = {
    Peer(name = peerName,
      nextIndex = 1,
      matchIndex = 0,
      voteGranted = false,
      lastRpcSent = 0,
      lastHeartbeatSent = 0
    )
  }
}



