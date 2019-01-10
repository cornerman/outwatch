package outwatch.dom

import cats.Functor
import com.raquo.domtypes.generic.keys
import monix.reactive.Observer
import outwatch.AsVDomModifier
import outwatch.dom.helpers.BasicStyleBuilder

trait VNodeApply[F[_]] {
  @inline def apply(f: F[VNode])(mods: VDomModifier*): F[VNode]
  @inline def append(f: F[VNode])(mods: VDomModifier*): F[VNode]
  @inline def prepend(f: F[VNode])(mods: VDomModifier*): F[VNode]
}
object VNodeApply {
  implicit def apply[F[_]](implicit va: VNodeApply[F]): VNodeApply[F] = va

  implicit def functorApply[F[_] : Functor]: VNodeApply[F] = new VNodeApply[F] {
    @inline def apply(f: F[VNode])(mods: VDomModifier*): F[VNode] = Functor[F].map(f)(_.apply(mods :_*))
    @inline def append(f: F[VNode])(mods: VDomModifier*): F[VNode] = Functor[F].map(f)(_.append(mods :_*))
    @inline def prepend(f: F[VNode])(mods: VDomModifier*): F[VNode] = Functor[F].map(f)(_.prepend(mods :_*))
  }
}

trait Implicits {

  @inline implicit def asVDomModifier[T](value: T)(implicit vm: AsVDomModifier[T]): VDomModifier = vm.asVDomModifier(value)

  //TODO: would be better to have typeclass AsObserver on all functions using observer.
  @inline implicit def asObserver[T, F[_]](value: F[T])(implicit ao: AsObserver[F]): Observer[T] = ao.as(value)

  @inline implicit def StyleIsBuilder[T](style: keys.Style[T]): BasicStyleBuilder[T] = new BasicStyleBuilder[T](style.cssName)

  implicit class RichVNodeApply[F[_]: VNodeApply](val f: F[VNode]) {
    @inline def apply(mods: VDomModifier*): F[VNode] = VNodeApply[F].apply(f)(mods :_*)
    @inline def append(mods: VDomModifier*): F[VNode] = VNodeApply[F].append(f)(mods :_*)
    @inline def prepend(mods: VDomModifier*): F[VNode] = VNodeApply[F].prepend(f)(mods :_*)
  }
}
