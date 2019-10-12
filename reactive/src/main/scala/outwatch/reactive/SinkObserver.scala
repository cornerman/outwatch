package outwatch.reactive

import cats.{MonoidK, Contravariant}

import scala.util.control.NonFatal

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

  def lift[G[_] : Sink, A](sink: G[A]): SinkObserver[A] =  sink match {
    case sink: SinkObserver[A@unchecked] => sink
    case _ => new SinkObserver[A] {
      def onNext(value: A): Unit = Sink[G].onNext(sink)(value)
      def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
    }
  }

  @inline def createUnhandled[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = consume(value)
    def onError(error: Throwable): Unit = failure(error)
  }

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = recovered(consume(value), onError)
    def onError(error: Throwable): Unit = failure(error)
  }

  @inline def combine[G[_] : Sink, A](sinks: G[A]*): SinkObserver[A] = combineSeq(sinks)

  def combineSeq[G[_] : Sink, A](sinks: Seq[G[A]]): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = sinks.foreach(Sink[G].onNext(_)(value))
    def onError(error: Throwable): Unit = sinks.foreach(Sink[G].onError(_)(error))
  }

  def combineVaried[GA[_] : Sink, GB[_] : Sink, A](sinkA: GA[A], sinkB: GB[A]): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = {
      Sink[GA].onNext(sinkA)(value)
      Sink[GB].onNext(sinkB)(value)
    }
    def onError(error: Throwable): Unit = {
      Sink[GA].onError(sinkA)(error)
      Sink[GB].onError(sinkB)(error)
    }
  }

  def contramap[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => A): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = recovered(Sink[G].onNext(sink)(f(value)), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contramapEither[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Either[Throwable, A]): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = recovered(f(value) match {
      case Right(value) => Sink[G].onNext(sink)(value)
      case Left(error) => Sink[G].onError(sink)(error)
    }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contramapFilter[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Option[A]): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = recovered(f(value).foreach(Sink[G].onNext(sink)), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contracollect[G[_] : Sink, A, B](sink: G[_ >: A])(f: PartialFunction[B, A]): SinkObserver[B] = new SinkObserver[B] {
    def onNext(value: B): Unit = recovered({ f.runWith(Sink[G].onNext(sink))(value); () }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contrafilter[G[_] : Sink, A](sink: G[_ >: A])(f: A => Boolean): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = recovered(if (f(value)) Sink[G].onNext(sink)(value), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  //TODO return effect
  def contrascan[G[_] : Sink, A, B](sink: G[_ >: A])(seed: A)(f: (A, B) => A): SinkObserver[B] = new SinkObserver[B] {
    private var state = seed
    def onNext(value: B): Unit = recovered({
      val result = f(state, value)
      state = result
      Sink[G].onNext(sink)(result)
    }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def doOnError[G[_] : Sink, A](sink: G[_ >: A])(f: Throwable => Unit): SinkObserver[A] = new SinkObserver[A] {
    def onNext(value: A): Unit = Sink[G].onNext(sink)(value)
    def onError(error: Throwable): Unit = f(error)
  }

  def redirect[G[_] : Sink, S[_] : Source, A, B](sink: G[_ >: A])(transform: SourceStream[B] => S[A]): Connectable[B] = {
    val handler = SinkSourceHandler.publishToOne[B]
    val source = transform(handler)
    val subscription = Subscription.refCount(() => Source[S].subscribe(source)(sink))
    connectable(handler, () => subscription.ref())
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
    @inline def liftSink[G[_] : LiftSink]: G[A] = LiftSink[G].lift(sink)
    @inline def contramap[B](f: B => A): SinkObserver[B] = SinkObserver.contramap(sink)(f)
    @inline def contramapEither[B](f: B => Either[Throwable,A]): SinkObserver[B] = SinkObserver.contramapEither(sink)(f)
    @inline def contramapFilter[B](f: B => Option[A]): SinkObserver[B] = SinkObserver.contramapFilter(sink)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): SinkObserver[B] = SinkObserver.contracollect(sink)(f)
    @inline def contrafilter(f: A => Boolean): SinkObserver[A] = SinkObserver.contrafilter(sink)(f)
    @inline def contrascan[B](seed: A)(f: (A, B) => A): SinkObserver[B] = SinkObserver.contrascan(sink)(seed)(f)
    @inline def doOnError(f: Throwable => Unit): SinkObserver[A] = SinkObserver.doOnError(sink)(f)
    @inline def redirect[G[_] : Source, B](f: SourceStream[B] => G[A]): SinkObserver.Connectable[B] = SinkObserver.redirect(sink)(f)
  }

  private def recovered(action: => Unit, onError: Throwable => Unit): Unit = try action catch { case NonFatal(t) => onError(t) }
}
