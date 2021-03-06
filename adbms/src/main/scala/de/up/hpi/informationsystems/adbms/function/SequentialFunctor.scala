package de.up.hpi.informationsystems.adbms.function

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Props}
import de.up.hpi.informationsystems.adbms.protocols.RequestResponseProtocol.{Message, Request, Success}

import scala.reflect.ClassTag


object SequentialFunctor {

  /** Convenience type for a sequential functor step tuple consisting of mapping function and receiver actor refs. */
  private sealed trait Step[M1, M2] {
    def mapping: M1 => M2
    def recipients: Seq[ActorSelection]
  }

  /** Encapsulates the start step of a sequential functor consisting of the mapping from a request to another
    * request and a list of recipients.
    *
    * @param mapping    function mapping the start message of type `A` to a new request message
    * @param recipients list of recipients' actor references
    * @tparam A         type of the start message
    */
  private case class StartStep[A <: Request[_]: ClassTag](mapping: A => Request[_ <: Message], recipients: Seq[ActorSelection])
    extends Step[A, Request[_ <: Message]]

  /** Encapsulates an intermediate step of a sequential functor.
    *
    * @param mapping    maps a success message of message type `A` to a new request of message type `B`
    * @param recipients list of recipients' actor references
    * @tparam A         message type for success message
    * @tparam B         message type for new request
    */
  private case class IntermediateStep[A <: Message, B <: Message](mapping: Success[A] => Request[B], recipients: Seq[ActorSelection])
    extends Step[Success[A], Request[B]] {

    /** Casts this IntermediateStep into a more generic one using the upper type bound
      * [[de.up.hpi.informationsystems.adbms.protocols.RequestResponseProtocol.Message]] for both messages.
      *
      * @return
      */
    def toUpperBound: IntermediateStep[Message, Message] = this.asInstanceOf[IntermediateStep[Message, Message]]
  }

  /** Convenience type alias for generic intermediate steps using upper type bound. */
  private type IntermediateStepT = IntermediateStep[Message, Message]

  private object EndStep {
    def apply[T1 <: Message, T2 <: Message](mapping: Success[T1] => Success[T2]): EndStep[Success[T2]] =
      new EndStep(mapping.asInstanceOf[Success[Message] => Success[T2]])
  }

  /** Encapsulates the last step (end) of a sequential functor consisting of a mapping from one success
    * message type to another.
    *
    * @param mapping maps a success message to another success message with message type `B`
    * @tparam B      message type for the new success message (result of the sequential functor)
    */
  private class EndStep[B <: Success[_]: ClassTag](override val mapping: Success[Message] => B)
    extends Step[Success[Message], B] {
    // last step does not have any recipients as the result is sent to the creator of the message
    override def recipients: Seq[ActorSelection] = Seq.empty
  }

  /** Creates a new SequentialFunctionBuilder.
    *
    * This builder requires the definition of a start and end function to return a valid
    * [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SequentialFunctorDef]] that can be run using a
    * [[de.up.hpi.informationsystems.adbms.Dactor]]'s `startSequentialFunction` method or the `Dactor` companion
    * object's method of the same name. The `SequentialFunctionDef` is started with a `Request` message that can be
    * transformed and send to a list of recipients given as a `Seq` of `ActorSelection`. The function sends the
    * transformed request to all corresponding recipients, collects and aggregates their response contents and forwards
    * a corresponsing `Success` message to the next function. The `end` function defines a final transformation of a received
    * `Success` message before sending it back to the `Actor` which started the function.
    *
    * @example {{{
    * // sequential functor definition in Dactor
    * private val complexQuestionFunctor: SequentialFunctorDef[ Request[SomeMessage], Success[OtherMessage] ] =
    *   SeqFunctor()
    *     .start( (req: Success[SomeMessage]) => req, Seq(recipient1) )
    *     .next( (res: Success[SomeMessage]) => {
    *       // do something and create new request
    *       val req: Request[OtherMessage] = YetAnotherMessage.Req(someParam)
    *       req
    *     }, Seq(recipient2, recipient3))
    *     .end( (res: Success[OtherMessage]) => {
    *       // transform response before sending it back to this dactor
    *       val newRes: Success[YetAnotherMessage] = YetAnotherMessage.Success(relation)
    *       newRes
    *     })
    *
    * // Instantiate sequential functor to answer request in receive
    * override def receive: Receive = {
    *   case message: ComplexQuestion.Request(_) => {
    *     startSequentialFunctor(complexQuestionFunctor)(message)
    *     context.become(awaitingComplexQuestionResponse)
    *   }
    * }
    *
    * def awaitingComplexQuestionResponse: Receive = {
    *   case message: YetAnotherMessage.Success =>
    *     // do something with the result
    * }
    * }}}
    * @note   this should always be used with [[de.up.hpi.informationsystems.adbms.protocols.RequestResponseProtocol]]
    *         subclasses
    * @return a new [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilder]]
    */
  def apply(): SeqFunctorBuilderBare = new SeqFunctorBuilderBare()

  /** Builder for [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SequentialFunctorDef]]s, which
    * can be instantiated into [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor]]s.
    *
    * The builder's start, next, and end methods can be used to define the steps for the sequential functor.
    *
    * @see [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderBare#start]]
    * @see [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#next]]
    * @see [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#end]]
    */
  sealed trait SeqFunctorBuilder

  /** @inheritdoc */
  class SeqFunctorBuilderBare extends SeqFunctorBuilder {

    /** Defines the first step of the sequential functor using a mapping function `start` to create a new request
      * that gets sent to all `recipients`.
      *
      * @param start      mapping function, creates a new request for message type `M`
      * @param recipients recipient list
      * @tparam S         start message type, received by the sequential functor
      * @tparam M         first sent message type
      * @return           a new [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilder]]
      */
    def start[S <: Request[_]: ClassTag, M <: Message]
             (start: S => Request[M], recipients: Seq[ActorSelection]): SeqFunctorBuilderStarted[S, M] =
      new SeqFunctorBuilderStarted[S, M](StartStep(start, recipients), Seq.empty)
  }

  /** @inheritdoc */
  class SeqFunctorBuilderStarted[A <: Request[_]: ClassTag, M <: Message]
                                        (start: StartStep[A], steps: Seq[IntermediateStepT])
    extends SeqFunctorBuilder {

    /** Defines a new step of the sequential functor.
      *
      * Calling this method multiple times will result in a list of sequential steps.
      * <em>Call Order Matters!</em>
      *
      * @param transform  mapping function, transforming the received result into a new request message
      * @param recipients recipient list
      * @tparam N         message type of the next request
      * @return           a new [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilder]]
      */
    def next[N <: Message]
            (transform: Success[M] => Request[N], recipients: Seq[ActorSelection]): SeqFunctorBuilderStarted[A, N] =
      new SeqFunctorBuilderStarted[A, N](start, steps :+ IntermediateStep(transform, recipients).toUpperBound)

    /** Concludes the definition of the sequential functor with a last transformation of the result message.
      *
      * Use `end(identity)` to send the same result message back to the creator of the sequential functor.
      *
      * @param end mapping function, transforming the result message to another format
      * @tparam E  message type of the last success message, received by the creator of the sequential functor
      *            containing the result
      * @return    the sequential functor definition
      */
    def end[E <: Message]
           (end: Success[M] => Success[E]): SequentialFunctorDef[A, Success[E]] =
      new SequentialFunctorDef[A, Success[E]](start, steps, EndStep(end))
  }

  /** Definition of a sequential functor.
    *
    * Instances of this class are created using a builder-pattern implemented by
    * [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor#apply]] and
    * [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilder]]
    * Use the resulting sequential functor definition to create a sequential functor actor instance
    * to handle complex message flows, e.g.:
    *
    * @example {{{
    * implicit val sender: ActorRef = _
    * val startMessage: Request[SomeMessage] = _
    *
    * val complexFunctor: SequentialFunctorDef[ Request[SomeMessage], Success[OtherMessage] ] = _
    * Dactor.startSequentialFunctor(complexFunctor)(startMessage)
    * }}}
    * @param start start mapping function, see
    *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderBare#start]]
    * @param steps intermediate mapping functions, see
    *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#next]]
    * @param end   end mapping function, see
    *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#end]]
    * @tparam S    message type of the start message
    * @tparam E    message type of the result message
    */
  class SequentialFunctorDef[S <: Request[_]: ClassTag, E <: Success[_]: ClassTag] private[SequentialFunctor]
  (start: StartStep[S], steps: Seq[IntermediateStepT], end: EndStep[E]) {

    /** Returns the Akka actor properties needed to create a function instance (actor).
      *
      * @return the Akka actor properties
      */
    def props: Props = Props(new SequentialFunctor[S, E](start, steps, end))
  }
}

/** Implementation of a sequential functor as an Akka actor.
  *
  * <br/><b>INTERNAL API</b>
  *
  * @param start start mapping function, see
  *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderBare#start]]
  * @param steps intermediate mapping functions, see
  *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#next]]
  * @param end   end mapping function, see
  *              [[de.up.hpi.informationsystems.adbms.function.SequentialFunctor.SeqFunctorBuilderStarted#end]]
  * @tparam S    message type of the start message
  * @tparam E    message type of the result message
  */
private[adbms] class SequentialFunctor[S <: Request[_]: ClassTag, E <: Success[_]: ClassTag]
                        (start: SequentialFunctor.StartStep[S],
                         steps: Seq[SequentialFunctor.IntermediateStepT],
                         end: SequentialFunctor.EndStep[E]) extends Actor with ActorLogging {

  override def receive: Receive = startReceive()

  def startReceive(): Receive = {
    case startMessage: S =>
      log.debug("Processing start step and waiting for responses")
      val request = start.mapping(startMessage)
      start.recipients foreach { _ ! request }
      val backTo = sender()
      context.become(awaitResponsesReceive(start.recipients.length, Seq.empty, steps, backTo))
  }

  def awaitResponsesReceive(totalResponses: Int,
                            receivedResponses: Seq[Success[Message]],
                            pendingSteps: Seq[SequentialFunctor.IntermediateStepT],
                            backTo: ActorRef): Receive = {
    case message: Success[_] =>
      if ((totalResponses - (receivedResponses :+ message).length) > 0) {
        context.become(awaitResponsesReceive(totalResponses, receivedResponses :+ message, pendingSteps, backTo))
      } else {
        log.debug("Received all pending responses")

        val constructor = message.getClass.getConstructors()(0)
        val unionResponse = constructor.newInstance((receivedResponses :+ message).map(_.result).reduce(_ union _))

        pendingSteps.headOption match {
          case None =>
            self ! unionResponse
            context.become(endReceive(backTo))
          case Some(nextStep) =>
            self ! unionResponse
            context.become(nextReceive(nextStep.mapping, nextStep.recipients, pendingSteps.drop(1), backTo))
        }
      }
  }

  def nextReceive(currentFunction: Success[Message] => Request[Message],
                  currentRecipients: Seq[ActorSelection],
                  pendingSteps: Seq[SequentialFunctor.IntermediateStepT],
                  backTo: ActorRef): Receive = {
    case message: Success[Message] =>
      log.debug("Processing next step and waiting for responses")
      val request = currentFunction(message)
      currentRecipients foreach { _ ! request }
      context.become(awaitResponsesReceive(currentRecipients.length, Seq.empty, pendingSteps, backTo))
  }

  def endReceive(backTo: ActorRef): Receive = {
    case message: Success[Message] =>
      log.debug(s"Processing end step and stopping this ${this.getClass.getSimpleName}")
      backTo ! end.mapping(message)
      context.stop(self)
  }
}

