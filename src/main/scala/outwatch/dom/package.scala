package outwatch

import cats.effect.IO
import org.scalajs.dom.Element

package object dom extends Attributes with Tags with HandlerFactories {

  type VTree[Elem <: Element] = IO[VTree_[Elem]]
  type VNode = IO[VNode_]
  type VDomModifier = IO[VDomModifier_]

  type Observable[+A] = rxscalajs.Observable[A]
  val Observable = rxscalajs.Observable

  type Sink[-A] = outwatch.Sink[A]
  val Sink = outwatch.Sink

  type Pipe[-I, +O] = outwatch.Pipe[I, O]
  val Pipe = outwatch.Pipe

  type Handler[T] = outwatch.Handler[T]
  val Handler = outwatch.Handler

  implicit def renderVNode[T](value: T)(implicit vnr: VNodeRender[T]): VNode = vnr.render(value)

  implicit def optionIsEmptyModifier(opt: Option[VDomModifier]): VDomModifier = opt getOrElse IO.pure(EmptyVDomModifier)

  implicit def compositeModifier(modifiers: Seq[VDomModifier]): VDomModifier = IO.pure(CompositeVDomModifier(modifiers))

  implicit class ioVTreeMerge[Elem <: Element](vnode: VTree[Elem]) {
    def apply(args: (TagContext[Elem] => VDomModifier)*): VTree[Elem] = {
      vnode.flatMap(vnode_ => vnode_(args:_*))
    }
  }

  //TODO type clase vdommodifierrender
  implicit def ModifierIsContextualModifier[Elem <: Element, T](mod: T)(implicit conv: T => VDomModifier): TagContext[Elem] => VDomModifier = _ => conv(mod)
}
