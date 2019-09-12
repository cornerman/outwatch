package outwatch.reactive

import cats.{MonoidK, Contravariant}

trait SinkObserver[-A] {
  def onNext(value: A): Unit
  def onError(error: Throwable): Unit
}
object SinkObserver {

  object Empty extends SinkObserver[Any] {
    @inline def onNext(value: Any): Unit = ()
    @inline def onError(error: Throwable): Unit = ()
  }

  @inline def empty = Empty

  class Connectable[-T](
    val sink: SinkObserver[T],
    val connect: () => Subscription
  )
  @inline def connectable[T](sink: SinkObserver[T], connect: () => Subscription): Connectable[T] = new Connectable(sink, connect)

  @inline def lift[F[_] : Sink, A](sink: F[A]): SinkObserver[A] =  create(Sink[F].onNext(sink), Sink[F].onError(sink))

  def create[A](consume: A => Unit, failure: Throwable => Unit = t => throw t): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = consume(value)
    def onError(error: Throwable): Unit = failure(error)
  }

  def combine[F[_] : Sink, A](sinks: F[A]*): SinkObserver[A] = combineSeq(sinks)

  def combineSeq[F[_] : Sink, A](sinks: Seq[F[A]]): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = {
      sinks.foreach(Sink[F].onNext(_)(value))
    }
    def onError(error: Throwable): Unit = {
      sinks.foreach(Sink[F].onError(_)(error))
    }
  }

  def combineVaried[F[_] : Sink, G[_] : Sink, A](sinkA: F[A], sinkB: G[A]): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = {
      Sink[F].onNext(sinkA)(value)
      Sink[G].onNext(sinkB)(value)
    }
    def onError(error: Throwable): Unit = {
      Sink[F].onError(sinkA)(error)
      Sink[G].onError(sinkB)(error)
    }
  }

  def contramap[F[_] : Sink, A, B](sink: F[A])(f: B => A): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = Sink[F].onNext(sink)(f(value))
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def contramapFilter[F[_] : Sink, A, B](sink: F[A])(f: B => Option[A]): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = f(value).foreach(Sink[F].onNext(sink))
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def contracollect[F[_] : Sink, A, B](sink: F[A])(f: PartialFunction[B, A]): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = { f.runWith(Sink[F].onNext(sink))(value); () }
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def filter[F[_] : Sink, A](sink: F[_ >: A])(f: A => Boolean): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = if (f(value)) Sink[F].onNext(sink)(value)
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def redirect[F[_] : Sink, G[_] : Source, A, B](sink: F[_ >: A])(transform: SourceStream[B] => G[A]): Connectable[B] = {
    val handler = SinkSourceHandler.publish[B]
    val source = transform(handler)
    connectable(handler, () => Source[G].subscribe(source)(sink))
  }

  implicit object liftSink extends LiftSink[SinkObserver] {
    @inline def lift[G[_] : Sink, A](sink: G[A]): SinkObserver[A] = SinkObserver.lift(sink)
  }

  implicit object sink extends Sink[SinkObserver] {
    @inline def onNext[A](sink: SinkObserver[A])(value: A): Unit = sink.onNext(value)

    @inline def onError[A](sink: SinkObserver[A])(error: Throwable): Unit = sink.onError(error)
  }

  implicit object monoidK extends MonoidK[SinkObserver] {
    @inline def empty[T] = SinkObserver.empty
    @inline def combineK[T](a: SinkObserver[T], b: SinkObserver[T]) = SinkObserver.combineVaried(a, b)
  }

  implicit object contravariant extends Contravariant[SinkObserver] {
    @inline def contramap[A, B](fa: SinkObserver[A])(f: B => A): SinkObserver[B] = SinkObserver.contramap(fa)(f)
  }

  @inline implicit class Operations[A](val sink: SinkObserver[A]) extends AnyVal {
    @inline def lift[G[_] : LiftSink]: G[A] = LiftSink[G].lift(sink)
    @inline def contramap[B]()(f: B => A): SinkObserver[B] = SinkObserver.contramap(sink)(f)
    @inline def contramapFilter[B](f: B => Option[A]): SinkObserver[B] = SinkObserver.contramapFilter(sink)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): SinkObserver[B] = SinkObserver.contracollect(sink)(f)
    @inline def filter(f: A => Boolean): SinkObserver[A] = SinkObserver.filter(sink)(f)
    @inline def redirect[F[_] : Source, B]()(f: SourceStream[B] => F[A]): SinkObserver.Connectable[B] = SinkObserver.redirect(sink)(f)
  }
}