package outwatch

import cats.effect.SyncIO
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.{BehaviorSubject, ReplaySubject}
import monix.reactive.{Observable, Observer}

import scala.concurrent.Future

object Handler {
  @inline def empty[T]:SyncIO[Handler[T]] = create[T]

  @inline def create[T]:SyncIO[Handler[T]] = SyncIO(unsafe[T])
  @inline def create[T](seed:T):SyncIO[Handler[T]] = SyncIO(unsafe[T](seed))

  @inline def unsafe[T]:Handler[T] = ReplaySubject.createLimited(1)
  @inline def unsafe[T](seed:T):Handler[T] = BehaviorSubject[T](seed)
}

object ProHandler {
  def create[I,O](seed: I, f: I => O): SyncIO[ProHandler[I,O]] = for {
    handler <- Handler.create[I](seed)
  } yield handler.mapObservable[O](f)

  def create[I,O](f: I => O): SyncIO[ProHandler[I,O]] = for {
    handler <- Handler.create[I]
  } yield handler.mapObservable[O](f)

  def apply[I,O](observer:Observer[I], observable: Observable[O]):ProHandler[I,O] = new Observable[O] with Observer[I] {
    override def onNext(elem: I): Future[Ack] = observer.onNext(elem)
    override def onError(ex: Throwable): Unit = observer.onError(ex)
    override def onComplete(): Unit = observer.onComplete()
    override def unsafeSubscribeFn(subscriber: Subscriber[O]): Cancelable = observable.unsafeSubscribeFn(subscriber)
  }
  def connectable[I,O](observer: Observer[I] with ReactiveConnectable, observable: Observable[O]):ProHandler[I,O] with ReactiveConnectable = new Observable[O] with Observer[I] with ReactiveConnectable {
    override def onNext(elem: I): Future[Ack] = observer.onNext(elem)
    override def onError(ex: Throwable): Unit = observer.onError(ex)
    override def onComplete(): Unit = observer.onComplete()
    override def connect()(implicit scheduler: Scheduler): Cancelable = observer.connect()
    override def unsafeSubscribeFn(subscriber: Subscriber[O]): Cancelable = observable.unsafeSubscribeFn(subscriber)
  }
}
