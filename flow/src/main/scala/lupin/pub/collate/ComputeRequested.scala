package lupin.pub.collate

/**
  * Something which will perform an overly-complicated
  */
private[collate] object ComputeRequested {

  /**
    * given a map of subscription IDs to the number of elements requested and a new 'request' Long,
    * compute how that request should be spread across the subscriptions
    *
    * @param currentlyRequestedById
    * @param request
    * @return
    */
  def apply[K](currentlyRequestedById: Iterator[(K, Long)], request: Long): Map[K, Long] = {
    if (currentlyRequestedById.isEmpty) {
      Map.empty
    } else {
      if (request == 1) {
        val (leastKey, _) = currentlyRequestedById.minBy(_._2)
        Map(leastKey -> 1)
      } else {

        val (total, maxRequested, root) = sort(currentlyRequestedById)
        root.calculateRequested(root, maxRequested, request, total)
      }
    }
  }

  private[collate] def sort[K](currentlyRequestedById: Iterator[(K, Long)]): (Int, Long, SortedDiffNode[K]) = {
    val first: SortedDiffNode[K] = {
      val (k, v) = currentlyRequestedById.next()
      new SortedDiffNode[K](k, v)
    }
    currentlyRequestedById.foldLeft((1, first.requested, first)) {
      case ((cnt, max, node), (k, v)) => (cnt + 1, max.max(v), node.insert(k, v))
    }
  }

  /** @param key
    * @param r
    */
  class SortedDiffNode[K](val key: K, r: Long) {
    def append(updateMap: Map[K, Long]): Map[K, Long] = {
      if (added > 0) {
        if (next != null) {
          next.append(updateMap.updated(key, added))
        } else {
          updateMap.updated(key, added)
        }
      } else if (next != null) {
        next.append(updateMap)
      } else {
        updateMap
      }
    }

    var next: SortedDiffNode[K] = null

    def requested = r + added

    var added = 0L

    def calculateRequested(head: SortedDiffNode[K],
                           maxRequestedFromAllSubscriptions: Long,
                           totalRemainingToRequest: Long,
                           totalSubscriptions: Int): Map[K, Long] = {
      // don't bother trying to make everything evenly subscribed if
      // the first and last subscriptions are already equal
      if (head.requested == maxRequestedFromAllSubscriptions) {
        head.fillEvenly(totalRemainingToRequest, totalSubscriptions)
      } else {
        val remaining = fillToMax(head, totalRemainingToRequest, totalSubscriptions)
        require(remaining == 0)
      }
      head.append(Map[K, Long]())
    }

    /**
      * Used to build the linked list/sorted chain
      *
      * @param k2
      * @param r2
      * @return
      */
    def insert(k2: K, r2: Long): SortedDiffNode[K] = {
      if (r2 > r) {
        if (next == null) {
          next = new SortedDiffNode[K](k2, r2)
          this
        } else {
          next = next.insert(k2, r2)
          this
        }
      } else {
        val d = new SortedDiffNode[K](k2, r2)
        d.next = this
        d
      }
    }

    /**
      * Adds some of the 'requested' to this and subsequent nodes
      */
    def inc(amountToTakeForEachNode: Long, amountToTakeForEachNodeRemainder: Long, remaining: Long): Long = {
      if (remaining > 0) {
        val remainder = amountToTakeForEachNodeRemainder.min(1)
        val take = (amountToTakeForEachNode + remainder).min(remaining)
        added = added + take
        if (next != null && next.requested < requested) {
          next.inc(amountToTakeForEachNode, amountToTakeForEachNodeRemainder - remainder, remaining - take)
        } else {
          remaining - take
        }
      } else {
        0
      }
    }

    /** all nodes are topped up, just distribute the remaining evenly
      */
    def fillEvenly(totalRemainingToRequest: Long,
                   totalSubscriptions: Int): Long = {
      val ave = totalRemainingToRequest / totalSubscriptions
      val mod = totalRemainingToRequest % totalSubscriptions
      inc(ave, mod, totalRemainingToRequest)
    }

    /**
      * try to ensure all nodes have the same nr of requested
      */
    def fillToMax(head: SortedDiffNode[K],
                  totalRemainingToRequest: Long,
                  totalSubscriptions: Int): Long = {
      if (totalRemainingToRequest > 0) {
        // if there's a difference between us and the next node,
        // fill from the beginning of the list
        if (next != null) {
          val diff = next.requested - requested
          if (diff > 0) {
            // always top-up from left to right
            val newRemaining = head.inc(diff, 0, totalRemainingToRequest)

            val nextNode = if (next.requested == requested) {
              next // we're even - crack on
            } else {
              head // start over
            }
            nextNode.fillToMax(head, newRemaining, totalSubscriptions)

          } else {
            // check the next one
            next.fillToMax(head, totalRemainingToRequest, totalSubscriptions)
          }
        } else {
          head.fillEvenly(totalRemainingToRequest, totalSubscriptions)
        }
      } else {
        0
      }
    }
  }

}