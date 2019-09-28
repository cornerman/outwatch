package outwatch.reactive

import cats.{MonoidK, Contravariant}

import scala.util.control.NonFatal

trait SinkObserver[-A] {
  def onNext(value: A): Unit
  def onError(error: Throwable): Unit
  def onComplete(): Unit
}
object SinkObserver {

  object Empty extends SinkObserver[Any] {
    @inline def onNext(value: Any): Unit = ()
    @inline def onError(error: Throwable): Unit = ()
    @inline def onComplete(): Unit = ()
  }

  class Connectable[-T](
    val sink: SinkObserver[T],
    val connect: () => Subscription
  )

  @inline def empty = Empty

  @inline def connectable[T](sink: SinkObserver[T], connect: () => Subscription): Connectable[T] = new Connectable(sink, connect)

  @inline def lift[F[_] : Sink, A](sink: F[_ >: A]): SinkObserver[A] =  create(Sink[F].onNext(sink), Sink[F].onError(sink), () => Sink[F].onComplete(sink))

  @inline def create[A](next: A => Unit, error: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext, complete: () => Unit = () => ()) = createFull(next, error, complete)

  def createFull[A](next: A => Unit, error: Throwable => Unit, complete: () => Unit): SinkObserver[A] = new SinkObserver[A] {
    var isCompleted = false
    def onNext(value: A): Unit = if (!isCompleted) recovered(next(value), onError)
    def onError(ex: Throwable): Unit = if (!isCompleted) error(ex)
    def onComplete(): Unit = if (!isCompleted) {
      isCompleted = true
      complete()
    }
  }

  def createFullNoComplete[A](next: A => Unit, error: Throwable => Unit): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = recovered(next(value), onError)
    def onError(ex: Throwable): Unit = error(ex)
    def onComplete(): Unit = ()
  }

  @inline def combine[F[_] : Sink, A](sinks: F[A]*): SinkObserver[A] = combineSeq(sinks)

  def combineSeq[F[_] : Sink, A](sinks: Seq[F[A]]): SinkObserver[A] = SinkObserver.createFull[A](
    value => sinks.foreach(Sink[F].onNext(_)(value)),
    error => sinks.foreach(Sink[F].onError(_)(error)),
    () => sinks.foreach(Sink[F].onComplete(_))
  )

  def combineVaried[F[_] : Sink, G[_] : Sink, A](sinkA: F[A], sinkB: G[A]): SinkObserver[A] = SinkObserver.createFull[A](
    { value =>
      Sink[F].onNext(sinkA)(value)
      Sink[G].onNext(sinkB)(value)
    },
    { error =>
      Sink[F].onError(sinkA)(error)
      Sink[G].onError(sinkB)(error)
    },
    { () =>
      Sink[F].onComplete(sinkA)
      Sink[G].onComplete(sinkB)
    }
  )

  def contramap[F[_] : Sink, A, B](sink: F[_ >: A])(f: B => A): SinkObserver[B] = SinkObserver.createFull[B](
    value => Sink[F].onNext(sink)(f(value)),
    error => Sink[F].onError(sink)(error),
    () => Sink[F].onComplete(sink)
  )

  def contramapFilter[F[_] : Sink, A, B](sink: F[_ >: A])(f: B => Option[A]): SinkObserver[B] = SinkObserver.createFull[B](
    value => f(value).foreach(Sink[F].onNext(sink)),
    error => Sink[F].onError(sink)(error),
    () => Sink[F].onComplete(sink)
  )

  def contracollect[F[_] : Sink, A, B](sink: F[_ >: A])(f: PartialFunction[B, A]): SinkObserver[B] = SinkObserver.createFull[B](
    value => { f.runWith(Sink[F].onNext(sink))(value); () },
    error => Sink[F].onError(sink)(error),
    () => Sink[F].onComplete(sink)
  )

  def contrafilter[F[_] : Sink, A](sink: F[_ >: A])(f: A => Boolean): SinkObserver[A] = SinkObserver.createFull[A](
    value => if (f(value)) Sink[F].onNext(sink)(value),
    error => Sink[F].onError(sink)(error),
    () => Sink[F].onComplete(sink)
  )

  def tapOnComplete[F[_] : Sink, A](sink: F[_ >: A])(f: () => Unit): SinkObserver[A] = SinkObserver.createFull[A](
    value => Sink[F].onNext(sink)(value),
    error => Sink[F].onError(sink)(error),
    () => { f(); Sink[F].onComplete(sink) }
  )

  def doOnComplete[F[_] : Sink, A](sink: F[_ >: A])(f: () => Unit): SinkObserver[A] = SinkObserver.createFull[A](
    value => Sink[F].onNext(sink)(value),
    error => Sink[F].onError(sink)(error),
    f
  )

  def doOnError[F[_] : Sink, A](sink: F[_ >: A])(f: Throwable => Unit): SinkObserver[A] = SinkObserver.createFull[A](
    value => Sink[F].onNext(sink)(value),
    error => f(error),
    () => Sink[F].onComplete(sink)
  )

  def completeOnError[F[_] : Sink, A](sink: F[_ >: A]): SinkObserver[A] = doOnError(sink) { error =>
    Sink[F].onError(sink)(error)
    Sink[F].onComplete(sink)
  }

  @inline def dropOnComplete[F[_] : Sink, A](sink: F[_ >: A]): SinkObserver[A] = doOnComplete(sink)(() => ())

  @inline def noComplete[F[_] : Sink, A](sink: F[_ >: A]): SinkObserver[A] = createFullNoComplete(Sink[F].onNext(sink), Sink[F].onError(sink))

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
    @inline def onComplete[A](sink: SinkObserver[A]): Unit = sink.onComplete()
  }

  implicit object monoidK extends MonoidK[SinkObserver] {
    @inline def empty[T] = SinkObserver.empty
    @inline def combineK[T](a: SinkObserver[T], b: SinkObserver[T]) = SinkObserver.combineVaried(a, b)
  }

  implicit object contravariant extends Contravariant[SinkObserver] {
    @inline def contramap[A, B](fa: SinkObserver[A])(f: B => A): SinkObserver[B] = SinkObserver.contramap(fa)(f)
  }

  @inline implicit class Operations[A](val sink: SinkObserver[A]) extends AnyVal {
    @inline def liftSink[G[_] : LiftSink]: G[A] = LiftSink[G].lift(sink)
    @inline def contramap[B](f: B => A): SinkObserver[B] = SinkObserver.contramap(sink)(f)
    @inline def contramapFilter[B](f: B => Option[A]): SinkObserver[B] = SinkObserver.contramapFilter(sink)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): SinkObserver[B] = SinkObserver.contracollect(sink)(f)
    @inline def contrafilter(f: A => Boolean): SinkObserver[A] = SinkObserver.contrafilter(sink)(f)
    @inline def redirect[F[_] : Source, B](f: SourceStream[B] => F[A]): SinkObserver.Connectable[B] = SinkObserver.redirect(sink)(f)
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action catch { case NonFatal(t) => onError(t) }
}
