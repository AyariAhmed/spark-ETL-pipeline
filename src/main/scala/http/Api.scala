package http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContextExecutor, Future}
import java.net.InetSocketAddress
import scala.io.StdIn
import scala.util.{Failure, Success}

case class ResponseData(total_sales_promo_cat: Int, incremental_lift: Option[Int], promo_lift: Double)

object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val responseDataFormat: RootJsonFormat[ResponseData] = jsonFormat3(ResponseData)
}

object Api extends App {
  implicit val system: ActorSystem = ActorSystem("API")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  implicit val responseDataFormat: RootJsonFormat[ResponseData] = MyJsonProtocol.responseDataFormat

  private val cassandraSession: CqlSession = CqlSession.builder()
    .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
    .withLocalDatacenter("datacenter1")
    .withKeyspace(CqlIdentifier.fromCql("challenge"))
    .build()

  def queryDatabase(promo_cat: String, prod_id: Int) = Future {
    val selectStmt = "SELECT total_sales_promo_cat, incremental_lift, promo_lift FROM sales_data WHERE promo_cat = {promo_cat} and prod_id = {prod_id}"
    val boundStatement = cassandraSession.prepare(selectStmt).bind()
    boundStatement.setString("promo_cat", promo_cat)
    boundStatement.setInt("prod_id", prod_id)

    //    val resultSet = cassandraSession.execute(boundStatement)

    cassandraSession.execute(boundStatement)
  }

  private val route = path("sales") {
    get {
      parameters("promo_cat", "prod_id") { (promo_cat, prod_id) => {
        import scala.collection.JavaConverters._
        val resultSet: Future[ResultSet] = queryDatabase(promo_cat, prod_id.toInt)
        resultSet.onComplete {
          case Failure(exception) => complete(StatusCodes.InternalServerError, s"Error occurred while executing the query: ${exception.getMessage}")
          case Success(result) => {
            val response = result.iterator.asScala.toStream.map(row =>
              ResponseData(row.getInt("total_sales_promo_cat"), Option(row.getInt("incremental_lift")), row.getDouble("promo_lift"))
            ).toList.toJson
            complete(StatusCodes.OK, "hello")
          }
        }
      }
      }
    }
  }

  val host = "localhost"
  val port = 8080
  private val bindingFuture = Http().newServerAt(host, port).bindFlow(route)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}