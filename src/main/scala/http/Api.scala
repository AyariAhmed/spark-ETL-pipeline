package http

//import akka.actor.ActorSystem
//import akka.http.scaladsl.server.Route
//import akka.http.scaladsl.Http
//import akka.http.scaladsl.server.Directives._
//import akka.stream.Materializer
//import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
//import com.datastax.oss.driver.api.core.cql.ResultSet
//import spray.json.DefaultJsonProtocol._
//import spray.json.RootJsonFormat
//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
//
//import java.net.InetSocketAddress
//import java.util.concurrent.CompletionStage
//import scala.collection.convert.ImplicitConversions.`iterable AsScalaIterable`
//import scala.concurrent.{ExecutionContextExecutor, Future}
//
//case class ResponseData(total_sales_promo_cat: Int, incremental_lift: Option[Int], promo_lift: Double)
//
//object ResponseData {
//  implicit val ResponseDataFormat: RootJsonFormat[ResponseData] = jsonFormat3(ResponseData.apply)
//}
//
//object Api extends App {
//  implicit val system: ActorSystem = ActorSystem("Api")
//  implicit val materializer: Materializer = Materializer(system)
//  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
//
//  val cassandraSession: CqlSession = CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1", 9042)).build()
//  cassandraSession.execute("USE " + CqlIdentifier.fromCql("challenges")) // set keyspace to challenges
//
//  def queryDatabase(promo_cat: String, prod_id: Int): Future[List[ResponseData]] = Future {
//    val selectStmt = "SELECT total_sales_promo_cat, incremental_lift, promo_lift FROM sales_data WHERE promo_cat = ? and prod_id = ?"
//    val preparedStatement = cassandraSession.prepare(selectStmt)
//    val boundStatement = preparedStatement.bind(promo_cat, prod_id)
//    val resultSetFuture: CompletionStage[ResultSet] = cassandraSession.executeAsync(boundStatement)
//
//
//    resultSetFuture.map(row =>
//      ResponseData(row.getInt("total_sales_promo_cat"), Option(row.getInt("incremental_lift")), row.getDouble("promo_lift"))
//    ).toList
//  }
//
//  private val route: Route = {
//    path("sales") {
//      get {
//        parameters("promo_cat", "prod_id") { (promo_cat, prod_id) =>
//          val dbQueryResult: Future[List[ResponseData]] = queryDatabase(promo_cat, prod_id.toInt)
//          onSuccess(dbQueryResult) {
//            complete(dbQueryResult)
//          }
//        }
//      }
//    }
//  }
//
//  val host = "localhost"
//  val port = 8080
//  Http().newServerAt(host, port).bindFlow(route)
//
//  println(s"Server online at http://localhost:8080/users/{id}")
//}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer

import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn

object Api extends App {
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val route: Route = path("hello") {
    get {
      complete(StatusCodes.OK, "Hello, world!")
    }
  }

  val host = "localhost"
  val port = 8080
  val bindingFuture = Http().newServerAt(host, port).bindFlow(route)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}