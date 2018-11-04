package outwatch.dom.helpers

import monix.execution.Ack.Continue
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import outwatch.dom._
import snabbdom._

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

object OutwatchTracing {
  private[outwatch] val patchSubject = PublishSubject[(VNodeProxy, VNodeProxy)]()
  def patch: Observable[(VNodeProxy, VNodeProxy)] = patchSubject
}

private[outwatch] object DictionaryOps {
  def merge[T](first: js.Dictionary[T], second: js.Dictionary[T]) = {
    first.foreach { case (key, value) if second.get(key).isEmpty => second(key) = value }
    second
  }

  def mergeWithAccum[T](first: js.Dictionary[T], second: js.Dictionary[T], keys: Set[String], accum: (T,T) => T) = {
    first.foreach {
      case (key, value) if keys(key) => second.get(key) match {
        case Some(next) => second(key) = accum(value, next)
        case None => second(key) = value
      }
      case (key, value) => second(key) = value
    }
    second
  }
}

private[outwatch] object SnabbdomStyles {
  def toSnabbdom(styles: js.Array[Style]): js.Dictionary[Style.Value] = {
    val styleDict = js.Dictionary[Style.Value]()

    val delayedDict = js.Dictionary[String]()
    val removeDict = js.Dictionary[String]()
    val destroyDict = js.Dictionary[String]()

    styles.foreach {
      case s: BasicStyle => styleDict(s.title) = s.value
      case s: DelayedStyle => delayedDict(s.title) = s.value
      case s: RemoveStyle => removeDict(s.title) = s.value
      case s: DestroyStyle => destroyDict(s.title) = s.value
      case a: AccumStyle =>
        styleDict(a.title) = styleDict.get(a.title).fold[Style.Value](a.value)(s =>
          a.accum(s.asInstanceOf[String], a.value): Style.Value
        )

    }

    if (delayedDict.nonEmpty) styleDict("delayed") = delayedDict : Style.Value
    if (removeDict.nonEmpty) styleDict("remove") = removeDict : Style.Value
    if (destroyDict.nonEmpty) styleDict("destroy") = destroyDict : Style.Value

    styleDict
  }
}

private[outwatch] object SnabbdomAttributes {

  type jsDict[T] = js.Dictionary[T]

  def toSnabbdom(attributes: SeparatedAttributes): (jsDict[Attr.Value], jsDict[Prop.Value], jsDict[Style.Value]) = {
    import attributes._

    val attrsDict = js.Dictionary[Attr.Value]()
    val propsDict = js.Dictionary[Prop.Value]()

    attrs.foreach {
      case a: BasicAttr => attrsDict(a.title) = a.value
      case a: AccumAttr => attrsDict(a.title) = attrsDict.get(a.title).map(a.accum(_, a.value)).getOrElse(a.value)
    }
    props.foreach { p => propsDict(p.title) = p.value }

    (attrsDict, propsDict, SnabbdomStyles.toSnabbdom(styles))
  }
}

private[outwatch] object SnabbdomHooks {

  private def createHookSingle(hooks: js.Array[_ <: Hook[dom.Element]]): js.UndefOr[Hooks.HookSingleFn] = {
    if (hooks.isEmpty) js.undefined
    else { (p: VNodeProxy) =>
      for (e <- p.elm) hooks.foreach(_.trigger(e))
    }: Hooks.HookSingleFn
  }

  private def createHookPair(hooks: js.Array[_ <: Hook[(dom.Element, dom.Element)]]): js.UndefOr[Hooks.HookPairFn] = {
    if (hooks.isEmpty) js.undefined
    else { (old: VNodeProxy, cur: VNodeProxy) =>
      for (o <- old.elm; c <- cur.elm) hooks.foreach(_.trigger((o, c)))
    }: Hooks.HookPairFn
  }

  private def createHookPairOption(hooks: js.Array[_ <: Hook[(Option[dom.Element], Option[dom.Element])]]): js.UndefOr[Hooks.HookPairFn] = {
    if (hooks.isEmpty) js.undefined
    else { (old: VNodeProxy, cur: VNodeProxy) =>
      hooks.foreach(_.trigger((old.elm.toOption, cur.elm.toOption)))
    }: Hooks.HookPairFn
  }

  private def createPostPatchHookWithUnmount(hooks: SeparatedHooks)(implicit s: Scheduler): Hooks.HookPairFn = {
    import hooks._

    { (oldProxy: VNodeProxy, proxy: VNodeProxy) =>
      if (proxy.outwatchState.map(_.id) != oldProxy.outwatchState.map(_.id)) {
        oldProxy.outwatchState.foreach(_.domUnmountHooks.foreach(_ (oldProxy)))
        proxy.elm.foreach(elm => domMountHooks.foreach(_.trigger(elm)))
      } else {
        proxy.elm.foreach(elm => domUpdateHooks.foreach(_.trigger(elm)))
      }
      for (o <- oldProxy.elm; c <- proxy.elm) postPatchHooks.foreach(_.trigger((o, c)))
    }
  }

  private def createPostPatchHook(hooks: SeparatedHooks)(implicit s: Scheduler): js.UndefOr[Hooks.HookPairFn] = {
    import hooks._

    if (domMountHooks.isEmpty && domUpdateHooks.isEmpty && postPatchHooks.isEmpty) js.undefined
    else { (oldProxy: VNodeProxy, proxy: VNodeProxy) =>
      if (proxy.outwatchState.map(_.id) != oldProxy.outwatchState.map(_.id)) {
        oldProxy.outwatchState.foreach(_.domUnmountHooks.foreach(_ (oldProxy)))
        proxy.elm.foreach(elm => domMountHooks.foreach(_.trigger(elm)))
      } else {
        proxy.elm.foreach(elm => domUpdateHooks.foreach(_.trigger(elm)))
      }
      for (o <- oldProxy.elm; c <- proxy.elm) postPatchHooks.foreach(_.trigger((o, c)))
    }: Hooks.HookPairFn
  }

  def toOutwatchState(hooks: SeparatedHooks, vNodeId: Int): js.UndefOr[OutwatchState] = {
    import hooks._

    if (domMountHooks.isEmpty && domUnmountHooks.isEmpty && domUpdateHooks.isEmpty) js.undefined
    else OutwatchState(vNodeId, createHookSingle(domUnmountHooks))
  }

  def toSnabbdom(hooks: SeparatedHooks, unmount: Boolean)(implicit s: Scheduler): Hooks = {
    import hooks._
    val insertHook = createHookSingle(domMountHooks ++ insertHooks)
    val destroyHook = createHookSingle(domUnmountHooks ++ destroyHooks)
    val prePatchHook = createHookPairOption(prePatchHooks)
    val updateHook = createHookPair(updateHooks)
    val postPatchHook: js.UndefOr[Hooks.HookPairFn]= if (unmount) createPostPatchHookWithUnmount(hooks) else createPostPatchHook(hooks)

    Hooks(insertHook, prePatchHook, updateHook, postPatchHook, destroyHook)
  }
}

private[outwatch] object SnabbdomEmitters {

  private def emittersToFunction(emitters: js.Array[Emitter]): js.Function1[dom.Event, Unit] = {
    (event: dom.Event) => emitters.foreach(_.trigger(event))
  }

  def toSnabbdom(emitters: js.Array[Emitter]): js.Dictionary[js.Function1[dom.Event, Unit]] = {
    emitters
      .groupBy(_.eventType)
      .mapValues(emittersToFunction)
      .toJSDictionary
  }
}

private[outwatch] object SnabbdomModifiers {

  private[outwatch] def createDataObject(modifiers: SeparatedModifiers, vNodeId: Int)(implicit s: Scheduler): DataObject = {
    import modifiers._

    val key: js.UndefOr[Key.Value] = if (children.hasStream) keyOption.orElse(vNodeId) else keyOption

    val (attrs, props, style) = SnabbdomAttributes.toSnabbdom(attributes)
    DataObject(
      attrs, props, style,
      SnabbdomEmitters.toSnabbdom(emitters),
      SnabbdomHooks.toSnabbdom(hooks, unmount = true),
      key
    )
  }

  private def mergeHooksSingle[T](prevHook: js.UndefOr[Hooks.HookSingleFn], newHook: js.UndefOr[Hooks.HookSingleFn]): js.UndefOr[Hooks.HookSingleFn] = {
    prevHook.map { prevHook =>
      newHook.fold[Hooks.HookSingleFn](prevHook) { newHook => e =>
        prevHook(e)
        newHook(e)
      }
    }.orElse(newHook)
  }
  private def mergeHooksPair[T](prevHook: js.UndefOr[Hooks.HookPairFn], newHook: js.UndefOr[Hooks.HookPairFn]): js.UndefOr[Hooks.HookPairFn] = {
    prevHook.map { prevHook =>
      newHook.fold[Hooks.HookPairFn](prevHook) { newHook => (e1, e2) =>
        prevHook(e1, e2)
        newHook(e1, e2)
      }
    }.orElse(newHook)
  }

  private def updateDataObject(modifiers: SeparatedModifiers, previousData: DataObject)(implicit scheduler: Scheduler): DataObject = {
    import modifiers._

    val (attrs, props, style) = SnabbdomAttributes.toSnabbdom(attributes)
    val snabbdomHooks = SnabbdomHooks.toSnabbdom(hooks, unmount = false)

    DataObject(
      //TODO this is a workaround for streaming accum attributes. the fix only
      //handles "class"-attributes, because it is the only default accum
      //attribute.  But this should for work for all accum attributes.
      //Therefore we need to have all accum keys for initial attrs and styles
      //whith their accum function here.
      attrs = DictionaryOps.mergeWithAccum(previousData.attrs, attrs, Set("class"), (p,n) => p.toString + " " + n.toString),
      props = DictionaryOps.merge(previousData.props, props),
      style = DictionaryOps.merge(previousData.style, style),
      on = DictionaryOps.merge(previousData.on, SnabbdomEmitters.toSnabbdom(emitters)),
      hook = Hooks(
        mergeHooksSingle(previousData.hook.insert, snabbdomHooks.insert),
        mergeHooksPair(previousData.hook.prepatch, snabbdomHooks.prepatch),
        mergeHooksPair(previousData.hook.update, snabbdomHooks.update),
        mergeHooksPair(previousData.hook.postpatch, snabbdomHooks.postpatch),
        mergeHooksSingle(previousData.hook.destroy, snabbdomHooks.destroy)
      ),
      key = keyOption orElse previousData.key
    )
  }

  // This is called initially once the VNode is constructed. Every time a
  // dynamic modifier of this node yields a new VNodeState, this new state with
  // new modifiers and attributes needs to be applied to the current
  // VNodeProxy.
  private[outwatch] def createProxy(nodeType: String, state: js.UndefOr[OutwatchState], dataObject: DataObject, children: js.Array[ChildVNode], hasVTree: Boolean)(implicit scheduler: Scheduler): VNodeProxy = {
    val proxy = if (children.isEmpty) {
      hFunction(nodeType, dataObject)
    } else if (hasVTree) {
      val childProxies = children.collect { case s: StaticVNode => s.toSnabbdom }
      hFunction(nodeType, dataObject, childProxies)
    } else {
      val textChildren = children.collect { case s: StringVNode => s.string }
      hFunction(nodeType, dataObject, textChildren.mkString)
    }

    proxy.outwatchState = state
    proxy
  }

  private[outwatch] def updateSnabbdom(modifiers: SeparatedModifiers, previousProxy: VNodeProxy)(implicit scheduler: Scheduler): VNodeProxy = {
    import modifiers._

    // Updates never contain streamable content and therefore we do not need to
    // handle them with Receivers.
    val dataObject = updateDataObject(modifiers, previousProxy.data)

    createProxy(previousProxy.sel, previousProxy.outwatchState, dataObject, children.nodes, children.hasVTree)
  }

  private[outwatch] def toSnabbdom(modifiers: SeparatedModifiers, nodeType: String)(implicit scheduler: Scheduler): VNodeProxy = {
    import modifiers._

    val vNodeId = modifiers.hashCode

    // if there is streamable content, we update the initial proxy with
    // subscribe and unsubscribe callbakcs.  additionally we update it with the
    // initial state of the obseravbles.
    if (children.hasStream) {
      // if there is streamable content and static nodes, we give keys to all static nodes
      val childrenWithKey = if (children.hasVTree) children.nodes.map {
        case vtree: VTree => vtree.copy(modifiers = Key(vtree.hashCode) +: vtree.modifiers)
        case other => other
      } else children.nodes

      val receivers = new Receivers(childrenWithKey)

      // needs var for forward referencing
      var initialProxy: VNodeProxy = null
      var proxy: VNodeProxy = null

      def toProxy(modifiers: js.Array[Modifier]): VNodeProxy = {
        SnabbdomModifiers.updateSnabbdom(SeparatedModifiers.from(modifiers), initialProxy)
      }
      def subscribe(): Cancelable = {
        receivers.observable
          .map(toProxy)
          .scan(proxy) { case (old, crt) =>
            OutwatchTracing.patchSubject.onNext((old, crt))
            val next = patch(old, crt)
            proxy.sel = next.sel
            proxy.data = next.data
            proxy.children = next.children
            proxy.elm = next.elm
            proxy.text = next.text
            proxy.key = next.key
            next
          }.subscribe(
          _ => Continue,
          error => dom.console.error(error.getMessage + "\n" + error.getStackTrace.mkString("\n"))
        )
      }

      // hooks for subscribing and unsubscribing
      val cancelable = new QueuedCancelable()
      modifiers.append(DomMountHook(_ => cancelable.enqueue(subscribe())))
      modifiers.append(DomUnmountHook(_ => cancelable.dequeue().cancel()))

      // create initial proxy
      val state = SnabbdomHooks.toOutwatchState(hooks, vNodeId)
      val dataObject = createDataObject(modifiers, vNodeId)
      initialProxy = createProxy(nodeType, state, dataObject, childrenWithKey, children.hasVTree)

      // directly update this dataobject with default values from the receivers
      proxy = updateSnabbdom(SeparatedModifiers.from(receivers.initialState), initialProxy)

      proxy
    } else {
      val state = SnabbdomHooks.toOutwatchState(hooks, vNodeId)
      val dataObject = createDataObject(modifiers, vNodeId)
      createProxy(nodeType, state, dataObject, children.nodes, children.hasVTree)
    }
  }
}
