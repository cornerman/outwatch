package outwatch.dom.helpers

import org.scalajs.dom.Element
import org.scalajs.dom.raw.CSSStyleDeclaration

import scala.scalajs.js
import scala.scalajs.js.annotation.JSBracketAccess

object JSDefined {
  // provides an extractor for js.UndefOr
  // https://gitter.im/scala-js/scala-js?at=5c3e221135350772cf375515
  def apply[A](a: A): js.UndefOr[A] = a
  def unapply[A](a: js.UndefOr[A]): UnapplyResult[A] = new UnapplyResult(a)

  final class UnapplyResult[+A](val self: js.UndefOr[A])
  extends AnyVal {
    @inline def isEmpty: Boolean = self eq js.undefined
    /** Calling `get` when `isEmpty` is true is undefined behavior. */
    @inline def get: A = self.asInstanceOf[A]
  }
}

@js.native
trait DictionaryRawApply[A] extends js.Object {
  @JSBracketAccess
  def apply(key: String): js.UndefOr[A] = js.native
}

object NativeHelpers {
  implicit class WithRaw[A](val dict: js.Dictionary[A]) extends AnyVal {
    @inline def raw: DictionaryRawApply[A] = dict.asInstanceOf[DictionaryRawApply[A]]
  }

  implicit class RichElement(val elem: Element) {
    def style: CSSStyleDeclaration = elem.asInstanceOf[js.Dynamic].style.asInstanceOf[CSSStyleDeclaration] // HTMLElement already has .style, but SVGElement doesn't
    def dataset: js.Dictionary[String] = elem.asInstanceOf[js.Dynamic].dataset.asInstanceOf[js.Dictionary[String]] //TODO: https://github.com/scala-js/scala-js-dom/pull/337
  }

  @inline def assign[T](value: T)(f: T => Unit): T = { f(value); value }

  @inline def appendSeq[T](source: js.Array[T], other: Seq[T]): js.Array[T] = other match {
    case wrappedOther:js.WrappedArray[T] => source.concat(wrappedOther.array)
    case _                               => source ++ other
  }

  @inline def prependSeq[T](source: js.Array[T], other: Seq[T]): js.Array[T] = other match {
    case wrappedOther:js.WrappedArray[T] => wrappedOther.array.concat(source)
    case _                               => other.++(source)(collection.breakOut)
  }
}
