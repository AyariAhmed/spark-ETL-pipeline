package http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import scala.concurrent.{ExecutionContextExecutor, Future}
import java.net.InetSocketAddress
import scala.util.{Failure, Success}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import spray.json.DefaultJsonProtocol._
import spray.json._
import scala.util.Properties.envOrElse

case class ResponseData(total_sales_promo_cat: Long, incremental_lift: Option[Long], promo_lift: Double)

object ResponseDataJsonProtocol extends DefaultJsonProtocol {
  implicit val responseDataFormat: RootJsonFormat[ResponseData] = jsonFormat3(ResponseData)
}

object Api extends App {
  implicit val system: ActorSystem = ActorSystem("API")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  implicit val responseDataFormat: RootJsonFormat[ResponseData] = ResponseDataJsonProtocol.responseDataFormat

  private val CASSANDRA_HOST = envOrElse("CASSANDRA_HOST", "127.0.0.1")
  private val CASSANDRA_PORT = envOrElse("CASSANDRA_PORT", "9042")
  private val CASSANDRA_USERNAME = envOrElse("CASSANDRA_USERNAME", "cassandra")
  private val CASSANDRA_PASSWORD = envOrElse("CASSANDRA_PASSWORD", "cassandra")

  private val cassandraSession: CqlSession = CqlSession.builder()
    .addContactPoint(new InetSocketAddress(CASSANDRA_HOST, CASSANDRA_PORT.toInt))
    .withAuthCredentials(CASSANDRA_USERNAME, CASSANDRA_PASSWORD)
    .withLocalDatacenter("datacenter1")
    .withKeyspace(CqlIdentifier.fromCql("challenge"))
    .build()

  private def queryDatabase(promo_cat: String, prod_id: Int): Future[ResultSet] = Future {
    val selectStmt = "SELECT total_sales_promo_cat, incremental_lift, promo_lift FROM sales_data WHERE promo_cat =:promo_cat and prod_id =:prod_id"
    val boundStatement = cassandraSession.prepare(selectStmt)
      .bind()
      .setString("promo_cat", promo_cat)
      .setInt("prod_id", prod_id)

    cassandraSession.execute(boundStatement)
  }

  implicit val responseDataMarshaller: ToEntityMarshaller[ResponseData] = sprayJsonMarshaller[ResponseData]

  private val route: Route = path("sales") {
    get {
      parameters("promo_cat", "prod_id") { (promo_cat, prod_id) => {
        import scala.collection.JavaConverters._
        val resultSet = queryDatabase(promo_cat, prod_id.toInt)
        onComplete(resultSet) {
          case Failure(exception) => complete(StatusCodes.InternalServerError, s"Error occurred while executing the query: ${exception.getMessage}")
          case Success(result) => {
            val response = result.all.asScala.toStream.map(row =>
              ResponseData(row.getLong("total_sales_promo_cat"), Option(row.getLong("incremental_lift")), row.getDouble("promo_lift"))
            ).toList
            complete(StatusCodes.OK, response)
          }
        }
      }
      }
    }
  }

  private val HOST = envOrElse("HOST", "127.0.0.1")
  private val PORT = envOrElse("PORT", "8080")
  Http().newServerAt(HOST, PORT.toInt).bindFlow(route)

  println(s"Api served at http://$HOST:$PORT/")
}