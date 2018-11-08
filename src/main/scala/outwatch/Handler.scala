package outwatch

import cats.effect.IO
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.{BehaviorSubject, ReplaySubject}
import monix.reactive.{Observable, Observer}
import outwatch.dom.helpers.BehaviorProHandler
import outwatch.dom.{AsValueObservable, ValueObservable}

import scala.concurrent.Future

object Handler {
  def empty[T]:IO[Handler[T]] = create[T]

  def create[T]:IO[Handler[T]] = IO(unsafe[T])
  def create[T](seed:T):IO[Handler[T]] = IO(unsafe[T](seed))

  def unsafe[T]:Handler[T] = new BehaviorProHandler[T](None)
  def unsafe[T](seed:T):Handler[T] = new BehaviorProHandler[T](Some(seed))
}

object ProHandler {
  def create[I,O](f: I => O): IO[ProHandler[I,O]] = for {
    handler <- Handler.create[I]
  } yield handler.mapObservable[O](f)

  def apply[I,O,F[_]: AsValueObservable](observer:Observer[I], observable: F[O]):ProHandler[I,O] = apply(observer, ValueObservable(observable))
  def apply[I,O](observer:Observer[I], valueObservable: ValueObservable[O]):ProHandler[I,O] = new ValueObservable[O] with Observer[I] {
    override def onNext(elem: I): Future[Ack] = observer.onNext(elem)
    override def onError(ex: Throwable): Unit = observer.onError(ex)
    override def onComplete(): Unit = observer.onComplete()
    override def tailObservable: Observable[O] = valueObservable.tailObservable
    override def value: Option[O] = valueObservable.value
  }
  def connectable[I,O,F[_]: AsValueObservable](observer:Observer[I] with ReactiveConnectable, observable: F[O]):ProHandler[I,O] with ReactiveConnectable = connectable(observer, ValueObservable(observable))
  def connectable[I,O](observer: Observer[I] with ReactiveConnectable, valueObservable: ValueObservable[O]):ProHandler[I,O] with ReactiveConnectable = new ValueObservable[O] with Observer[I] with ReactiveConnectable {
    override def onNext(elem: I): Future[Ack] = observer.onNext(elem)
    override def onError(ex: Throwable): Unit = observer.onError(ex)
    override def onComplete(): Unit = observer.onComplete()
    override def connect()(implicit scheduler: Scheduler): Cancelable = observer.connect()
    override def tailObservable: Observable[O] = valueObservable.tailObservable
    override def value: Option[O] = valueObservable.value
  }
}
