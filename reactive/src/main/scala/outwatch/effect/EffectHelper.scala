package outwatch.effect

import cats.effect.{IO, Effect}
import outwatch.reactive.Subscription
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}

sealed trait RunAsyncResult[+T] {
  def map[B](f: T => B): RunAsyncResult[B]
}
object RunAsyncResult {
  type Result[T] = Either[Throwable, T]
  case class Sync[T](value: Result[T])  extends RunAsyncResult[T] {
    def map[B](f: T => B) = Sync[B](value.map(f))
  }
  case class Async[T](singleSubscribe: (Result[T] => Unit) => Subscription) extends RunAsyncResult[T] {
    def map[B](f: T => B) = Async[B](cb => singleSubscribe(result => cb(result.map(f))))
  }
}

private[outwatch] object EffectHelper {
  def futureRunSyncOrAsync[T](future: Future[T])(implicit ec: ExecutionContext): RunAsyncResult[T] = future.value match {

    case Some(value) => RunAsyncResult.Sync(value.toEither)

    case None => RunAsyncResult.Async[T] { asyncCallback =>
      var isCancel = false
      future.onComplete(t => if (!isCancel) t match {
        case Success(v) => asyncCallback(Right(v))
        case Failure(error) => asyncCallback(Left(error))
      })
      Subscription(() => isCancel = true)
    }
  }

  //TODO: proper cancelable. ConcurrentEffect?
  def unsafeRunSyncOrAsync[F[_]: Effect, T](effect: F[T]): RunAsyncResult[T] = {

    var cb: Either[Throwable, T] => Unit = either => return RunAsyncResult.Sync(either)
    Effect[F].runAsync(effect)(either => IO.pure(cb(either))).unsafeRunSync()

    RunAsyncResult.Async[T] { asyncCallback =>
      var isCancel = false
      cb = either => if (!isCancel) asyncCallback(either)
      Subscription(() => isCancel = true)
    }
  }
}
