package outwatch.dom.interpreter

import outwatch.dom._
import outwatch.dom.helpers._
import outwatch.reactive._
import snabbdom._

import scala.scalajs.js
import scala.annotation.tailrec

private[outwatch] object SnabbdomOps {
  // currently async patching is disabled, because it yields flickering render results.
  // We would like to evaluate whether async patching can make sense and whether some kind
  // sync/async batching like monix-scheduler makes sense for us.
  var asyncPatchEnabled = false

  @inline private def createDataObject(modifiers: SeparatedModifiers, vNodeNS: js.UndefOr[String]): DataObject =
    new DataObject {
      attrs = modifiers.attrs
      props = modifiers.props
      style = modifiers.styles
      on = modifiers.emitters
      hook = new Hooks {
        init = modifiers.initHook
        insert = modifiers.insertHook
        prepatch = modifiers.prePatchHook
        oldprepatch = modifiers.oldPrePatchHook
        update = modifiers.updateHook
        postpatch = modifiers.postPatchHook
        oldpostpatch = modifiers.oldPostPatchHook
        destroy = modifiers.destroyHook
      }
      key = modifiers.keyOption
      ns = vNodeNS
    }

  private def createProxy(modifiers: SeparatedModifiers, nodeType: String, vNodeId: js.UndefOr[Int], vNodeNS: js.UndefOr[String]): VNodeProxy = {
    val dataObject = createDataObject(modifiers, vNodeNS)

    @inline def newProxy(childProxies: js.UndefOr[js.Array[VNodeProxy]], string: js.UndefOr[String]) = new VNodeProxy {
      sel = nodeType
      data = dataObject
      children = childProxies
      text = string
      key = modifiers.keyOption
      _id = vNodeId
    }

    if (modifiers.hasOnlyTextChildren) {
      modifiers.proxies.fold(newProxy(js.undefined, js.undefined)) { proxies =>
        newProxy(js.undefined, proxies.foldLeft("")(_ + _.text))
      }
    } else newProxy(modifiers.proxies, js.undefined)
  }

   def getNamespace(node: BasicVNode): js.UndefOr[String] = node match {
    case _: SvgVNode => "http://www.w3.org/2000/svg": js.UndefOr[String]
    case _ => js.undefined
  }

   def toSnabbdom(node: VNode): VNodeProxy = node match {
     case node: BasicVNode =>
       toRawSnabbdomProxy(node)
     case node: ConditionalVNode =>
       thunk.conditional(getNamespace(node.baseNode), node.baseNode.nodeType, node.key, () => toRawSnabbdomProxy(node.baseNode(node.renderFn(), new Key(node.key))), node.shouldRender)
     case node: ThunkVNode =>
       thunk(getNamespace(node.baseNode), node.baseNode.nodeType, node.key, () => toRawSnabbdomProxy(node.baseNode(node.renderFn(), new Key(node.key))), node.arguments)
   }

   private val newNodeId: () => Int = {
     var vNodeIdCounter = 0
     () => {
      vNodeIdCounter += 1
      vNodeIdCounter
     }
   }

   type SetImmediate = js.Function1[js.Function0[Unit], Int]
   type ClearImmediate = js.Function1[Int, Unit]
   private val (setImmediateRef, clearImmediateRef): (SetImmediate, ClearImmediate) = {
      if (!js.isUndefined(js.Dynamic.global.setImmediate))
        (js.Dynamic.global.setImmediate.bind(js.Dynamic.global).asInstanceOf[SetImmediate], js.Dynamic.global.clearImmediate.bind(js.Dynamic.global).asInstanceOf[ClearImmediate])
      else
        (js.Dynamic.global.setTimeout.bind(js.Dynamic.global).asInstanceOf[SetImmediate], js.Dynamic.global.clearTimeout.bind(js.Dynamic.global).asInstanceOf[ClearImmediate])
    }

    // we are mutating the initial proxy with VNodeProxy.copyInto, because parents of this node have a reference to this proxy.
    // if we are changing the content of this proxy via a stream, the parent will not see this change.
    // if now the parent is rerendered because a sibiling of the parent triggers an update, the parent
    // renders its children again. But it would not have the correct state of this proxy. Therefore,
    // we mutate the initial proxy and thereby mutate the proxy the parent knows.
   private def toRawSnabbdomProxy(node: BasicVNode): VNodeProxy = {

    val vNodeNS = getNamespace(node)
    val vNodeId: Int = newNodeId()

    var patchFun: () => Unit = () => ()
    val patchSink = SinkObserver.create[Unit](_ => patchFun(), OutwatchTracing.errorSubject.onNext)

    val nativeModifiers = NativeModifiers.from(node.modifiers, patchSink)

    if (nativeModifiers.subscribables.isEmpty) {
      // if no dynamic/subscribable content, then just create a simple proxy
      createProxy(nativeModifiers.separatedModifiers, node.nodeType, vNodeId, vNodeNS)
    } else if (nativeModifiers.separatedEndIndex != -1) {
      // if there is streamable content, we update the initial proxy with
      // in subscribe and unsubscribe callbacks. We subscribe and unsubscribe
      // based in dom events.

      var proxy: VNodeProxy = null
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isActive: Boolean = false

      var patchIsRunning = false
      var patchIsNeeded = false

      @tailrec
      def doPatch(): Unit = {
        patchIsRunning = true
        patchIsNeeded = false

        // update the current proxy with the new state
        val separatedModifiers = SeparatedModifiers.from(nativeModifiers.modifiers)
        addLifetimeHooks(separatedModifiers)
        val newProxy = createProxy(separatedModifiers, node.nodeType, vNodeId, vNodeNS)
        newProxy._update = proxy._update
        newProxy._args = proxy._args

        // call the snabbdom patch method to update the dom
        OutwatchTracing.patchSubject.onNext(newProxy)
        patch(proxy, newProxy)

        patchIsRunning = false
        if (patchIsNeeded) doPatch()
      }

      def resetTimeout(): Unit = {
        lastTimeout.foreach(clearImmediateRef)
        lastTimeout = js.undefined
      }

      def asyncDoPatch(): Unit = {
        resetTimeout()
        lastTimeout = setImmediateRef(() => doPatch())
      }

      def invokeDoPatch(async: Boolean): Unit = if (isActive) {
        if (patchIsRunning) {
          patchIsNeeded = true
        } else {
          if (async) asyncDoPatch()
          else doPatch()
        }
      }

      patchFun = () => invokeDoPatch(async = asyncPatchEnabled)

      def start(): Unit = {
        resetTimeout()
        nativeModifiers.subscribables.foreach { subscribable =>
          subscribable.subscribe()
        }
      }

      def stop(): Unit = {
        resetTimeout()
        nativeModifiers.subscribables.foreach(_.unsubscribe())
      }

      def addLifetimeHooks(separatedModifiers: SeparatedModifiers): Unit = {
        separatedModifiers.insertHook = separatedModifiers.prependHooksSingle[VNodeProxy](separatedModifiers.insertHook, { p =>
          VNodeProxy.copyInto(p, proxy)
          isActive = true
          start()
        })
        separatedModifiers.postPatchHook = separatedModifiers.prependHooksPair[VNodeProxy](separatedModifiers.postPatchHook, { (o, p) =>
          VNodeProxy.copyInto(p, proxy)
          proxy._update.foreach(_(proxy))
          if (!NativeModifiers.equalsVNodeIds(o._id, p._id)) {
            isActive = true
            start()
          } else if (isActive) {
            start()
          } else {
            stop()
          }
        })
        separatedModifiers.oldPostPatchHook = separatedModifiers.prependHooksPair[VNodeProxy](separatedModifiers.oldPostPatchHook, { (o,p) =>
          if (!NativeModifiers.equalsVNodeIds(o._id, p._id)) {
            isActive = false
            stop()
          }
        })
        separatedModifiers.destroyHook = separatedModifiers.prependHooksSingle[VNodeProxy](separatedModifiers.destroyHook, { _ =>
          isActive = false
          stop()
        })
      }

      addLifetimeHooks(nativeModifiers.separatedModifiers)
      nativeModifiers.separatedModifiers.appendAllFrom(nativeModifiers.modifiers, nativeModifiers.separatedEndIndex)

      // create initial proxy, we want to apply the initial state of the
      // receivers to the node
      proxy = createProxy(nativeModifiers.separatedModifiers, node.nodeType, vNodeId, vNodeNS)

      proxy
    } else {
      // simpler version with only subscriptions, no streams.
      var isActive = false

      def start(): Unit = if (!isActive) {
        isActive = true
        nativeModifiers.subscribables.foreach { subscribable =>
          subscribable.subscribe()
        }
      }

      def stop(): Unit = if (isActive) {
        isActive = false
        nativeModifiers.subscribables.foreach(_.unsubscribe())
      }

      // hooks for subscribing and unsubscribing the sub subscribables
      def addLifetimeHooks(separatedModifiers: SeparatedModifiers): Unit = {
        separatedModifiers.insertHook = separatedModifiers.prependHooksSingle[VNodeProxy](separatedModifiers.insertHook, { _ =>
          start()
        })
        separatedModifiers.postPatchHook = separatedModifiers.prependHooksPair[VNodeProxy](separatedModifiers.postPatchHook, { (o, p) =>
          if (!NativeModifiers.equalsVNodeIds(o._id, p._id)) {
            start()
          }
        })
        separatedModifiers.oldPostPatchHook = separatedModifiers.prependHooksPair[VNodeProxy](separatedModifiers.oldPostPatchHook, { (o,p) =>
          if (!NativeModifiers.equalsVNodeIds(o._id, p._id)) {
            stop()
          }
        })
        separatedModifiers.destroyHook = separatedModifiers.prependHooksSingle[VNodeProxy](separatedModifiers.destroyHook, { _ =>
          stop()
        })
      }

      addLifetimeHooks(nativeModifiers.separatedModifiers)

      // create the proxy from the modifiers
      createProxy(nativeModifiers.separatedModifiers, node.nodeType, vNodeId, vNodeNS)
    }
  }
}
