package outwatch.dom

import cats.Id

package object helpers {
  type EmitterBuilder[+O, +R] = EmitterBuilderExecution[O, R, Id, EmitterBuilder.Execution]
  @deprecated("Use EmitterBuilder.Sync[O, R] instead", "0.11.0")
  type SyncEmitterBuilder[+O, +R] = EmitterBuilderExecution[O, R, Id, EmitterBuilder.SyncExecution]
  @deprecated("Use EmitterBuilder.Sync[O, R] instead", "0.11.0")
  type CustomEmitterBuilder[+O, +R] = EmitterBuilderExecution[O, R, Id, EmitterBuilder.SyncExecution]
}
