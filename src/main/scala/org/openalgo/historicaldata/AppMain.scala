package org.openalgo.historicaldata

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, get, parameters, path}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import argonaut.Argonaut._
import argonaut._
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.akkahttpargonaut.ArgonautSupport

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object AppMain extends App with ArgonautSupport {

  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  val config = ConfigFactory.load()

  lazy val quandlConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnectionHttps(config.getString("services.quandlHost"), config.getInt("services.quandlPort"))

  def quandlRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(quandlConnectionFlow).runWith(Sink.head)


  def fetchStockData(ticker: String): Future[Either[String, Json]] = {
    quandlRequest(RequestBuilding.Get(s"/api/v3/datasets/WIKI/$ticker/data.json")).flatMap { response =>
      response.status match {
        case OK => {
          Unmarshal(response.entity).to[Json].map(Right(_))
        }
        case BadRequest => Future.successful(Left(s"$ticker: incorrect IP format"))
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"Quandl request failed with status code ${response.status} and entity $entity"
          Future.failed(new IOException(error))
        }
      }
    }
  }

  val route =
    path("stocks") {
      get {
        parameters('ticker) { (ticker) =>
          complete {
            val ret = Await.result(fetchStockData(ticker), 30000 millis)
            ret match {
              case Right(json) => {
                json.field(new JsonField("dataset_data"))
              }
              case Left(str) => {
                str
              }
            }
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine() // for the future transformations
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.terminate()) // and shutdown when done
}