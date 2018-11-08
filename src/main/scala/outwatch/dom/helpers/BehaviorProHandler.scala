package outwatch.dom.helpers

import monix.execution.Ack.Stop
import monix.execution.{Ack, Cancelable}
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer}
import outwatch.dom.ValueObservable

import scala.collection.mutable
import scala.concurrent.Future

private[outwatch] final class BehaviorProHandler[A](initialValue: Option[A]) extends ValueObservable[A] with Observer[A] { self =>

  private var cached: Option[A] = initialValue
  private val subscribers: mutable.Set[Subscriber[A]] = new mutable.HashSet
  private var isDone: Boolean = false
  private var errorThrown: Throwable = null

  override def value: Option[A] = cached
  override val tailObservable: Observable[A] = (subscriber: Subscriber[A]) => {
    if (errorThrown != null) {
      subscriber.onError(errorThrown)
      Cancelable.empty
    } else if (isDone) {
      Cancelable.empty
    } else {
      subscribers += subscriber
      Cancelable { () => subscribers -= subscriber }
    }
  }

  def onNext(elem: A): Future[Ack] = if (isDone) Stop else {
    cached = Some(elem)
    SubscriberHelpers.sendToSubscribers(subscribers, elem)
  }

  override def onError(ex: Throwable): Unit = if (!isDone) {
    isDone = true
    errorThrown = ex
    subscribers.foreach(_.onError(ex))
  }

  override def onComplete(): Unit = if (!isDone) {
    isDone = true
    subscribers.foreach(_.onComplete())
  }
}

