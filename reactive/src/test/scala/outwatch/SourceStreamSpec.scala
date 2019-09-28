package outwatch

import cats.effect.IO
import org.scalatest.{FlatSpec, Matchers}
import outwatch.reactive._

class SourceStreamSpec extends FlatSpec with Matchers {

  "SourceStream" should "map" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = SourceStream.fromIterable(Seq(1,2,3)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    stream.subscribe(SinkObserver.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    stream.subscribe(SinkObserver.create[Int](received ::= _))

    mapped shouldBe List(3,2,1,3,2,1)
    received shouldBe List(3,2,1,3,2,1)
  }

  it should "dropWhile" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = SourceStream.fromIterable(Seq(1,2,3,4)).dropWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    stream.subscribe(SinkObserver.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(4,3)
  }

  it should "share" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val handler = SinkSourceHandler[Int]
    val stream = SourceStream.merge(handler, SourceStream.fromIterable(Seq(1,2,3))).map { x => mapped ::= x; x }.share

    mapped shouldBe List.empty

    val sub1 = stream.subscribe(SinkObserver.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    val sub2 = stream.subscribe(SinkObserver.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    handler.onNext(4)

    mapped shouldBe List(4,3,2,1)
    received shouldBe List(4,4,3,2,1)

    sub1.cancel()

    handler.onNext(5)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,2,1)

    sub2.cancel()

    handler.onNext(6)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,2,1)
  }

  it should "shareWithLatest" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler = SinkSourceHandler[Int]
    val stream = SourceStream.merge(handler, SourceStream.fromIterable(Seq(1,2,3))).map { x => mapped ::= x; x }.shareWithLatest

    mapped shouldBe List.empty

    val sub1 = stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    val sub2 = stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,3,2,1)

    handler.onNext(4)

    mapped shouldBe List(4,3,2,1)
    received shouldBe List(4,4,3,3,2,1)

    sub1.cancel()

    handler.onNext(5)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,3,2,1)

    sub2.cancel()

    handler.onNext(6)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,3,2,1)

    errors shouldBe 0
    completed shouldBe 0

    val sub3 = stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    mapped shouldBe List(3,2,1,6,5,4,3,2,1)
    received shouldBe List(3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 0
    completed shouldBe 0

    handler.onError(new Exception)

    errors shouldBe 1
    completed shouldBe 0

    handler.onComplete()

    errors shouldBe 1
    completed shouldBe 1

    handler.onNext(19)

    mapped shouldBe List(3,2,1,6,5,4,3,2,1)
    received shouldBe List(3,2,1,6,5,5,4,4,3,3,2,1)

    sub3.cancel()

    mapped shouldBe List(3,2,1,6,5,4,3,2,1)
    received shouldBe List(3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 1
    completed shouldBe 1
  }

  it should "concatVaried" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val stream = SourceStream.concatVaried(SourceStream.fromAsync(IO { runEffect += 1; 0 }), SourceStream.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "concat" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler = SinkSourceHandler(0)
    val stream = SourceStream.concat(handler, SourceStream.fromIterable(Seq(1,2,3)))

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List(0)
    errors shouldBe 0
    completed shouldBe 0

    handler.onNext(19)

    received shouldBe List(19,0)
    errors shouldBe 0
    completed shouldBe 0

    handler.onComplete()

    received shouldBe List(3,2,1,19,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "concatMap" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler0 = SinkSourceHandler[Int]
    val handler1 = SinkSourceHandler[Int]
    val handler2 = SinkSourceHandler[Int]
    val handler3 = SinkSourceHandler[Int]
    val handlers = Array(handler0, handler1, handler2)
    val stream = SourceStream.fromIterable(Seq(0,1,2)).concatMap(handlers(_))

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List()
    errors shouldBe 0
    completed shouldBe 0

    handler0.onNext(19)

    received shouldBe List(19)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onNext(20)

    received shouldBe List(20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onComplete()

    received shouldBe List(20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onNext(1)

    received shouldBe List(1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(13)

    received shouldBe List(1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler3.onNext(14)

    received shouldBe List(1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler3.onComplete()

    received shouldBe List(1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onNext(2)

    received shouldBe List(2,1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onComplete()

    received shouldBe List(13,2,1,20,19)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onComplete()

    received shouldBe List(13,2,1,20,19)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "switchVaried" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val stream = SourceStream.switchVaried(SourceStream.fromAsync(IO { runEffect += 1; 0 }), SourceStream.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "switch" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler = SinkSourceHandler(0)
    val stream = SourceStream.switch(handler, SourceStream.fromIterable(Seq(1,2,3)))

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1

    handler.onNext(19)

    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1

    handler.onComplete()
    handler.onComplete()
    handler.onComplete()
    handler.onComplete()

    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "switchMap" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler0 = SinkSourceHandler[Int](0)
    val handler1 = SinkSourceHandler[Int]
    val handler2 = SinkSourceHandler[Int](2)
    val handlers = Array(handler0, handler1, SourceStream.empty, handler2)
    val stream = SourceStream.fromIterable(Seq(0,1,2,3)).switchMap(handlers(_))

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List(2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onNext(19)

    received shouldBe List(2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onComplete()

    received shouldBe List(2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onNext(1)

    received shouldBe List(2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onComplete()

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 1

    handler2.onNext(13)

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 1

    handler2.onComplete()

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 1

    handler1.onNext(2)

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 1

    handler1.onComplete()

    received shouldBe List(13,2,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "mergeVaried" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val stream = SourceStream.mergeVaried(SourceStream.fromAsync(IO { runEffect += 1; 0 }), SourceStream.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "merge" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler = SinkSourceHandler(0)
    val handler2 = SinkSourceHandler(3)
    val stream = SourceStream.merge(handler, handler2)

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List(3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler.onNext(19)

    received shouldBe List(19,3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(20)

    received shouldBe List(20,19,3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onComplete()
    handler2.onComplete()
    handler2.onComplete()
    handler2.onComplete()

    received shouldBe List(20,19,3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(21)

    received shouldBe List(20,19,3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler.onNext(39)

    received shouldBe List(39,20,19,3,0)
    errors shouldBe 0
    completed shouldBe 0

    handler.onComplete()

    received shouldBe List(39,20,19,3,0)
    errors shouldBe 0
    completed shouldBe 1

    handler.onNext(-1)

    received shouldBe List(39,20,19,3,0)
    errors shouldBe 0
    completed shouldBe 1

    handler2.onNext(-1)

    received shouldBe List(39,20,19,3,0)
    errors shouldBe 0
    completed shouldBe 1
  }

  it should "mergeMap" in {
    var received = List.empty[Int]
    var errors = 0
    var completed = 0
    val handler0 = SinkSourceHandler[Int](0)
    val handler1 = SinkSourceHandler[Int]
    val handler2 = SinkSourceHandler[Int](2)
    val handlers = Array(handler0, handler1, handler2)
    val stream = SourceStream.fromIterable(Seq(0,1,2)).mergeMap(handlers(_))

    stream.subscribe(SinkObserver.createFull[Int](
      received ::= _,
      _ => errors += 1,
      () => completed += 1
    ))

    received shouldBe List(2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onNext(19)

    received shouldBe List(19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler0.onComplete()

    received shouldBe List(19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onNext(1)

    received shouldBe List(1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onComplete()

    received shouldBe List(13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler2.onComplete()

    received shouldBe List(13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onNext(2)

    received shouldBe List(2,13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 0

    handler1.onComplete()

    received shouldBe List(2,13,1,19,2,0)
    errors shouldBe 0
    completed shouldBe 1
  }
}
