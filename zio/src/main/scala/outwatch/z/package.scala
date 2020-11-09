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

  @inline implicit class EmitterBuilderOpsModifier[Env, O, Exec <: EmitterBuilderExec.Execution](val self: EmitterBuilderExec[O, RModifier[Env], Exec]) extends AnyVal {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, RModifier[ZModifierEnv with R with Env]] =
      concatMapZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, RModifier[ZModifierEnv with R with Env]] =
      EmitterBuilder.access { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).provide(env)
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): RModifier[ZModifierEnv with R with Env] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): RModifier[ZModifierEnv with R with Env] = foreachZIO(_ => action)
  }

  @inline implicit class EmitterBuilderOpsRIO[Env, O, Result, Exec <: EmitterBuilderExec.Execution](val self: EmitterBuilderExec[O, RIO[Env, Result], Exec]) extends AnyVal {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R with Env, Result]] =
      concatMapZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R with Env, Result]] =
      ???
      // EmitterBuilder.access { env =>
      //   implicit val runtime = Runtime(env, env.get[Platform])
      //   self.concatMapAsync(effect).provide(env)
      // }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): RIO[ZModifierEnv with R with Env, Result] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): RIO[ZModifierEnv with R with Env, Result] = foreachZIO(_ => action)
  }
}
