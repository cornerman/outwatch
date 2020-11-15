package outwatch

import zio._
import zio.internal.Platform
import zio.interop.catz._

package object z {
  type ZModifierEnv = Has[Platform]
  type ZModifierM[-Env] = ModifierM[ZModifierEnv with Env]
  type ZModifier = ZModifierM[Any]

  implicit def renderWithoutError[Env, R, T: Render[R, ?]]: Render[ZModifierEnv with Env with R, RIO[Env, T]] = new Render[ZModifierEnv with Env with R, RIO[Env, T]] {
    def render(effect: RIO[Env, T]) = ModifierM.access[ZModifierEnv with Env with R] { env =>
      implicit val runtime = Runtime(env, env.get[Platform])
      Render.EffectRenderAs[RIO[Env, ?], R, T].render(effect).provide(env)
    }
  }

  implicit def renderWithError[Env, RE, RT, E: Render[RE, ?], T: Render[RT, ?]]: Render[ZModifierEnv with Env with RT with RE, ZIO[Env, E, T]] = new Render[ZModifierEnv with Env with RT with RE, ZIO[Env, E, T]] {
    def render(effect: ZIO[Env, E, T]) = ModifierM.access[ZModifierEnv with Env with RT with RE] { env =>
      implicit val runtime = Runtime(env, env.get[Platform])
      Render.EffectRenderAs[RIO[Env, ?], RE with RT, ModifierM[RE with RT]].render(effect.fold(ModifierM(_), ModifierM(_))).provide(env)
    }
  }

  implicit def accessEnvironment[Result]: AccessEnvironment[RIO[-?, Result]] = new AccessEnvironment[RIO[-?, Result]] {
    @inline def access[Env](f: Env => RIO[Any, Result]): RIO[Env, Result] = RIO.accessM(f)
    @inline def provide[Env](t: RIO[Env, Result])(env: Env): RIO[Any, Result] = t.provide(env)
    @inline def provideSome[Env, R](t: RIO[Env, Result])(map: R => Env): RIO[R, Result] = t.provideSome(map)
  }

  @inline implicit class EmitterBuilderOpsAccessEnvironment[Env, O, Result[-_] : AccessEnvironment](val self: EmitterBuilder[O, Result[Env]]) {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] = concatMapZIO(_ => effect)
    @inline def useSingleZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] = concatMapSingleZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] =
      EmitterBuilder.accessM { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).provide(env)
      }

    @inline def concatMapSingleZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, Result[ZModifierEnv with R with Env]] =
      EmitterBuilder.accessM { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapSingleAsync(effect).provide(env)
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): Result[ZModifierEnv with R with Env] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): Result[ZModifierEnv with R with Env] = foreachZIO(_ => action)
    @inline def foreachSingleZIO[R](action: O => RIO[R, Unit]): Result[ZModifierEnv with R with Env] = concatMapSingleZIO(action).discard
    @inline def doSingleZIO[R](action: RIO[R, Unit]): Result[ZModifierEnv with R with Env] = foreachSingleZIO(_ => action)
  }

  @inline implicit class EmitterBuilderOps[O, Result](val self: EmitterBuilder[O, Result]) extends AnyVal {
    @inline def useZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] = concatMapZIO(_ => effect)
    @inline def useSingleZIO[R, T](effect: RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] = concatMapSingleZIO(_ => effect)

    @inline def concatMapZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] =
      EmitterBuilder.accessM[ZModifierEnv with R].apply[Any, T, RIO[-?, Result], EmitterBuilderExec.Execution] { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapAsync(effect).mapResult(RIO.succeed(_))
      }

    @inline def concatMapSingleZIO[R, T](effect: O => RIO[R, T]): EmitterBuilder[T, RIO[ZModifierEnv with R, Result]] =
      EmitterBuilder.accessM[ZModifierEnv with R].apply[Any, T, RIO[-?, Result], EmitterBuilderExec.Execution] { env =>
        implicit val runtime = Runtime(env, env.get[Platform])
        self.concatMapSingleAsync(effect).mapResult(RIO.succeed(_))
      }

    @inline def foreachZIO[R](action: O => RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = concatMapZIO(action).discard
    @inline def doZIO[R](action: RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = foreachZIO(_ => action)
    @inline def foreachSingleZIO[R](action: O => RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = concatMapSingleZIO(action).discard
    @inline def doSingleZIO[R](action: RIO[R, Unit]): RIO[ZModifierEnv with R, Result] = foreachSingleZIO(_ => action)
  }
}
