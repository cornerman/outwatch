package outwatch.dom

import org.scalajs.dom

import scala.annotation.compileTimeOnly

private[outwatch] object MacroMessages {
  val error = "Events can only be used in arguments of the VTree.apply method. Otherwise, you need to provide an implicit TagContext or use onElement explicitly (e.g. onClick.onElement[Element] --> sink)."
}

@compileTimeOnly("compile-time macro expansion")
private[outwatch] object Macros {
  import scala.reflect.macros.blackbox.Context

  private def injectTagContext[Elem <: dom.Element : c.WeakTypeTag](c: Context)(src: c.Tree, injectable: c.universe.TermName): c.Tree = {
    import c.universe._

    val elemType = weakTypeOf[Elem]
    val eventType = weakTypeOf[TypedCurrentTargetEvent[Elem]]
    val contextType = weakTypeOf[TagContext[Elem]]
    val withStringType = weakTypeOf[TagWithString[Elem]]
    val withNumberType = weakTypeOf[TagWithNumber[Elem]]
    val withBooleanType = weakTypeOf[TagWithChecked[Elem]]
    val withStringImpl = c.inferImplicitValue(withStringType)
    val withNumberImpl = c.inferImplicitValue(withNumberType)
    val withBooleanImpl = c.inferImplicitValue(withBooleanType)

    object Transformer extends c.universe.Transformer {
      override def transform(tree: c.Tree): c.Tree = {
        tree.tpe match {
          case null => tree
          case tpe if tree.isType && tpe =:= weakTypeOf[DummyElement.AnyType] => q"$elemType"
          case tpe if tree.isType && tpe =:= weakTypeOf[TypedCurrentTargetEvent[DummyElement.AnyType]] => q"$eventType"
          case tpe if tpe =:= weakTypeOf[TagContext[DummyElement.AnyType]] => if (tree.isType) q"$contextType" else q"$injectable"
          case tpe if tpe =:= weakTypeOf[TagWithString[DummyElement.AnyType]] => if (tree.isType) q"$withStringType" else withStringImpl
          case tpe if tpe =:= weakTypeOf[TagWithNumber[DummyElement.AnyType]] => if (tree.isType) q"$withNumberType" else withNumberImpl
          case tpe if tpe =:= weakTypeOf[TagWithChecked[DummyElement.AnyType]] => if (tree.isType) q"$withBooleanType" else withBooleanImpl

          case t@RefinedType(components, scope) if tree.isType =>
            // manually traverse refined types
            val types = components.map(tpe => transform(tq"$tpe"))
            tq"${types.head} with ..${types.tail}"

          case _ =>
            tree match {
              case q"$x.DummyElementUsableEvent[$_]($arg)" => q"$arg"
              case q"$x.DummyWithProperties($arg).$fun" => q"$arg.$fun"

              case _ => super.transform(tree)
            }
        }
      }
    }

    Transformer.transform(src)
  }

  def vtreeImpl[Elem <: dom.Element : c.WeakTypeTag](c: Context)(newModifiers: c.Expr[VDomModifier]*): c.Expr[VTree[Elem]] = {
    import c.universe._

    val elemType = weakTypeOf[Elem]
    val tagContextName = TermName("_injected_tag_context_")
    val injectedMofifiers = newModifiers.map { expr =>
      val body = injectTagContext(c)(expr.tree, tagContextName)
      q"($tagContextName: _root_.outwatch.dom.TagContext[$elemType]) => $body"
    }

    val tree = q"""
      ${c.prefix}.applyDirect(..$injectedMofifiers)
    """

    println(s"PREE: ${newModifiers.map(m => showCode(m.tree))}")
    println(s"TREE: ${showCode(tree)}")

    val untypedTree = c.untypecheck(tree)
    c.Expr[VTree[Elem]](untypedTree)
  }
}
