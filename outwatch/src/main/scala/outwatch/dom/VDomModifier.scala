package outwatch.dom

import cats.Monoid
import org.scalajs.dom._
import outwatch.dom.helpers.NativeHelpers._
import outwatch.effect.RunAsyncResult
import outwatch.reactive.{SinkObserver, Subscription, SubscriptionOwner}
import snabbdom.{DataObject, VNodeProxy}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

sealed trait VDomModifier

object VDomModifier {
  @inline def empty: VDomModifier = EmptyModifier

  @inline def apply(): VDomModifier = empty

  @inline def apply[T : Render](t: T): VDomModifier = Render[T].render(t)

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2))

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier, modifier3: VDomModifier): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2, modifier3))

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier, modifier3: VDomModifier, modifier4: VDomModifier): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2, modifier3, modifier4))

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier, modifier3: VDomModifier, modifier4: VDomModifier, modifier5: VDomModifier): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2, modifier3, modifier4, modifier5))

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier, modifier3: VDomModifier, modifier4: VDomModifier, modifier5: VDomModifier, modifier6: VDomModifier): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2, modifier3, modifier4, modifier5, modifier6))

  @inline def apply(modifier: VDomModifier, modifier2: VDomModifier, modifier3: VDomModifier, modifier4: VDomModifier, modifier5: VDomModifier, modifier6: VDomModifier, modifier7: VDomModifier, modifiers: VDomModifier*): VDomModifier =
    new CompositeModifier(js.Array(modifier, modifier2, modifier3, modifier4, modifier5, modifier6, modifier7, new CompositeModifier(modifiers)))

  @inline def delay[T : Render](modifier: => T): VDomModifier = new SyncEffectModifier(() => VDomModifier(modifier))

  implicit object monoid extends Monoid[VDomModifier] {
    @inline def empty: VDomModifier = VDomModifier.empty
    @inline def combine(x: VDomModifier, y: VDomModifier): VDomModifier = VDomModifier(x, y)
  }

  implicit object subscriptionOwner extends SubscriptionOwner[VDomModifier] {
    @inline def own(owner: VDomModifier)(subscription: () => Subscription): VDomModifier = VDomModifier(managedFunction(subscription), owner)
  }

  @inline implicit def renderToVDomModifier[T : Render](value: T): VDomModifier = Render[T].render(value)
}

sealed trait StaticVDomModifier extends VDomModifier

final class VNodeProxyNode(val proxy: VNodeProxy) extends StaticVDomModifier

final class Key(val value: Key.Value) extends StaticVDomModifier
object Key {
  type Value = DataObject.KeyValue
}

final class Emitter(val eventType: String, val trigger: js.Function1[Event, Unit]) extends StaticVDomModifier

sealed trait Attr extends StaticVDomModifier
object Attr {
  type Value = DataObject.AttrValue
}
final class BasicAttr(val title: String, val value: Attr.Value) extends Attr
final class AccumAttr(val title: String, val value: Attr.Value, val accum: (Attr.Value, Attr.Value)=> Attr.Value) extends Attr

final class Prop(val title: String, val value: Prop.Value) extends StaticVDomModifier
object Prop {
  type Value = DataObject.PropValue
}

sealed trait Style extends StaticVDomModifier
final class AccumStyle(val title: String, val value: String, val accum: (String, String) => String) extends Style
final class BasicStyle(val title: String, val value: String) extends Style
final class DelayedStyle(val title: String, val value: String) extends Style
final class RemoveStyle(val title: String, val value: String) extends Style
final class DestroyStyle(val title: String, val value: String) extends Style

sealed trait SnabbdomHook extends StaticVDomModifier
final class InitHook(val trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook
final class InsertHook(val trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook
final class OldPrePatchHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final class PrePatchHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final class UpdateHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final class OldPostPatchHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final class PostPatchHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends SnabbdomHook
final class DestroyHook(val trigger: js.Function1[VNodeProxy, Unit]) extends SnabbdomHook

sealed trait DomHook extends SnabbdomHook
final class DomMountHook(val trigger: js.Function1[VNodeProxy, Unit]) extends DomHook
final class DomUnmountHook(val trigger: js.Function1[VNodeProxy, Unit]) extends DomHook
final class DomUpdateHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends DomHook
final class DomPreUpdateHook(val trigger: js.Function2[VNodeProxy, VNodeProxy, Unit]) extends DomHook

final class StaticCompositeModifier(val modifiers: js.Array[StaticVDomModifier]) extends StaticVDomModifier

object EmptyModifier extends VDomModifier
final class CompositeModifier(val modifiers: Iterable[VDomModifier]) extends VDomModifier
final class StreamModifier(val subscription: SinkObserver[VDomModifier] => Subscription) extends VDomModifier
final class SubscriptionModifier(val subscription: () => Subscription) extends VDomModifier
final class EffectModifier(val unsafeRun: () => RunAsyncResult[VDomModifier]) extends VDomModifier
final class SyncEffectModifier(val unsafeRun: () => VDomModifier) extends VDomModifier
final class StringVNode(val text: String) extends VDomModifier

sealed trait VNode extends VDomModifier {
  def apply(args: VDomModifier*): VNode
  def append(args: VDomModifier*): VNode
  def prepend(args: VDomModifier*): VNode
}
object VNode {
  implicit object subscriptionOwner extends SubscriptionOwner[VNode] {
    @inline def own(owner: VNode)(subscription: () => Subscription): VNode = owner.append(managedFunction(subscription))
  }
}
sealed trait BasicVNode extends VNode {
  def nodeType: String
  def modifiers: js.Array[VDomModifier]
  def apply(args: VDomModifier*): BasicVNode
  def append(args: VDomModifier*): BasicVNode
  def prepend(args: VDomModifier*): BasicVNode
  def thunk(key: Key.Value)(arguments: Any*)(renderFn: => VDomModifier): ThunkVNode = new ThunkVNode(this, key, arguments.toJSArray, () => renderFn)
  def thunkConditional(key: Key.Value)(shouldRender: Boolean)(renderFn: => VDomModifier): ConditionalVNode = new ConditionalVNode(this, key, shouldRender, () => renderFn)
  @inline def thunkStatic(key: Key.Value)(renderFn: => VDomModifier): ConditionalVNode = thunkConditional(key)(false)(renderFn)
}
@inline final class ThunkVNode(val baseNode: BasicVNode, val key: Key.Value, val arguments: js.Array[Any], val renderFn: () => VDomModifier) extends VNode {
  @inline def apply(args: VDomModifier*): ThunkVNode = append(args: _*)
  def append(args: VDomModifier*): ThunkVNode = new ThunkVNode(baseNode = baseNode(args: _*), key = key, arguments = arguments, renderFn = renderFn)
  def prepend(args: VDomModifier*): ThunkVNode = new ThunkVNode(baseNode = baseNode.prepend(args :_*), key = key, arguments = arguments, renderFn = renderFn)
}
@inline final class ConditionalVNode(val baseNode: BasicVNode, val key: Key.Value, val shouldRender: Boolean, val renderFn: () => VDomModifier) extends VNode {
  @inline def apply(args: VDomModifier*): ConditionalVNode = append(args: _*)
  def append(args: VDomModifier*): ConditionalVNode = new ConditionalVNode(baseNode = baseNode(args: _*), key = key, shouldRender = shouldRender, renderFn = renderFn)
  def prepend(args: VDomModifier*): ConditionalVNode = new ConditionalVNode(baseNode = baseNode.prepend(args: _*), key = key, shouldRender = shouldRender, renderFn = renderFn)
}
@inline final class HtmlVNode(val nodeType: String, val modifiers: js.Array[VDomModifier]) extends BasicVNode {
  @inline def apply(args: VDomModifier*): HtmlVNode = append(args: _*)
  def append(args: VDomModifier*): HtmlVNode = new HtmlVNode(nodeType = nodeType, modifiers = appendSeq(modifiers, args))
  def prepend(args: VDomModifier*): HtmlVNode = new HtmlVNode(nodeType = nodeType, modifiers = prependSeq(modifiers, args))
}
@inline final class SvgVNode(val nodeType: String, val modifiers: js.Array[VDomModifier]) extends BasicVNode {
  @inline def apply(args: VDomModifier*): SvgVNode = append(args: _*)
  def append(args: VDomModifier*): SvgVNode = new SvgVNode(nodeType = nodeType, modifiers = appendSeq(modifiers, args))
  def prepend(args: VDomModifier*): SvgVNode = new SvgVNode(nodeType = nodeType, modifiers = prependSeq(modifiers, args))
}
