package outwatch.reactive

import cats.Monoid

import scala.scalajs.js

trait Subscription {
  def cancel(): Unit
}
object Subscription {

  trait Finite extends Subscription {
    def completed: SourceStream[Unit]
  }

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

  class Consecutive extends Finite {
    private var isCancel = false
    private var latest: Finite = null

    def +=(subscription: () => Finite): Unit = if (!isCancel) {
      println("ADD CONSEC ") 
      if (latest == null) {
        println("CONSEC RUN ") 
        latest = subscription()
      } else {
        println("CONSEC WAIT ") 
        val current = latest
        latest = finiteBuilder { completion =>
          println("CONSEC FINITe ")
          current.completed.foreach(_ => {println("CONSEC COMPL"); completion.onNext(())})
        }
      }
    }

    def completed: SourceStream[Unit] =
      if (latest == null) SourceStream(())
      else latest.completed

    def cancel(): Unit = {
      if (latest != null) {
        latest.cancel()
        latest = null
        isCancel = true
      }
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

  @inline def consecutive(): Consecutive = new Consecutive

  def finiteBuilder(f: SinkObserver[Unit] => Subscription): Finite = {
    val completion = SinkSourceHandler[Unit]
    val subscription = f(completion)

    new Finite {
      def cancel() = subscription.cancel()
      def completed = completion.head
    }
  }

  val finiteCompleted: Finite = new Finite {
    def cancel() = ()
    def completed = SourceStream(())
  }

  implicit object monoid extends Monoid[Subscription] {
    @inline def empty = Subscription.empty
    @inline def combine(a: Subscription, b: Subscription) = Subscription.composite(a, b)
  }

  implicit object cancelSubscription extends CancelSubscription[Subscription] {
    @inline def cancel(subscription: Subscription): Unit = subscription.cancel()
  }
}
