package jabroni.api.exchange


trait FilteredPublisher[T, C] {
  def subscribe(subscriber: FilteringSubscriber[_ >: T, C])
}
