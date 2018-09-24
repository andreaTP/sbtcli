package org.akkajs.sbt

import utest._
import scala.scalajs.js

object SocketClientTests extends TestSuite {

  def jsonMsg(id: Int) =
    "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"status\":\"Done\",\"channelName\":\"network-1\",\"execId\":1,\"commandQueue\":[\"shell\"],\"exitCode\":0}}"

  def LSPMessage(id: Int) =
    s"""Content-Type: application/vscode-jsonrpc; charset=utf-8
       |Content-Length: 126
       |
       |${jsonMsg(id)}
       |""".stripMargin

  val mockSocket = {
    val sock = js.Dynamic.literal()
    sock.on = () => {}
    sock.asInstanceOf[com.definitelyscala.node.net.Socket]
  }

  val tests = Tests {
    'parse_LSP_messages - {
      val sc = new SocketClient(mockSocket) {
        var results: Seq[js.Dynamic] = Seq()

        override def onMessage(msg: js.Dynamic) = {
          results = results :+ msg
        }
      }

      sc.buffer = LSPMessage(1)

      sc.parseBuffer()

      val result = sc.results.head
      val msg = js.JSON.parse(jsonMsg(1))

      assert(result.id == msg.id)
      assert(result.result.status == msg.result.status)
      assert(result.result.channelName == msg.result.channelName)
      assert(result.result.exitCode == msg.result.exitCode)
    }

    'parse_LSP_messages_with_junk - {
      val sc = new SocketClient(mockSocket) {
        var results: Seq[js.Dynamic] = Seq()

        override def onMessage(msg: js.Dynamic) = {
          results = results :+ msg
        }
      }

      sc.buffer = "junk characters here.... \n\r\t ...." + LSPMessage(1)

      sc.parseBuffer()

      val result = sc.results.head
      val msg1 = js.JSON.parse(jsonMsg(1))

      assert(result.id == msg1.id)
    }

    'parse_multiple_LSP_messages - {
      val sc = new SocketClient(mockSocket) {
        var results: Seq[js.Dynamic] = Seq()

        override def onMessage(msg: js.Dynamic) = {
          results = results :+ msg
        }
      }

      sc.buffer = LSPMessage(1) + LSPMessage(2) + LSPMessage(3)

      sc.parseBuffer()

      val msg1 = js.JSON.parse(jsonMsg(1))
      val msg2 = js.JSON.parse(jsonMsg(2))
      val msg3 = js.JSON.parse(jsonMsg(3))

      assert(sc.results.size == 3)
      assert(sc.results(0).id == msg1.id)
      assert(sc.results(1).id == msg2.id)
      assert(sc.results(2).id == msg3.id)
    }

    'parse_multiple_LSP_messages_in_multiple_phases - {
      val sc = new SocketClient(mockSocket) {
        var results: Seq[js.Dynamic] = Seq()

        override def onMessage(msg: js.Dynamic) = {
          results = results :+ msg
        }
      }

      val secondMessage = LSPMessage(2)

      sc.buffer = LSPMessage(1) + secondMessage.take(20)

      sc.parseBuffer()

      assert(sc.results.size == 1)

      sc.buffer = sc.buffer + secondMessage.drop(20)

      sc.parseBuffer()

      val msg1 = js.JSON.parse(jsonMsg(1))
      val msg2 = js.JSON.parse(jsonMsg(2))

      assert(sc.results.size == 2)
      assert(sc.results(0).id == msg1.id)
      assert(sc.results(1).id == msg2.id)
    }
  }
}
