package outwatch.reactive

import cats.Monoid

import scala.scalajs.js

sealed trait Subscription {
  def cancel(): Unit
}
object Subscription {
  class Builder extends Subscription {
    private var buffer = new js.Array[Subscription]()

    def +=(subscription: Subscription): Unit =
      if (buffer == null) {
        subscription.cancel()
      } else {
        buffer.push(subscription)
        ()
      }

    def cancel(): Unit =
      if (buffer != null) {
        buffer.foreach(_.cancel())
        buffer = null
      }
  }

  class Variable extends Subscription {
    private var current: Subscription = Subscription.empty

    def update(subscription: Subscription): Unit =
      if (current == null) {
        subscription.cancel()
      } else {
        current.cancel()
        current = subscription
      }

    def cancel(): Unit =
      if (current != null) {
        current.cancel()
        current = null
      }
  }

  object Empty extends Subscription {
    @inline def cancel(): Unit = ()
  }

  @inline def empty = Empty

  @inline def apply(f: () => Unit) = new Subscription {
    @inline def cancel() = f()
  }

  @inline def lift[T : CancelSubscription](subscription: T) = apply(() => CancelSubscription[T].cancel(subscription))

  @inline def composite(subscriptions: Subscription*): Subscription = compositeFromIterable(subscriptions)
  @inline def compositeFromIterable(subscriptions: Iterable[Subscription]): Subscription = new Subscription {
    def cancel() = subscriptions.foreach(_.cancel())
  }

  @inline def builder(): Builder = new Builder
  @inline def variable(): Variable = new Variable

  implicit object monoid extends Monoid[Subscription] {
    @inline def empty = Subscription.empty
    @inline def combine(a: Subscription, b: Subscription) = Subscription.composite(a, b)
  }

  implicit object cancelSubscription extends CancelSubscription[Subscription] {
    @inline def cancel(subscription: Subscription): Unit = subscription.cancel()
  }
}

trait FiniteSubscription extends Subscription
object FiniteSubscription {
  object Sync extends FiniteSubscription {
    def cancel() = ()
  }
  trait Async extends FiniteSubscription {
    def completed(callback: () => Unit): Subscription
  }

  trait AsyncCompleter extends Async {
    def onComplete: () => Unit
  }

  @inline def AsyncCompleter(subscription: () => Unit): AsyncCompleter = new AsyncCompleter {
    var subscribers = js.Array[() => Unit]
    var completed = false
    def onComplete: () => Unit =
    def async: Async
  }
}
