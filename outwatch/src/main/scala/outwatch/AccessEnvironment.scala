package outwatch

trait AccessEnvironment[T[-_]] {
  def access[Env](f: Env => T[Any]): T[Env]
  def provide[Env](t: T[Env])(env: Env): T[Any]
  final def provideMap[Env, R](t: T[Env])(map: R => Env): T[R] = access(env => provide(t)(map(env)))
}
object AccessEnvironment {
  @inline def apply[T[-_]](implicit env: AccessEnvironment[T]): AccessEnvironment[T] = env

  implicit object modifier extends AccessEnvironment[RModifier] {
    @inline def access[Env](f: Env => RModifier[Any]): RModifier[Env] = RModifier.access(f)
    @inline def provide[Env](t: RModifier[Env])(env: Env): RModifier[Any] = t.provide(env)
  }
  implicit object vnode extends AccessEnvironment[RVNode] {
    @inline def access[Env](f: Env => RVNode[Any]): RVNode[Env] = RVNode.access(f)
    @inline def provide[Env](t: RVNode[Env])(env: Env): RVNode[Any] = t.provide(env)
  }
}
