package outwatch

import zio._
import zio.internal.Platform
import zio.interop.catz._

package object z {
  type ZModifierEnv = Has[Platform]
  type ZRModifier[-Env] = RModifier[ZModifierEnv with Env]
  type ZModifier = ZRModifier[Any]

  type ZREmitterBuilderExec[-Env, +O, +R <: RModifier[Env], +Exec <: REmitterBuilderExec.Execution] = REmitterBuilderExec[ZModifierEnv with Env, O, R, Exec]
  type ZEmitterBuilderExec[+O, +R <: Modifier, +Exec <: REmitterBuilderExec.Execution] = ZREmitterBuilderExec[Any, O, R, Exec]
  type ZREmitterBuilder[-Env, +O, +R <: RModifier[Env]] = REmitterBuilder[ZModifierEnv with Env, O, R]
  type ZEmitterBuilder[+O, +R <: Modifier] = ZREmitterBuilder[Any, O, R]

  object ZREmitterBuilder {
    type Sync[-Env, +O, +R <: RModifier[Env]] = REmitterBuilder.Sync[ZModifierEnv with Env, O, R]
  }
  object ZEmitterBuilder {
    type Sync[+O, +R <: Modifier] = ZREmitterBuilder.Sync[Any, O, R]
  }

  implicit def render[Env, R, T: Render[R, ?]]: Render[ZModifierEnv with Env with R, RIO[Env, T]] = new Render[ZModifierEnv with Env with R, RIO[Env, T]] {
    def render(effect: RIO[Env, T]) = RModifier.access[ZModifierEnv with Env with R] { env =>
      implicit val runtime = Runtime(env, env.get[Platform])
      RModifier(effect).provide(env)
    }
  }

  @inline implicit class EmitterBuilderOpsModifier[Env, O, Exec <: REmitterBuilderExec.Execution](val self: REmitterBuilderExec[Env, O, RModifier[Env], Exec]) extends AnyVal {
    @inline def useZIO[R, T](effect: RIO[R, T]): REmitterBuilder[ZModifierEnv with R with Env, T, RModifier[ZModifierEnv with R with Env]] =
      concatMapZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): REmitterBuilder[ZModifierEnv with R with Env, T, RModifier[ZModifierEnv with R with Env]] =
      REmitterBuilder.access { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).provide(env)
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): RModifier[ZModifierEnv with R with Env] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): RModifier[ZModifierEnv with R with Env] = foreachZIO(_ => action)
  }
}
