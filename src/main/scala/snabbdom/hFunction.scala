package snabbdom

import org.scalajs.dom._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.{UndefOr, |}

@js.native
@JSImport("snabbdom/h", JSImport.Namespace, globalFallback = "h")
object hProvider extends js.Object {
  val default: hFunction = js.native
}

@js.native
trait hFunction extends js.Any {
  def apply(nodeType: String, dataObject: DataObject): VNodeProxy = js.native
  def apply(nodeType: String, dataObject: DataObject, text: js.UndefOr[String]): VNodeProxy = js.native
  def apply(nodeType: String, dataObject: DataObject, children: js.Array[VNodeProxy]): VNodeProxy = js.native
}

object hFunction {
  def apply(nodeType: String, dataObject: DataObject): VNodeProxy = {
    hProvider.default.apply(nodeType, dataObject)
  }
  def apply(nodeType: String, dataObject: DataObject, text: js.UndefOr[String]): VNodeProxy = {
    hProvider.default.apply(nodeType, dataObject, text)
  }
  def apply(nodeType: String, dataObject: DataObject, children: js.Array[VNodeProxy]): VNodeProxy = {
    hProvider.default.apply(nodeType, dataObject, children)
  }
}


trait Hooks extends js.Object {
  val insert: js.UndefOr[Hooks.HookSingleFn]
  val prepatch: js.UndefOr[Hooks.HookPairFn]
  val update: js.UndefOr[Hooks.HookPairFn]
  val postpatch: js.UndefOr[Hooks.HookPairFn]
  val destroy: js.UndefOr[Hooks.HookSingleFn]
}

object Hooks {
  type HookSingleFn = js.Function1[VNodeProxy, Unit]
  type HookPairFn = js.Function2[VNodeProxy, VNodeProxy, Unit]

  def apply(
    insert: js.UndefOr[HookSingleFn],
    prepatch: js.UndefOr[HookPairFn],
    update: js.UndefOr[HookPairFn],
    postpatch: js.UndefOr[HookPairFn],
    destroy: js.UndefOr[HookSingleFn]
  ): Hooks = {
    val _insert = insert
    val _prepatch = prepatch
    val _update = update
    val _postpatch = postpatch
    val _destroy = destroy
    new Hooks {
      val insert = _insert
      val prepatch = _prepatch
      val update = _update
      val postpatch = _postpatch
      val destroy = _destroy
    }
  }
}

trait DataObject extends js.Object {
  import DataObject._

  val attrs: js.UndefOr[js.Dictionary[AttrValue]]
  val props: js.UndefOr[js.Dictionary[PropValue]]
  val style: js.UndefOr[js.Dictionary[StyleValue]]
  val on: js.UndefOr[js.Dictionary[js.Function1[Event, Unit]]]
  val hook: js.UndefOr[Hooks]
  val key: js.UndefOr[KeyValue]
}

object DataObject {

  type PropValue = Any
  type AttrValue = String | Boolean
  type StyleValue = String | js.Dictionary[String]
  type KeyValue = String | Double  // https://github.com/snabbdom/snabbdom#key--string--number

  def empty: DataObject = apply(js.undefined, js.undefined, js.undefined, js.undefined, js.undefined, js.undefined)

  def apply(attrs: js.UndefOr[js.Dictionary[AttrValue]],
            props: js.UndefOr[js.Dictionary[PropValue]],
            style: js.UndefOr[js.Dictionary[StyleValue]],
            on: js.UndefOr[js.Dictionary[js.Function1[Event, Unit]]],
            hook: js.UndefOr[Hooks],
            key: js.UndefOr[KeyValue]
           ): DataObject = {

    val _attrs = attrs
    val _props = props
    val _style = style
    val _on = on
    val _hook = hook
    val _key = key

    new DataObject {
      val attrs: js.UndefOr[js.Dictionary[AttrValue]] = _attrs
      val props: js.UndefOr[js.Dictionary[PropValue]] = _props
      val style: js.UndefOr[js.Dictionary[StyleValue]] = _style
      val on: js.UndefOr[js.Dictionary[js.Function1[Event, Unit]]] = _on
      val hook: js.UndefOr[Hooks] = _hook
      val key: js.UndefOr[KeyValue] = _key
    }
  }
}

object patch {

  private lazy val p = Snabbdom.init(js.Array(
    SnabbdomClass.default,
    SnabbdomEventListeners.default,
    SnabbdomAttributes.default,
    SnabbdomCustomProps.default,
    SnabbdomStyle.default
  ))

  def apply(firstNode: VNodeProxy, vNode: VNodeProxy): VNodeProxy = p(firstNode,vNode)

  def apply(firstNode: org.scalajs.dom.Element, vNode: VNodeProxy): VNodeProxy = p(firstNode,vNode)
}

case class OutwatchState(id: Int, domUnmountHook: js.UndefOr[Hooks.HookSingleFn])

@js.native
trait VNodeProxy extends js.Object {
  var sel: js.UndefOr[String]
  var data: js.UndefOr[DataObject]
  var children: js.UndefOr[js.Array[VNodeProxy]]
  var elm: js.UndefOr[Element]
  var text: js.UndefOr[String]
  var key: js.UndefOr[DataObject.KeyValue]

  var outwatchState: js.UndefOr[OutwatchState]
}

object VNodeProxy {
  def fromString(string: String): VNodeProxy = js.Dynamic.literal(text = string).asInstanceOf[VNodeProxy]

  def fromElement(element: Element): VNodeProxy = js.Dynamic.literal(
    sel = element.tagName.toLowerCase,
    elm = element,
    text = "",
    data = DataObject.empty
  ).asInstanceOf[VNodeProxy]
}


@js.native
@JSImport("snabbdom", JSImport.Namespace, globalFallback = "snabbdom")
object Snabbdom extends js.Object {
  def init(args: js.Array[Any]): js.Function2[Node | VNodeProxy, VNodeProxy, VNodeProxy] = js.native

}

@js.native
@JSImport("snabbdom/modules/class", JSImport.Namespace, globalFallback = "snabbdom_class")
object SnabbdomClass extends js.Object {
  val default: js.Any = js.native
}

@js.native
@JSImport("snabbdom/modules/eventlisteners", JSImport.Namespace, globalFallback = "snabbdom_eventlisteners")
object SnabbdomEventListeners extends js.Object{
  val default: js.Any = js.native
}

@js.native
@JSImport("snabbdom/modules/attributes", JSImport.Namespace, globalFallback = "snabbdom_attributes")
object SnabbdomAttributes extends js.Object{
  val default: js.Any = js.native
}

// forked snabbdom-props module where the ground-thruth is the dom
@js.native
@JSImport("./snabbdom-custom-props", JSImport.Namespace)
object SnabbdomCustomProps extends js.Object {
  val default: js.Any = js.native
}

// original snabbdom-props
// @js.native
// @JSImport("snabbdom/modules/props", JSImport.Namespace, globalFallback = "snabbdom_props")
// object SnabbdomProps extends js.Object{
//   val default: js.Any = js.native
// }


@js.native
@JSImport("snabbdom/modules/style", JSImport.Namespace, globalFallback = "snabbdom_style")
object SnabbdomStyle extends js.Object {
  val default: js.Any = js.native
}
