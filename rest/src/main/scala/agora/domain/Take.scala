package agora.domain

import scala.annotation.tailrec
import scala.collection.SeqLike
import scala.collection.generic.CanBuildFrom

object Take {

  def apply[T, Coll <: SeqLike[(Int, T), Coll]](n: Int, values: Coll)(implicit bf: CanBuildFrom[Coll, (Int, T), Coll]): (Coll, Coll) = {
    val took: Coll           = values.take(0)
    val retVal: (Coll, Coll) = takeRecursive[T, Coll](n, took, values)
    retVal
  }

  @tailrec
  private def takeRecursive[T, Coll <: SeqLike[(Int, T), Coll]](n: Int, took: Coll, values: Coll)(implicit bf: CanBuildFrom[Coll, (Int, T), Coll]): (Coll, Coll) = {
    values match {
      case coll if coll.isEmpty || n <= 0 => took -> coll
      case (head @ (x, _)) +: tail if x <= n =>
        val appended = head +: took
        takeRecursive[T, Coll](n - x, appended, tail.asInstanceOf[Coll])

      case (x, thing) +: tail =>
        val remaining = x - n
        val newValues = (remaining, thing) +: tail.asInstanceOf[Coll]
        val newTook   = (n, thing) +: took
        newTook -> newValues
    }
  }

}