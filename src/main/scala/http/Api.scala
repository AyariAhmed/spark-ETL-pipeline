package http
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.datastax.driver.core.{Cluster, ResultSet, Session}
import com.datastax.oss.driver.api.core.session.Session
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future

case class User(id: Int, name: String)

object UserJsonProtocol {
  implicit val userFormat = jsonFormat2(User)
}

object Api extends App {
  implicit val system = ActorSystem("CassandraApp")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val cassandraSession: Session = {
    val cluster = Cluster.builder().addContactPoint("127.0.0.1").build()
    cluster.connect("test")
  }

  val route = path("users" / IntNumber) { id =>
    get {
      val userFuture: Future[Option[User]] = Future {
        val resultSet: ResultSet = cassandraSession.execute(s"SELECT * FROM users WHERE id = $id")
        val row = resultSet.one()
        if (row == null) None else Some(User(row.getInt("id"), row.getString("name")))
      }
      onSuccess(userFuture) {
        case Some(user) => complete(user)
        case None => complete(HttpResponse(404, entity = "User not found"))
      }
    }
  }

  Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/users/{id}")
}
