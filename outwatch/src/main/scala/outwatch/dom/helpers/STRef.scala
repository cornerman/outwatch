package outwatch.dom.helpers

import cats.effect.SyncIO

class STRef[A](private var unsafeGet: A) {
  def put(a: A): SyncIO[A] = SyncIO { unsafeGet = a; a }

  def getOrThrow(t: Throwable): SyncIO[A] = SyncIO(unsafeGet)
    .flatMap(s => if (s == null) SyncIO.raiseError(t) else SyncIO.pure(s)) // scalastyle:ignore

  def get: SyncIO[A] = getOrThrow(new IllegalStateException())
  def update(f: A => A): SyncIO[A] = SyncIO { unsafeGet = f(unsafeGet); unsafeGet }
}

object STRef {
  def apply[A](a: A): STRef[A] = new STRef(a)
  def empty[A]: STRef[A] = new STRef[A](null.asInstanceOf[A]) // scalastyle:ignore
}
