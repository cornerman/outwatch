package outwatch.dom.helpers

import monix.reactive.Observable
import outwatch.AsVDomModifier
import outwatch.dom._

import scala.language.dynamics

@inline trait AttributeBuilder[-T, +A <: VDomModifier] {
  @inline def assign(value: T): A

  @inline def :=(value: T): A = assign(value)
  @inline def :=?(value: Option[T]): Option[A] = value.map(assign)
  @inline def <--[F[_] : AsValueObservable](valueStream: F[_ <: T]): ModifierStreamReceiver = {
    ModifierStreamReceiver(ValueObservable(valueStream).map(assign))
  }
}

object AttributeBuilder {
  @inline implicit def toAttribute[A <: VDomModifier](builder: AttributeBuilder[Boolean, A]): A = builder := true
}

// Attr

@inline trait AccumulateAttrOps[T] { self: AttributeBuilder[T, BasicAttr] =>
  protected def name: String
  @inline def accum(s: String): AccumAttrBuilder[T] = accum(_ + s + _)
  @inline def accum(reducer: (Attr.Value, Attr.Value) => Attr.Value) = new AccumAttrBuilder[T](name, this, reducer)
}

@inline final class BasicAttrBuilder[T](val name: String, encode: T => Attr.Value) extends AttributeBuilder[T, BasicAttr]
                                                                                   with AccumulateAttrOps[T] {
  @inline def assign(value: T) = BasicAttr(name, encode(value))
}

@inline final class DynamicAttrBuilder[T](parts: List[String]) extends Dynamic
                                                               with AttributeBuilder[T, BasicAttr]
                                                               with AccumulateAttrOps[T] {
  lazy val name: String = parts.reverse.mkString("-")

  def selectDynamic(s: String) = new DynamicAttrBuilder[T](s :: parts)

  @inline def assign(value: T) = BasicAttr(name, value.toString)
}

@inline final class AccumAttrBuilder[T](
  val name: String,
  builder: AttributeBuilder[T, BasicAttr],
  reduce: (Attr.Value, Attr.Value) => Attr.Value
) extends AttributeBuilder[T, AccumAttr] {
  @inline def assign(value: T) = AccumAttr(name, builder.assign(value).value, reduce)
}

// Props

@inline trait AccumulatePropOps[T] { self: AttributeBuilder[T, BasicProp] =>
  protected def name: String
  @inline def accum(s: String): AccumPropBuilder[T] = accum(_ + s + _)
  @inline def accum(reducer: (Prop.Value, Prop.Value) => Prop.Value) = new AccumPropBuilder[T](name, this, reducer)
}

@inline final class PropBuilder[T](val name: String, encode: T => Prop.Value) extends AttributeBuilder[T, BasicProp] with AccumulatePropOps[T] {
  @inline def assign(value: T) = BasicProp(name, encode(value))
}

@inline final class AccumPropBuilder[T](
  val name: String,
  builder: AttributeBuilder[T, BasicProp],
  reduce: (Prop.Value, Prop.Value) => Prop.Value
) extends AttributeBuilder[T, AccumProp] {
  @inline def assign(value: T) = AccumProp(name, builder.assign(value).value, reduce)
}

// Styles

@inline trait AccumulateStyleOps[T] extends Any { self: AttributeBuilder[T, BasicStyle] =>
  protected def name: String
  @inline def accum: AccumStyleBuilder[T] = accum(",")
  @inline def accum(s: String): AccumStyleBuilder[T] = accum(_ + s + _)
  @inline def accum(reducer: (String, String) => String) = new AccumStyleBuilder[T](name, reducer)
}

@inline final class BasicStyleBuilder[T](val name: String) extends AttributeBuilder[T, BasicStyle]
                                                           with AccumulateStyleOps[T] {
  @inline def assign(value: T) = BasicStyle(name, value.toString)

  @inline def delayed: DelayedStyleBuilder[T] = new DelayedStyleBuilder[T](name)
  @inline def remove: RemoveStyleBuilder[T] = new RemoveStyleBuilder[T](name)
  @inline def destroy: DestroyStyleBuilder[T] = new DestroyStyleBuilder[T](name)
}

@inline final class DelayedStyleBuilder[T](val name: String) extends AttributeBuilder[T, DelayedStyle] {
  @inline def assign(value: T) = DelayedStyle(name, value.toString)
}

@inline final class RemoveStyleBuilder[T](val name: String) extends AttributeBuilder[T, RemoveStyle] {
  @inline def assign(value: T) = RemoveStyle(name, value.toString)
}

@inline final class DestroyStyleBuilder[T](val name: String) extends AttributeBuilder[T, DestroyStyle] {
  @inline def assign(value: T) = DestroyStyle(name, value.toString)
}

@inline final class AccumStyleBuilder[T](val name: String, reducer: (String, String) => String)
  extends AttributeBuilder[T, AccumStyle] {
  @inline def assign(value: T) = AccumStyle(name, value.toString, reducer)
}


object KeyBuilder {
  @inline def :=(key: Key.Value): Key = Key(key)
}

// Child / Children

object ChildStreamReceiverBuilder {
  def <--[T](valueStream: Observable[VDomModifier]): ModifierStreamReceiver =
    ModifierStreamReceiver(AsValueObservable.observable.as(valueStream))

  def <--[T](valueStream: Observable[T])(implicit r: AsVDomModifier[T]): ModifierStreamReceiver =
    ModifierStreamReceiver(AsValueObservable.observable.as(valueStream.map(r.asVDomModifier)))
}

object ChildrenStreamReceiverBuilder {
  def <--(childrenStream: Observable[Seq[VDomModifier]]): ModifierStreamReceiver =
    ModifierStreamReceiver(AsValueObservable.observable.as(childrenStream.map[VDomModifier](x => x)))

  def <--[T](childrenStream: Observable[Seq[T]])(implicit r: AsVDomModifier[T]): ModifierStreamReceiver =
    ModifierStreamReceiver(AsValueObservable.observable.as(childrenStream.map(_.map(r.asVDomModifier))))
}
