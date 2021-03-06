package org.http4s
package server

import scalaz._
import scalaz.concurrent.Task
import Service._

/**
 * A service is an asynchronous function from a request to an optional response.  It wraps
 * a function `A => Task[Option[B]]` with useful methods to deal with both the asynchronicity
 * and the optionality.
 *
 * @param run the underlying function
 * @tparam A the request type
 * @tparam B the response type
 */
final class Service[A, B] private (val run: A => Task[Option[B]]) extends AnyVal {
  /**
   * Returns an asynchronous, optional response to a request
   */
  def apply(a: A): Task[Option[B]] = run(a)

  /**
   * Like apply, but returns a monad transformer.
   */
  def runT(a: A): OptionT[Task, B] = OptionT.optionT(apply(a))

  def contramap[C](f: C => A): Service[C, B] = lift(run.compose(f))

  def map[C](f: B => C): Service[A, C] = lift(run.andThen(_.map(_.map(f))))

  def flatMapTask[C](f: B => Task[Option[C]]): Service[A, C] = lift(run.andThen(_.flatMap {
    case Some(b) => f(b)
    case None    => TaskNone
  }))

  // The Monadic 'bind', >>=
  def flatMap[C](f: B => Service[A, C]): Service[A, C] = lift(a => run(a).flatMap {
    case Some(b) => f(b).run.apply(a)
    case None    => TaskNone
  })

  /**
   * Returns a response from this service if defined, or else returns
   * the supplied default response.
   */
  def or[B1 >: B](a: A, default: => Task[B1]): Task[B1] = apply(a).flatMap {
    case Some(b) => Task.now(b)
    case None => default
  }

  /**
   * Composes this service with a fallback service, which is invoked
   * where this service is not defined.
   */
  def orElse[A1 <: A, B1 >: B](other: Service[A1, B1]): Service[A1, B1] = {
    Service.lift { a :A1 =>  run(a).flatMap {
      case r@ Some(_) => Task.now(r)
      case None       => other(a)
    }}
  }
}

object Service {
  /**
   * Lifts a function to a service.
   */
  def lift[A, B](f: A => Task[Option[B]]): Service[A, B] = new Service[A, B](f)

  private[http4s] val TaskNone = Task.now(None)

  /**
   * Lifts a partial function to a service.  The resulting service will
   * return a Task containing `None` where the partial function is not
   * defined, and Some result where it is.
   */
  def apply[A, B](pf: PartialFunction[A, Task[B]]): Service[A, B] = lift {
    pf.lift.andThen {
      case Some(respTask) => respTask.map(Some(_))
      case None => TaskNone
    }
  }

  /**
   * The empty service.  Returns a Task of `None` for all inputs.
   */
  def empty[A, B]: Service[A, B] = lift(Function.const(TaskNone))

  implicit def serviceInstance[A]: Nondeterminism[({type λ[α] = Service[A,α]})#λ]
                                    with Catchable[({type λ[α] = Service[A,α]})#λ]
                                    with MonadError[({type λ[α,β] = Service[A,β]})#λ,Throwable] =
    new Nondeterminism[({type λ[α] = Service[A,α]})#λ]
      with Catchable[({type λ[α] = Service[A,α]})#λ]
      with MonadError[({type λ[α,β] = Service[A,β]})#λ,Throwable] {

      override def chooseAny[B](head: Service[A, B], tail: Seq[Service[A, B]]): Service[A, (B, Seq[Service[A, B]])] = Service.lift { a =>

        def sorter(h: Option[B], tails: Seq[Task[Option[B]]]): Task[Option[(B, Seq[Task[Option[B]]])]] = h match {
          case Some(v) => Task.now(Some((v, tails)))
          case None if tails.isEmpty => Task.now(None)
          case None => tails.head.flatMap(sorter(_, tails.tail))
        }

        Task.taskInstance.chooseAny(head(a), tail.map(_.apply(a))).flatMap {
          case (h, ts) => sorter(h, ts).map(_.map {
            case (h, ts) => (h, ts.map(r => Service.lift[A,B](_ => r)))
          })
        }
      }

      override def fail[B](err: Throwable): Service[A, B] = Service.lift(_ => Task.taskInstance.fail(err))

      override def attempt[B](f: Service[A, B]): Service[A, \/[Throwable, B]] = Service.lift { a =>
        f(a).attempt.map {
          case \/-(Some(b)) => Some(\/-(b))
          case \/-(None)    => None
          case e @ -\/(_)   => Some(e)
        }
      }

      override def raiseError[B](e: Throwable): Service[A, B] = Service.lift(_ => Task.taskInstance.raiseError(e))

      override def handleError[B](fa: Service[A, B])(f: (Throwable) => Service[A, B]): Service[A, B] = Service.lift { a =>
        Task.taskInstance.handleError(fa(a))(f.andThen(_.run(a)))
      }

      override def point[B](a: => B): Service[A, B] = Service.lift(_ => Task.taskInstance.point(Some(a)))

      override def bind[B, C](fa: Service[A, B])(f: B => Service[A, C]): Service[A, C] = fa.flatMap(f)
    }
}
