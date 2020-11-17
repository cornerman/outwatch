package outwatch

trait AccessEnvironment[-T[-_], +U[-_]] {
  def access[Env](f: Env => T[Any]): U[Env]
  def provide[Env](t: T[Env])(env: Env): U[Any]
  def provideSome[Env, R](t: T[Env])(map: R => Env): U[R]
}
object AccessEnvironment {
  @inline def apply[T[-_], U[-_]](implicit env: AccessEnvironment[T,U]): AccessEnvironment[T,U] = env

  implicit object modifier extends AccessEnvironment[ModifierM, ModifierM] {
    @inline def access[Env](f: Env => ModifierM[Any]): ModifierM[Env] = ModifierM.access(f)
    @inline def provide[Env](t: ModifierM[Env])(env: Env): ModifierM[Any] = t.provide(env)
    @inline def provideSome[Env, R](t: ModifierM[Env])(map: R => Env): ModifierM[R] = t.provideSome(map)
  }
  implicit object vnode extends AccessEnvironment[VNodeM, VNodeM] {
    @inline def access[Env](f: Env => VNodeM[Any]): VNodeM[Env] = VNodeM.access(f)
    @inline def provide[Env](t: VNodeM[Env])(env: Env): VNodeM[Any] = t.provide(env)
    @inline def provideSome[Env, R](t: VNodeM[Env])(map: R => Env): VNodeM[R] = t.provideSome(map)
  }
}
