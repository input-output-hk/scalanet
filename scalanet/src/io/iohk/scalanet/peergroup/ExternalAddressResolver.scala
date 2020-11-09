package io.iohk.scalanet.peergroup

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, URL}

import monix.eval.Task
import scala.util.control.NonFatal

/** Resolve the external address based on a list of URLs that each return the IP of the caller. */
class ExternalAddressResolver(urls: List[String]) {
  def resolve: Task[Option[InetAddress]] =
    ExternalAddressResolver.checkUrls(urls)
}

object ExternalAddressResolver {
  val default = new ExternalAddressResolver(List("http://checkip.amazonaws.com", "http://bot.whatismyipaddress.com"))

  /** Retrieve the external address from a URL that returns a single line containing the IP. */
  def checkUrl(url: String): Task[InetAddress] = Task.async { cb =>
    try {
      val ipCheckUrl = new URL(url)
      val in: BufferedReader = new BufferedReader(new InputStreamReader(ipCheckUrl.openStream()))
      cb.onSuccess(InetAddress.getByName(in.readLine()))
    } catch {
      case NonFatal(ex) => cb.onError(ex)
    }
  }

  /** Try multiple URLs until an IP address is found. */
  def checkUrls(urls: List[String]): Task[Option[InetAddress]] = {
    if (urls.isEmpty) {
      Task.now(None)
    } else {
      checkUrl(urls.head).attempt.flatMap {
        case Left(_) =>
          checkUrls(urls.tail)
        case Right(value) =>
          Task.now(Some(value))
      }
    }
  }
}
