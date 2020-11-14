package outwatch

import cats._
import cats.implicits._
import cats.effect.Effect

import outwatch.helpers.{ModifierBooleanOps, BasicAttrBuilder, PropBuilder, BasicStyleBuilder}
import outwatch.helpers.NativeHelpers._

import colibri._
import colibri.effect.RunSyncEffect

import snabbdom.{DataObject, VNodeProxy}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import org.scalajs.dom

sealed trait RModifier[-Env] {
  type Self[-R] <: RModifier[R]

  def provide(env: Env): Self[Any]
  def provideSome[R](map: R => Env): Self[R]

  def append[R](args: RModifier[R]*): Self[Env with R]
  def prepend[R](args: RModifier[R]*): Self[Env with R]
}

sealed trait RModifierOps {
  type WithSelf[-Env, Mod[-Env] <: RModifier[Env]] = Mod[Env] { type Self[-R] = Mod[R] }

  @inline final def empty: Modifier = EmptyModifier

  @inline final def apply(): Modifier = empty

  @inline final def ifTrue(condition: Boolean): ModifierBooleanOps = new ModifierBooleanOps(condition)
  @inline final def ifNot(condition: Boolean): ModifierBooleanOps = new ModifierBooleanOps(!condition)

  @inline final def attr[T](key: String, convert: T => Attr.Value = (t: T) => t.toString : Attr.Value) = new BasicAttrBuilder[T](key, convert)
  @inline final def prop[T](key: String, convert: T => Prop.Value = (t: T) => t) = new PropBuilder[T](key, convert)
  @inline final def style[T](key: String) = new BasicStyleBuilder[T](key)

  @inline final def managed[F[_] : RunSyncEffect, T : CanCancel](subscription: F[T]): Modifier = managedFunction(() => RunSyncEffect[F].unsafeRun(subscription))

  @inline final def managedFunction[T : CanCancel](subscription: () => T): Modifier = CancelableModifier(() => Cancelable.lift(subscription()))

  object managedElement {
    def apply[T : CanCancel](subscription: dom.Element => T): Modifier = Modifier.delay {
      var lastSub: js.UndefOr[T] = js.undefined
      Modifier(
        DomMountHook(proxy => proxy.elm.foreach(elm => lastSub = subscription(elm))),
        DomUnmountHook(_ => lastSub.foreach(CanCancel[T].cancel))
      )
    }

    @inline def asHtml[T : CanCancel](subscription: dom.html.Element => T): Modifier = apply(elem => subscription(elem.asInstanceOf[dom.html.Element]))

    @inline def asSvg[T : CanCancel](subscription: dom.svg.Element => T): Modifier = apply(elem => subscription(elem.asInstanceOf[dom.svg.Element]))
  }
}

object RModifier extends RModifierOps {

  @inline def apply[Env, T : Render[Env, ?]](t: T): RModifier[Env] = Render[Env, T].render(t)

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env]): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2))

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env], modifier3: RModifier[Env]): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2, modifier3))

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env], modifier3: RModifier[Env], modifier4: RModifier[Env]): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2, modifier3, modifier4))

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env], modifier3: RModifier[Env], modifier4: RModifier[Env], modifier5: RModifier[Env]): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2, modifier3, modifier4, modifier5))

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env], modifier3: RModifier[Env], modifier4: RModifier[Env], modifier5: RModifier[Env], modifier6: RModifier[Env]): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2, modifier3, modifier4, modifier5, modifier6))

  @inline def apply[Env](modifier: RModifier[Env], modifier2: RModifier[Env], modifier3: RModifier[Env], modifier4: RModifier[Env], modifier5: RModifier[Env], modifier6: RModifier[Env], modifier7: RModifier[Env], modifiers: RModifier[Env]*): RModifier[Env] =
    CompositeModifier[Env](js.Array(modifier, modifier2, modifier3, modifier4, modifier5, modifier6, modifier7, CompositeModifier(modifiers)))

  @inline def composite[Env](modifiers: Iterable[RModifier[Env]]): RModifier[Env] = CompositeModifier[Env](modifiers.toJSArray)

  @inline def delay[Env, T : Render[Env, ?]](modifier: => T): RModifier[Env] = AccessEnvModifier[Env](env => RModifier(modifier).provide(env))

  @inline def access[Env](modifier: Env => Modifier): RModifier[Env] = AccessEnvModifier(modifier)
  @inline def accessM[Env] = new PartiallyAppliedAccessM[Env]
  @inline class PartiallyAppliedAccessM[Env] {
    @inline def apply[R](modifier: Env => RModifier[R]): RModifier[Env with R] = access(env => modifier(env).provide(env))
  }

  implicit object monoidk extends MonoidK[RModifier] {
    @inline def empty[Env]: RModifier[Env] = RModifier.empty
    @inline def combineK[Env](x: RModifier[Env], y: RModifier[Env]): RModifier[Env] = RModifier[Env](x, y)
  }

  @inline implicit def monoid[Env]: Monoid[RModifier[Env]] = new ModifierMonoid[Env]
  @inline class ModifierMonoid[Env] extends Monoid[RModifier[Env]] {
    @inline def empty: RModifier[Env] = RModifier.empty
    @inline def combine(x: RModifier[Env], y: RModifier[Env]): RModifier[Env] = RModifier[Env](x, y)
    // @inline override def combineAll(x: Iterable[RModifier[Env]]): RModifier[Env] = RModifier.composite[Env](x)
  }

  implicit object contravariant extends Contravariant[RModifier] {
    def contramap[A, B](fa: RModifier[A])(f: B => A): RModifier[B] = fa.provideSome(f)
  }

  @inline implicit def subscriptionOwner[Env]: SubscriptionOwner[RModifier[Env]] = new ModifierSubscriptionOwner[Env]
  @inline class ModifierSubscriptionOwner[Env] extends SubscriptionOwner[RModifier[Env]] {
    @inline def own(owner: RModifier[Env])(subscription: () => Cancelable): RModifier[Env] = RModifier(owner, Modifier.managedFunction(subscription))
  }

  @inline implicit def renderToModifier[Env, T : Render[Env, ?]](value: T): RModifier[Env] = Render[Env, T].render(value)
}

object Modifier extends RModifierOps {
  @inline def apply[T : Render[Any, ?]](t: T): Modifier = Render[Any, T].render(t)

  @inline def apply(modifier: Modifier, modifier2: Modifier): Modifier =
    RModifier(modifier, modifier2)

  @inline def apply(modifier: Modifier, modifier2: Modifier, modifier3: Modifier): Modifier =
    RModifier(modifier, modifier2, modifier3)

  @inline def apply(modifier: Modifier, modifier2: Modifier, modifier3: Modifier, modifier4: Modifier): Modifier =
    RModifier(modifier, modifier2, modifier3, modifier4)

  @inline def apply(modifier: Modifier, modifier2: Modifier, modifier3: Modifier, modifier4: Modifier, modifier5: Modifier): Modifier =
    RModifier(modifier, modifier2, modifier3, modifier4, modifier5)

  @inline def apply(modifier: Modifier, modifier2: Modifier, modifier3: Modifier, modifier4: Modifier, modifier5: Modifier, modifier6: Modifier): Modifier =
    RModifier(modifier, modifier2, modifier3, modifier4, modifier5, modifier6)

  @inline def apply(modifier: Modifier, modifier2: Modifier, modifier3: Modifier, modifier4: Modifier, modifier5: Modifier, modifier6: Modifier, modifier7: Modifier, modifiers: Modifier*): Modifier =
    RModifier(modifier, modifier2, modifier3, modifier4, modifier5, modifier6, modifier7, modifiers: _*)

  @inline def composite(modifiers: Iterable[Modifier]): Modifier = RModifier.composite(modifiers)

  @inline def delay[T : Render[Any, ?]](modifier: => T): Modifier = RModifier.delay(modifier)
}

sealed trait DefaultModifier[-Env] extends RModifier[Env] {
  type Self[-R] = RModifier[R]

  final def append[R](args: RModifier[R]*): RModifier[Env with R] = RModifier(this, RModifier.composite(args))
  final def prepend[R](args: RModifier[R]*): RModifier[Env with R] = RModifier(RModifier.composite(args), this)

  final def provide(env: Env): Modifier = ProvidedEnvModifier(this, env)
  final def provideSome[R](map: R => Env): RModifier[R] = AccessEnvModifier[R](env => provide(map(env)))
}

sealed trait StaticModifier extends DefaultModifier[Any]

final case class VNodeProxyNode(proxy: VNodeProxy) extends StaticModifier

final case class Key(value: Key.Value) extends StaticModifier
object Key {
  type Value = DataObject.KeyValue
}

final case class Emitter(eventType: String, trigger: js.Function1[dom.Event, Unit]) extends StaticModifier

sealed trait Attr extends StaticModifier
object Attr {
  type Value = DataObject.AttrValue
}
final case class BasicAttr(title: String, value: Attr.Value) extends Attr
final case class AccumAttr(title: String, value: Attr.Value, accum: (Attr.Value, Attr.Value)=> Attr.Value) extends Attr

final case class Prop(title: String, value: Prop.Value) extends StaticModifier
object Prop {
  type Value = DataObject.PropValue
}

sealed trait Style extends StaticModifier
final case class AccumStyle(title: String, value: String, accum: (String, String) => String) extends Style
final case class BasicStyle(title: String, value: String) extends Style
final case class DelayedStyle(title: String, value: String) extends Style
final case class RemoveStyle(title: String, value: String) extends Style
final case class DestroyStyle(title: String, value: String) extends Style

sealed trait SnabbdomHook extends StaticModifier
final case class InitHook(trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook
final case class InsertHook(trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook
final case class PrePatchHook(trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final case class UpdateHook(trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final case class PostPatchHook(trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final case class DestroyHook(trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook

sealed trait DomHook extends StaticModifier
final case class DomMountHook(trigger: js.Function1[VNodeProxy, Unit]) extends DomHook
final case class DomUnmountHook(trigger: js.Function1[VNodeProxy, Unit]) extends DomHook
final case class DomUpdateHook(trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends DomHook
final case class DomPreUpdateHook(trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends DomHook

final case class NextModifier(modifier: StaticModifier) extends StaticModifier

case object EmptyModifier extends DefaultModifier[Any]
final case class CancelableModifier(subscription: () => Cancelable) extends DefaultModifier[Any]
final case class StringVNode(text: String) extends DefaultModifier[Any]
final case class ProvidedEnvModifier[Env](modifier: RModifier[Env], env: Env) extends DefaultModifier[Any]
final case class AccessEnvModifier[-Env](modifier: Env => Modifier) extends DefaultModifier[Env]
final case class CompositeModifier[-Env](modifiers: Iterable[RModifier[Env]]) extends DefaultModifier[Env]
final case class StreamModifier[-Env](subscription: Observer[RModifier[Env]] => Cancelable) extends DefaultModifier[Env]

sealed trait RVNode[-Env] extends RModifier[Env] {
  type Self[-R] <: RVNode[R]

  def provide(env: Env): Self[Any]
  def provideSome[R](map: R => Env): Self[R]

  def append[R](args: RModifier[R]*): Self[Env with R]
  def prepend[R](args: RModifier[R]*): Self[Env with R]

  @inline final def apply[R](args: RModifier[R]*): Self[Env with R] = append[R](args: _*)
}
sealed trait RVNodeOps {
  @inline final def html(name: String): HtmlVNode = RBasicVNodeNS(name, js.Array[Modifier](), VNodeNamespace.Html)
  @inline final def svg(name: String): SvgVNode = RBasicVNodeNS(name, js.Array[Modifier](), VNodeNamespace.Svg)
}
object RVNode extends RVNodeOps {
  @inline def access[Env](node: Env => VNode): RVNode[Env] = new AccessEnvVNode(node)
  @inline def accessM[Env] = new PartiallyAppliedAccessM[Env]
  @inline class PartiallyAppliedAccessM[Env] {
    @inline def apply[R](node: Env => RVNode[R]): RVNode[Env with R] = access(env => node(env).provide(env))
  }

  @inline implicit def subscriptionOwner[Env]: SubscriptionOwner[RVNode[Env]] = new VNodeSubscriptionOwner[Env]
  @inline class VNodeSubscriptionOwner[Env] extends SubscriptionOwner[RVNode[Env]] {
    @inline def own(owner: RVNode[Env])(subscription: () => Cancelable): RVNode[Env] = owner.append(Modifier.managedFunction(subscription))
  }
}
object VNode extends RVNodeOps

@inline final case class AccessEnvVNode[-Env](node: Env => VNode) extends RVNode[Env] {
  type Self[-R] = AccessEnvVNode[R]

  def provide(env: Env): Self[Any] = copy(node = _ => node(env))
  def provideSome[R](map: R => Env): Self[R] = copy(node = r => node(map(r)))
  def append[R](args: RModifier[R]*): Self[Env with R] = copy(node = env => node(env).append(RModifier.composite(args).provide(env)))
  def prepend[R](args: RModifier[R]*): Self[Env with R] = copy(node = env => node(env).prepend(RModifier.composite(args).provide(env)))
}

@inline final case class RThunkVNode[-Env](baseNode: RBasicVNode[Env], key: Key.Value, condition: VNodeThunkCondition, renderFn: () => RModifier[Env]) extends RVNode[Env] {
  type Self[-R] = RThunkVNode[R]

  def provide(env: Env): Self[Any] = copy(baseNode = baseNode.provide(env), renderFn = () => renderFn().provide(env))
  def provideSome[R](map: R => Env): Self[R] = copy(baseNode = baseNode.provideSome(map), renderFn = () => renderFn().provideSome(map))
  def append[R](args: RModifier[R]*): Self[Env with R] = copy(baseNode = baseNode.append(args: _*))
  def prepend[R](args: RModifier[R]*): Self[Env with R] = copy(baseNode = baseNode.prepend(args: _*))
}

@inline final case class RBasicVNodeNS[+N <: VNodeNamespace, -Env](nodeType: String, modifiers: js.Array[_ <: RModifier[Env]], namespace: N) extends RVNode[Env] {
  type Self[-R] = RBasicVNode[R]

  def provide(env: Env): Self[Any] = copy(modifiers = js.Array(CompositeModifier(modifiers).provide(env)))
  def provideSome[R](map: R => Env): Self[R] = copy(modifiers = js.Array(CompositeModifier(modifiers).provideSome(map)))
  def append[R](args: RModifier[R]*): Self[Env with R] = copy(modifiers = appendSeq(modifiers, args))
  def prepend[R](args: RModifier[R]*): Self[Env with R] = copy(modifiers = prependSeq(modifiers, args))
}
object RBasicVNodeNS {
  @inline implicit class RBasicVNodeOps[Env](val self: RBasicVNodeNS[VNodeNamespace, Env]) extends AnyVal {
    @inline def thunk(key: Key.Value)(arguments: Any*)(renderFn: => RModifier[Env]): RThunkVNode[Env] = RThunkVNode(self, key, VNodeThunkCondition.Compare(arguments.toJSArray), () => renderFn)
    @inline def thunkConditional(key: Key.Value)(shouldRender: Boolean)(renderFn: => RModifier[Env]): RThunkVNode[Env] = RThunkVNode(self, key, VNodeThunkCondition.Check(shouldRender), () => renderFn)
    @inline def thunkStatic(key: Key.Value)(renderFn: => RModifier[Env]): RThunkVNode[Env] = thunkConditional(key)(false)(renderFn)
  }
}

sealed trait VNodeNamespace
object VNodeNamespace {
  case object Html extends VNodeNamespace
  case object Svg extends VNodeNamespace
}
sealed trait VNodeThunkCondition
object VNodeThunkCondition {
  case class Check(shouldRender: Boolean) extends VNodeThunkCondition
  case class Compare(arguments: js.Array[Any]) extends VNodeThunkCondition
}
