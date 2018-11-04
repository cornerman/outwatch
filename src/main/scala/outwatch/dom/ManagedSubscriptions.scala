package outwatch.dom

import cats.effect.IO
import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Cancelable, Scheduler}
import outwatch.dom.dsl.attributes.lifecycle
import outwatch.dom.helpers.QueuedCancelable

trait ManagedSubscriptions {

  def managed(subscription: IO[Cancelable])(implicit s: Scheduler): VDomModifier = {
    val cancelable = new QueuedCancelable()
    VDomModifier(
      lifecycle.onDomMount --> sideEffect{ cancelable.enqueue(subscription.unsafeRunSync()) },
      lifecycle.onDomUnmount --> sideEffect{ cancelable.dequeue().cancel() }
    )
  }

  def managed(sub1: IO[Cancelable], sub2: IO[Cancelable], subscriptions: IO[Cancelable]*)(implicit s: Scheduler): VDomModifier = {
      val composite = IO(CompositeCancelable((sub1.unsafeRunSync :: sub2.unsafeRunSync :: subscriptions.map(_.unsafeRunSync).toList): _*))
      managed(composite)
  }
}

object ManagedSubscriptions extends ManagedSubscriptions
