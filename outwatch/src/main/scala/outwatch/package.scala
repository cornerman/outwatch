import com.raquo.domtypes.generic.keys
import outwatch.helpers.BasicStyleBuilder

package object outwatch extends definitions.ManagedHelpers {
  type EmitterBuilder[+O, +R] = EmitterBuilderExec[O, R, EmitterBuilderExec.Execution]

  type Modifier = RModifier[Any]
  type VNode = RVNode[Any]
  type BasicVNode = RBasicVNode[Any]
  type ConditionalVNode = RConditionalVNode[Any]
  type ThunkVNode = RThunkVNode[Any]
  type HtmlVNode = RHtmlVNode[Any]
  type SvgVNode = RSvgVNode[Any]

  @deprecated("use Modifier instead", "1.0.0")
  type VDomModifier = Modifier
  @deprecated("use Modifier instead", "1.0.0")
  val VDomModifier = Modifier

  @deprecated("use Outwatch instead", "1.0.0")
  val OutWatch = Outwatch

  //TODO: invent typeclass CanBuildStyle[F[_]]
  @inline implicit def StyleIsBuilder[T](style: keys.Style[T]): BasicStyleBuilder[T] = new BasicStyleBuilder[T](style.cssName)
}
