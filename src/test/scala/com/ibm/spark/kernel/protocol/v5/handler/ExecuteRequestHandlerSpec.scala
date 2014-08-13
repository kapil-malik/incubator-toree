package com.ibm.spark.kernel.protocol.v5.handler

import akka.actor.{Actor, ActorSelection, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.content._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpecLike, Matchers}
import play.api.libs.json.Json

import scala.concurrent.duration._


class ExecuteRequestHandlerSpec extends TestKit(
  ActorSystem("ExecuteRequestHandlerSpec")
) with ImplicitSender with FunSpecLike with Matchers with MockitoSugar {

  // Mocked interpreter that responds to an ExecuteRequest with an
  // ExecuteReply ExecuteResult tuple
  class MockInterpreterActor extends Actor {
    override def receive: Receive = {
      case message: ExecuteRequest =>
        val executeReply = new ExecuteReply("ok", 1, None, None, None, None, None)
        val executeResult = new ExecuteResult(1, Data(), Metadata())
        sender ! ((executeReply, executeResult))
    }
  }

  // Construct a execute request kernel message for the handler
  val header = Header("", "", "", "", "")
  val executeRequest: ExecuteRequest = ExecuteRequest("spark code", false, true, Map(), false)
  val kernelMessage = new KernelMessage(
    Seq[String](), "test message", header, header, Map[String, String](), Json.toJson(executeRequest).toString
  )
  val kernelMessageWithBadExecuteRequest = new KernelMessage(
    Seq[String](), "test message", header, header, Map[String, String](),
      """
        {"code" : 124 }
      """
  )

  val executeReply = new KernelMessage(
  List("status"), "", Header("","","","execute_reply",""), header, Map[String, String](), """
    {
    "status": "error",
    "execution_count": 1,
    "ename": "akka.pattern.AskTimeoutException",
    "evalue": "Timed out",
    "traceback":[]}"""
  )

  describe("ExecuteRequestHandler( ActorLoader )") {
    val actorLoader : ActorLoader = mock[ActorLoader]

    // Add our handler and mock interpreter to the actor system
    val handlerActor = system.actorOf(Props(classOf[ExecuteRequestHandler], actorLoader))

    val probe : TestProbe = TestProbe()
    val mockSelection: ActorSelection = system.actorSelection(probe.ref.path.toString)
    when(actorLoader.load(SystemActorType.KernelMessageRelay)).thenReturn(mockSelection)

    val executeRequestRelayProbe: TestProbe = new TestProbe(system)
    val mockExecuteRequestRelaySelection: ActorSelection = system.actorSelection(executeRequestRelayProbe.ref.path.toString)
    when(actorLoader.load(SystemActorType.ExecuteRequestRelay)).thenReturn(mockExecuteRequestRelaySelection)

    // NOTE: This is a bad test design because it depends on the messages being
    //       sent back in a specific order from the ExecuteRequestHandler as
    //       well as the tests below being run in sequential order.
    //
    // TODO: Refactor tests such that you send a message within each it { ... }
    //       and check for a specific message to be returned in the batch of
    //       returned messages
    describe("#receive( KernelMessage )") {
      // Send message to the handler
      handlerActor ! kernelMessage
      executeRequestRelayProbe.expectMsgClass(classOf[ExecuteRequest])
      executeRequestRelayProbe.reply((
        ExecuteReplyOk(1, Some(Payloads()), Some(UserExpressions())),
        ExecuteResult(1, Data("text/plain" -> ""), Metadata())
      ))

      it("should send a busy status message to the relay") {
        //  KernelStatus = busy
        val kernelBusyReply = probe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val busyStatus = Json.parse(kernelBusyReply.contentString).as[KernelStatus]
        busyStatus.execution_state should be("busy")
      }

      it("should send execute_input message to the relay") {
        //  ExecuteInput
        val kernelExecuteInputReply = probe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val input = Json.parse(kernelExecuteInputReply.contentString).as[ExecuteInput]
        input.code should be("spark code")
      }

      it("should send an ok execute_reply message to the relay") {
        // Interpreter should respond with tuple
        val kernelExecuteReply = probe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val reply = Json.parse(kernelExecuteReply.contentString).as[ExecuteReply]
        reply.status should be("ok")
      }

      it("should send an execute_result message to the relay") {
        val kernelExecuteResult = probe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val result = Json.parse(kernelExecuteResult.contentString).as[ExecuteResult]
        result.execution_count should be(1)
      }

      it("should send an idle status message to the relay") {
        //  KernelStatus = idle
        val kernelIdleReply = probe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val idleStatus = Json.parse(kernelIdleReply.contentString).as[KernelStatus]
        idleStatus.execution_state should be("idle")
      }
    }
  }

  //  Testing error timeout for interpreter future
  describe("ExecuteRequestHandler( ActorLoader )") {
    val actorLoader: ActorLoader = mock[ActorLoader]

    // Add our handler and mock interpreter to the actor system
    val handlerActor = system.actorOf(Props(classOf[ExecuteRequestHandler], actorLoader))

    val kernelMessageRelayProbe: TestProbe = new TestProbe(system)
    val mockSelection: ActorSelection = system.actorSelection(kernelMessageRelayProbe.ref.path.toString)
    when(actorLoader.load(SystemActorType.KernelMessageRelay)).thenReturn(mockSelection)

    val executeRequestRelayProbe: TestProbe = new TestProbe(system)
    val mockExecuteRequestRelaySelection: ActorSelection = system.actorSelection(executeRequestRelayProbe.ref.path.toString)
    when(actorLoader.load(SystemActorType.ExecuteRequestRelay)).thenReturn(mockExecuteRequestRelaySelection)

    // Send message to the handler
    handlerActor ! kernelMessage
    describe("#receive( KernelMessage )") {
      it("should send a busy status message to the ") {
        //  KernelStatus = busy
        val kernelBusyReply = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val busyStatus = Json.parse(kernelBusyReply.contentString).as[KernelStatus]
        busyStatus.execution_state should be("busy")
      }

      it("should send execute_input message to the ") {
        //  ExecuteInput
        val kernelExecuteInputReply = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val input = Json.parse(kernelExecuteInputReply.contentString).as[ExecuteInput]
        input.code should be("spark code")
      }

      it("should send an ExecuteRequest to ExecuteRequestRelay") {
        executeRequestRelayProbe.expectMsg(executeRequest)
      }


      var executeReplyErrorName: String = null
      var executeReplyErrorValue: String = null
      var executeReplyErrorTraceback: List[String] = null
      it("interpreter should not reply, and future times out") {
        //  We need a little longer timeout here because the future itself has a timeout
        val executeReplyMessage: KernelMessage = kernelMessageRelayProbe.receiveOne(3.seconds).asInstanceOf[KernelMessage]
        val replyContent = Json.parse(executeReplyMessage.contentString).as[ExecuteReply]
        //  Make sure we have the correct message type
        executeReplyMessage.header.msg_type should be (MessageType.ExecuteReply.toString)
        //  Make sure it is an error
        replyContent.status should be("error")

        //  Get the values to compare with the error message below
        executeReplyErrorName = replyContent.ename.get
        executeReplyErrorValue = replyContent.evalue.get
        executeReplyErrorTraceback = replyContent.traceback.get
      }

      it("should send error message to relay") {
        val errorContentMessage = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val errorContent = Json.parse(errorContentMessage.contentString).as[ErrorContent]
        errorContent.ename should not be(null)
        //  Check the error messages are the same
        executeReplyErrorName should be(errorContent.ename)
        executeReplyErrorValue should be(errorContent.evalue)
        executeReplyErrorTraceback should be(errorContent.traceback)
      }

      it("should send idle status to relay") {
        val kernelBusyReply = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val busyStatus = Json.parse(kernelBusyReply.contentString).as[KernelStatus]
        busyStatus.execution_state should be("idle")
      }
    }
  }

  //  Testing error timeout for interpreter future
  describe("ExecuteRequestHandler( ActorLoader )") {
    val actorLoader: ActorLoader = mock[ActorLoader]

    // Add our handler and mock interpreter to the actor system
    val handlerActor = system.actorOf(Props(classOf[ExecuteRequestHandler], actorLoader))

    val kernelMessageRelayProbe: TestProbe = new TestProbe(system)
    val mockSelection: ActorSelection = system.actorSelection(kernelMessageRelayProbe.ref.path.toString)
    when(actorLoader.load(SystemActorType.KernelMessageRelay)).thenReturn(mockSelection)

    handlerActor ! kernelMessageWithBadExecuteRequest
    describe("#receive( KernelMessage with bad JSON content )"){
      var executeReplyErrorName: String = null
      var executeReplyErrorValue: String = null
      var executeReplyErrorTraceback: List[String] = null
      it("should respond with an execute_reply with status error")    {
        val badExecuteReplyMessage: KernelMessage = kernelMessageRelayProbe.receiveOne(1.seconds).asInstanceOf[KernelMessage]
        val badReplyContent = Json.parse(badExecuteReplyMessage.contentString).as[ExecuteReply]
        badExecuteReplyMessage.header.msg_type should be (MessageType.ExecuteReply.toString)
        badReplyContent.status should be("error")

        //  Get the values to compare with the error message below
        executeReplyErrorName = badReplyContent.ename.get
        executeReplyErrorValue = badReplyContent.evalue.get
        executeReplyErrorTraceback = badReplyContent.traceback.get
      }

      it("should send error message to relay") {
        val errorContentMessage = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val errorContent = Json.parse(errorContentMessage.contentString).as[ErrorContent]
        errorContent.ename should not be(null)

        //  Check the error messages are the same
        executeReplyErrorName should be(errorContent.ename)
        executeReplyErrorValue should be(errorContent.evalue)
        executeReplyErrorTraceback should be(errorContent.traceback)
      }

      it("should send idle message to relay") {
        val kernelBusyReply = kernelMessageRelayProbe.receiveOne(1.second).asInstanceOf[KernelMessage]
        val busyStatus = Json.parse(kernelBusyReply.contentString).as[KernelStatus]
        busyStatus.execution_state should be("idle")
      }
    }
  }

}
