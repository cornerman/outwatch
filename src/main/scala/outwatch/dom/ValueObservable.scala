package outwatch.dom

import cats.Functor
import monix.execution.Cancelable
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.Var

import scala.collection.mutable

trait ValueObservable[+T] {
  self =>
  def value: Option[T]

  def tailObservable: Observable[T]

  lazy val toObservable: Observable[T] = (subscriber: Subscriber[T]) => value.fold(tailObservable)(v => tailObservable.startWith(v :: Nil)).unsafeSubscribeFn(subscriber)

  @inline def map[B](f: T => B): ValueObservable[B] = TransformingValueObservable[T,B](self, v => Some(f(v)))
  @inline def collect[B](f: PartialFunction[T,B]): ValueObservable[B] = TransformingValueObservable[T,B](self, f.lift)
  @inline def filter(f: T => Boolean): ValueObservable[T] = TransformingValueObservable[T,T](self, v => Some(v).filter(f))
  @inline def withLatestFrom[B,R](other: ValueObservable[B])(f: (T,B) => R): ValueObservable[R] = TransformingValueObservable[T,R](self, x => other.value.map(v => f(x, v)))
  @inline def scanValue[S](f: (Option[S], T) => S): ValueObservable[S] = scanValueOption[S]((p,c) => Some(f(p,c)))
  @inline def scanValueOption[S](f: (Option[S], T) => Option[S]): ValueObservable[S] = TransformingValueObservable.scan[T,S](self, (p,c) => f(p, c))
  @inline def scan[S](seed: => S)(op: (S, T) => S): ValueObservable[S] = TransformingValueObservable.scan[T,S](self, (p,c) => Some(op(p.getOrElse(seed), c)))
  @inline def distinctUntilChanged[TT >: T](implicit equality: cats.Eq[TT]) : ValueObservable[TT] = TransformingValueObservable.scan[T,TT](self, (p,c) => Some(c).filter(c => p.fold(true)(equality.neqv(_, c))))
  @inline def startWith[B >: T](elems: Seq[B]): ValueObservable[B] = {
    if (elems.isEmpty) this
    else if (elems.size == 1) ValueObservable(toObservable, elems.head)
    else ValueObservable(toObservable.startWith(elems.tail), elems.head)
  }
}

object ValueObservable {
  implicit def functor: Functor[ValueObservable] = new Functor[ValueObservable] {
    override def map[A, B](fa: ValueObservable[A])(f: A => B): ValueObservable[B] = fa.map(f)
  }

  @inline implicit def asObservable[T](stream: ValueObservable[T]): Observable[T] = stream.toObservable

  @inline def empty: ValueObservable[Nothing] = apply(Observable.empty)

  def apply[T](stream: Observable[T]): ValueObservable[T] = new ValueObservable[T] {
    override def value: Option[T] = None
    override def tailObservable: Observable[T] = stream
  }
  def apply[T](stream: Observable[T], initialValue: T): ValueObservable[T] = new ValueObservable[T] {
    override def value: Option[T] = Some(initialValue)
    override def tailObservable: Observable[T] = stream
  }
  def apply[T](stream: Var[T]): ValueObservable[T] = new ValueObservable[T] {
    override def value: Option[T] = Some(stream.apply())
    override def tailObservable: Observable[T] = stream.drop(1)
  }
  @inline def merge[T](a: ValueObservable[T], b: ValueObservable[T]): ValueObservable[T] = merge(a, b.toObservable)
  def merge[T](a: ValueObservable[T], b: Observable[T]): ValueObservable[T] = new ValueObservable[T] {
    override def value: Option[T] = a.value
    override def tailObservable: Observable[T] = Observable(a.tailObservable, b).merge
  }
  @inline def concat[T](a: ValueObservable[T], b: ValueObservable[T]): ValueObservable[T] = concat(a, b.toObservable)
  def concat[T](a: ValueObservable[T], b: Observable[T]): ValueObservable[T] = new ValueObservable[T] {
    override def value: Option[T] = a.value
    override def tailObservable: Observable[T] = Observable(a.tailObservable, b).concat
  }

  @inline def apply[F[_], T](stream: F[T])(implicit asValueObservable: AsValueObservable[F]): ValueObservable[T] = asValueObservable.as(stream)
}

object TransformingValueObservable {
  def apply[A,B](valueObservable: ValueObservable[A], filterValue: A => Option[B]): ValueObservable[B] = new ValueObservable[B] {
    override def value: Option[B] = valueObservable.value.flatMap(filterValue)
    override val tailObservable: Observable[B] = (subscriber: Subscriber[B]) => {
      valueObservable.tailObservable
        .map(filterValue).collect { case Some(v) => v }
        .unsafeSubscribeFn(subscriber)
    }
  }
  def scan[A,B](valueObservable: ValueObservable[A], filterValue: (Option[B], A) => Option[B]): ValueObservable[B] = new ValueObservable[B] {
    override def value: Option[B] = valueObservable.value.flatMap(filterValue(None, _))
    override val tailObservable: Observable[B] = (subscriber: Subscriber[B]) => {
      valueObservable.tailObservable
        .scan(value)(filterValue).collect { case Some(v) => v }
        .unsafeSubscribeFn(subscriber)
    }
  }
}

private[outwatch] final class ObservableOfValueObservable[A] private(valueObservable: ValueObservable[A])
