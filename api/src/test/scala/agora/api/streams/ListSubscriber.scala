package agora.api.streams

class ListSubscriber[T] extends BaseSubscriber[T](0) {
  private var elms = List[T]()
  def received()   = elms
  override def onNext(t: T): Unit = {
    elms = t :: elms
  }
}
