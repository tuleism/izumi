package com.github.pshirshov.izumi.idealingua.runtime.rpc.http4s

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import cats.data.{Kleisli, OptionT}
import cats.effect._
import com.github.pshirshov.izumi.fundamentals.platform.network.IzSockets
import com.github.pshirshov.izumi.idealingua.runtime.bio.BIO
import com.github.pshirshov.izumi.idealingua.runtime.bio.BIO._
import com.github.pshirshov.izumi.idealingua.runtime.rpc._
import com.github.pshirshov.izumi.logstage.api.routing.StaticLogRouter
import com.github.pshirshov.izumi.logstage.api.{IzLogger, Log}
import com.github.pshirshov.izumi.r2.idealingua.test.generated.{GreeterServiceClientWrapped, GreeterServiceServerWrapped}
import com.github.pshirshov.izumi.r2.idealingua.test.impls._
import org.http4s._
import org.http4s.headers.Authorization
import org.http4s.server.AuthMiddleware
import org.http4s.server.blaze._
import org.scalatest.WordSpec
import scalaz.zio

import scala.concurrent.TimeoutException
import scala.language.higherKinds

class Http4sTransportTest extends WordSpec {

  import Http4sTransportTest.Http4sTestContext._
  import Http4sTransportTest._

  "Http4s transport" should {
    "support direct calls" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val builder = BlazeBuilder[CIO]
        .bindHttp(port, host)
        .withWebSockets(true)
        .mountService(ioService.service, "/")
        .start

      builder.unsafeRunAsync {
        case Right(server) =>
          try {
            performTests(clientDispatcher())
            performWsTests(wsClientDispatcher())
          } finally {
            server.shutdownNow()
          }

        case Left(error) =>
          throw error
      }
    }
  }

  private def performWsTests(disp: IRTDispatcher[BiIO] with TestDispatcher with AutoCloseable): Unit = {
    val greeterClient = new GreeterServiceClientWrapped(disp)

    disp.setupCredentials("user", "pass")

    assert(BIOR.unsafeRun(greeterClient.greet("John", "Smith")) == "Hi, John Smith!")
    assert(BIOR.unsafeRun(greeterClient.alternative()) == "value")

    ioService.buzzersFor("user").foreach {
      buzzer =>
        val client = new GreeterServiceClientWrapped(buzzer)
        assert(BIOR.unsafeRun(client.greet("John", "Buzzer")) == "Hi, John Buzzer!")
    }

    disp.setupCredentials("user", "badpass")
    intercept[TimeoutException] {
      BIOR.unsafeRun(greeterClient.alternative())
    }
    disp.close()
    ()

  }


  private def performTests(disp: IRTDispatcher[BiIO] with TestDispatcher): Unit = {
    val greeterClient = new GreeterServiceClientWrapped(disp)

    disp.setupCredentials("user", "pass")

    assert(BIOR.unsafeRun(greeterClient.greet("John", "Smith")) == "Hi, John Smith!")
    assert(BIOR.unsafeRun(greeterClient.alternative()) == "value")

    disp.cancelCredentials()
    val forbidden = intercept[IRTUnexpectedHttpStatus] {
      BIOR.unsafeRun(greeterClient.alternative())
    }
    assert(forbidden.status == Status.Forbidden)

    disp.setupCredentials("user", "badpass")
    val unauthorized = intercept[IRTUnexpectedHttpStatus] {
      BIOR.unsafeRun(greeterClient.alternative())
    }
    assert(unauthorized.status == Status.Unauthorized)
    ()

  }
}

object Http4sTransportTest {
  type BiIO[+E, +V] = zio.IO[E, V]
  type CIO[+T] = cats.effect.IO[T]
  val BIOR: BIORunner[BiIO] = implicitly

  final case class DummyContext(ip: String, credentials: Option[Credentials])


  final class AuthCheckDispatcher2[R[+ _, + _] : BIO, Ctx](proxied: IRTWrappedService[R, Ctx]) extends IRTWrappedService[R, Ctx] {
    override def serviceId: IRTServiceId = proxied.serviceId

    override def allMethods: Map[IRTMethodId, IRTMethodWrapper[R, Ctx]] = proxied.allMethods.mapValues {
      method =>
        new IRTMethodWrapper[R, Ctx] {
          val R: BIO[R] = implicitly

          override val signature: IRTMethodSignature = method.signature
          override val marshaller: IRTCirceMarshaller = method.marshaller

          override def invoke(ctx: Ctx, input: signature.Input): R.Just[signature.Output] = {
            ctx match {
              case DummyContext(_, Some(BasicCredentials(user, pass))) =>
                if (user == "user" && pass == "pass") {
                  method.invoke(ctx, input.asInstanceOf[method.signature.Input]).map(_.asInstanceOf[signature.Output])
                } else {
                  R.terminate(IRTBadCredentialsException(Status.Unauthorized))
                }

              case _ =>
                R.terminate(IRTNoCredentialsException(Status.Forbidden))
            }
          }
        }
    }
  }

  class DemoContext[R[+ _, + _] : BIO, Ctx] {
    object Server {
      private val greeterService = new AbstractGreeterServer.Impl[R, Ctx]
      private val greeterDispatcher = new GreeterServiceServerWrapped(greeterService)
      private val dispatchers: Set[IRTWrappedService[R, Ctx]] = Set(greeterDispatcher).map(d => new AuthCheckDispatcher2(d))
      val multiplexor = new IRTServerMultiplexor[R, Ctx](dispatchers)

      private val clients: Set[IRTWrappedClient] = Set(GreeterServiceClientWrapped)
      val codec = new IRTClientMultiplexor[R](clients)
    }

    object Client {
      private val greeterService = new AbstractGreeterServer.Impl[R, Unit]
      private val greeterDispatcher = new GreeterServiceServerWrapped(greeterService)
      private val dispatchers: Set[IRTWrappedService[R, Unit]] = Set(greeterDispatcher)

      private val clients: Set[IRTWrappedClient] = Set(GreeterServiceClientWrapped)
      val codec = new IRTClientMultiplexor[R](clients)
      val buzzerMultiplexor = new IRTServerMultiplexor[R, Unit](dispatchers)
    }

  }

  object Http4sTestContext {


    //
    final val addr = IzSockets.temporaryServerAddress()
    final val port = addr.getPort
    final val host = addr.getHostName
    final val baseUri = Uri(Some(Uri.Scheme.http), Some(Uri.Authority(host = Uri.RegName(host), port = Some(port))))
    final val wsUri = new URI("ws", null, host, port, "/ws", null, null)

    //
    final val demo = new DemoContext[BiIO, DummyContext]()

    import scala.concurrent.ExecutionContext.Implicits.global

    final val rt = new Http4sRuntime[BiIO, CIO](makeLogger())

    //
    final val authUser: Kleisli[OptionT[CIO, ?], Request[CIO], DummyContext] =
      Kleisli {
        request: Request[CIO] =>
          val context = DummyContext(request.remoteAddr.getOrElse("0.0.0.0"), request.headers.get(Authorization).map(_.credentials))
          OptionT.liftF(IO(context))
      }


    final val wsContextProvider = new rt.WsContextProvider[DummyContext, String] {
      val knownAuthorization = new AtomicReference[Credentials](null)

      override def toContext(initial: DummyContext, packet: RpcPacket): DummyContext = {
        initial.credentials match {
          case Some(value) =>
            knownAuthorization.compareAndSet(null, value)
          case None =>
        }

        val maybeAuth = packet.headers.get("Authorization")

        maybeAuth.map(Authorization.parse).flatMap(_.toOption) match {
          case Some(value) =>
            knownAuthorization.set(value.credentials)
          case None =>
        }
        initial.copy(credentials = Option(knownAuthorization.get()))
      }

      override def toId(initial: DummyContext, packet: RpcPacket): Option[String] = {
        packet.headers.get("Authorization")
          .map(Authorization.parse)
          .flatMap(_.toOption)
          .collect {
            case Authorization(BasicCredentials((user, _))) => user
          }
      }
    }

    final val ioService = new rt.HttpServer(demo.Server.multiplexor, demo.Server.codec, AuthMiddleware(authUser), wsContextProvider, rt.WsSessionListener.empty)

    //
    final def clientDispatcher(): rt.ClientDispatcher with TestDispatcher = new rt.ClientDispatcher(baseUri, demo.Client.codec) with TestDispatcher {
      override protected def transformRequest(request: Request[CIO]): Request[CIO] = {
        request.withHeaders(Headers(creds.get(): _*))
      }
    }

    final val wsClientContextProvider = new WsClientContextProvider[Unit] {
      override def toContext(packet: RpcPacket): Unit = ()
    }

    final def wsClientDispatcher(): rt.ClientWsDispatcher[Unit] with TestDispatcher = new rt.ClientWsDispatcher(wsUri, demo.Client.codec, demo.Client.buzzerMultiplexor, wsClientContextProvider) with TestDispatcher {
      override protected def transformRequest(request: RpcPacket): RpcPacket = {
        Option(creds.get()) match {
          case Some(value) =>
            val update = value.map(h => (h.name.value, h.value)).toMap
            request.copy(headers = request.headers ++ update)
          case None => request
        }
      }
    }

    final val greeterClient = new GreeterServiceClientWrapped(clientDispatcher)
  }

  private def makeLogger(): IzLogger = {
    val out = IzLogger.basic(Log.Level.Info, Map(
      "org.http4s" -> Log.Level.Warn
      , "org.http4s.server.blaze" -> Log.Level.Error
      , "org.http4s.blaze.channel.nio1" -> Log.Level.Crit
      , "com.github.pshirshov.izumi.idealingua.runtime.rpc.http4s" -> Log.Level.Trace
    ))
    StaticLogRouter.instance.setup(out.receiver)
    out
  }
}


