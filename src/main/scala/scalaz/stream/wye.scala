package scalaz.stream

import Cause._
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process._
import scalaz.stream.ReceiveY._
import scalaz.stream.Util._
import scalaz.stream.process1.Await1
import scalaz.{-\/, Either3, Left3, Middle3, Right3, \/, \/-}


object wye {

  /**
   * A `Wye` which emits values from its right branch, but allows up to `n`
   * elements from the left branch to enqueue unanswered before blocking
   * on the right branch.
   */
  def boundedQueue[I](n: Int): Wye[Any,I,I] =
    yipWithL[Any,I,I](n)((i,i2) => i2) ++ tee.passR

  /**
   * After each input, dynamically determine whether to read from the left, right, or both,
   * for the subsequent input, using the provided functions `f` and `g`. The returned
   * `Wye` begins by reading from the left side and is left-biased--if a read of both branches
   * returns a `These(x,y)`, it uses the signal generated by `f` for its next step.
   */
  def dynamic[I,I2](f: I => wye.Request, g: I2 => wye.Request): Wye[I,I2,ReceiveY[I,I2]] = {
    import scalaz.stream.wye.Request._
    def go(signal: wye.Request): Wye[I,I2,ReceiveY[I,I2]] = signal match {
      case L => receiveL { i => emit(ReceiveL(i)) fby go(f(i)) }
      case R => receiveR { i2 => emit(ReceiveR(i2)) fby go(g(i2)) }
      case Both => receiveBoth {
        case t@ReceiveL(i) => emit(t) fby go(f(i))
        case t@ReceiveR(i2) => emit(t) fby go(g(i2))
        case HaltOne(rsn) => Halt(rsn)
      }
    }
    go(L)
  }

  /**
   * A `Wye` which echoes the right branch while draining the left,
   * taking care to make sure that the left branch is never more
   * than `maxUnacknowledged` behind the right. For example:
   * `src.connect(snk)(observe(10))` will output the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainL[I](maxUnacknowledged: Int): Wye[Any,I,I] =
    yipWithL[Any,I,I](maxUnacknowledged)((_,i) => i) ++ tee.passR

  /**
   * A `Wye` which echoes the left branch while draining the right,
   * taking care to make sure that the right branch is never more
   * than `maxUnacknowledged` behind the left. For example:
   * `src.connect(snk)(observe(10))` will output the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainR[I](maxUnacknowledged: Int): Wye[I,Any,I] =
    yipWithL[I,Any,I](maxUnacknowledged)((i,i2) => i) ++ tee.passL

  /**
   * Invokes `dynamic` with `I == I2`, and produces a single `I` output. Output is
   * left-biased: if a `These(i1,i2)` is emitted, this is translated to an
   * `emitSeq(List(i1,i2))`.
   */
  def dynamic1[I](f: I => wye.Request): Wye[I,I,I] =
    dynamic(f, f).flatMap {
      case ReceiveL(i) => emit(i)
      case ReceiveR(i) => emit(i)
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def either[I,I2]: Wye[I,I2,I \/ I2] =
    receiveBoth {
      case ReceiveL(i) => emit(left(i)) fby either
      case ReceiveR(i) => emit(right(i)) fby either
      case HaltL(End)     => awaitR[I2].map(right).repeat
      case HaltR(End)     => awaitL[I].map(left).repeat
      case h@HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Continuous wye, that first reads from Left to get `A`,
   * Then when `A` is not available it reads from R echoing any `A` that was received from Left
   * Will halt once any of the sides halt
   */
  def echoLeft[A]: Wye[A, Any, A] = {
    def go(a: A): Wye[A, Any, A] =
      receiveBoth {
        case ReceiveL(l)  => emit(l) fby go(l)
        case ReceiveR(_)  => emit(a) fby go(a)
        case HaltOne(rsn) => Halt(rsn)
      }
    receiveL(s => emit(s) fby go(s))
  }

  /**
   * Let through the right branch as long as the left branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as the right or left branch halts.
   */
  def interrupt[I]: Wye[Boolean, I, I] =
    receiveBoth {
      case ReceiveR(i)    => emit(i) ++ interrupt
      case ReceiveL(kill) => if (kill) halt else interrupt
      case HaltOne(e)     => Halt(e)
    }

  /**
   * Non-deterministic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   *
   * Will terminate once both sides terminate.
   */
  def merge[I]: Wye[I,I,I] =
    receiveBoth {
      case ReceiveL(i) => emit(i) fby merge
      case ReceiveR(i) => emit(i) fby merge
      case HaltL(End)   => awaitR.repeat
      case HaltR(End)   => awaitL.repeat
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Like `merge`, but terminates whenever one side terminate.
   */
  def mergeHaltBoth[I]: Wye[I,I,I] =
    receiveBoth {
      case ReceiveL(i) => emit(i) fby mergeHaltBoth
      case ReceiveR(i) => emit(i) fby mergeHaltBoth
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Like `merge`, but terminates whenever left side terminates.
   * use `flip` to reverse this for the right side
   */
  def mergeHaltL[I]: Wye[I,I,I] =
    receiveBoth {
      case ReceiveL(i) => emit(i) fby mergeHaltL
      case ReceiveR(i) => emit(i) fby mergeHaltL
      case HaltR(End)   => awaitL.repeat
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Like `merge`, but terminates whenever right side terminates
   */
  def mergeHaltR[I]: Wye[I,I,I] =
    wye.flip(mergeHaltL)

  /**
   * A `Wye` which blocks on the right side when either
   *   a) the age of the oldest unanswered element from the left size exceeds the given duration, or
   *   b) the number of unanswered elements from the left exceeds `maxSize`.
   */
  def timedQueue[I](d: Duration, maxSize: Int = Int.MaxValue): Wye[Duration,I,I] = {
    def go(q: Vector[Duration]): Wye[Duration,I,I] =
      receiveBoth {
        case ReceiveL(d2) =>
          if (q.size >= maxSize || (d2 - q.headOption.getOrElse(d2) > d))
            receiveR(i => emit(i) fby go(q.drop(1)))
          else
            go(q :+ d2)
        case ReceiveR(i) => emit(i) fby (go(q.drop(1)))
        case HaltOne(rsn) => Halt(rsn)
      }
    go(Vector())
  }


  /**
   * `Wye` which repeatedly awaits both branches, emitting any values
   * received from the right. Useful in conjunction with `connect`,
   * for instance `src.connect(snk)(unboundedQueue)`
   */
  def unboundedQueue[I]: Wye[Any,I,I] =
    receiveBoth {
      case ReceiveL(_) => halt
      case ReceiveR(i) => emit(i) fby unboundedQueue
      case HaltOne(rsn) => Halt(rsn)
    }


  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))

  /**
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipL[I,I2](n: Int): Wye[I,I2,(I,I2)] =
    yipWithL(n)((_,_))

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] =
    receiveBoth {
      case ReceiveL(i) => receiveR(i2 => emit(f(i,i2)) ++ yipWith(f))
      case ReceiveR(i2) => receiveL(i => emit(f(i,i2)) ++ yipWith(f))
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Vector[I]): Wye[I,O,O2] =
      if (buf.size > n) receiveR { o =>
        emit(f(buf.head,o)) ++ go(buf.tail)
      }
      else if (buf.isEmpty) receiveL { i => go(buf :+ i) }
      else receiveBoth {
        case ReceiveL(i) => go(buf :+ i)
        case ReceiveR(o) => emit(f(buf.head,o)) ++ go(buf.tail)
        case HaltOne(rsn) => Halt(rsn)
      }
    go(Vector())
  }

  //////////////////////////////////////////////////////////////////////
  // Helper combinator functions, useful when working with wye directly
  //////////////////////////////////////////////////////////////////////

  /**
   * Transform the left input of the given `Wye` using a `Process1`.
   */
  def attachL[I0,I,I2,O](p1: Process1[I0,I])(y: Wye[I,I2,O]): Wye[I0,I2,O] =  {
    y.step match {
      case Step(emt@Emit(os), cont) =>
        emt onHalt (rsn => attachL(p1)(Halt(rsn) +: cont))

      case Step(AwaitL(rcv), cont) => p1.step match {
        case Step(Emit(is), cont1) => suspend(attachL(cont1.continue)(feedL(is)(y)))
        case Step(Await1(rcv1), cont1) =>
          wye.receiveLOr(cause => attachL(rcv1(left(cause)) +: cont1)(y))(
           i0 => attachL(p1.feed1(i0))(y)
          )
        case hlt@Halt(cause) =>
          suspend(cause.fold(attachL(hlt)(disconnectL(Kill)(y).swallowKill))(
            early => attachL(hlt)(disconnectL(early)(y))
          ))

      }

      case Step(AwaitR(rcv), cont) =>
        wye.receiveROr(early => attachL(p1)(rcv(left(early)) +: cont))(
          i2 => attachL(p1)(feed1R(i2)(y))
        )

      case Step(AwaitBoth(rcv), cont) => p1.step match {
        case Step(Emit(is), cont1) => suspend(attachL(cont1.continue)(feedL(is)(y)))
        case Step(Await1(rcv1), _) =>
            wye.receiveBoth[I0,I2,O] {
              case ReceiveL(i0) => attachL(p1.feed1(i0))(y)
              case ReceiveR(i2) => attachL(p1)(feed1R(i2)(y))
              case HaltL(cause) =>
                cause.fold(attachL(p1)(disconnectL(Kill)(y).swallowKill))(
                 early => attachL(p1)(disconnectL(early)(y))
                )
              case HaltR(cause) =>
                cause.fold( attachL(p1)(disconnectR(Kill)(y).swallowKill))(
                 early => attachL(p1)(disconnectR(early)(y))
                )
            }
        case hlt@Halt(cause) =>
          val ny = rcv(HaltL(cause)) +: cont
          suspend(cause.fold(attachL(hlt)(disconnectL(Kill)(ny).swallowKill))(
           early => attachL(hlt)(disconnectL(early)(ny))
          ))

      }

      case hlt@Halt(_) => hlt
    }
  }

  /**
   * Transform the right input of the given `Wye` using a `Process1`.
   */
  def attachR[I,I1,I2,O](p: Process1[I1,I2])(w: Wye[I,I2,O]): Wye[I,I1,O] =
    flip(attachL(p)(flip(w)))


  /**
   * Transforms the wye so it will stop to listen on left side.
   * Instead all requests on the left side are converted to termination with `Kill`,
   * and will terminate once the right side will terminate as well.
   * Transforms `AwaitBoth` to `AwaitR`
   * Transforms `AwaitL` to termination with `End`
   */
  def detach1L[I,I2,O](y: Wye[I,I2,O]): Wye[I,I2,O] =
    disconnectL(Kill)(y).swallowKill


  /** right alternative of detach1L */
  def detach1R[I,I2,O](y: Wye[I,I2,O]): Wye[I,I2,O] =
    disconnectR(Kill)(y).swallowKill

  /**
   * Feed a single `ReceiveY` value to a `Wye`.
   */
  def feed1[I,I2,O](r: ReceiveY[I,I2])(w: Wye[I,I2,O]): Wye[I,I2,O] =
    r match {
      case ReceiveL(i) => feed1L(i)(w)
      case ReceiveR(i2) => feed1R(i2)(w)
      case HaltL(cause) => cause.fold(detach1L(w))(e => disconnectL(e)(w))
      case HaltR(cause) => cause.fold(detach1R(w))(e => disconnectR(e)(w))
    }

  /** Feed a single value to the left branch of a `Wye`. */
  def feed1L[I,I2,O](i: I)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedL(Vector(i))(w)

  /** Feed a single value to the right branch of a `Wye`. */
  def feed1R[I,I2,O](i2: I2)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedR(Vector(i2))(w)

  /** Feed a sequence of inputs to the left side of a `Wye`. */
  def feedL[I,I2,O](is: Seq[I])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {
      if (in.nonEmpty) cur.step match {
        case Step(Emit(os), cont) =>
          go(in, out :+ os, cont.continue)

        case Step(AwaitL(rcv), cont) =>
          go(in.tail, out,  rcv(right(in.head)) +: cont)

        case Step(awt@AwaitR(rcv), cont) =>
          emitAll(out.flatten) onHalt {
            case End               => awt.extend(p => feedL(in)(p +: cont))
            case early: EarlyCause => feedL(in)(rcv(left(early)) +: cont)
          }

        case Step(AwaitBoth(rcv), cont) =>
          go(in.tail, out, Try(rcv(ReceiveY.ReceiveL(in.head))) +: cont)

        case Halt(rsn)                  =>
          emitAll(out.flatten).causedBy(rsn)

      } else cur.prepend(out.flatten)

    }
    go(is, Vector(), y)

  }

  /** Feed a sequence of inputs to the right side of a `Wye`. */
  def feedR[I,I2,O](i2s: Seq[I2])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {
      if (in.nonEmpty) cur.step match {
        case  Step(Emit(os), cont) =>
          go(in, out :+ os, cont.continue)

        case Step(awt@AwaitL(rcv), cont) =>
          emitAll(out.flatten) onHalt {
            case End               => awt.extend(p => feedR(in)(p +: cont))
            case early: EarlyCause => feedR(in)(rcv(left(early)) +: cont)
          }

        case Step(AwaitR(rcv), cont) =>
          go(in.tail, out, rcv(right(in.head)) +: cont)

        case Step(AwaitBoth(rcv), cont) =>
          go(in.tail, out, rcv(ReceiveY.ReceiveR(in.head)) +: cont)

        case Halt(rsn)                  =>
          emitAll(out.flatten).causedBy(rsn)

      } else cur.prepend(out.flatten)
    }
    go(i2s, Vector(), y)

  }

  /**
   * Convert right requests to left requests and vice versa.
   */
  def flip[I,I2,O](y: Wye[I,I2,O]): Wye[I2,I,O] = {
    y.step match {
      case Step(Emit(os), cont)       => emitAll(os) onHalt (rsn => flip(Halt(rsn) +: cont))

      case Step(awt@AwaitL(rcv), cont)    =>
        wye.receiveROr[I2, I, O](e=>flip(rcv(left(e)) +: cont))(
          i => flip(rcv(right(i)) +: cont)
        )

      case Step(AwaitR(rcv), cont)    =>
        wye.receiveLOr[I2, I, O](e =>flip(rcv(left(e)) +: cont))(
          i2 => flip(rcv(right(i2)) +: cont)
        )

      case Step(AwaitBoth(rcv), cont) =>
        wye.receiveBoth[I2, I, O](ry => flip(rcv(ry.flip) +: cont))

      case hlt@Halt(rsn)           => hlt
    }
  }

  /**
   * Signals to wye, that Left side terminated.
   * Reason for termination is `cause`. Any `Left` requests will be terminated with `cause`
   * Wye will be switched to listen only on Right side, that means Await(Both) is converted to Await(R)
   */
  def disconnectL[I, I2, O](cause: EarlyCause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
    val ys = y.step
    ys match {
      case Step(emt@Emit(os), cont) =>
        emt onHalt (rsn => disconnectL(cause)(Halt(rsn) +: cont))

      case Step(AwaitL(rcv), cont) =>
        suspend(disconnectL(cause)(rcv(left(cause)) +: cont))

      case Step(AwaitR(rcv), cont) =>
        wye.receiveROr[I,I2,O](e => disconnectL(cause)(rcv(left(e)) +: cont))(
          i => disconnectL(cause)(rcv(right(i)) +: cont)
        )

      case Step(AwaitBoth(rcv), cont) =>
        wye.receiveROr(e => disconnectL(cause)(rcv(HaltR(e)) +: cont))(
         i2 => disconnectL(cause)(rcv(ReceiveR(i2)) +: cont)
        )

      case hlt@Halt(rsn) => Halt(rsn)
    }

  }

  /**
   * Right side alternative for `disconnectL`
   */
  def disconnectR[I, I2, O](cause: EarlyCause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
      val ys = y.step
      ys match {
        case Step(emt@Emit(os), cont) =>
          emt onHalt (rsn => disconnectR(cause)(Halt(rsn) +: cont))

        case Step(AwaitR(rcv), cont) =>
          suspend(disconnectR(cause)(rcv(left(cause)) +: cont))

        case Step(AwaitL(rcv), cont) =>
          wye.receiveLOr[I,I2,O](e => disconnectR(cause)(rcv(left(e))) +: cont)(
            i => disconnectR(cause)(rcv(right(i)) +: cont)
          )

        case Step(AwaitBoth(rcv), cont) =>
          wye.receiveLOr(e => disconnectR(cause)(rcv(HaltL(e)) +: cont))(
           i => disconnectR(cause)(rcv(ReceiveL(i)) +: cont)
          )

        case hlt@Halt(rsn) => Halt(rsn)
      }
  }


  /**
   * Signals to wye that left side halted with `cause`. Wye will be fed with `HaltL(cause)`
   * and will disconnect from Left side.
   */
  def haltL[I, I2, O](cause: Cause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
    val ys = y.step
    val ny = ys match {
      case Step(AwaitBoth(rcv), cont) => rcv(HaltL(cause)) +: cont
      case _ => y
    }
    cause.fold(disconnectL(Kill)(ny).swallowKill)(e => disconnectL(e)(ny))
  }


  /**
   * Right alternative for `haltL`
   */
  def haltR[I, I2, O](cause: Cause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
    val ys = y.step
    val ny = ys match {
      case Step(AwaitBoth(rcv), cont) => rcv(HaltR(cause)) +: cont
      case _ => y
    }
    cause.fold(disconnectR(Kill)(ny).swallowKill)(e => disconnectR(e)(ny))

  }

  ////////////////////////////////////////////////////////////////////////
  // Request Algebra
  ////////////////////////////////////////////////////////////////////////

  /** Indicates required request side */
  trait Request

  object Request {
    /** Left side */
    case object L extends Request
    /** Right side */
    case object R extends Request
    /** Both, or Any side */
    case object Both extends Request
  }


  //////////////////////////////////////////////////////////////////////
  // De-constructors and type helpers
  //////////////////////////////////////////////////////////////////////

  type WyeAwaitL[I,I2,O] = Await[Env[I,I2]#Y,Env[I,Any]#Is[I],O]
  type WyeAwaitR[I,I2,O] = Await[Env[I,I2]#Y,Env[Any,I2]#T[I2],O]
  type WyeAwaitBoth[I,I2,O] = Await[Env[I,I2]#Y,Env[I,I2]#Y[ReceiveY[I,I2]],O]

  //correctly typed wye constructors
  def receiveL[I,I2,O](rcv:I => Wye[I,I2,O]) : Wye[I,I2,O] =
    await(L[I]: Env[I,I2]#Y[I])(rcv)

  def receiveLOr[I,I2,O](fb: EarlyCause => Wye[I,I2,O])(rcv:I => Wye[I,I2,O]) : Wye[I,I2,O] =
    awaitOr(L[I]: Env[I,I2]#Y[I])(fb)(rcv)

  def receiveR[I,I2,O](rcv:I2 => Wye[I,I2,O]) : Wye[I,I2,O] =
    await(R[I2]: Env[I,I2]#Y[I2])(rcv)

  def receiveROr[I,I2,O](fb: EarlyCause => Wye[I,I2,O])(rcv:I2 => Wye[I,I2,O]) : Wye[I,I2,O] =
    awaitOr(R[I2]: Env[I,I2]#Y[I2])(fb)(rcv)

  def receiveBoth[I,I2,O](rcv:ReceiveY[I,I2] => Wye[I,I2,O]): Wye[I,I2,O] =
    await(Both[I,I2]: Env[I,I2]#Y[ReceiveY[I,I2]])(rcv)

  def receiveBothOr[I,I2,O](fb:EarlyCause => Wye[I,I2,O] )(rcv:ReceiveY[I,I2] => Wye[I,I2,O]): Wye[I,I2,O] =
    awaitOr(Both[I,I2]: Env[I,I2]#Y[ReceiveY[I,I2]])(fb)(rcv)


  object AwaitL {

    def unapply[I,I2,O](self: WyeAwaitL[I,I2,O]):
    Option[(EarlyCause \/ I => Wye[I,I2,O])] = self match {
      case Await(req,rcv)
        if req.tag == 0 =>
        Some((r : EarlyCause \/ I) =>
          Try(rcv.asInstanceOf[(EarlyCause \/ I) => Trampoline[Wye[I,I2,O]]](r).run)
        )
      case _ => None
    }

    /** Like `AwaitL.unapply` only allows fast test that wye is awaiting on left side */
    object is {
      def unapply[I,I2,O](self: WyeAwaitL[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 0 => true
        case _ => false
      }
    }
  }


  object AwaitR {
    def unapply[I,I2,O](self: WyeAwaitR[I,I2,O]):
    Option[(EarlyCause \/ I2 => Wye[I,I2,O])] = self match {
      case Await(req,rcv)
        if req.tag == 1 => Some((r : EarlyCause \/ I2) =>
        Try(rcv.asInstanceOf[(EarlyCause \/ I2) => Trampoline[Wye[I,I2,O]]](r).run)
      )
      case _ => None
    }

    /** Like `AwaitR.unapply` only allows fast test that wye is awaiting on right side */
    object is {
      def unapply[I,I2,O](self: WyeAwaitR[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 1 => true
        case _ => false
      }
    }
  }
  object AwaitBoth {
    def unapply[I,I2,O](self: WyeAwaitBoth[I,I2,O]):
    Option[(ReceiveY[I,I2] => Wye[I,I2,O])] = self match {
      case Await(req,rcv)
        if req.tag == 2 => Some((r : ReceiveY[I,I2]) =>
        Try(rcv.asInstanceOf[(EarlyCause \/ ReceiveY[I,I2]) => Trampoline[Wye[I,I2,O]]](right(r)).run)
      )
      case _ => None
    }


    /** Like `AwaitBoth.unapply` only allows fast test that wye is awaiting on both sides */
    object is {
      def unapply[I,I2,O](self: WyeAwaitBoth[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 2 => true
        case _ => false
      }
    }

  }

  //////////////////////////////////////////////////////////////////
  // Implementation
  //////////////////////////////////////////////////////////////////

  /**
   * Implementation of wye.
   *
   * @param pl    left process
   * @param pr    right process
   * @param y0    wye to control queueing and merging
   * @param S     strategy, preferably executor service based
   * @tparam L    Type of left process element
   * @tparam R    Type of right process elements
   * @tparam O    Output type of resulting process
   * @return      Process with merged elements.
   */
  def apply[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y0: Wye[L, R, O])(implicit S: Strategy): Process[Task, O] =
    suspend {

      val Left = new Env[L, R].Left
      val Right = new Env[L, R].Right

      sealed trait M
      case class Ready[A](side: Env[L, R]#Y[A], result: Cause \/ (Seq[A], Cont[Task,A])) extends M
      case class Get(cb: (Terminated \/ Seq[O]) => Unit) extends M
      case class DownDone(cb: (Throwable \/ Unit) => Unit) extends M

      type SideState[A] = Either3[Cause, EarlyCause => Unit, Cont[Task,A]]

      //current state of the wye
      var yy: Wye[L, R, O] = y0

      //cb to be completed for `out` side
      var out: Option[(Cause \/ Seq[O])  => Unit] = None

      //forward referenced actor
      var a: Actor[M] = null

      //Bias for reading from either left or right.
      var leftBias: Boolean = true

      // states of both sides
      // todo: resolve when we will get initially "kill"
      def initial[A](p:Process[Task,A]) : Cont[Task,A] = {
        val next = (c:Cause) => c match {
          case End => Trampoline.done(p)
          case e: EarlyCause => Trampoline.done(p.kill)
        }
        Cont(Vector(next))
      }
      var left: SideState[L] = Either3.right3(initial(pl))
      var right: SideState[R] = Either3.right3(initial(pr))

      // runs evaluation of next Seq[A] from either L/R
      // this signals to actor the next step of either left or right side
      // whenever that side is ready (emited Seq[O] or is done.
      def runSide[A](side: Env[L, R]#Y[A])(state: SideState[A]): SideState[A] = state match {
        case Left3(rsn)         => a ! Ready[A](side, -\/(rsn)); state //just safety callback
        case Middle3(interrupt) => state //no-op already awaiting the result  //todo: don't wee nedd a calback there as well.
        case Right3(cont)       => Either3.middle3(cont.continue.runAsync { res => a ! Ready[A](side, res) })
      }

      val runSideLeft = runSide(Left) _
      val runSideRight = runSide(Right) _


      // kills the given side either interrupts the execution
      // or creates next step for the process and then runs killed step.
      // note that this function apart from returning the next state perform the side effects
      def kill[A](side: Env[L, R]#Y[A])(state: SideState[A]): SideState[A] = {
        state match {
          case Middle3(interrupt) =>
            interrupt(Kill)
            Either3.middle3((_: Cause) => ()) //rest the interrupt so it won't get interrupted again

          case Right3(cont) =>
            (Halt(Kill) +: cont).runAsync(_ => a ! Ready[A](side, -\/(Kill)))
            Either3.middle3((_: Cause) => ()) // no-op cleanup can't be interrupted

          case left@Left3(_) =>
            left
        }
      }

      def killLeft = kill(Left) _
      def killRight = kill(Right) _

      //checks if given state is done
      def isDone[A](state: SideState[A]) = state.leftOr(false)(_ => true)


      // halts the open request if wye and L/R are done, and returns None
      // otherwise returns cb
      def haltIfDone(
        y: Wye[L, R, O]
        , l: SideState[L]
        , r: SideState[R]
        , cb: Option[(Cause \/ Seq[O]) => Unit]
        ): Option[(Cause \/ Seq[O]) => Unit] = {
        cb match {
          case Some(cb0) =>
            if (isDone(l) && isDone(r)) {
              y.unemit._2 match {
                case Halt(rsn) =>
                  yy = Halt(rsn)
                  S(cb0(-\/(rsn))); None
                case other     => cb
              }
            } else cb
          case None      => None
        }
      }




      // Consumes any output form either side and updates wye with it.
      // note it signals if the other side has to be killed
      def sideReady[A](
        side: Env[L, R]#Y[A])(
        result: Cause \/ (Seq[A], Cont[Task,A])
        ): (SideState[A], (Cause \/ Seq[A])) = {
        result match {
          case -\/(rsn)        => (Either3.left3(rsn), -\/(rsn))
          case \/-((as, next)) => (Either3.right3(next), \/-(as))
        }
      }

      def sideReadyLeft(
        result: Cause \/ (Seq[L], Cont[Task,L])
        , y: Wye[L, R, O]): Wye[L, R, O] = {
        val (state, input) = sideReady(Left)(result)
        left = state
        input.fold(
          rsn => wye.haltL(rsn)(y)
          , ls => wye.feedL(ls)(y)
        )
      }

      def sideReadyRight(
        result: Cause \/ (Seq[R], Cont[Task,R])
        , y: Wye[L, R, O]): Wye[L, R, O] = {
        val (state, input) = sideReady(Right)(result)
        right = state
        input.fold(
          rsn => wye.haltR(rsn)(y)
          , rs => wye.feedR(rs)(y)
        )
      }

      // interprets a single step of wye.
      // if wye is at emit, it tries to complete cb, if cb is nonEmpty
      // if wye is at await runs either side
      // if wye is halt kills either side
      // returns next state of wye and callback
      def runY(y: Wye[L, R, O], cb: Option[(Cause \/ Seq[O]) => Unit])
      : (Wye[L, R, O], Option[(Cause \/ Seq[O]) => Unit]) = {
        @tailrec
        def go(cur: Wye[L, R, O]): (Wye[L, R, O], Option[(Cause \/ Seq[O]) => Unit]) = {
          cur.step match {
            case Step(Emit(Seq()),cont) =>
              go(cont.continue)

            case Step(Emit(os), cont) =>
              cb match {
                case Some(cb0) => S(cb0(\/-(os))); (cont.continue, None)
                case None      => (cur, None)
              }

            case Step(AwaitL.is(), _) =>
              left = runSideLeft(left)
              leftBias = false
              (cur, cb)

            case Step(AwaitR.is(), _) =>
              right = runSideRight(right)
              leftBias = true
              (cur, cb)

            case Step(AwaitBoth.is(), _) =>
              if (leftBias) {left = runSideLeft(left); right = runSideRight(right) }
              else {right = runSideRight(right); left = runSideLeft(left) }
              leftBias = !leftBias
              (cur, cb)

            case Halt(_) =>
              if (!isDone(left)) left = killLeft(left)
              if (!isDone(right)) right = killRight(right)
              (cur, cb)

          }
        }
        go(y)
      }



      a = Actor[M]({ m =>
        m match {
          case Ready(side, result) =>
            val (y, cb) =
              if (side == Left) {
                val resultL = result.asInstanceOf[(Cause \/ (Seq[L], Cont[Task,L]))]
                runY(sideReadyLeft(resultL, yy), out)
              } else {
                val resultR = result.asInstanceOf[(Cause \/ (Seq[R], Cont[Task,R]))]
                runY(sideReadyRight(resultR, yy), out)
              }
            yy = y
            out = haltIfDone(y, left, right, cb)


          case Get(cb0) =>
            val (y, cb) = runY(yy, Some((r:Cause \/ Seq[O]) => cb0(r.bimap(c=>Terminated(c),identity))))
            yy = y
            out = haltIfDone(y, left, right, cb)

          case DownDone(cb0) =>
            if (!yy.isHalt) {
              val cb1 = Some((r: Cause \/ Seq[O]) => cb0(\/-(())))
              val (y,cb) = runY(disconnectL(Kill)(disconnectR(Kill)(yy)).kill, cb1)
              yy = y
              out = haltIfDone(yy, left, right, cb)
            }
            else S(cb0(\/-(())))
        }
      })(S)

      repeatEval(Task.async[Seq[O]] { cb => a ! Get(cb) })
      .flatMap(emitAll)
      .onComplete(eval_(Task.async[Unit](cb => a ! DownDone(cb))))
    }
}


protected[stream] trait WyeOps[+O] {
  val self: Process[Task, O]

  /**
   * Like `tee`, but we allow the `Wye` to read non-deterministically
   * from both sides at once.
   *
   * If `y` is in the state of awaiting `Both`, this implementation
   * will continue feeding `y` from either left or right side,
   * until either it halts or _both_ sides halt.
   *
   * If `y` is in the state of awaiting `L`, and the left
   * input has halted, we halt. Likewise for the right side.
   *
   * For as long as `y` permits it, this implementation will _always_
   * feed it any leading `Emit` elements from either side before issuing
   * new `F` requests. More sophisticated chunking and fairness
   * policies do not belong here, but should be built into the `Wye`
   * and/or its inputs.
   *
   * The strategy passed in must be stack-safe, otherwise this implementation
   * will throw SOE. Preferably use one of the `Strategys.Executor(es)` based strategies
   */
  final def wye[O2, O3](p2: Process[Task, O2])(y: Wye[O, O2, O3])(implicit S: Strategy): Process[Task, O3] =
    scalaz.stream.wye[O, O2, O3](self, p2)(y)(S)

  /** Non-deterministic version of `zipWith`. Note this terminates whenever one of streams terminate */
  def yipWith[O2,O3](p2: Process[Task,O2])(f: (O,O2) => O3)(implicit S:Strategy): Process[Task,O3] =
    self.wye(p2)(scalaz.stream.wye.yipWith(f))

  /** Non-deterministic version of `zip`. Note this terminates whenever one of streams terminate */
  def yip[O2](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,(O,O2)] =
    self.wye(p2)(scalaz.stream.wye.yip)

  /** Non-deterministic interleave of both streams.
    * Emits values whenever either is defined. Note this terminates after BOTH sides terminate */
  def merge[O2>:O](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,O2] =
    self.wye(p2)(scalaz.stream.wye.merge)

  /** Non-deterministic interleave of both streams. Emits values whenever either is defined.
    * Note this terminates after BOTH sides terminate  */
  def either[O2>:O,O3](p2: Process[Task,O3])(implicit S:Strategy): Process[Task,O2 \/ O3] =
    self.wye(p2)(scalaz.stream.wye.either)
}
