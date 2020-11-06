import com.raquo.domtypes.generic.keys
import outwatch.helpers.BasicStyleBuilder

package object outwatch extends definitions.ManagedHelpers {
  type EmitterBuilderExec[+O, +R <: Modifier, +Exec <: REmitterBuilderExec.Execution] = REmitterBuilderExec[Any, O, R, Exec]
  type REmitterBuilder[-Env, +O, +R <: RModifier[Env]] = REmitterBuilderExec[Env, O, R, REmitterBuilderExec.Execution]
  type EmitterBuilder[+O, +R <: Modifier] = REmitterBuilder[Any, O, R]

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
