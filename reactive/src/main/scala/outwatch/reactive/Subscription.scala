package outwatch.reactive

import cats.Monoid

import scala.scalajs.js

trait Subscription {
  def cancel(): Unit
  def completed: SourceStream[Unit]
}
object Subscription {

  trait Completable extends Subscription {
    def onComplete(): Unit
  }

  class Builder extends Completable {
    private val completion = completionHandler()
    private var isCompleted = false
    private var buffer = new js.Array[Subscription]()

    def +=(subscription: Subscription): Unit =
      if (buffer == null || isCompleted) {
        subscription.cancel()
      } else {
        buffer.push(subscription)
        ()
      }

    def onComplete(): Unit = if (buffer != null && !isCompleted) {
      val targetCount = buffer.length
      var count = 0
      isCompleted = true
      buffer += Subscription.compositeNoCompleteFromIterable(buffer.map(sub => sub.completed.foreach { _ =>
        count = count + 1
        if (count == targetCount) completion.onNext(())
      }))
      ()
    }

    def cancel(): Unit =
      if (buffer != null) {
        // if (!isCompleted) onComplete()
        buffer.foreach(_.cancel())
        buffer = null
      }

    def completed: SourceStream[Unit] = completion
  }

  class Variable extends Completable {
    private val completion = completionHandler()
    private var isCompleted = false
    private var current: Subscription = Subscription.empty

    def update(subscription: Subscription): Unit =
      if (current == null || isCompleted) {
        subscription.cancel()
      } else {
        current.cancel()
        current = subscription
      }

    def onComplete(): Unit = if (current != null && !isCompleted) {
      isCompleted = true
      current = Subscription.compositeNoComplete(current, current.completed.subscribe(completion))
    }

    def cancel(): Unit =
      if (current != null) {
        current.cancel()
        current = null
    }

    def completed: SourceStream[Unit] = completion
  }

  class Consecutive extends Completable {
    private val completion = completionHandler()
    private var isCompleted = false
    private var isCancel = false
    private var latest: Subscription = null

    def +=(subscription: () => Subscription): Unit = if (!isCancel) {
      if (latest == null) {
        latest = subscription()
      } else {
        latest = Subscription.composite(latest, latest.completed.foreach(_ => latest = subscription()))
      }
    }

    def onComplete(): Unit = if (latest != null && !isCompleted) {
      isCompleted = true
      latest = Subscription.compositeNoComplete(latest, latest.completed.subscribe(completion))
    }

    def cancel(): Unit = {
      if (latest != null) {
        isCancel = true
        latest.cancel()
        latest = null
      }
    }

    def completed = completion
  }

  object Empty extends Subscription {
    @inline def cancel(): Unit = ()
    @inline def completed = SourceStream(())
  }

  @inline def empty = Empty

  @inline def apply(f: () => Unit) = new Subscription {
    @inline def cancel() = f()
    @inline def completed = SourceStream.empty
  }

  @inline def lift[T : CancelSubscription](subscription: T) = apply(() => CancelSubscription[T].cancel(subscription))

  @inline def compositeNoComplete(subscriptions: Subscription*): Subscription = compositeNoCompleteFromIterable(subscriptions)
  @inline def compositeNoCompleteFromIterable(subscriptions: Iterable[Subscription]): Subscription = new Subscription {
    def cancel() = subscriptions.foreach(_.cancel())
    def completed = SourceStream.empty
  }

  @inline def composite(subscriptions: Subscription*): Subscription = compositeFromIterable(subscriptions)
  @inline def compositeFromIterable(subscriptions: Iterable[Subscription]): Subscription = {
    val builder = Subscription.builder()
    subscriptions.foreach(builder += _)
    builder.onComplete()
    builder
  }

  @inline def builder(): Builder = new Builder

  @inline def variable(): Variable = new Variable

  @inline def consecutive(): Consecutive = new Consecutive

  def completable(f: () => Unit): Completable = {
    val completion = SinkSourceHandler[Unit]

    new Completable {
      def cancel() = f()
      def completed = completion.head
      def onComplete() = completion.onNext(())
    }
  }

  implicit object monoid extends Monoid[Subscription] {
    @inline def empty = Subscription.empty
    @inline def combine(a: Subscription, b: Subscription) = Subscription.composite(a, b)
  }

  implicit object cancelSubscription extends CancelSubscription[Subscription] {
    @inline def cancel(subscription: Subscription): Unit = subscription.cancel()
  }

  private def completionHandler(): SinkSourceHandler.Simple[Unit] = SinkSourceHandler[Unit].transformHandler(identity)(_.head)
}
