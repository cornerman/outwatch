package outwatch.reactive

import scala.scalajs.js

trait SinkSourceHandler[-I, +O] extends SinkObserver[I] with SourceStream[O]

class SinkSourceBehavior[A](private var current: Option[A]) extends SinkSourceHandler[A, A] {

  private var subscribers = new js.Array[SinkObserver[A]]
  private var isRunning = false

  def onNext(value: A): Unit = {
    isRunning = true
    current = Some(value)
    subscribers.foreach(_.onNext(value))
    isRunning = false
  }

  def onError(error: Throwable): Unit = {
    isRunning = true
    subscribers.foreach(_.onError(error))
    isRunning = false
  }

  def subscribe[G[_] : Sink](sink: G[_ >: A]): Subscription = {
    val observer = SinkObserver.lift(sink)
    subscribers.push(observer)
    current.foreach(observer.onNext)
    Subscription { () =>
      if (isRunning) subscribers = subscribers.filter(_ != observer)
      else JSArrayHelper.removeElement(subscribers)(observer)
    }
  }
}

class SinkSourcePublisher[A] extends SinkSourceHandler[A, A] {

  private var subscribers = new js.Array[SinkObserver[A]]
  private var isRunning = false

  def onNext(value: A): Unit = {
    isRunning = true
    subscribers.foreach(_.onNext(value))
    isRunning = false
  }

  def onError(error: Throwable): Unit = {
    isRunning = true
    subscribers.foreach(_.onError(error))
    isRunning = false
  }

  def subscribe[G[_] : Sink](sink: G[_ >: A]): Subscription = {
    val observer = SinkObserver.lift(sink)
    subscribers.push(observer)
    Subscription { () =>
      if (isRunning) subscribers = subscribers.filter(_ != observer)
      else JSArrayHelper.removeElement(subscribers)(observer)
    }
  }
}

class SinkSourcePublisherToOne[A] extends SinkSourceHandler[A, A] {

  private var subscriber: SinkObserver[A] = null

  def onNext(value: A): Unit = if (subscriber != null) {
    subscriber.onNext(value)
  }

  def onError(error: Throwable): Unit = if (subscriber != null) {
    subscriber.onError(error)
  }

  def subscribe[G[_] : Sink](sink: G[_ >: A]): Subscription =
    if (subscriber == null) {
      subscriber = SinkObserver.lift(sink)
      Subscription { () => subscriber = null }
    } else {
      throw new IllegalStateException("Single Publisher has more than one subscriber")
    }
}

@inline class SinkSourceCombinator[SI[_] : Sink, SO[_] : Source, I, O](sink: SI[I], source: SO[O]) extends SinkSourceHandler[I, O] {

  @inline def onNext(value: I): Unit = Sink[SI].onNext(sink)(value)

  @inline def onError(error: Throwable): Unit = Sink[SI].onError(sink)(error)

  @inline def subscribe[G[_] : Sink](sink: G[_ >: O]): Subscription = Source[SO].subscribe(source)(sink)
}

object SinkSourceHandler {
  type Simple[T] = SinkSourceHandler[T,T]

  def apply[O]: Simple[O] = new SinkSourceBehavior[O](None)
  def apply[O](seed: O): Simple[O] = new SinkSourceBehavior[O](Some(seed))

  def publish[O]: Simple[O] = new SinkSourcePublisher[O]
  def publishToOne[O]: Simple[O] = new SinkSourcePublisherToOne[O]

  @inline def from[SI[_] : Sink, SO[_] : Source, I, O](sink: SI[I], source: SO[O]): SinkSourceHandler[I, O] = new SinkSourceCombinator[SI, SO, I, O](sink, source)

  @inline implicit class Operations[I,O](val handler: SinkSourceHandler[I,O]) extends AnyVal {
    @inline def transformSource[S[_] : Source, O2](g: SourceStream[O] => S[O2]): SinkSourceHandler[I, O2] = from[SinkObserver, S, I, O2](handler, g(handler))
    @inline def transformSink[G[_] : Sink, I2](f: SinkObserver[I] => G[I2]): SinkSourceHandler[I2, O] = from[G, SourceStream, I2, O](f(handler), handler)
    @inline def transformHandler[G[_] : Sink, S[_] : Source, I2, O2](f: SinkObserver[I] => G[I2])(g: SourceStream[O] => S[O2]): SinkSourceHandler[I2, O2] = from(f(handler), g(handler))
  }

  object createHandler extends CreateHandler[Simple] {
    @inline def publisher[A]: SinkSourceHandler[A, A] = SinkSourceHandler.publish[A]
    @inline def behavior[A]: SinkSourceHandler[A, A] = SinkSourceHandler.apply[A]
    @inline def behavior[A](seed: A): SinkSourceHandler[A, A] = SinkSourceHandler.apply[A](seed)
  }
  object createProHandler extends CreateProHandler[SinkSourceHandler] {
    @inline def from[SI[_] : Sink, SO[_] : Source, I,O](sink: SI[I], source: SO[O]): SinkSourceHandler[I, O] = SinkSourceHandler.from(sink, source)
  }
}
