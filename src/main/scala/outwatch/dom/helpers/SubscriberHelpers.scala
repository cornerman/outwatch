package outwatch.dom.helpers

import monix.execution.Ack
import monix.reactive.Observer
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Success
import scala.util.control.NonFatal

private[outwatch] object SubscriberHelpers {
  def sendToSubscribers[A](subscribers: mutable.Set[Subscriber[A]], elem: A): Future[Ack] = {
    // counter that's only used when we go async, hence the null
    var result: PromiseCounter[Ack.Continue.type] = null

    subscribers.foreach { subscriber =>
      import subscriber.scheduler

      val ack = try subscriber.onNext(elem) catch {
        case ex if NonFatal(ex) => Future.failed(ex)
      }

      // if execution is synchronous, takes the fast-path
      if (ack.isCompleted) {
        // subscriber canceled or triggered an error? then remove
        if (ack != Ack.Continue && ack.value.get != Ack.Continue.AsSuccess)
          subscribers -= subscriber
      }
      else {
        // going async, so we've got to count active futures for final Ack
        // the counter starts from 1 because zero implies isCompleted
        if (result == null) result = new PromiseCounter(Ack.Continue, 1)
        result.acquire()

        ack.onComplete {
          case Success(Ack.Continue) =>
            result.countdown()
          case _ =>
            // subscriber canceled or triggered an error? then remove
            subscribers -= subscriber
            result.countdown()
        }
      }
    }

    // has fast-path for completely synchronous invocation
    if (result == null) Ack.Continue else {
      result.countdown()
      result.future
    }
  }
}

private final class PromiseCounter[A](value: A, initial: Int) {
  require(initial > 0, "length must be strictly positive")

  private val promise = Promise[A]()
  private var counter = initial

  def future: Future[A] =
    promise.future

  def acquire(): Unit =
    counter += 1

  def countdown(): Unit = {
    counter -= 1
    val update = counter
    if (update == 0) promise.success(value)
  }

  def success(value: A): Unit =
    promise.success(value)
}
