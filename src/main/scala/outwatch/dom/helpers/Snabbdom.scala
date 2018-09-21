package outwatch.dom.helpers

import monix.execution.Ack.Continue
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import outwatch.dom._
import snabbdom._

import scala.scalajs.js

object OutwatchTracing {
  private[outwatch] val patchSubject = PublishSubject[(VNodeProxy, VNodeProxy)]()
  def patch: Observable[(VNodeProxy, VNodeProxy)] = patchSubject
}

private[outwatch] object SnabbdomModifiers {
  private def toOutwatchState(hooks: SeparatedHooks, vNodeId: Int): js.UndefOr[OutwatchState] = {
    import hooks._

    if (usesOutwatchState || domUnmountHook.nonEmpty) OutwatchState(vNodeId, domUnmountHook)
    else js.undefined
  }

  private def createDataObject(modifiers: SeparatedModifiers): DataObject = {
    import modifiers._

    DataObject(
      attributes.attrs, attributes.props, attributes.styles, emitters,
      Hooks(hooks.insertHook, hooks.prePatchHook, hooks.updateHook, hooks.postPatchHook, hooks.destroyHook),
      keyOption
    )
  }

  // This is called initially once the VNode is constructed. Every time a
  // dynamic modifier of this node yields a new VNodeState, this new state with
  // new modifiers and attributes needs to be applied to the current
  // VNodeProxy.
  private def createProxy(nodeType: String, state: js.UndefOr[OutwatchState], dataObject: DataObject, children: js.UndefOr[js.Array[VNodeProxy]])(implicit scheduler: Scheduler): VNodeProxy = {
    val proxy = if (children.isEmpty) {
      hFunction(nodeType, dataObject)
    } else {
      hFunction(nodeType, dataObject, children.get)
    }

    proxy.outwatchState = state
    proxy
  }

  private def updateSnabbdom(modifiersArray: js.Array[VDomModifier], nodeType: String, vNodeId: Int, initialModifiers: SeparatedModifiers)(implicit scheduler: Scheduler): VNodeProxy = {

    val newModifiers = SeparatedModifiers.fromWithoutChildren(initialModifiers)
    modifiersArray.foreach(newModifiers.append)

    // Updates never contain streamable content and therefore we do not need to
    // handle them with Receivers.

    val dataObject = createDataObject(newModifiers)
    val state = toOutwatchState(newModifiers.hooks, vNodeId)

    createProxy(nodeType, state, dataObject, newModifiers.children.proxies)
  }

  private[outwatch] def toSnabbdom(modifiers: SeparatedModifiers, nodeType: String)(implicit scheduler: Scheduler): VNodeProxy = {
    import modifiers._

    val vNodeId = modifiers.hashCode
    modifiers.appendUnmountHook()

    // if there is streamable content, we update the initial proxy with
    // subscribe and unsubscribe callbakcs.  additionally we update it with the
    // initial state of the obseravbles.
    if (children.hasStream) {

      val receivers = new Receivers(children.nodes.get)

      // needs var for forward referencing
      var proxy: VNodeProxy = null

      def subscribe(): Cancelable = {
        receivers.observable.subscribe(
          { newState =>
            // update the current proxy with the new state
            val newProxy = updateSnabbdom(newState, nodeType, vNodeId, modifiers)

            // call the snabbdom patch method and get the resulting proxy
            OutwatchTracing.patchSubject.onNext((proxy, newProxy))
            val next = patch(proxy, newProxy)

            // we are mutating the initial proxy, because parents of this node have a reference to this proxy.
            // if we are changing the content of this proxy via a stream, the parent will not see this change.
            // if now the parent is rerendered because a sibiling of the parent triggers an update, the parent
            // renders its children again. But it would not have the correct state of this proxy. Therefore,
            // we mutate the initial proxy and thereby mutate the proxy the parent knows.
            proxy.sel = next.sel
            proxy.data = next.data
            proxy.children = next.children
            proxy.elm = next.elm
            proxy.text = next.text
            proxy.key = next.key
            proxy.outwatchState = next.outwatchState

            Continue
          },
          error => dom.console.error(error.getMessage + "\n" + error.getStackTrace.mkString("\n"))
        )
      }

      // hooks for subscribing and unsubscribing the streamable content
      val cancelable = new QueuedCancelable()
      modifiers.append(DomMountHook(_ => cancelable.enqueue(subscribe())))
      modifiers.append(DomUnmountHook(_ => cancelable.dequeue().cancel()))

      // create initial proxy, we want to apply the initial state of the
      // receivers to the node
      proxy = updateSnabbdom(receivers.initialState, nodeType, vNodeId, modifiers)
      proxy
    } else {
      val state = toOutwatchState(hooks, vNodeId)
      val dataObject = createDataObject(modifiers)
      createProxy(nodeType, state, dataObject, children.proxies)
    }
  }

  private[outwatch] def toSnabbdom(vNode: VNode)(implicit scheduler: Scheduler): VNodeProxy = toSnabbdom(SeparatedModifiers.from(vNode.modifiers), vNode.nodeType)
}
