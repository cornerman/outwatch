package outwatch.dom.helpers

import cats.{Monoid, Functor, Bifunctor}
import cats.effect.{Effect, Sync => SyncCats, SyncIO}
import cats.implicits._
import org.scalajs.dom.{Element, Event, html, svg}
import outwatch.dom._
import outwatch.reactive._
import outwatch.effect._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

// The EmitterBuilder[O, R] allows you to build an R that produces values of type O.
// The builder gives you a declarative interface to describe transformations on the
// emitted values of type O and the result type R.
//
// Example onClick event:
// onClick: EmitterBuilder[ClickEvent, Emitter]

// The result emitter describes a registration of the click event on the embedding
// dom element. This produces click events that can be transformed:
// onClick.map(_ => 1): EmitterBuilder[Int, Emitter]

// We keep the same result, the registration for the click event, but map the emitted
// click events to integers. You can also map the result type:
// onClick.mapResult(emitter => VDomModifier(emitter, ???)): EmitterBuilder[Int, VDomModifier]
//
// Now you have conbined the emitter with another VDomModifier, so the combined modifier
// will later be rendered instead of only the emitter. Then you can describe the action
// that should be done when an event triggers:
//
// onClick.map(_ => 1).foreach(doSomething(_)): VDomModifier
//
// The EmitterBuilder result must be a SubscriptionOwner to handle the subscription
// from the emitterbuilder.
//


trait EmitterBuilderExecution[+O, +R, +Exec <: EmitterBuilder.Execution] {

  @inline def forwardTo[F[_] : Sink](sink: F[_ >: O]): R

  // this method keeps the current Execution but actually, the caller must decide,
  // whether this really keeps the execution type or might be async. Therefore private.
  @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilderExecution[T, R, Exec]

  @inline def -->[F[_] : Sink](sink: F[_ >: O]): R = forwardTo(sink)

  @inline def discard: R = forwardTo(SinkObserver.empty)

  @inline def foreach(action: O => Unit): R = forwardTo(SinkObserver.create(action))
  @inline def foreach(action: => Unit): R = foreach(_ => action)

  @inline def foreachSync[G[_] : RunSyncEffect](action: O => G[Unit]): R = mapSync(action).discard
  @inline def doSync[G[_] : RunSyncEffect](action: G[Unit]): R = foreachSync(_ => action)

  @inline def foreachAsync[G[_] : Effect](action: O => G[Unit]): R = concatMapAsync(action).discard
  @inline def doAsync[G[_] : Effect](action: G[Unit]): R = foreachAsync(_ => action)

  @inline def map[T](f: O => T): EmitterBuilderExecution[T, R, Exec] = transformWithExec(_.map(f))

  @inline def collect[T](f: PartialFunction[O, T]): EmitterBuilderExecution[T, R, Exec] = transformWithExec(_.collect(f))

  @inline def filter(predicate: O => Boolean): EmitterBuilderExecution[O, R, Exec] = transformWithExec(_.filter(predicate))

  @inline def use[T](value: T): EmitterBuilderExecution[T, R, Exec] = map(_ => value)
  @inline def useLazy[T](value: => T): EmitterBuilderExecution[T, R, Exec] = map(_ => value)

  @deprecated("Use .useLazy(value) instead", "")
  @inline def mapTo[T](value: => T): EmitterBuilderExecution[T, R, Exec] = useLazy(value)
  @deprecated("Use .use(value) instead", "")
  @inline def apply[T](value: T): EmitterBuilderExecution[T, R, Exec] = use(value)

  @inline def useSync[G[_]: RunSyncEffect, T](value: G[T]): EmitterBuilderExecution[T, R, Exec] = mapSync(_ => value)

  @inline def useAsync[G[_]: Effect, T](value: G[T]): EmitterBuilder[T, R] = concatMapAsync(_ => value)

  @inline def apply[G[_] : Source, T](source: G[T]): EmitterBuilderExecution[T, R, Exec] = useLatest(source)

  @inline def useLatest[F[_] : Source, T](latest: F[T]): EmitterBuilderExecution[T, R, Exec] =
    transformWithExec[SourceStream, T](source => SourceStream.withLatestFrom(source)(latest)((_, u) => u))

  @inline def withLatest[F[_] : Source, T](latest: F[T]): EmitterBuilderExecution[(O, T), R, Exec] =
    transformWithExec[SourceStream, (O, T)](source => SourceStream.withLatestFrom(source)(latest)(_ -> _))

  @inline def scan[T](seed: T)(f: (T, O) => T): EmitterBuilderExecution[T, R, Exec] =
    transformWithExec[SourceStream, T](source => SourceStream.scan(source)(seed)(f))

  @inline def scanSingle[T](seed: T)(f: T => T): EmitterBuilderExecution[T, R, Exec] = scan(seed)((t,_) => f(t))

  @inline def debounce(duration: FiniteDuration): EmitterBuilder[O, R] =
    transformWithExec[SourceStream, O](source => SourceStream.debounce(source)(duration))

  @inline def debounceMillis(millis: Int): EmitterBuilder[O, R] =
    transformWithExec[SourceStream, O](source => SourceStream.debounceMillis(source)(millis))

  @inline def async: EmitterBuilder[O, R] =
    transformWithExec[SourceStream, O](source => SourceStream.async(source))

  @inline def delay(duration: FiniteDuration): EmitterBuilder[O, R] =
    transformWithExec[SourceStream, O](source => SourceStream.delay(source)(duration))

  @inline def delayMillis(millis: Int): EmitterBuilder[O, R] =
    transformWithExec[SourceStream, O](source => SourceStream.delayMillis(source)(millis))

  @inline def concatMapFuture[T](f: O => Future[T]): EmitterBuilder[T, R] =
    transformWithExec[SourceStream, T](source => SourceStream.concatMapFuture(source)(f))

  @inline def concatMapAsync[G[_]: Effect, T](f: O => G[T]): EmitterBuilder[T, R] =
    transformWithExec[SourceStream, T](source => SourceStream.concatMapAsync(source)(f))

  @inline def mapSync[G[_]: RunSyncEffect, T](f: O => G[T]): EmitterBuilderExecution[T, R, Exec] =
    transformWithExec[SourceStream, T](source => SourceStream.mapSync(source)(f))

  @inline def transformLift[F[_] : Source : LiftSource, OO >: O, T](f: F[OO] => F[T]): EmitterBuilder[T, R] =
    transformWithExec[F, T]((s: SourceStream[OO]) => f(s.liftSource[F]))

  // do not expose transform with current exec but just normal Emitterbuilder. This tranform might be async
  @inline def transform[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilder[T, R] = transformWithExec(f)

  @inline def mapResult[S](f: R => S): EmitterBuilderExecution[O, S, Exec] = new EmitterBuilder.MapResult[O, R, S, Exec](this, f)
}

object EmitterBuilder {

  sealed trait Execution
  sealed trait SyncExecution extends Execution

  type Sync[+O, +R] = EmitterBuilderExecution[O, R, SyncExecution]

  @inline final class MapResult[+O, +I, +R, +Exec <: Execution](base: EmitterBuilder[O, I], mapF: I => R) extends EmitterBuilderExecution[O, R, Exec] {
    @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilderExecution[T, R, Exec] = new MapResult(base.transformWithExec(f), mapF)
    @inline def forwardTo[F[_] : Sink](sink: F[_ >: O]): R = mapF(base.forwardTo(sink))
  }

  @inline final class Empty[+R](empty: R) extends EmitterBuilderExecution[Nothing, R, Nothing] {
    @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[Nothing] => F[T]): EmitterBuilderExecution[T, R, Nothing] = this
    @inline def forwardTo[F[_] : Sink](sink: F[_ >: Nothing]): R = empty
  }

  @inline final class Stream[S[_] : Source, +O, +R: SubscriptionOwner](source: S[O], result: R) extends EmitterBuilderExecution[O, R, Execution] {
    @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilderExecution[T, R, Execution] = new Stream(f(SourceStream.lift(source)), result)
    @inline def forwardTo[F[_] : Sink](sink: F[_ >: O]): R = SubscriptionOwner[R].own(result)(() => Source[S].subscribe(source)(sink))
  }

  @inline final class Custom[+O, +R: SubscriptionOwner, + Exec <: Execution](create: SinkObserver[O] => R) extends EmitterBuilderExecution[O, R, Exec] {
    @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilderExecution[T, R, Exec] = new Transform(this, f)
    @inline def forwardTo[F[_] : Sink](sink: F[_ >: O]): R = create(SinkObserver.lift(sink))
  }

  @inline final class Transform[S[_] : Source, +I, +O, +R: SubscriptionOwner, Exec <: Execution](base: EmitterBuilderExecution[I, R, Exec], transformF: SourceStream[I] => S[O]) extends EmitterBuilderExecution[O, R, Exec] {
    @inline private[helpers] def transformWithExec[F[_] : Source, T](f: SourceStream[O] => F[T]): EmitterBuilderExecution[T, R, Exec] = new Transform[F, I, T, R, Exec](base, s => f(SourceStream.lift(transformF(s))))
    @inline def forwardTo[F[_] : Sink](sink: F[_ >: O]): R = {
      val connectable = SinkObserver.redirect[F, S, O, I](sink)(transformF)
      SubscriptionOwner[R].own(base.forwardTo(connectable.sink))(() => connectable.connect())
    }
  }

  //TODO: we requiring Monoid here, but actually just want an empty. Would allycats be better with Empty?
  @inline def emptyOf[R: Monoid]: EmitterBuilderExecution[Nothing, R, Nothing] = new Empty[R](Monoid[R].empty)

  @inline def apply[E, R : SubscriptionOwner](create: SinkObserver[E] => R): EmitterBuilder.Sync[E, R] = new Custom[E, R, SyncExecution](sink => create(sink))

  @inline def fromSourceOf[F[_] : Source, E, R : SubscriptionOwner : Monoid](source: F[E]): EmitterBuilder[E, R] = new Stream[F, E, R](source, Monoid[R].empty)

  // shortcuts for modifiers with less type ascriptions
  @inline def empty: EmitterBuilderExecution[Nothing, VDomModifier, Nothing] = emptyOf[VDomModifier]
  @inline def ofModifier[E](create: SinkObserver[E] => VDomModifier): EmitterBuilder.Sync[E, VDomModifier] = apply[E, VDomModifier](create)
  @inline def ofNode[E](create: SinkObserver[E] => VNode): EmitterBuilder.Sync[E, VNode] = apply[E, VNode](create)
  @inline def fromSource[F[_] : Source, E](source: F[E]): EmitterBuilder[E, VDomModifier] = fromSourceOf[F, E, VDomModifier](source)

  def fromEvent[E <: Event](eventType: String): EmitterBuilder.Sync[E, VDomModifier] = apply[E, VDomModifier] { sink =>
    Emitter(eventType, e => sink.onNext(e.asInstanceOf[E]))
  }

  @inline def combine[T, R : SubscriptionOwner : Monoid, Exec <: Execution](builders: EmitterBuilderExecution[T, R, Exec]*): EmitterBuilderExecution[T, R, Exec] = combineSeq(builders)

  def combineSeq[T, R : SubscriptionOwner : Monoid, Exec <: Execution](builders: Seq[EmitterBuilderExecution[T, R, Exec]]): EmitterBuilderExecution[T, R, Exec] = new Custom[T, R, Exec](sink =>
    Monoid[R].combineAll(builders.map(_.forwardTo(sink)))
  )

  @deprecated("Use EmitterBuilder.fromEvent[E] instead", "0.11.0")
  @inline def apply[E <: Event](eventType: String): EmitterBuilder.Sync[E, VDomModifier] = fromEvent[E](eventType)
  @deprecated("Use EmitterBuilder[E, O] instead", "0.11.0")
  @inline def custom[E, R : SubscriptionOwner](create: SinkObserver[E] => R): EmitterBuilder.Sync[E, R] = apply[E, R](create)

  implicit def monoid[T, R : SubscriptionOwner : Monoid, Exec <: Execution]: Monoid[EmitterBuilderExecution[T, R, Exec]] = new Monoid[EmitterBuilderExecution[T, R, Exec]] {
    def empty: EmitterBuilderExecution[T, R, Exec] = EmitterBuilder.emptyOf[R]
    def combine(x: EmitterBuilderExecution[T, R, Exec], y: EmitterBuilderExecution[T, R, Exec]): EmitterBuilderExecution[T, R, Exec] = EmitterBuilder.combine(x, y)
  }

  implicit def functor[R]: Functor[EmitterBuilder[?, R]] = new Functor[EmitterBuilder[?, R]] {
    def map[A, B](fa: EmitterBuilder[A,R])(f: A => B): EmitterBuilder[B,R] = fa.map(f)
  }

  implicit object bifunctor extends Bifunctor[EmitterBuilder] {
    def bimap[A, B, C, D](fab: EmitterBuilder[A,B])(f: A => C, g: B => D): EmitterBuilder[C,D] = fab.map(f).mapResult(g)
  }

  @inline implicit class HandlerIntegration[O, R : Monoid, Exec <: Execution](builder: EmitterBuilderExecution[O, R, Exec]) {
    @inline def handled(f: SourceStream[O] => R): SyncIO[R] = handledF[SyncIO](f)

    @inline def handledF[F[_] : SyncCats](f: SourceStream[O] => R): F[R] = handler.Handler.createF[F, O].map { handler =>
      Monoid[R].combine(builder.forwardTo(handler), f(handler))
    }
  }

  @inline implicit class EmitterOperations[O, R : Monoid : SubscriptionOwner, Exec <: Execution](builder: EmitterBuilderExecution[O, R, Exec]) {

    @inline def withLatestEmitter[T](emitter: EmitterBuilder[T, R]): EmitterBuilderExecution[(O,T), SyncIO[R], Exec] = combineWithLatestEmitter(builder, emitter)

    @inline def useLatestEmitter[T](emitter: EmitterBuilder[T, R]): EmitterBuilderExecution[T, SyncIO[R], Exec] = combineWithLatestEmitter(builder, emitter).map(_._2)
  }

  implicit class EventActions[O <: Event, R](val builder: EmitterBuilder.Sync[O, R]) extends AnyVal {
    def preventDefault: EmitterBuilder.Sync[O, R] = builder.map { e => e.preventDefault; e }
    def stopPropagation: EmitterBuilder.Sync[O, R] = builder.map { e => e.stopPropagation; e }
    def stopImmediatePropagation: EmitterBuilder.Sync[O, R] = builder.map { e => e.stopImmediatePropagation; e }
  }

  @inline implicit class TargetAsInput[O <: Event, R](builder: EmitterBuilder.Sync[O, R]) {
    object target {
      @inline def value: EmitterBuilder.Sync[String, R] = builder.map(_.target.asInstanceOf[html.Input].value)
      @inline def valueAsNumber: EmitterBuilder.Sync[Double, R] = builder.map(_.target.asInstanceOf[html.Input].valueAsNumber)
      @inline def checked: EmitterBuilder.Sync[Boolean, R] = builder.map(_.target.asInstanceOf[html.Input].checked)
    }
  }

  implicit class CurrentTargetAsInput[O <: Event, R](val builder: EmitterBuilder.Sync[O, R]) extends AnyVal {
    def value: EmitterBuilder.Sync[String, R] = builder.map(e => e.currentTarget.asInstanceOf[html.Input].value)
    def valueAsNumber: EmitterBuilder.Sync[Double, R] = builder.map(e => e.currentTarget.asInstanceOf[html.Input].valueAsNumber)
    def checked: EmitterBuilder.Sync[Boolean, R] = builder.map(e => e.currentTarget.asInstanceOf[html.Input].checked)
  }

  implicit class CurrentTargetAsElement[O <: Event, R](val builder: EmitterBuilder.Sync[O, R]) extends AnyVal {
    def asHtml: EmitterBuilder.Sync[html.Element, R] = builder.map(_.currentTarget.asInstanceOf[html.Element])
    def asSvg: EmitterBuilder.Sync[svg.Element, R] = builder.map(_.currentTarget.asInstanceOf[svg.Element])
    def asElement: EmitterBuilder.Sync[Element, R] = builder.map(_.currentTarget.asInstanceOf[Element])
  }

  implicit class TypedElements[O <: Element, R](val builder: EmitterBuilder.Sync[O, R]) extends AnyVal {
    def asHtml: EmitterBuilder.Sync[html.Element, R] = builder.map(_.asInstanceOf[html.Element])
    def asSvg: EmitterBuilder.Sync[svg.Element, R] = builder.map(_.asInstanceOf[svg.Element])
  }

  implicit class TypedElementTuples[E <: Element, R](val builder: EmitterBuilder.Sync[(E,E), R]) extends AnyVal {
    def asHtml: EmitterBuilder.Sync[(html.Element, html.Element), R] = builder.map(_.asInstanceOf[(html.Element, html.Element)])
    def asSvg: EmitterBuilder.Sync[(svg.Element, svg.Element), R] = builder.map(_.asInstanceOf[(svg.Element, svg.Element)])
  }

  private def combineWithLatestEmitter[O, T, R : Monoid : SubscriptionOwner, Exec <: Execution](sourceEmitter: EmitterBuilderExecution[O, R, Exec], latestEmitter: EmitterBuilder[T, R]): EmitterBuilderExecution[(O, T), SyncIO[R], Exec] =
    new Custom[(O, T), SyncIO[R], Exec]({ sink =>
      import scala.scalajs.js

      SyncIO {
        var lastValue: js.UndefOr[T] = js.undefined
        Monoid[R].combine(
          latestEmitter.forwardTo(SinkObserver.create[T](lastValue = _, sink.onError)),
          sourceEmitter.forwardTo(SinkObserver.create[O](
            { o =>
              lastValue.foreach { t =>
                sink.onNext((o, t))
              }
            },
            sink.onError
          ))
        )
      }
    })
}
