package outwatch

import zio._
import zio.internal.Platform
import zio.interop.catz._

package object z {
  type ZModifierEnv = Has[Platform]
  type ZRModifier[-Env] = RModifier[ZModifierEnv with Env]
  type ZModifier = ZRModifier[Any]

  implicit def render[Env, R, T: Render[R, ?]]: Render[ZModifierEnv with Env with R, RIO[Env, T]] = new Render[ZModifierEnv with Env with R, RIO[Env, T]] {
    def render(effect: RIO[Env, T]) = RModifier.access[ZModifierEnv with Env with R] { env =>
      implicit val runtime = Runtime(env, env.get[Platform])
      RModifier(effect).provide(env)
    }
  }

  implicit def accessEnvironment[Result]: AccessEnvironment[RIO[-?, Result]] = new AccessEnvironment[RIO[-?, Result]] {
    def access[Env](f: Env => RIO[Any, Result]): RIO[Env, Result] = RIO.accessM(f)
    def provide[Env](t: RIO[Env, Result])(env: Env): RIO[Any, Result] = t.provide(env)
  }

  @inline implicit class EmitterBuilderOpsAccessEnvironment[Env, O, Result[-_] : AccessEnvironment](val self: EmitterBuilder[O, Result[Env]]) {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] =
      concatMapZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] =
      EmitterBuilder.access { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).provide(env)
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): Result[ZModifierEnv with R with Env] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): Result[ZModifierEnv with R with Env] = foreachZIO(_ => action)
  }

  @inline implicit class EmitterBuilderOps[O, Result](val self: EmitterBuilder[O, Result]) extends AnyVal {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] =
      concatMapZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] =
      EmitterBuilder.access[ZModifierEnv with R][T, RIO[-?, Result], EmitterBuilderExec.Execution] { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).mapResult(RIO.succeed(_))
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = foreachZIO(_ => action)
  }
}
