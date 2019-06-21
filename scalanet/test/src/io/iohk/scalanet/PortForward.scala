package io.iohk.scalanet

/**
  * This program is an example from the book "Internet
  * programming with Java" by Svetlin Nakov. It is freeware.
  * For more information: http://www.nakov.com/books/inetjava/
  */
import java.io._
import java.net._

import PortForward._

/**
  * TCPForwardServer is a simple TCP bridging software that
  * allows a TCP port on some host to be transparently forwarded
  * to some other TCP port on some other host. TCPForwardServer
  * continuously accepts client connections on the listening TCP
  * port (source port) and starts a thread (ClientThread) that
  * connects to the destination host and starts forwarding the
  * data between the client socket and destination socket.
  */
class PortForward(sourcePort: Int, dest: InetSocketAddress) {

  private val serverSocket: ServerSocket = new ServerSocket(sourcePort)

  def start(): Unit = {
    while (true) {
      val clientSocket: Socket = serverSocket.accept()
      val clientThread: ClientThread = new ClientThread(clientSocket, dest.getHostName, dest.getPort)
      clientThread.start()
    }
  }

  def stop(): Unit = {
    println("stopping the forward")
    serverSocket.close()
  }
}

object PortForward {

  /**
    * ClientThread is responsible for starting forwarding between
    * the client and the server. It keeps track of the client and
    * servers sockets that are both closed on input/output error
    * durinf the forwarding. The forwarding is bidirectional and
    * is performed by two ForwardThread instances.
    */
  class ClientThread(clientSocket: Socket, destHost: String, destPort: Int) extends Thread {

    private var forwardingActive: Boolean = false

    /**
      * Establishes connection to the destination server and
      * starts bidirectional forwarding ot data between the
      * client and the server.
      */
    override def run(): Unit = {

      // Connect to the destination server
      val serverSocket = new Socket(destHost, destPort)

      try {

        // Turn on keep-alive for both the sockets
        serverSocket.setKeepAlive(true);
        clientSocket.setKeepAlive(true);

        // Obtain client & server input & output streams
        val clientIn = clientSocket.getInputStream
        val clientOut = clientSocket.getOutputStream
        val serverIn = serverSocket.getInputStream
        val serverOut = serverSocket.getOutputStream

        // Start forwarding data between server and client
        forwardingActive = true
        val clientForward: ForwardThread = new ForwardThread(this, clientIn, serverOut)
        clientForward.start()

        val serverForward: ForwardThread = new ForwardThread(this, serverIn, clientOut)
        serverForward.start()

        println(
          "TCP Forwarding " +
            clientSocket.getInetAddress.getHostAddress +
            ":" + clientSocket.getPort + " <--> " +
            serverSocket.getInetAddress.getHostAddress +
            ":" + serverSocket.getPort + " started."
        );

      } catch {
        case _: IOException =>
          println(
            "TCP Forwarding " +
              clientSocket.getInetAddress.getHostAddress
              + ":" + clientSocket.getPort + " <--> " +
              serverSocket.getInetAddress.getHostAddress
              + ":" + serverSocket.getPort + " stopped."
          )
          forwardingActive = false
          serverSocket.close()
          clientSocket.close()
      }
    }
  }

  /**
    * ForwardThread handles the TCP forwarding between a socket
    * input stream (source) and a socket output stream (dest).
    * It reads the input stream and forwards everything to the
    * output stream. If some of the streams fails, the forwarding
    * stops and the parent is notified to close all its sockets.
    */
  class ForwardThread(parent: ClientThread, inputStream: InputStream, outputStream: OutputStream) extends Thread {

    val BUFFER_SIZE: Int = 8192

    /**
      * Runs the thread. Continuously reads the input stream and
      * writes the read data to the output stream. If reading or
      * writing fail, exits the thread and notifies the parent
      * about the failure.
      */
    override def run(): Unit = {
      val buffer: Array[Byte] = new Array(BUFFER_SIZE)
      try {
        var bytesRead: Int = inputStream.read(buffer)
        while (bytesRead != -1) {
          outputStream.write(buffer, 0, bytesRead)
          outputStream.flush()
          bytesRead = inputStream.read(buffer)
        }
      } catch {
        case ioe: IOException => ???
      }
    }
  }
}
