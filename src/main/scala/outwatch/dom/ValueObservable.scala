package outwatch.dom

import cats.Functor
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.Var

trait ValueObservable[+T] { self =>
  def headValue: Option[T]

  def tailObservable: Observable[T]

  val toObservable: Observable[T] = (subscriber: Subscriber[T]) => headValue.fold(tailObservable)(v => tailObservable.startWith(v :: Nil)).unsafeSubscribeFn(subscriber)

  @inline def map[B](f: T => B): ValueObservable[B] = mapOption[B](v => Some(f(v)))
  @inline def collect[B](f: PartialFunction[T, B]): ValueObservable[B] = mapOption[B](f.lift)
  @inline def filter(f: T => Boolean): ValueObservable[T] = mapOption[T](v => Some(v).filter(f))
  @inline def scan[S](seed: => S)(op: (S, T) => S): ValueObservable[S] = scanOption[S]((p, c) => Some(op(p.getOrElse(seed), c)))
  @inline def doOnNext(cb: T => Task[Unit]): ValueObservable[T] = transformTailObservable(_.doOnNext(cb))
  @inline def doOnComplete(cb: Task[Unit]): ValueObservable[T] = transformTailObservable(_.doOnComplete(cb))
  @inline def doOnError(cb: Throwable => Task[Unit]): ValueObservable[T] = transformTailObservable(_.doOnError(cb))
  @inline def share(implicit s: Scheduler): ValueObservable[T] = transformTailObservable(_.share)
  @inline def prepend[B >: T](elem: B): ValueObservable[B] = ValueObservable.from(toObservable, elem)
  @inline def +:[B >: T](elem: B): ValueObservable[B] = prepend(elem)
  @inline def append[B >: T](elem: B): ValueObservable[B] = transformTailObservable[B](_.append(elem))
  @inline def :+[B >: T](elem: B): ValueObservable[B] = append(elem)
  def startWith[B >: T](elems: Seq[B]): ValueObservable[B] = {
    if (elems.isEmpty) this
    else if (elems.size == 1) ValueObservable.from(toObservable, elems.head)
    else ValueObservable.from(toObservable.startWith(elems.tail), elems.head)
  }

  def distinctUntilChanged[TT >: T](implicit e: cats.Eq[TT]): ValueObservable[TT] = scanOption[TT]((p, c) => Some(c).filter(c => p.fold(true)(e.neqv(_, c))))
  def distinctUntilChangedByKey[K](key: T => K)(implicit e: cats.Eq[K]): Observable[T] = scanOption[T]((p, c) => Some(c).filter(c => p.fold(true)(p => e.neqv(key(p), key(c)))))

  def merge[B](implicit ev: T <:< ValueObservable[B], os: OverflowStrategy[B] = OverflowStrategy.Default): ValueObservable[B] = new ValueObservable[B] {
    override def headValue: Option[B] = self.headValue.flatMap(_.headValue)
    override def tailObservable: Observable[B] = self.headValue.fold {
      self.tailObservable.mergeMap(_.toObservable)
    } { value =>
      self.tailObservable.map(_.toObservable).prepend(value.tailObservable).merge
    }
  }
  @inline def flatten[B](implicit ev: T <:< ValueObservable[B]): ValueObservable[B] = concat
  def concat[B](implicit ev: T <:< ValueObservable[B]): ValueObservable[B] = new ValueObservable[B] {
    override def headValue: Option[B] = self.headValue.flatMap(_.headValue)
    override def tailObservable: Observable[B] = self.headValue.fold {
      self.tailObservable.concatMap(_.toObservable)
    } { value =>
      self.tailObservable.map(_.toObservable).prepend(value.tailObservable).concat
    }
  }
  def withLatestFrom[B, R](other: ValueObservable[B])(f: (T, B) => R): ValueObservable[R] = new ValueObservable[R] {
    override def headValue: Option[R] = for {v1 <- self.headValue; v2 <- other.headValue } yield f(v1, v2)
    override val tailObservable: Observable[R] = self.tailObservable.withLatestFrom(other.toObservable)(f)
  }

  def transformTailObservable[TT >: T](f: Observable[TT] => Observable[TT]): ValueObservable[TT] = new ValueObservable[TT] {
    override def headValue: Option[TT] = self.headValue
    override val tailObservable: Observable[TT] = f(self.tailObservable)
  }

  def mapOption[B](f: T => Option[B]): ValueObservable[B] = new ValueObservable[B] {
    override def headValue: Option[B] = self.headValue.flatMap(f)
    override val tailObservable: Observable[B] = (subscriber: Subscriber[B]) => {
      self.tailObservable
        .map(f).collect { case Some(v) => v }
        .unsafeSubscribeFn(subscriber)
    }
  }
  def scanOption[B](f: (Option[B], T) => Option[B]): ValueObservable[B] = new ValueObservable[B] {
    override def headValue: Option[B] = self.headValue.flatMap(f(None, _))
    override val tailObservable: Observable[B] = self.tailObservable.scan(headValue)(f).collect { case Some(v) => v }
  }
}

object ValueObservable {
  implicit def functor: Functor[ValueObservable] = new Functor[ValueObservable] {
    override def map[A, B](fa: ValueObservable[A])(f: A => B): ValueObservable[B] = fa.map(f)
  }

  @inline implicit def asObservable[T](stream: ValueObservable[T]): Observable[T] = stream.toObservable

  @inline def empty: ValueObservable[Nothing] = apply()
  @inline def apply[T](): ValueObservable[T] = from[T](Observable.empty)
  @inline def apply[T](initialValue: T): ValueObservable[T] = from[T](Observable.empty, initialValue)
  @inline def apply[T](initialValue: T, value: T, values: T*): ValueObservable[T] = from[T](Observable.fromIterable(value :: values.toList), initialValue)
  @inline def from[T](stream: Observable[T]): ValueObservable[T] = fromOption(stream, None)
  @inline def from[T](stream: Observable[T], initialValue: T): ValueObservable[T] = fromOption(stream, Some(initialValue))
  def fromOption[T](stream: Observable[T], initialValue: Option[T]): ValueObservable[T] = new ValueObservable[T] {
    override def headValue: Option[T] = initialValue
    override val tailObservable: Observable[T] = stream
  }
  def from[T](stream: Var[T]): ValueObservable[T] = new ValueObservable[T] {
    override def headValue: Option[T] = Some(stream.apply())
    override def tailObservable: Observable[T] = stream.drop(1)
  }

  @inline def from[F[_], T](stream: F[T])(implicit asValueObservable: AsValueObservable[F]): ValueObservable[T] = asValueObservable.as(stream)
}
