package outwatch.reactive

import outwatch.effect._

import cats.{ MonoidK, Functor, FunctorFilter, Eq }
import cats.effect.{ Effect, IO }

import scala.scalajs.js
import scala.util.{ Success, Failure, Try }
import scala.util.control.NonFatal
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration


trait SourceStream[+A] {
  //TODO: def subscribe[G[_]: Sink, F[_] : Sync](sink: G[_ >: A]): F[Subscription]
  def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription
}
object SourceStream {
  // Only one execution context in javascript that is a queued execution
  // context using the javascript event loop. We skip the implicit execution
  // context and just fire on the global one. As it is most likely what you
  // want to do in this API.
  import ExecutionContext.Implicits.global

  object Empty extends SourceStream[Nothing] {
    @inline def subscribe[G[_]: Sink](sink: G[_ >: Nothing]): Subscription = Subscription.empty
  }

  @inline def empty = Empty

  def apply[T](value: T): SourceStream[T] = new SourceStream[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Subscription = {
      Sink[G].onNext(sink)(value)
      Sink[G].onComplete(sink)
      Subscription.empty
    }
  }

  def fromIterable[T](values: Iterable[T]): SourceStream[T] = new SourceStream[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Subscription = {
      values.foreach(Sink[G].onNext(sink))
      Sink[G].onComplete(sink)
      Subscription.empty
    }
  }

  @inline def lift[F[_]: Source, A](source: F[A]): SourceStream[A] = create(Source[F].subscribe(source))

  @inline def create[A](produce: SinkObserver[A] => Subscription): SourceStream[A] = createLift[SinkObserver, A](produce)

  def createLift[F[_]: Sink: LiftSink, A](produce: F[_ >: A] => Subscription): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = produce(LiftSink[F].lift(sink))
  }

  def fromTry[A](value: Try[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      value match {
        case Success(a) => Sink[G].onNext(sink)(a)
        case Failure(error) => Sink[G].onError(sink)(error)
      }
      Sink[G].onComplete(sink)
      Subscription.empty
    }
  }

  def fromSync[F[_]: RunSyncEffect, A](effect: F[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      recovered(Sink[G].onNext(sink)(RunSyncEffect[F].unsafeRun(effect)), Sink[G].onError(sink)(_))
      Sink[G].onComplete(sink)
      Subscription.empty
    }
  }

  def fromAsync[F[_]: Effect, A](effect: F[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      //TODO: proper cancel effects?
      var isCancel = false

      val subscription = Subscription(() => isCancel = true)

      Effect[F].runAsync(effect)(either => IO {
        if (!isCancel) {
          either match {
            case Right(value) => Sink[G].onNext(sink)(value)
            case Left(error)  => Sink[G].onError(sink)(error)
          }
          Sink[G].onComplete(sink)
        }
      }).unsafeRunSync()

      subscription
    }
  }

  def fromFuture[A](future: Future[A]): SourceStream[A] = fromAsync(IO.fromFuture(IO(future))(IO.contextShift(global)))

  def failed[S[_]: Source, A](source: S[A]): SourceStream[Throwable] = new SourceStream[Throwable] {
    def subscribe[G[_]: Sink](sink: G[_ >: Throwable]): Subscription =
      Source[S].subscribe(source)(SinkObserver.createFull[A](
        _ => (),
        Sink[G].onError(sink)(_),
        () => Sink[G].onComplete(sink)
      ))
  }

  def completed[S[_]: Source, A](source: S[A]): SourceStream[Unit] = new SourceStream[Unit] {
    def subscribe[G[_]: Sink](sink: G[_ >: Unit]): Subscription =
      Source[S].subscribe(source)(SinkObserver.createFull[A](
        _ => (),
        _ => (),
        { () =>
          Sink[G].onNext(sink)(())
          Sink[G].onComplete(sink)
        }
      ))
  }

  @inline def interval(delay: FiniteDuration): SourceStream[Long] = intervalMillis(delay.toMillis.toInt)

  def intervalMillis(delay: Int): SourceStream[Long] = new SourceStream[Long] {
    def subscribe[G[_]: Sink](sink: G[_ >: Long]): Subscription = {
      import org.scalajs.dom
      var isCancel = false
      var counter: Long = 0

      def send(): Unit = {
        val current = counter
        counter += 1
        Sink[G].onNext(sink)(current)
      }

      send()

      val intervalId = dom.window.setInterval(() => if (!isCancel) send(), delay.toDouble)

      Subscription { () =>
        isCancel = true
        dom.window.clearInterval(intervalId)
      }
    }
  }

  def merge[S[_]: Source, A](sources: S[A]*): SourceStream[A] = mergeSeq(sources)

  def mergeSeq[S[_]: Source, A](sources: Seq[S[A]]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = completable[G, A, Subscription](sink) { (start, complete) =>
      start()
      val subscriptions = sources.map { source =>
        start()
        Source[S].subscribe(source)(SinkObserver.doOnComplete(sink)(complete))
      }
      complete()

      Subscription.compositeFromIterable(subscriptions)
    }
  }

  def mergeVaried[SA[_]: Source, SB[_]: Source, A](sourceA: SA[A], sourceB: SB[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = completable[G, A, Subscription](sink) { (start, complete) =>
      start()
      start()
      Subscription.composite(
        Source[SA].subscribe(sourceA)(SinkObserver.doOnComplete(sink)(complete)),
        Source[SB].subscribe(sourceB)(SinkObserver.doOnComplete(sink)(complete))
      )
    }
  }

  def switch[S[_]: Source, A](sources: S[A]*): SourceStream[A] = switchSeq(sources)

  def switchSeq[S[_]: Source, A](sources: Seq[S[A]]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      val variable = Subscription.variable()

      var counter = 0
      val length = sources.size
      sources.foreach { source =>
        variable() = (
          if (counter < length - 1) Source[S].subscribe(source)(SinkObserver.dropOnComplete(sink))
          else Source[S].subscribe(source)(sink)
        )
        counter += 1
      }

      variable
    }
  }

  def switchVaried[SA[_]: Source, SB[_]: Source, A](sourceA: SA[A], sourceB: SB[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      val variable = Subscription.variable()
      variable() = Source[SA].subscribe(sourceA)(SinkObserver.dropOnComplete(sink))
      variable() = Source[SB].subscribe(sourceB)(sink)
      variable
    }
  }

  def concat[S[_]: Source, A](sources: S[A]*): SourceStream[A] = concatSeq(sources)

  def concatSeq[S[_]: Source, A](sources: Seq[S[A]]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      val consecutive = Subscription.consecutive()

      var counter = 0
      val length = sources.size
      sources.foreach { source =>
        consecutive += (
          if (counter < length - 1) (() => Source[S].subscribe(source)(SinkObserver.doOnComplete(sink)(consecutive.switch)))
          else (() => Source[S].subscribe(source)(sink))
        )
        counter += 1
      }

      consecutive
    }
  }

  def concatVaried[SA[_]: Source, SB[_]: Source, A](sourceA: SA[A], sourceB: SB[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      val consecutive = Subscription.consecutive()
      consecutive += (() => Source[SA].subscribe(sourceA)(SinkObserver.doOnComplete(sink)(() => consecutive.switch())))
      consecutive += (() => Source[SB].subscribe(sourceB)(sink))
      consecutive
    }
  }

  def map[F[_]: Source, A, B](source: F[A])(f: A => B): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = Source[F].subscribe(source)(SinkObserver.contramap(sink)(f))
  }

  def mapFilter[F[_]: Source, A, B](source: F[A])(f: A => Option[B]): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = Source[F].subscribe(source)(SinkObserver.contramapFilter(sink)(f))
  }

  def collect[F[_]: Source, A, B](source: F[A])(f: PartialFunction[A, B]): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = Source[F].subscribe(source)(SinkObserver.contracollect(sink)(f))
  }

  def filter[F[_]: Source, A](source: F[A])(f: A => Boolean): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = Source[F].subscribe(source)(SinkObserver.contrafilter[G, A](sink)(f))
  }

  def recover[F[_]: Source, A](source: F[A])(f: PartialFunction[Throwable, A]): SourceStream[A] = recoverOption(source)(f andThen (Some(_)))

  def recoverOption[F[_]: Source, A](source: F[A])(f: PartialFunction[Throwable, Option[A]]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      Source[F].subscribe(source)(SinkObserver.doOnError(sink) { error =>
        f.lift(error) match {
          case Some(v) => v.foreach(Sink[G].onNext(sink)(_))
          case None => Sink[G].onError(sink)(error)
        }
      })
    }
  }

  def scan[F[_]: Source, A, B](source: F[A])(seed: B)(f: (B, A) => B): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = {
      var state = seed

      Sink[G].onNext(sink)(seed)

      Source[F].subscribe(source)(SinkObserver.contramap[G, B, A](sink) { value =>
        val result = f(state, value)
        state = result
        result
      })
    }
  }

  def dropOnComplete[S[_]: Source, A](source: S[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = Source[S].subscribe(source)(SinkObserver.dropOnComplete(sink))
  }

  def completeOnError[S[_]: Source, A](source: S[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = Source[S].subscribe(source)(SinkObserver.completeOnError(sink))
  }

  def mergeMap[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(f: A => SB[B]): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = completable[G, B, Subscription](sink) { (start, complete) =>
      val subscriptions = Subscription.builder()

      start()
      val subscription = Source[SA].subscribe(sourceA)(SinkObserver.createFull[A](
        { value =>
          val sourceB = f(value)
          start()
          subscriptions += Source[SB].subscribe(sourceB)(SinkObserver.doOnComplete(sink)(complete))
        },
        Sink[G].onError(sink),
        complete
      ))

      Subscription.composite(subscription, subscriptions)
    }
  }

  def switchMap[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(f: A => SB[B]): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = completable[G, B, Subscription](sink) { (start, complete) =>
      val current = Subscription.variable()
      var isRunning = false

      start()
      val subscription = Source[SA].subscribe(sourceA)(SinkObserver.createFull[A](
        { value =>
          val sourceB = f(value)
          if (!isRunning) start()
          isRunning = true
          current() = Source[SB].subscribe(sourceB)(SinkObserver.doOnComplete(sink)(complete))
        },
        Sink[G].onError(sink),
        complete
      ))

      Subscription.composite(current, subscription)
    }
  }

  def concatMap[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(f: A => SB[B]): SourceStream[B] = new SourceStream[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Subscription = completable[G, B, Subscription](sink) { (start, complete) =>
      val consecutive = Subscription.consecutive()

      start()
      val subscription = Source[SA].subscribe(sourceA)(SinkObserver.createFull[A](
        { value =>
          val sourceB = f(value)
          start()
          consecutive += (() => Source[SB].subscribe(sourceB)(SinkObserver.doOnComplete(sink) { () =>
            complete()
            consecutive.switch()
          }))
        },
        Sink[G].onError(sink),
        complete
      ))

      Subscription.composite(subscription, consecutive)
    }
  }

  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(sourceB: SB[B]): SourceStream[(A,B)] = combineLatestMap(sourceA)(sourceB)(_ -> _)

  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, R](sourceA: SA[A])(sourceB: SB[B])(f: (A, B) => R): SourceStream[R] = new SourceStream[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Subscription = {
      var latestA: Option[A] = None
      var latestB: Option[B] = None

      def send(): Unit = for {
        a <- latestA
        b <- latestB
      } Sink[G].onNext(sink)(f(a,b))

      Subscription.composite(
        Source[SA].subscribe(sourceA)(SinkObserver.createFull[A](
          { value =>
            latestA = Some(value)
            send()
          },
          Sink[G].onError(sink),
          () => Sink[G].onComplete(sink)
        )),
        Source[SB].subscribe(sourceB)(SinkObserver.createFull[B](
          { value =>
            latestB = Some(value)
            send()
          },
          Sink[G].onError(sink),
          () => Sink[G].onComplete(sink)
        ))
      )
    }
  }

  def withLatestFrom[SA[_]: Source, SB[_]: Source, A, B, R](source: SA[A])(latest: SB[B])(f: (A, B) => R): SourceStream[R] = new SourceStream[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Subscription = {
      var latestValue: Option[B] = None

      Subscription.composite(
        Source[SA].subscribe(source)(SinkObserver.createFull[A](
          value => latestValue.foreach(latestValue => Sink[G].onNext(sink)(f(value, latestValue))),
          Sink[G].onError(sink),
          () => Sink[G].onComplete(sink)
        )),
        Source[SB].subscribe(latest)(SinkObserver.createFull[B](
          value => latestValue = Some(value),
          Sink[G].onError(sink),
          () => ()
        ))
      )
    }
  }

  def zipWithIndex[S[_]: Source, A, R](source: S[A]): SourceStream[(A, Int)] = new SourceStream[(A, Int)] {
    def subscribe[G[_]: Sink](sink: G[_ >: (A, Int)]): Subscription = {
      var counter = 0

      Source[S].subscribe(source)(SinkObserver.createFull[A](
        { value =>
          val index = counter
          counter += 1
          Sink[G].onNext(sink)((value, index))
        },
        Sink[G].onError(sink),
        () => Sink[G].onComplete(sink)
      ))
    }
  }

  @inline def debounce[S[_]: Source, A](source: S[A])(duration: FiniteDuration): SourceStream[A] = debounceMillis(source)(duration.toMillis.toInt)

  def debounceMillis[S[_]: Source, A](source: S[A])(duration: Int): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      import org.scalajs.dom
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isCancel = false
      var isCompleted = false
      var isRunning = false

      Subscription.composite(
        Subscription { () =>
          isCancel = true
          lastTimeout.foreach(dom.window.clearTimeout)
        },
        Source[S].subscribe(source)(SinkObserver.createFull[A](
          { value =>
            lastTimeout.foreach { id =>
              dom.window.clearTimeout(id)
            }
            isRunning = true
            lastTimeout = dom.window.setTimeout(
              () =>  if (!isCancel) {
                Sink[G].onNext(sink)(value)
                if (isCompleted) Sink[G].onComplete(sink)
                isRunning = false
              },
              duration.toDouble
            )
          },
          Sink[G].onError(sink),
          { () =>
            isCompleted = true
            if (!isRunning) Sink[G].onComplete(sink)
          }
        ))
      )
    }
  }

  //TODO setImmediate?
  @inline def async[S[_]: Source, A](source: S[A]): SourceStream[A] = delayMillis(source)(0)

  @inline def delay[S[_]: Source, A](source: S[A])(duration: FiniteDuration): SourceStream[A] = delayMillis(source)(duration.toMillis.toInt)

  def delayMillis[S[_]: Source, A](source: S[A])(duration: Int): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = completable[G, A, Subscription](sink) { (start, complete) =>
      import org.scalajs.dom
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isCancel = false
      start()

      // TODO: we onyl actually cancel the last timeout. The check isCancel
      // makes sure that cancelled subscription is really respected.
      Subscription.composite(
        Subscription { () =>
          isCancel = true
          lastTimeout.foreach(dom.window.clearTimeout)
        },
        Source[S].subscribe(source)(SinkObserver.createFull[A](
          { value =>
            start()
            lastTimeout = dom.window.setTimeout(
              () => if (!isCancel) {
                Sink[G].onNext(sink)(value)
                complete()
              },
              duration.toDouble
            )
          },
          Sink[G].onError(sink),
          complete
        ))
      )
    }
  }

  def distinct[S[_]: Source, A : Eq](source: S[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      var lastValue: Option[A] = None

      Source[S].subscribe(source)(SinkObserver.createFull[A](
        { value =>
            val shouldSend = lastValue.forall(lastValue => !Eq[A].eqv(lastValue, value))
            if (shouldSend) {
              lastValue = Some(value)
              Sink[G].onNext(sink)(value)
            }
        },
        Sink[G].onError(sink),
        () => Sink[G].onComplete(sink)
      ))
    }
  }

  @inline def distinctOnEquals[S[_]: Source, A](source: S[A]): SourceStream[A] = distinct(source)(Source[S], Eq.fromUniversalEquals)

  def withDefaultSubscription[S[_]: Source, F[_]: Sink, A](source: S[A])(sink: F[A]): SourceStream[A] = new SourceStream[A] {
    private var defaultSubscription = Source[S].subscribe(source)(sink)

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      // stop the default subscription.
      if (defaultSubscription != null) {
        defaultSubscription.cancel()
        defaultSubscription = null
      }

      Source[S].subscribe(source)(sink)
    }
  }

  @inline def share[F[_]: Source, A](source: F[A]): SourceStream[A] = pipeThrough(source)(SinkSourceHandler.publish[A])
  @inline def shareWithLatest[F[_]: Source, A](source: F[A]): SourceStream[A] = pipeThrough(source)(SinkSourceHandler[A])

  def pipeThrough[F[_]: Source, A, S[_] : Source : Sink](source: F[A])(pipe: S[A]): SourceStream[A] = new SourceStream[A] {
    private var subscribers = 0
    private var currentSubscription: Subscription = null

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      subscribers += 1
      val subscription = Source[S].subscribe(pipe)(sink)

      if (currentSubscription == null) {
        val variable = Subscription.variable()
        currentSubscription = variable
        variable() = Source[F].subscribe(source)(pipe)
      }

      Subscription { () =>
        subscription.cancel()
        subscribers -= 1
        if (subscribers == 0) {
          currentSubscription.cancel()
          currentSubscription = null
        }
      }
    }
  }

  def append[F[_]: Source, A](source: F[A])(value: A): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription =
      Source[F].subscribe(source)(SinkObserver.tapOnComplete(sink)(() => Sink[G].onNext(sink)(value)))
  }

  def endsWith[F[_]: Source, A](source: F[A])(values: Iterable[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription =
      Source[F].subscribe(source)(SinkObserver.tapOnComplete(sink)(() => values.foreach(Sink[G].onNext(sink))))
  }

  def prepend[F[_]: Source, A](source: F[A])(value: A): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      Sink[G].onNext(sink)(value)
      Source[F].subscribe(source)(sink)
    }
  }

  def startWith[F[_]: Source, A](source: F[A])(values: Iterable[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      values.foreach(Sink[G].onNext(sink))
      Source[F].subscribe(source)(sink)
    }
  }

  def last[F[_]: Source, A](source: F[A]): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      var lastValue: Option[A] = None
      Source[F].subscribe(source)(SinkObserver.createFull[A](
        value => lastValue = Some(value),
        Sink[G].onError(sink),
        { () =>
          lastValue.foreach(Sink[G].onNext(sink))
          Sink[G].onComplete(sink)
        }
      ))
    }
  }

  @inline def head[F[_]: Source, A](source: F[A]): SourceStream[A] = take(source)(1)

  def take[F[_]: Source, A](source: F[A])(num: Int): SourceStream[A] = {
    if (num <= 0) SourceStream.empty
    else new SourceStream[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
        var counter = 0
        val subscription = Subscription.variable()
        subscription() = Source[F].subscribe(source)(SinkObserver.contrafilter(sink) { _ =>
          if (num > counter) {
            counter += 1
            true
          } else {
            subscription.cancel()
            Sink[G].onComplete(sink)
            false
          }
        })

        subscription
      }
    }
  }

  def drop[F[_]: Source, A](source: F[A])(num: Int): SourceStream[A] = {
    if (num <= 0) SourceStream.lift(source)
    else new SourceStream[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
        var counter = 0
        Source[F].subscribe(source)(SinkObserver.contrafilter(sink) { _ =>
          if (num > counter) {
            counter += 1
            false
          } else true
        })
      }
    }
  }

  def dropWhile[F[_]: Source, A](source: F[A])(predicate: A => Boolean): SourceStream[A] = new SourceStream[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Subscription = {
      var finishedDrop = false
      Source[F].subscribe(source)(SinkObserver.contrafilter[G, A](sink) { v =>
        if (finishedDrop) true
        else if (predicate(v)) false
        else {
          finishedDrop = true
          true
        }
      })
    }
  }

  implicit object source extends Source[SourceStream] {
    @inline def subscribe[G[_]: Sink, A](source: SourceStream[A])(sink: G[_ >: A]): Subscription = source.subscribe(sink)
  }

  implicit object liftSource extends LiftSource[SourceStream] {
    @inline def lift[G[_]: Source, A](source: G[A]): SourceStream[A] = SourceStream.lift[G, A](source)
  }

  implicit object monoidK extends MonoidK[SourceStream] {
    @inline def empty[T] = SourceStream.empty
    @inline def combineK[T](a: SourceStream[T], b: SourceStream[T]) = SourceStream.mergeVaried(a, b)
  }

  implicit object functor extends Functor[SourceStream] {
    @inline def map[A, B](fa: SourceStream[A])(f: A => B): SourceStream[B] = SourceStream.map(fa)(f)
  }

  implicit object functorFilter extends FunctorFilter[SourceStream] {
    @inline def functor = SourceStream.functor
    @inline def mapFilter[A, B](fa: SourceStream[A])(f: A => Option[B]): SourceStream[B] = SourceStream.mapFilter(fa)(f)
  }

  @inline implicit class Operations[A](val source: SourceStream[A]) extends AnyVal {
    @inline def liftSource[G[_]: LiftSource]: G[A] = LiftSource[G].lift(source)
    @inline def failed: SourceStream[Throwable] = SourceStream.failed(source)
    @inline def completed: SourceStream[Unit] = SourceStream.completed(source)
    @inline def dropOnComplete: SourceStream[A] = SourceStream.dropOnComplete(source)
    @inline def completeOnError: SourceStream[A] = SourceStream.completeOnError(source)
    @inline def mergeMap[S[_]: Source, B](f: A => S[B]): SourceStream[B] = SourceStream.mergeMap(source)(f)
    @inline def switchMap[S[_]: Source, B](f: A => S[B]): SourceStream[B] = SourceStream.switchMap(source)(f)
    @inline def concatMap[S[_]: Source, B](f: A => S[B]): SourceStream[B] = SourceStream.concatMap(source)(f)
    @inline def combineLatest[S[_]: Source, B, R](combined: S[B]): SourceStream[(A,B)] = SourceStream.combineLatest(source)(combined)
    @inline def combineLatestMap[S[_]: Source, B, R](combined: S[B])(f: (A, B) => R): SourceStream[R] = SourceStream.combineLatestMap(source)(combined)(f)
    @inline def withLatestFrom[S[_]: Source, B, R](latest: S[B])(f: (A, B) => R): SourceStream[R] = SourceStream.withLatestFrom(source)(latest)(f)
    @inline def zipWithIndex: SourceStream[(A, Int)] = SourceStream.zipWithIndex(source)
    @inline def debounce(duration: FiniteDuration): SourceStream[A] = SourceStream.debounce(source)(duration)
    @inline def async: SourceStream[A] = SourceStream.async(source)
    @inline def delay(duration: FiniteDuration): SourceStream[A] = SourceStream.delay(source)(duration)
    @inline def delayMillis(millis: Int): SourceStream[A] = SourceStream.delayMillis(source)(millis)
    @inline def distinctOnEquals: SourceStream[A] = SourceStream.distinctOnEquals(source)
    @inline def distinct(implicit eq: Eq[A]): SourceStream[A] = SourceStream.distinct(source)
    @inline def map[B](f: A => B): SourceStream[B] = SourceStream.map(source)(f)
    @inline def mapFilter[B](f: A => Option[B]): SourceStream[B] = SourceStream.mapFilter(source)(f)
    @inline def collect[B](f: PartialFunction[A, B]): SourceStream[B] = SourceStream.collect(source)(f)
    @inline def filter(f: A => Boolean): SourceStream[A] = SourceStream.filter(source)(f)
    @inline def scan[B](seed: B)(f: (B, A) => B): SourceStream[B] = SourceStream.scan(source)(seed)(f)
    @inline def recover(f: PartialFunction[Throwable, A]): SourceStream[A] = SourceStream.recover(source)(f)
    @inline def recoverOption(f: PartialFunction[Throwable, Option[A]]): SourceStream[A] = SourceStream.recoverOption(source)(f)
    @inline def share: SourceStream[A] = SourceStream.share(source)
    @inline def shareWithLatest: SourceStream[A] = SourceStream.shareWithLatest(source)
    @inline def append(value: A): SourceStream[A] = SourceStream.append(source)(value)
    @inline def endsWith(values: Iterable[A]): SourceStream[A] = SourceStream.endsWith(source)(values)
    @inline def prepend(value: A): SourceStream[A] = SourceStream.prepend(source)(value)
    @inline def startWith(values: Iterable[A]): SourceStream[A] = SourceStream.startWith(source)(values)
    @inline def head: SourceStream[A] = SourceStream.head(source)
    @inline def take(num: Int): SourceStream[A] = SourceStream.take(source)(num)
    @inline def drop(num: Int): SourceStream[A] = SourceStream.drop(source)(num)
    @inline def dropWhile(predicate: A => Boolean): SourceStream[A] = SourceStream.dropWhile(source)(predicate)
    @inline def withDefaultSubscription[G[_] : Sink](sink: G[A]): SourceStream[A] = SourceStream.withDefaultSubscription(source)(sink)
    @inline def subscribe(): Subscription = source.subscribe(SinkObserver.empty)
    @inline def foreach(f: A => Unit): Subscription = source.subscribe(SinkObserver.create(f))
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action catch { case NonFatal(t) => onError(t) }

  private def completable[G[_]: Sink, A, T](sink: G[_ >: A])(f: (() => Unit, () => Unit) => T): T = {
    var runCounter = 0

    def start(): Unit = {
      runCounter += 1
    }

    def complete(): Unit = {
      runCounter -= 1
      if (runCounter == 0) Sink[G].onComplete(sink)
    }

    f(start, complete)
  }
}
