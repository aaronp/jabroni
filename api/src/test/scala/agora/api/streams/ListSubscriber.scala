package agora.api.streams

class ListSubscriber[T]() extends BaseSubscriber[T] {
  private var elms = List[T]()
  def received()   = elms
  def clear() = {
    elms = Nil
  }
  override def onNext(t: T): Unit = {
    elms = t :: elms
  }
}
